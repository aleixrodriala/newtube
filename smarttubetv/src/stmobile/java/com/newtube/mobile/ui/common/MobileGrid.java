package com.newtube.mobile.ui.common;

import android.content.Context;
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
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float widthDp = metrics.widthPixels / metrics.density;

        return Math.max(1, Math.round(widthDp / 460f));
    }
}
