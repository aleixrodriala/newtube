package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * NEWTUBE(gestures): the switches for the player's optional swipes (issue #12), both on by default:
 * up and down on the sides of the fullscreen video set the brightness (left) and the volume
 * (right), and sideways anywhere on the video seeks. The fullscreen swipes (up into it, down out
 * of it) and swipe down to minimize are YouTube's own and always on.
 *
 * <p>A private preferences file, like the mobile flavor's other one-off stores (CastPrefs,
 * ThemeMode). The player reads the switches when a swipe starts, so a change applies to the next
 * swipe without a restart.</p>
 */
public final class PlayerGesturePrefs {

    private static final String PREFS_NAME = "newtube_gestures";
    private static final String KEY_LEVEL_SWIPES = "level_swipes";
    private static final String KEY_SEEK_SWIPE = "seek_swipe";
    private static final String KEY_DOUBLE_TAP_SEEK_SECONDS = "double_tap_seek_seconds";

    public static final int DEFAULT_DOUBLE_TAP_SEEK_SECONDS = 10;
    public static final int[] DOUBLE_TAP_SEEK_OPTIONS = {3, 5, 10, 15, 20, 25, 30, 60};

    private PlayerGesturePrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Swipe up and down on the left (brightness) and right (volume) of the fullscreen video. */
    public static boolean isLevelSwipesOn(Context context) {
        return prefs(context).getBoolean(KEY_LEVEL_SWIPES, true);
    }

    public static void setLevelSwipesOn(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_LEVEL_SWIPES, on).apply();
    }

    /** Swipe sideways on the video to seek. */
    public static boolean isSeekSwipeOn(Context context) {
        return prefs(context).getBoolean(KEY_SEEK_SWIPE, true);
    }

    public static void setSeekSwipeOn(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_SEEK_SWIPE, on).apply();
    }

    /** Duration in seconds for each double-tap skip forward or rewind. */
    public static int getDoubleTapSeekSeconds(Context context) {
        int stored = prefs(context).getInt(KEY_DOUBLE_TAP_SEEK_SECONDS, DEFAULT_DOUBLE_TAP_SEEK_SECONDS);
        return stored > 0 ? stored : DEFAULT_DOUBLE_TAP_SEEK_SECONDS;
    }

    public static void setDoubleTapSeekSeconds(Context context, int seconds) {
        int normalized = seconds > 0 ? seconds : DEFAULT_DOUBLE_TAP_SEEK_SECONDS;
        prefs(context).edit().putInt(KEY_DOUBLE_TAP_SEEK_SECONDS, normalized).apply();
    }
}
