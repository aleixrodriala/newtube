package com.newtube.mobile.ui.playback;

import android.view.MotionEvent;

/**
 * NEWTUBE(touch-prefetch, experiment): "this finger is about to tap" for a related row, earlier
 * than the click. A click is only dispatched at ACTION_UP (after the scrolling container's tap
 * disambiguation), so the whole press - typically ~100 ms - is dead time for the open. This fires
 * once a finger has rested within the touch slop for {@code stillMs}; any movement past the slop,
 * a lift before the deadline (the click itself then does the work) or an ACTION_CANCEL (the
 * NestedScrollView took the gesture over for a scroll) drops it.
 *
 * <p>Pure event bookkeeping: the scheduler and the callback are injected so the timing rules are
 * unit-testable without a view hierarchy.</p>
 */
final class PressIntentDetector {
    interface Scheduler {
        void postDelayed(Runnable task, long delayMs);

        void remove(Runnable task);
    }

    private final float mTouchSlopPx;
    private final long mStillMs;
    private final Scheduler mScheduler;
    private final Runnable mOnIntent;
    private final Runnable mFire = this::fire;
    private float mDownX;
    private float mDownY;
    private boolean mArmed;

    PressIntentDetector(float touchSlopPx, long stillMs, Scheduler scheduler, Runnable onIntent) {
        mTouchSlopPx = touchSlopPx;
        mStillMs = stillMs;
        mScheduler = scheduler;
        mOnIntent = onIntent;
    }

    /** Observes, never consumes: the row's own click/ripple handling must see every event. */
    boolean onTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                disarm();
                mDownX = event.getX();
                mDownY = event.getY();
                mArmed = true;
                mScheduler.postDelayed(mFire, mStillMs);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mArmed && (Math.abs(event.getX() - mDownX) > mTouchSlopPx
                        || Math.abs(event.getY() - mDownY) > mTouchSlopPx)) {
                    disarm();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                disarm();
                break;
            default:
                break;
        }
        return false;
    }

    void disarm() {
        if (mArmed) {
            mArmed = false;
            mScheduler.remove(mFire);
        }
    }

    private void fire() {
        if (!mArmed) {
            return;
        }
        mArmed = false;
        mOnIntent.run();
    }
}
