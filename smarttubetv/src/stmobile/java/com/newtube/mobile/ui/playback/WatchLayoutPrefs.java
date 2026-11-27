package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * NEWTUBE: layout preference for the "Up next / related" videos list under the player: compact
 * horizontal rows (default) or full-width 16:9 cards.
 */
public final class WatchLayoutPrefs {

    private static final String PREFS_NAME = "newtube_watch_layout";
    private static final String KEY_RELATED_FULL_CARDS = "related_full_cards";

    private WatchLayoutPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isRelatedFullCards(Context context) {
        return prefs(context).getBoolean(KEY_RELATED_FULL_CARDS, false);
    }

    public static void setRelatedFullCards(Context context, boolean fullCards) {
        prefs(context).edit().putBoolean(KEY_RELATED_FULL_CARDS, fullCards).apply();
    }
}
