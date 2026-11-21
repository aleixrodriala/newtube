package com.newtube.mobile.auto;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * NEWTUBE(android-auto): settings for the car screens, kept between connections.
 *
 * <p>{@code video_always}: the driver turned the parked gate off ("Vídeo sempre ligado"). While it
 * is on, the video plays with no speed/location check at all — the responsibility is the user's.
 */
public final class AutoPrefs {
    private static final String PREFS = "newtube_auto";
    private static final String KEY_VIDEO_ALWAYS = "video_always";

    private AutoPrefs() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isVideoAlwaysOn(Context context) {
        return prefs(context).getBoolean(KEY_VIDEO_ALWAYS, false);
    }

    public static void setVideoAlwaysOn(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_VIDEO_ALWAYS, on).apply();
    }
}
