package com.liskovsoft.smartyoutubetv2.common.utils;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.util.regex.Pattern;

public class ClickbaitRemover {
    public static final int THUMB_QUALITY_DEFAULT = 0;
    public static final int THUMB_QUALITY_START = 1;
    public static final int THUMB_QUALITY_MIDDLE = 2;
    public static final int THUMB_QUALITY_END = 3;

    private static final Pattern THUMB_QUALITY_PATTERN = Pattern.compile("/(hq1|hq2|hq3|hqdefault|mqdefault|sddefault|hq720)\\.");

    public static String updateThumbnail(String thumbUrl, int thumbQuality) {
        if (thumbUrl == null || thumbQuality == THUMB_QUALITY_DEFAULT) {
            return thumbUrl;
        }

        String quality = "hqdefault";

        switch (thumbQuality) {
            case THUMB_QUALITY_START:
                quality = "hq1";
                break;
            case THUMB_QUALITY_MIDDLE:
                quality = "hq2";
                break;
            case THUMB_QUALITY_END:
                quality = "hq3";
                break;
        }

        return Helpers.replace(thumbUrl, THUMB_QUALITY_PATTERN, "/" + quality + ".");
    }

    // NEWTUBE(net): ask the CDN for a rendition that fits the view, instead of whatever InnerTube
    // happened to hand us.
    //
    // InnerTube returns sddefault (640x480, ~114 KB) for related/feed cards. A watch page binds a
    // dozen of those at once, so a single open pulled ~1.3 MB of thumbnails - measured on the
    // netshape rig at 1200 kbps, that flood halved the media rate for 9 s straight through the
    // window where the player is trying to build its first buffer. hqdefault is 480x360 and 25 KB;
    // for a 160dp row (480 px at 3x) it is a pixel-exact match, so those bytes bought nothing.
    //
    // Two rules keep this safe:
    //  - Only ever DOWNGRADE, and only within {mqdefault, hqdefault, sddefault}. hq720 and
    //    maxresdefault are not generated for every video, so "upgrading" into them 404s.
    //  - Leave hq1/hq2/hq3 alone. Those are the clickbait-remover's start/middle/end FRAME
    //    selectors, not sizes (they are 480x360 already, i.e. the right size regardless).
    private static final String[] FITTABLE_NAMES = {"mqdefault", "hqdefault", "sddefault"};
    private static final int[] FITTABLE_WIDTHS = {320, 480, 640};
    private static final Pattern FITTABLE_PATTERN = Pattern.compile("/(hqdefault|mqdefault|sddefault|hq720|maxresdefault)\\.");

    /**
     * Narrows {@code thumbUrl} to the smallest always-available rendition at least
     * {@code targetWidthPx} wide. Returns the URL untouched when it carries no recognisable
     * rendition token (DeArrow covers, custom hosts) or already asks for something smaller.
     */
    public static String fitThumbnail(String thumbUrl, int targetWidthPx) {
        if (thumbUrl == null || targetWidthPx <= 0) {
            return thumbUrl;
        }

        java.util.regex.Matcher matcher = FITTABLE_PATTERN.matcher(thumbUrl);
        if (!matcher.find()) {
            return thumbUrl;
        }

        String current = matcher.group(1);
        int currentWidth = Integer.MAX_VALUE; // hq720/maxresdefault: wider than anything we'd pick
        for (int i = 0; i < FITTABLE_NAMES.length; i++) {
            if (FITTABLE_NAMES[i].equals(current)) {
                currentWidth = FITTABLE_WIDTHS[i];
                break;
            }
        }

        for (int i = 0; i < FITTABLE_NAMES.length; i++) {
            if (FITTABLE_WIDTHS[i] >= targetWidthPx) {
                // Never widen: a small original stays small.
                return FITTABLE_WIDTHS[i] >= currentWidth
                        ? thumbUrl
                        : thumbUrl.substring(0, matcher.start()) + "/" + FITTABLE_NAMES[i] + "."
                                + thumbUrl.substring(matcher.end());
            }
        }

        return thumbUrl; // wider than every safe rendition - leave the original alone
    }

    public static String updateThumbnail(Video video, int thumbQuality) {
        if (video == null) {
            return null;
        }

        if (video.isLive || video.isUpcoming || video.altCardImageUrl != null) { // priority to DeArrow
            return video.getCardImageUrl();
        }

        return updateThumbnail(video.getCardImageUrl(), thumbQuality);
    }
}
