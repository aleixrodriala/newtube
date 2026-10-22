package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * NEWTUBE(gestures): the switches for the player's optional swipes (issue #12), all on by default:
 * up and down on the left of the fullscreen video sets the brightness, on the right the volume
 * (each with its own switch, like NewPipe), and sideways anywhere on the video seeks. With only one
 * of brightness and volume on, that one works on both sides ({@link #sideSwipe}). The fullscreen swipes (up into it, down out
 * of it) and swipe down to minimize are YouTube's own and always on.
 *
 * <p>A private preferences file, like the mobile flavor's other one-off stores (CastPrefs,
 * ThemeMode). The player reads the switches when a swipe starts, so a change applies to the next
 * swipe without a restart.</p>
 */
public final class PlayerGesturePrefs {

    private static final String PREFS_NAME = "newtube_gestures";
    /** Before brightness and volume had a switch each; still read as their default. */
    private static final String KEY_LEVEL_SWIPES = "level_swipes";
    private static final String KEY_BRIGHTNESS_SWIPE = "brightness_swipe";
    private static final String KEY_VOLUME_SWIPE = "volume_swipe";
    private static final String KEY_SEEK_SWIPE = "seek_swipe";

    private PlayerGesturePrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Swipe up and down on the left of the fullscreen video for brightness. */
    public static boolean isBrightnessSwipeOn(Context context) {
        SharedPreferences prefs = prefs(context);
        return prefs.getBoolean(KEY_BRIGHTNESS_SWIPE, prefs.getBoolean(KEY_LEVEL_SWIPES, true));
    }

    public static void setBrightnessSwipeOn(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_BRIGHTNESS_SWIPE, on).apply();
    }

    /** Swipe up and down on the right of the fullscreen video for volume. */
    public static boolean isVolumeSwipeOn(Context context) {
        SharedPreferences prefs = prefs(context);
        return prefs.getBoolean(KEY_VOLUME_SWIPE, prefs.getBoolean(KEY_LEVEL_SWIPES, true));
    }

    public static void setVolumeSwipeOn(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_VOLUME_SWIPE, on).apply();
    }

    public static final int SIDE_NONE = 0;
    public static final int SIDE_BRIGHTNESS = 1;
    public static final int SIDE_VOLUME = 2;

    /**
     * What an up-and-down swipe on one side of the video sets: brightness on the left and volume on
     * the right while both are on; with only one on, that one on either side, so it can be reached
     * with whichever thumb is free.
     */
    public static int sideSwipe(boolean leftSide, boolean brightnessOn, boolean volumeOn) {
        if (brightnessOn && volumeOn) {
            return leftSide ? SIDE_BRIGHTNESS : SIDE_VOLUME;
        }
        if (brightnessOn) {
            return SIDE_BRIGHTNESS;
        }
        return volumeOn ? SIDE_VOLUME : SIDE_NONE;
    }

    /** Swipe sideways on the video to seek. */
    public static boolean isSeekSwipeOn(Context context) {
        return prefs(context).getBoolean(KEY_SEEK_SWIPE, true);
    }

    public static void setSeekSwipeOn(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_SEEK_SWIPE, on).apply();
    }
}
