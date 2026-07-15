package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

/**
 * Shared feed/grid column math (Home, Search, Channel — was copy-pasted per activity).
 *
 * One full-width card per row on phone portrait (tester feedback: "bigger videos",
 * YouTube-style), ~460dp per column beyond that → 2 columns on phone landscape /
 * 8-10" tablet, 3 on wide tablets.
 */
public final class MobileGrid {
    private MobileGrid() {
    }

    public static int computeSpanCount(Context context) {
        Configuration configuration = context.getResources().getConfiguration();
        if (configuration.screenWidthDp != Configuration.SCREEN_WIDTH_DP_UNDEFINED) {
            return computeSpanCountForWidthDp(configuration.screenWidthDp);
        }

        // Old devices/configuration contexts can omit screenWidthDp. Keep a metrics fallback for
        // them, but prefer Configuration above: display metrics can briefly retain the player's
        // landscape/PiP window while another Activity is being brought back to the foreground.
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int widthDp = Math.round(metrics.widthPixels / metrics.density);
        return computeSpanCountForWidthDp(widthDp);
    }

    /**
     * Configuration-callback variant. Using the callback payload avoids reading display metrics
     * that may still describe the window from before a fullscreen/PiP transition.
     */
    public static int computeSpanCount(Configuration configuration) {
        if (configuration == null
                || configuration.screenWidthDp == Configuration.SCREEN_WIDTH_DP_UNDEFINED) {
            return 1;
        }
        return computeSpanCountForWidthDp(configuration.screenWidthDp);
    }

    static int computeSpanCountForWidthDp(int widthDp) {
        return Math.max(1, Math.round(widthDp / 460f));
    }
}
