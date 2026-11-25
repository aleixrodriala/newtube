package com.newtube.mobile.ui.common;

import android.text.TextUtils;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;

/** Controls whether video card titles and details are shown in full. */
public final class VideoCardText {
    private VideoCardText() {
    }

    public static void applyLineLimits(TextView title, TextView details) {
        boolean expanded = AppPrefs.instance(title.getContext()).isFullCardTextEnabled();
        setLineLimit(title, expanded, 2);
        setLineLimit(details, expanded, 1);
    }

    private static void setLineLimit(TextView view, boolean expanded, int compactLines) {
        int maxLines = expanded ? Integer.MAX_VALUE : compactLines;
        if (view.getMaxLines() != maxLines) {
            view.setMaxLines(maxLines);
            view.setEllipsize(expanded ? null : TextUtils.TruncateAt.END);
        }
    }
}
