package com.newtube.mobile.ui.common;

import android.text.TextUtils;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

/**
 * NEWTUBE(history-thumbs): signed-out History is the app's LOCAL watch history, and its entries
 * are saved without an image URL - so every card was a grey box (Glide "Load failed for [null]").
 * A video's thumbnail lives at a fixed, public CDN path, so fill that in at render time. hqdefault
 * (480x360) is the rendition the feed cards already use for their width (ClickbaitRemover.fitThumbnail
 * notes); mqdefault would be visibly soft on a full-width card.
 */
public final class LocalThumbnails {
    private static final String URL_FORMAT = "https://i.ytimg.com/vi/%s/hqdefault.jpg";

    private LocalThumbnails() {
    }

    /** Give {@code video} a card image when it has a video id but no image at all. */
    public static void fill(Video video) {
        String url = fallbackFor(video);
        if (url != null) {
            video.cardImageUrl = url;
        }
    }

    /** The CDN thumbnail {@link #fill} would set, or null when the video needs none (or has no id). */
    static String fallbackFor(Video video) {
        if (video == null || video.getCardImageUrl() != null || TextUtils.isEmpty(video.videoId)) {
            return null;
        }
        return String.format(URL_FORMAT, video.videoId);
    }
}
