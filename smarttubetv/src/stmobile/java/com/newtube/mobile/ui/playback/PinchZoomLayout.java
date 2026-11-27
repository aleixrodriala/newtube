package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/**
 * The video-area container that recognizes YouTube-style two-finger pinch-zoom on top of whatever
 * children are showing, feeding continuous scale updates so the player can zoom smoothly between
 * Original (fit) and Fill.
 *
 * <p>Detection lives HERE, on the container, and not inside
 * {@link com.github.vkay94.dtpv3.DoubleTapPlayerViewImpl}: when the controls overlay is visible it
 * is clickable and consumes every touch before the player view sees it, so a pinch must be caught
 * at the parent level to work in both overlay states (like the official app). The layout only
 * takes over the gesture once a SECOND finger lands ({@code ACTION_POINTER_DOWN}); interception
 * then cancels the children automatically, so a pending single-tap (controls toggle), double-tap
 * (seek) or pressed state is dropped cleanly. Single-finger gestures are never touched.</p>
 */
public class PinchZoomLayout extends FrameLayout {

    public interface PinchListener {
        void onPinchBegin();

        void onPinchScale(float scaleFactor);

        void onPinchEnd();
    }

    private final ScaleGestureDetector mScaleDetector;
    @Nullable
    private PinchListener mListener;
    private boolean mPinchEnabled;

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
                        if (mListener != null) {
                            mListener.onPinchBegin();
                        }
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (mListener != null) {
                            mListener.onPinchScale(detector.getScaleFactor());
                        }
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        if (mListener != null) {
                            mListener.onPinchEnd();
                        }
                    }
                });
        // No press-and-hold delay before scaling starts: a pinch should track the fingers at once.
        mScaleDetector.setQuickScaleEnabled(false);
    }

    /**
     * NEWTUBE(motion): claims a whole touch for the player's tap detector before any child can
     * hit-test it - the second tap of a double tap whose first tap just revealed the controls, which
     * would otherwise land on whatever control appeared under it (back, gear, the seek bar).
     */
    public interface TapRouter {
        boolean claimDown(MotionEvent down);

        void route(MotionEvent event);
    }

    @Nullable
    private TapRouter mTapRouter;
    private boolean mRoutingTap;

    public void setTapRouter(@Nullable TapRouter router) {
        mTapRouter = router;
    }

    public void setPinchListener(@Nullable PinchListener listener) {
        mListener = listener;
    }

    public void setPinchEnabled(boolean enabled) {
        mPinchEnabled = enabled;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mRoutingTap = mTapRouter != null && mTapRouter.claimDown(ev);
            if (mRoutingTap) {
                return true; // the rest of this touch comes to onTouchEvent
            }
        }
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
        if (mRoutingTap && mTapRouter != null) {
            mTapRouter.route(ev);
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mRoutingTap = false;
            }
            return true;
        }
        if (!mPinchEnabled || mListener == null) {
            return super.onTouchEvent(ev);
        }
        mScaleDetector.onTouchEvent(ev);
        return true;
    }
}
