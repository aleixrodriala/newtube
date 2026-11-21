package com.newtube.mobile.auto;

import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.MediaBrowserServiceCompat;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NEWTUBE(android-auto): NewTube in the car's music screen. Same feeds as the car screens, sound only,
 * through the same {@link AutoPlayer} and media session. Unlike the navigation-category car app,
 * a media app needs only Android Auto's "Unknown sources", so NewTube stays reachable in the car
 * even if Android Auto ever hides the video screen. Also answers "play X on NewTube Auto".
 */
public class AutoMediaBrowserService extends MediaBrowserServiceCompat {
    private static final String ROOT = "root";
    private static final String PLAYLISTS = "playlists";
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);

    @Override
    public void onCreate() {
        super.onCreate();
        AutoCarAppService.initOnce(this);
        setSessionToken(AutoPlayer.get(this).ensureSession());
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        Bundle extras = new Bundle();
        extras.putBoolean("android.media.browse.SEARCH_SUPPORTED", true);
        return new BrowserRoot(ROOT, extras);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        if (ROOT.equals(parentId)) {
            List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
            items.add(folder(AutoFeeds.SUBSCRIPTIONS, "Subscrições"));
            items.add(folder(AutoFeeds.HOME, "Início"));
            items.add(folder(AutoFeeds.CONTINUE, "Continuar a ver"));
            items.add(folder(PLAYLISTS, "Playlists"));
            items.add(folder(AutoFeeds.HISTORY, "Histórico"));
            result.sendResult(items);
            return;
        }
        result.detach();
        IO.execute(() -> {
            List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
            if (PLAYLISTS.equals(parentId)) {
                for (MediaItem playlist : AutoFeeds.playlists()) {
                    items.add(folder(AutoFeeds.PLAYLIST_PREFIX + playlist.getPlaylistId(), playlist.getTitle()));
                }
            } else {
                items.addAll(playable(parentId, AutoFeeds.load(parentId, null)));
            }
            result.sendResult(items);
        });
    }

    @Override
    public void onSearch(@NonNull String query, @Nullable Bundle extras,
            @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.detach();
        IO.execute(() -> result.sendResult(playable("search:" + query, AutoFeeds.load(AutoFeeds.SEARCH, query))));
    }

    private List<MediaBrowserCompat.MediaItem> playable(String feedId, List<Video> videos) {
        AutoPlayer.get(this).putCatalog(feedId, videos);
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        for (int i = 0; i < videos.size(); i++) {
            Video video = videos.get(i);
            MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                    .setMediaId(AutoPlayer.mediaId(feedId, i))
                    .setTitle(video.title)
                    .setSubtitle(video.getAuthor())
                    .build();
            items.add(new MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
        }
        return items;
    }

    private static MediaBrowserCompat.MediaItem folder(String id, String title) {
        return new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                .setMediaId(id).setTitle(title).build(), MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }
}
