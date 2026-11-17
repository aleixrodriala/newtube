package com.newtube.mobile.auto;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** NEWTUBE(android-auto): blocking feed loads for the car's music screen and voice search. */
final class AutoFeeds {
    static final String SUBSCRIPTIONS = "subs";
    static final String HOME = "home";
    static final String CONTINUE = "continue";
    static final String HISTORY = "history";
    static final String SEARCH = "search";
    static final String PLAYLIST_PREFIX = "pl:";

    private static final int MAX_ITEMS = 40;

    private AutoFeeds() {
    }

    /** Videos of a feed; empty on any failure. Never on the main thread. */
    static List<Video> load(String feed, @Nullable String query) {
        List<Video> videos = new ArrayList<>();
        try {
            ContentService content = YouTubeServiceManager.instance().getContentService();
            List<MediaGroup> groups = new ArrayList<>();
            switch (feed) {
                case SUBSCRIPTIONS: add(groups, content.getSubscriptions()); break;
                case HOME: addAll(groups, content.getHome()); break;
                case CONTINUE:
                case HISTORY: add(groups, content.getHistory()); break;
                case SEARCH: addAll(groups, content.getSearch(query)); break;
                default:
                    if (feed.startsWith(PLAYLIST_PREFIX)) {
                        MediaItem playlist = findPlaylist(feed.substring(PLAYLIST_PREFIX.length()));
                        if (playlist != null) add(groups, content.getGroup(playlist));
                    }
            }
            Set<String> seen = new HashSet<>();
            for (MediaGroup group : groups) {
                if (group == null || group.getMediaItems() == null) continue;
                for (MediaItem item : group.getMediaItems()) {
                    if (videos.size() >= MAX_ITEMS) break;
                    if (item == null || item.getVideoId() == null || item.getVideoId().isEmpty()) continue;
                    if (!seen.add(item.getVideoId())) continue;
                    Video video = Video.from(item);
                    if (video.isShorts) continue;
                    if (CONTINUE.equals(feed) && (video.percentWatched < 1 || video.percentWatched > 95)) continue;
                    videos.add(video);
                }
            }
        } catch (Throwable ignored) {
        }
        return videos;
    }

    /** The user's playlists (Watch later included). */
    static List<MediaItem> playlists() {
        List<MediaItem> result = new ArrayList<>();
        try {
            MediaGroup group = YouTubeServiceManager.instance().getContentService().getPlaylistsObserve().blockingFirst();
            if (group != null && group.getMediaItems() != null) {
                for (MediaItem item : group.getMediaItems()) {
                    if (item != null && item.getTitle() != null && item.getPlaylistId() != null) {
                        result.add(item);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    @Nullable
    private static MediaItem findPlaylist(String playlistId) {
        for (MediaItem item : playlists()) {
            if (playlistId.equals(item.getPlaylistId())) return item;
        }
        return null;
    }

    private static void add(List<MediaGroup> groups, @Nullable MediaGroup group) {
        if (group != null) groups.add(group);
    }

    private static void addAll(List<MediaGroup> groups, @Nullable List<MediaGroup> more) {
        if (more != null) groups.addAll(more);
    }
}
