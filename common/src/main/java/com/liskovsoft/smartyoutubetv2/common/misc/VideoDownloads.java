package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.util.Collections;
import java.util.List;

/**
 * NEWTUBE(downloads): the seam between the shared menus and the phone-only download stack.
 *
 * <p>The card context menu and the player live in shared code; the downloader (format picker,
 * foreground service, storage) is a phone feature. The application installs a {@link Handler}
 * at startup, and the shared menus only offer "Download" while one is installed - so the shared
 * layer never references the phone classes and a build without the feature simply shows no item.
 */
public final class VideoDownloads {
    /**
     * Browse section id of the Downloads tab. Well clear of the {@code MediaGroup.TYPE_*} range
     * (0..22) so an upstream addition there can never alias it.
     */
    public static final int SECTION_ID = 200;

    public interface Handler {
        void requestDownload(Context context, Video video);

        /** Cards for the Downloads section, newest first; the SAME instances across calls. */
        List<Video> listSectionVideos();

        /** A finished, playable local copy of this video (content:// or file://), or null. */
        @Nullable
        String localUriFor(String videoId);
    }

    @Nullable
    private static Handler sHandler;

    private VideoDownloads() {
    }

    public static void setHandler(@Nullable Handler handler) {
        sHandler = handler;
    }

    public static boolean isAvailable() {
        return sHandler != null;
    }

    /** True when the item is something the downloader can take: an ordinary, finished video. */
    public static boolean canDownload(@Nullable Video video) {
        return isAvailable() && video != null && video.hasVideo() && !video.isLive && !video.isUpcoming
                && !video.isLocal();
    }

    public static List<Video> listSectionVideos() {
        Handler handler = sHandler;
        return handler != null ? handler.listSectionVideos() : Collections.<Video>emptyList();
    }

    @Nullable
    public static String localUriFor(@Nullable String videoId) {
        Handler handler = sHandler;
        return handler != null && videoId != null ? handler.localUriFor(videoId) : null;
    }

    public static void request(Context context, @Nullable Video video) {
        Handler handler = sHandler;
        if (handler != null && context != null && video != null) {
            handler.requestDownload(context, video);
        }
    }
}
