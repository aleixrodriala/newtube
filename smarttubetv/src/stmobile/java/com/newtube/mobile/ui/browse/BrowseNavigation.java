package com.newtube.mobile.ui.browse;

import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.common.misc.VideoDownloads;

import java.util.ArrayList;
import java.util.List;

/** The same enabled, renamed tabs on Browse and Search; other sections stay under You. */
public final class BrowseNavigation {
    public static final int ITEM_ID_OFFSET = 1_000_000;
    public static final int YOU_ITEM_ID = 2_000_000;
    private static final int[] IDS = {MediaGroup.TYPE_HOME, MediaGroup.TYPE_SUBSCRIPTIONS,
            MediaGroup.TYPE_HISTORY, VideoDownloads.SECTION_ID};
    private static final int[] TITLES = {R.string.header_home, R.string.header_subscriptions,
            R.string.header_history, R.string.header_downloads};
    private static final int[] ICONS = {R.drawable.ic_nav_home, R.drawable.ic_nav_subscriptions,
            R.drawable.ic_nav_history, R.drawable.ic_nav_downloads};

    public static List<BrowseSection> sections(Context context, List<BrowseSection> sections) {
        List<BrowseSection> chosen = new ArrayList<>();
        for (int i = 0; i < IDS.length; i++) {
            String fallback = context.getString(TITLES[i]);
            for (BrowseSection section : sections) {
                if (section.isEnabled() && section.getId() != MediaGroup.TYPE_SETTINGS && !chosen.contains(section)
                        && (section.getId() == IDS[i] || fallback.equals(section.getTitle()))) {
                    chosen.add(section);
                    break;
                }
            }
        }
        return chosen;
    }

    public static int iconFor(BrowseSection section) {
        for (int i = 0; i < IDS.length; i++) {
            if (section.getId() == IDS[i]) return ICONS[i];
        }
        return Math.max(section.getResId(), 0);
    }
}
