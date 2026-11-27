package com.newtube.mobile.ui.playback;

import android.content.res.Configuration;
import android.view.OrientationEventListener;

/**
 * NEWTUBE(fullscreen): when the fullscreen button's forced orientation goes back to the sensor,
 * the way YouTube does it. The button forces landscape (or portrait on the way out) whatever way
 * the phone is held; once the phone is physically held that way, the force has done its job and
 * rotation follows the phone again - so turning the phone upright after entering fullscreen with
 * the button exits fullscreen, and a phone laid sideways after leaving it with the button enters
 * it again.
 *
 * <p>Fed with {@link OrientationEventListener} angles (0 = upright, 90/270 = on either side). The
 * phone has to stay in the target orientation for {@link #SETTLE_MS} before the hand-over, so the
 * system's own rotation sensor (which debounces too) already agrees when the request is freed -
 * freeing it earlier could let the display snap to the stale orientation for a frame.</p>
 */
final class OrientationHandBack {
    static final long SETTLE_MS = 1000;
    private static final int NONE = Configuration.ORIENTATION_UNDEFINED;

    private int mTarget = NONE;
    private long mMatchingSinceMs = -1;

    int getTarget() {
        return mTarget;
    }

    /** Start waiting for the phone to be held in {@code target} ({@code Configuration.ORIENTATION_*}). */
    void arm(int target) {
        mTarget = target;
        mMatchingSinceMs = -1;
    }

    void disarm() {
        mTarget = NONE;
        mMatchingSinceMs = -1;
    }

    /**
     * Readings stop (PiP, background): keep the target, forget the hold. Time without readings
     * says nothing about how the phone was held, so after a pause the phone has to settle again
     * from its next reading instead of handing back on it at once.
     */
    void pause() {
        mMatchingSinceMs = -1;
    }

    boolean isArmed() {
        return mTarget != NONE;
    }

    /**
     * How much longer the phone has to stay where it is before the hand-over, or -1 when it is
     * not in the target orientation. A phone held still may not report another angle (sensors
     * batch; the emulator reports changes only), so the caller re-checks after this long instead
     * of waiting for a reading that may never come.
     */
    long remainingMs(long nowMs) {
        if (!isArmed() || mMatchingSinceMs < 0) {
            return -1;
        }
        return Math.max(0, SETTLE_MS - (nowMs - mMatchingSinceMs));
    }

    /** @return true when the orientation request should be handed back to the sensor now. */
    boolean onOrientation(int degrees, long nowMs) {
        if (!isArmed()) {
            return false;
        }
        if (!matches(mTarget, degrees)) {
            mMatchingSinceMs = -1;
            return false;
        }
        if (mMatchingSinceMs < 0) {
            mMatchingSinceMs = nowMs;
        }
        return nowMs - mMatchingSinceMs >= SETTLE_MS;
    }

    /**
     * The phone is clearly held in {@code target}: within 30 degrees of upright for portrait, of
     * either side for landscape. Flat on a table ({@link OrientationEventListener#ORIENTATION_UNKNOWN})
     * and the diagonals in between match neither, so a wobble never hands over.
     */
    static boolean matches(int target, int degrees) {
        if (degrees == OrientationEventListener.ORIENTATION_UNKNOWN || degrees < 0) {
            return false;
        }
        int angle = degrees % 360;
        if (target == Configuration.ORIENTATION_LANDSCAPE) {
            return (angle >= 60 && angle <= 120) || (angle >= 240 && angle <= 300);
        }
        if (target == Configuration.ORIENTATION_PORTRAIT) {
            return angle <= 30 || angle >= 330;
        }
        return false;
    }
}
