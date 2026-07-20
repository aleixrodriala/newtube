package com.newtube.mobile.casting;

import android.app.Activity;
import android.view.KeyEvent;

import androidx.annotation.Nullable;

/**
 * Routes hardware volume keys to the TV while a cast session is connected (official-app
 * behavior): both host activities call {@link #onDispatchKeyEvent} first in their
 * {@code dispatchKeyEvent} and fall through to normal (local media volume) handling when it
 * returns false.
 *
 * <p>UP events for the volume keys are consumed too - letting them through would pop the system
 * volume panel over our adjustment. KEYCODE_VOLUME_MUTE deliberately passes through untouched
 * (muting the phone locally while casting is a reasonable thing to want).</p>
 *
 * <p>Indicator: {@link CastVolumeOverlay}, a floating slider pill - key presses nudge and show
 * it, and the slider itself is draggable for coarse jumps.</p>
 */
public final class CastVolumeKeys {

    private CastVolumeKeys() {
    }

    /** @return true when the event was consumed (TV volume adjusted, or the paired UP swallowed). */
    public static boolean onDispatchKeyEvent(Activity activity, @Nullable KeyEvent event) {
        if (event == null) {
            return false;
        }
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }
        CastSessionManager manager = CastSessionManager.instance(activity);
        if (!manager.isConnected()) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) { // key repeats included - each nudges again
            int delta = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                    ? CastSessionManager.VOLUME_STEP_PERCENT
                    : -CastSessionManager.VOLUME_STEP_PERCENT;
            int volumePercent = manager.adjustVolume(delta);
            if (volumePercent >= 0) {
                CastVolumeOverlay.show(activity, volumePercent);
            }
        }
        return true;
    }
}
