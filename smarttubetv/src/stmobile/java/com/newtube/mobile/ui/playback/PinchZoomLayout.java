package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/**
 * The video-area container that recognizes YouTube's pinch-zoom gesture (pinch out = fill the
 * screen, pinch in = back to original) on top of whatever children are showing.
 *
 * <p>Detection lives HERE, on the container, and not inside
 * {@link com.github.vkay94.dtpv3.DoubleTapPlayerViewImpl}: when the controls overlay is visible it
 * is clickable and consumes every touch before the player view sees it, so a pinch must be caught
 * at the parent level to work in both overlay states (like the official app). The layout only
 * takes over the gesture once a SECOND finger lands ({@code ACTION_POINTER_DOWN}); interception
 * then cancels the children automatically, so a pending single-tap (controls toggle), double-tap
 * (seek) or pressed state is dropped cleanly. Single-finger gestures are never touched.</p>
 *
 * <p>The listener gets a discrete event each time the cumulative pinch span crosses the trigger
 * ratio, and the accumulator resets, so one long gesture can toggle fill -> original -> fill,
 * matching YouTube's mid-gesture snapping.</p>
 */
public class PinchZoomLayout extends FrameLayout {

    public interface PinchListener {
        /** {@code zoomIn} true = pinched out (fill), false = pinched in (original). */
        void onPinchZoom(boolean zoomIn);
    }

    /** Span change (vs the reference watermark) that triggers a zoom event. */
    private static final float PINCH_TRIGGER_RATIO = 1.15f;

    private final ScaleGestureDetector mScaleDetector;
    @Nullable
    private PinchListener mListener;
    private boolean mPinchEnabled;
    private float mAccumulatedSpan = 1f;
    /** Watermark the trigger ratio is measured against: gesture-start span, then the extreme
     *  reached after the last fire, so reversing direction needs a REAL 15% back-track and
     *  continuing in the fired direction never re-fires (no chip flicker mid-pinch). */
    private float mSpanRef = 1f;
    /** Direction fired last in this gesture: 0 none, 1 zoom-in, -1 zoom-out. */
    private int mLastDirection;

    public PinchZoomLayout(Context context) {
        this(context, null);
    }

    public PinchZoomLayout(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PinchZoomLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mScaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        mAccumulatedSpan = 1f;
                        mSpanRef = 1f;
                        mLastDirection = 0;
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        mAccumulatedSpan *= detector.getScaleFactor();
                        float ratio = mAccumulatedSpan / mSpanRef;
                        if (ratio >= PINCH_TRIGGER_RATIO) {
                            if (mLastDirection != 1 && mListener != null) {
                                mListener.onPinchZoom(true);
                            }
                            mLastDirection = 1;
                            mSpanRef = mAccumulatedSpan;
                        } else if (ratio <= 1f / PINCH_TRIGGER_RATIO) {
                            if (mLastDirection != -1 && mListener != null) {
                                mListener.onPinchZoom(false);
                            }
                            mLastDirection = -1;
                            mSpanRef = mAccumulatedSpan;
                        } else if (mLastDirection == 1) {
                            mSpanRef = Math.max(mSpanRef, mAccumulatedSpan);
                        } else if (mLastDirection == -1) {
                            mSpanRef = Math.min(mSpanRef, mAccumulatedSpan);
                        }
                        return true;
                    }
                });
        // No press-and-hold delay before scaling starts: a pinch should track the fingers at once.
        mScaleDetector.setQuickScaleEnabled(false);
    }

    public void setPinchListener(@Nullable PinchListener listener) {
        mListener = listener;
    }

    public void setPinchEnabled(boolean enabled) {
        mPinchEnabled = enabled;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!mPinchEnabled || mListener == null) {
            return super.onInterceptTouchEvent(ev);
        }
        // Every event passes through exactly one of intercept/onTouch, so feeding the detector
        // from both keeps it on a single consistent stream.
        mScaleDetector.onTouchEvent(ev);
        // Second finger down = this is a pinch; take the gesture (children get ACTION_CANCEL).
        return ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mPinchEnabled || mListener == null) {
            return super.onTouchEvent(ev);
        }
        mScaleDetector.onTouchEvent(ev);
        return true;
    }
}
