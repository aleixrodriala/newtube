package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * NEWTUBE(clock): whether the fullscreen player shows the time beside the title (on by default).
 * A private preferences file, like PlayerGesturePrefs; the player reads it on every layout and on
 * resume, so a change in Settings shows on the way back.
 */
public final class PlayerClockPrefs {
    private static final String PREFS_NAME = "newtube_player_clock";
    private static final String KEY_CLOCK = "fullscreen_clock";

    private PlayerClockPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isClockOn(Context context) {
        return prefs(context).getBoolean(KEY_CLOCK, true);
    }

    public static void setClockOn(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_CLOCK, on).apply();
    }
}
