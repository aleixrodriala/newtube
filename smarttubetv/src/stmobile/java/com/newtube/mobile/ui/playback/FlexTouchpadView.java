package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

/**
 * Interactive touchpad for the lower screen in Flex (tabletop) mode.
 *
 * <p>One job: translate intentional touchpad gestures (single tap play/pause, double-tap left/right
 * skip, horizontal drag seek, vertical side drags brightness/volume via {@link SwipeLevels}) into
 * host callbacks with crisp transient feedback.</p>
 */
public final class FlexTouchpadView extends FrameLayout {

    interface Listener {
        void onTouchpadTogglePlayPause();

        void onTouchpadDoubleTapSeek(boolean forward);

        long onTouchpadSeekStart();

        long onTouchpadGetDurationMs();

        void onTouchpadSeekPreview(long positionMs, long deltaMs);

        void onTouchpadSeekCommit(long positionMs, boolean canceled);
    }

    private static final int MODE_NONE = 0;
    private static final int MODE_SEEK = 1;
    private static final int MODE_LEVEL = 2;
    private static final float LEVEL_ZONE_FRACTION = 0.28f;
    private static final long FULL_WIDTH_SCRUB_SPAN_MS = 120_000L;

    private final int mTouchSlopPx;
    private final GestureDetector mTapDetector;
    private final TextView mFeedbackView;
    private final Runnable mHideFeedback = this::hideFeedbackNow;

    @Nullable
    private Listener mListener;
    @Nullable
    private SwipeLevels mSwipeLevels;

    private int mDragMode = MODE_NONE;
    private float mDownX;
    private float mDownY;
    private float mStartDy;
    private long mSeekOriginMs;
    private long mSeekPreviewMs;

    public FlexTouchpadView(@NonNull Context context) {
        this(context, null);
    }

    public FlexTouchpadView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FlexTouchpadView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mTouchSlopPx = Math.max(24, ViewConfiguration.get(context).getScaledTouchSlop() * 2);
        setClickable(true);
        setFocusable(true);

        mFeedbackView = new TextView(context);
        mFeedbackView.setTextColor(0xFFFFFFFF);
        mFeedbackView.setTextSize(15f);
        mFeedbackView.setGravity(Gravity.CENTER);
        mFeedbackView.setBackgroundResource(R.drawable.bg_mobile_player_pill);
        int hPad = Math.round(16 * getResources().getDisplayMetrics().density);
        int vPad = Math.round(8 * getResources().getDisplayMetrics().density);
        mFeedbackView.setPadding(hPad, vPad, hPad, vPad);
        mFeedbackView.setVisibility(GONE);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        addView(mFeedbackView, lp);

        mTapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                if (mDragMode == MODE_NONE && mListener != null) {
                    Haptics.tick(FlexTouchpadView.this);
                    mListener.onTouchpadTogglePlayPause();
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (mListener == null || getWidth() <= 0) {
                    return true;
                }
                boolean forward = e.getX() >= getWidth() * 0.5f;
                Haptics.tick(FlexTouchpadView.this);
                mListener.onTouchpadDoubleTapSeek(forward);
                showFeedback(forward ? "+10s" : "-10s", 650);
                return true;
            }
        });
    }

    void bind(@Nullable Listener listener, @Nullable SwipeLevels swipeLevels) {
        mListener = listener;
        mSwipeLevels = swipeLevels;
    }

    void showFeedback(CharSequence text, long hideAfterMs) {
        removeCallbacks(mHideFeedback);
        mFeedbackView.setText(text);
        mFeedbackView.setVisibility(VISIBLE);
        mFeedbackView.setAlpha(1f);
        if (hideAfterMs > 0) {
            postDelayed(mHideFeedback, hideAfterMs);
        }
    }

    private void hideFeedbackNow() {
        mFeedbackView.animate().alpha(0f).setDuration(150)
                .withEndAction(() -> mFeedbackView.setVisibility(GONE)).start();
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (mDragMode == MODE_NONE) {
            mTapDetector.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDragMode = MODE_NONE;
                mDownX = event.getX();
                mDownY = event.getY();
                return true;

            case MotionEvent.ACTION_MOVE:
                handleMove(event);
                return true;

            case MotionEvent.ACTION_UP:
                finishDrag(false);
                return true;

            case MotionEvent.ACTION_CANCEL:
                finishDrag(true);
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private void handleMove(MotionEvent event) {
        float dx = event.getX() - mDownX;
        float dy = event.getY() - mDownY;
        if (mDragMode == MODE_NONE) {
            maybeStartDrag(dx, dy);
        }
        if (mDragMode == MODE_SEEK) {
            updateSeekDrag(dx);
        } else if (mDragMode == MODE_LEVEL && mSwipeLevels != null) {
            mSwipeLevels.move(mStartDy - dy);
        }
    }

    private void maybeStartDrag(float dx, float dy) {
        float absX = Math.abs(dx);
        float absY = Math.abs(dy);
        if (absX >= mTouchSlopPx && absX > absY * 1.4f && mListener != null) {
            long duration = mListener.onTouchpadSeekStart();
            if (duration > 0) {
                mDragMode = MODE_SEEK;
                mSeekOriginMs = Math.max(0L, mSeekOriginMs);
                mSeekPreviewMs = mSeekOriginMs;
            }
            return;
        }
        if (absY >= mTouchSlopPx && absY > absX * 1.4f && mSwipeLevels != null && getWidth() > 0) {
            float fractionX = mDownX / getWidth();
            int kind = fractionX < LEVEL_ZONE_FRACTION ? SwipeLevels.BRIGHTNESS
                    : (fractionX > 1f - LEVEL_ZONE_FRACTION ? SwipeLevels.VOLUME : 0);
            if (kind != 0 && mSwipeLevels.canSwipe(kind)) {
                mDragMode = MODE_LEVEL;
                mStartDy = dy;
                mSwipeLevels.begin(kind, Math.max(getHeight(), 200));
            }
        }
    }

    private void updateSeekDrag(float dx) {
        if (mListener == null || getWidth() <= 0) {
            return;
        }
        long duration = mListener.onTouchpadGetDurationMs();
        if (duration <= 0) {
            return;
        }
        long span = Math.min(duration, FULL_WIDTH_SCRUB_SPAN_MS);
        long delta = Math.round((dx / getWidth()) * span);
        long target = Math.max(0L, Math.min(duration, mSeekOriginMs + delta));
        mSeekPreviewMs = target;
        mListener.onTouchpadSeekPreview(target, delta);
    }

    void setSeekOriginMs(long originMs) {
        mSeekOriginMs = originMs;
    }

    private void finishDrag(boolean canceled) {
        if (mDragMode == MODE_SEEK && mListener != null) {
            mListener.onTouchpadSeekCommit(mSeekPreviewMs, canceled);
            postDelayed(mHideFeedback, 350);
        } else if (mDragMode == MODE_LEVEL && mSwipeLevels != null) {
            if (canceled) {
                mSwipeLevels.cancel();
            } else {
                mSwipeLevels.end();
            }
        }
        mDragMode = MODE_NONE;
    }
}
