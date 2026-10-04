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
            return computeSpanCountForWidthDp(context, configuration.screenWidthDp);
        }

        // Old devices/configuration contexts can omit screenWidthDp. Keep a metrics fallback for
        // them, but prefer Configuration above: display metrics can briefly retain the player's
        // landscape/PiP window while another Activity is being brought back to the foreground.
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int widthDp = Math.round(metrics.widthPixels / metrics.density);
        return computeSpanCountForWidthDp(context, widthDp);
    }

    /**
     * Configuration-callback variant. Using the callback payload avoids reading display metrics
     * that may still describe the window from before a fullscreen/PiP transition.
     */
    public static int computeSpanCount(Context context, Configuration configuration) {
        if (configuration == null
                || configuration.screenWidthDp == Configuration.SCREEN_WIDTH_DP_UNDEFINED) {
            return 1;
        }
        return computeSpanCountForWidthDp(context, configuration.screenWidthDp);
    }

    static int computeSpanCountForWidthDp(Context context, int widthDp) {
        int layout = com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData.instance(context).getMobileFeedLayout();
        if (layout == com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData.MOBILE_FEED_LAYOUT_COMPACT) {
            return 1;
        }
        if (layout == com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData.MOBILE_FEED_LAYOUT_GRID) {
            return Math.max(2, Math.round(widthDp / 230f));
        }
        return Math.max(1, Math.round(widthDp / 460f));
    }
    public static void setupFeedSkeleton(android.content.Context context, android.view.ViewGroup skeleton) {
        if (skeleton == null) return;
        skeleton.removeAllViews();
        int layout = com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData.instance(context).getMobileFeedLayout();
        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(context);
        
        if (layout == com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData.MOBILE_FEED_LAYOUT_COMPACT) {
            for (int i = 0; i < 8; i++) {
                inflater.inflate(com.liskovsoft.smartyoutubetv2.tv.R.layout.item_mobile_skeleton_related_row, skeleton, true);
            }
        } else if (layout == com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData.MOBILE_FEED_LAYOUT_GRID) {
            int spanCount = computeSpanCount(context);
            for (int i = 0; i < 4; i++) {
                android.widget.LinearLayout row = new android.widget.LinearLayout(context);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                for (int j = 0; j < spanCount; j++) {
                    android.view.View item = inflater.inflate(com.liskovsoft.smartyoutubetv2.tv.R.layout.item_mobile_skeleton_feed_card, row, false);
                    android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) item.getLayoutParams();
                    lp.width = 0;
                    lp.weight = 1;
                    if (j < spanCount - 1) {
                        lp.setMarginEnd(context.getResources().getDimensionPixelSize(com.liskovsoft.smartyoutubetv2.tv.R.dimen.mobile_card_spacing));
                    }
                    row.addView(item);
                }
                skeleton.addView(row);
            }
        } else {
            for (int i = 0; i < 3; i++) {
                inflater.inflate(com.liskovsoft.smartyoutubetv2.tv.R.layout.item_mobile_skeleton_feed_card, skeleton, true);
            }
        }
    }
}