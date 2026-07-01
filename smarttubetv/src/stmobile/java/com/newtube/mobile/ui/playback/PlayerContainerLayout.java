package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/**
 * Root container for {@link MobilePlaybackActivity} that adds YouTube-style
 * swipe-DOWN-to-dismiss without depending on a translucent theme.
 *
 * <p>The {@code slidableactivity} (Slidr) module was evaluated first but doesn't integrate cleanly
 * here: it hijacks the decor view and animates an edge swipe assuming a translucent window so the
 * screen behind shows through, whereas the player is an opaque, fullscreen, {@code singleInstance}
 * Activity launched into its own task - there is no live Activity behind it to reveal mid-drag.
 * It would also fight the {@link com.github.vkay94.dtpv.DoubleTapPlayerViewImpl} which consumes all
 * touch events. So we do a small, self-contained vertical drag instead, exactly the fallback the
 * brief allows.</p>
 *
 * <p>Only a clearly <em>downward, vertically-dominant</em> drag past 2x touch slop is intercepted,
 * so single taps (toggle controls), double taps (seek) and horizontal scrubbing all still reach the
 * player view untouched. Deltas use raw screen coordinates so the math stays correct even while the
 * Activity translates this view during the drag.</p>
 */
public class PlayerContainerLayout extends FrameLayout {

    public interface DragListener {
        /** @return true if a drag-to-dismiss may begin right now (e.g. not while scrubbing). */
        boolean canStartDismissDrag();

        /** Called for every move while dragging. {@code dy} is the downward distance in px (>= 0). */
        void onDismissDrag(float dy);

        /** Released while dragging. Implementor decides to dismiss or spring back. */
        void onDismissDragReleased(float dy, float yVelocity);
    }

    private DragListener mListener;
    private final int mTouchSlop;
    private float mDownRawX;
    private float mDownRawY;
    private boolean mDragging;
    private boolean mDownInDragRegion;
    @Nullable
    private View mDragStartBoundView;
    private final int[] mTmpLocation = new int[2];
    @Nullable
    private VelocityTracker mVelocityTracker;

    public PlayerContainerLayout(Context context) {
        this(context, null);
    }

    public PlayerContainerLayout(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PlayerContainerLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setDragListener(DragListener listener) {
        mListener = listener;
    }

    /**
     * Restrict where a dismiss drag may begin. When set, a swipe-to-dismiss only starts if the
     * initial touch-down landed inside {@code view}'s on-screen bounds (the watch page uses the
     * 16:9 video area). This keeps the vertical drag off the scrollable content column below, so
     * the related list scrolls normally. When {@code null}, a drag may begin anywhere (the old
     * full-screen behaviour, still used effectively in landscape where the video fills the screen).
     */
    public void setDragStartBoundView(@Nullable View view) {
        mDragStartBoundView = view;
    }

    private boolean isInDragRegion(MotionEvent ev) {
        if (mDragStartBoundView == null) {
            return true;
        }
        if (mDragStartBoundView.getVisibility() != VISIBLE
                || mDragStartBoundView.getWidth() == 0 || mDragStartBoundView.getHeight() == 0) {
            return false;
        }
        mDragStartBoundView.getLocationOnScreen(mTmpLocation);
        float x = ev.getRawX();
        float y = ev.getRawY();
        return x >= mTmpLocation[0] && x <= mTmpLocation[0] + mDragStartBoundView.getWidth()
                && y >= mTmpLocation[1] && y <= mTmpLocation[1] + mDragStartBoundView.getHeight();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mListener == null) {
            return false;
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownRawX = ev.getRawX();
                mDownRawY = ev.getRawY();
                mDragging = false;
                mDownInDragRegion = isInDragRegion(ev);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!mDragging && mDownInDragRegion) {
                    float dy = ev.getRawY() - mDownRawY;
                    float dx = ev.getRawX() - mDownRawX;
                    // Downward, clearly vertical, and beyond a comfortable slop.
                    if (dy > mTouchSlop * 2 && dy > Math.abs(dx) * 1.5f
                            && mListener.canStartDismissDrag()) {
                        mDragging = true;
                        startVelocityTracking(ev);
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                break;
        }

        return mDragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mDragging || mListener == null) {
            return super.onTouchEvent(ev);
        }

        if (mVelocityTracker == null) {
            startVelocityTracking(ev);
        } else {
            mVelocityTracker.addMovement(ev);
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                float dy = Math.max(0f, ev.getRawY() - mDownRawY);
                mListener.onDismissDrag(dy);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float releaseDy = Math.max(0f, ev.getRawY() - mDownRawY);
                float yVelocity = 0f;
                if (mVelocityTracker != null) {
                    mVelocityTracker.computeCurrentVelocity(1000);
                    yVelocity = mVelocityTracker.getYVelocity();
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                mDragging = false;
                mListener.onDismissDragReleased(releaseDy, yVelocity);
                return true;
        }

        return true;
    }

    private void startVelocityTracking(MotionEvent ev) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);
    }
}
