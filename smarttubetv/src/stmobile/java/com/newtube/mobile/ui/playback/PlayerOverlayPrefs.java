package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * NEWTUBE(issue #82): optional quick-resolution chip on the player overlay (bottom-right next to
 * the fullscreen button). Off by default to keep the stock YouTube-clean bar uncluttered.
 */
public final class PlayerOverlayPrefs {

    private static final String PREFS_NAME = "newtube_player_overlay";
    private static final String KEY_SHOW_QUALITY_BUTTON = "show_quality_button";

    private PlayerOverlayPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isQualityButtonShown(Context context) {
        return prefs(context).getBoolean(KEY_SHOW_QUALITY_BUTTON, false);
    }

    public static void setQualityButtonShown(Context context, boolean shown) {
        prefs(context).edit().putBoolean(KEY_SHOW_QUALITY_BUTTON, shown).apply();
    }
}
