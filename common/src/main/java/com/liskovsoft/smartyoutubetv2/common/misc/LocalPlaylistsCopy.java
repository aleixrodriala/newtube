package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * NEWTUBE(playlists): copies the phone's playlists ({@link LocalPlaylists}) and Watch later
 * ({@link LocalWatchLater}) to the signed-in account, when the user says yes to the offer the phone
 * makes after signing in. Nothing on the phone is removed: signing out again shows it all as before.
 *
 * <p>Only what this account hasn't been given yet is copied ({@link Copied}, kept per account): a
 * second sign-in after saving one more video copies that one video, not every playlist again.</p>
 *
 * <p>YouTube's "create playlist" answers with no id, so each new playlist is found again by its
 * title among the playlists that now hold its first video ({@link #findCreated}), and the rest are
 * added to it. Playlists are made one at a time, so two with the same name can't be confused.</p>
 */
public final class LocalPlaylistsCopy {
    private static final String TAG = LocalPlaylistsCopy.class.getSimpleName();
    private static final String PREFS_NAME = "newtube_playlist_copies";
    private static final String WATCH_LATER = "wl";

    public interface Callback {
        /** On the main thread. {@code failed}: items YouTube didn't take (they stay on the phone). */
        void onDone(int playlists, int videos, int failed);
    }

    private LocalPlaylistsCopy() {
    }

    /**
     * What the signed-in account hasn't been given yet: {new playlists, new videos} (videos in
     * Watch later and in playlists it already has). {0, 0}: nothing to offer.
     */
    @NonNull
    public static int[] countNew(@NonNull Context context) {
        return countNew(context, accountKey());
    }

    @NonNull
    @VisibleForTesting
    static int[] countNew(@NonNull Context context, @NonNull String accountKey) {
        Copied copied = Copied.load(context, accountKey);
        int playlists = 0;
        int videos = 0;
        LocalPlaylists store = LocalPlaylists.instance(context);
        for (LocalPlaylists.LocalPlaylist playlist : store.playlists()) {
            if (!copied.hasPlaylist(playlist.id)) {
                playlists++;
            } else {
                videos += notCopied(copied, playlist.id, store.videos(playlist.id)).size();
            }
        }
        videos += notCopied(copied, WATCH_LATER, LocalWatchLater.instance(context).oldestFirst()).size();
        return new int[] {playlists, videos};
    }

    @NonNull
    public static Disposable copyToAccount(@NonNull Context context, @NonNull Callback callback) {
        Context app = context.getApplicationContext();
        MediaItemService service = YouTubeMediaItemService.instance();
        Copied copied = Copied.load(app, accountKey());
        LocalPlaylists store = LocalPlaylists.instance(app);
        List<LocalPlaylists.LocalPlaylist> playlists = store.playlists();

        AtomicInteger copiedPlaylists = new AtomicInteger();
        AtomicInteger copiedVideos = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        List<Completable> steps = new ArrayList<>();
        List<Video> watchLater = notCopied(copied, WATCH_LATER, LocalWatchLater.instance(app).oldestFirst());
        if (!watchLater.isEmpty()) {
            steps.add(copyWatchLater(service, watchLater, copied, copiedVideos, failed));
        }
        // The list is newest first; making the oldest first keeps the account's order the same.
        for (int i = playlists.size() - 1; i >= 0; i--) {
            LocalPlaylists.LocalPlaylist playlist = playlists.get(i);
            List<Video> videos = notCopied(copied, playlist.id, store.videos(playlist.id));
            if (!copied.hasPlaylist(playlist.id)) {
                steps.add(copyPlaylist(service, playlist, videos, copied, copiedPlaylists, copiedVideos, failed));
            } else if (!videos.isEmpty()) {
                steps.add(addToCopied(service, playlist, videos, copied, copiedVideos, failed));
            }
        }

        return Completable.concat(steps)
                .doFinally(copied::save)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        () -> callback.onDone(copiedPlaylists.get(), copiedVideos.get(), failed.get()),
                        error -> { // each step swallows its own errors; this is a safety net
                            Log.e(TAG, "Copy failed: %s", error.getMessage());
                            callback.onDone(copiedPlaylists.get(), copiedVideos.get(), failed.get() + 1);
                        });
    }

    /** Watch later is the first entry of any video's "add to playlist" list, as on YouTube. */
    private static Completable copyWatchLater(MediaItemService service, List<Video> videos, Copied copied,
                                              AtomicInteger copiedVideos, AtomicInteger failed) {
        return service.getPlaylistsInfoObserve(videos.get(0).videoId)
                .firstOrError()
                .flatMapCompletable(infos -> {
                    if (infos.isEmpty()) {
                        failed.addAndGet(videos.size());
                        return Completable.complete();
                    }
                    return addAll(service, infos.get(0).getPlaylistId(), WATCH_LATER, videos, copied, copiedVideos, failed);
                })
                .onErrorComplete(error -> {
                    Log.e(TAG, "Watch later: %s", error.getMessage());
                    failed.addAndGet(videos.size());
                    return true;
                });
    }

    /** A playlist this account doesn't have yet: create it, find its id, add the rest. */
    private static Completable copyPlaylist(MediaItemService service, LocalPlaylists.LocalPlaylist playlist,
                                            List<Video> videos, Copied copied, AtomicInteger copiedPlaylists,
                                            AtomicInteger copiedVideos, AtomicInteger failed) {
        String title = playlist.title;
        if (videos.isEmpty()) {
            return service.createPlaylistObserve(title, (com.liskovsoft.mediaserviceinterfaces.data.MediaItem) null)
                    .ignoreElements()
                    .doOnComplete(() -> {
                        copiedPlaylists.incrementAndGet();
                        copied.putPlaylist(playlist.id, null); // its id is found when it gets a video
                    })
                    .onErrorComplete(error -> {
                        Log.e(TAG, "Create %s: %s", title, error.getMessage());
                        failed.incrementAndGet();
                        return true;
                    });
        }

        Video first = videos.get(0);
        List<Video> rest = videos.subList(1, videos.size());
        return service.createPlaylistObserve(title, first.videoId)
                .ignoreElements()
                .andThen(Completable.defer(() -> {
                    copiedPlaylists.incrementAndGet();
                    copiedVideos.incrementAndGet();
                    copied.putVideo(playlist.id, first.videoId);
                    return service.getPlaylistsInfoObserve(first.videoId)
                            .firstOrError()
                            .flatMapCompletable(infos -> {
                                String id = findCreated(infos, title);
                                copied.putPlaylist(playlist.id, id);
                                if (rest.isEmpty()) {
                                    return Completable.complete();
                                }
                                if (id == null) {
                                    Log.e(TAG, "Created %s but can't find it", title);
                                    failed.addAndGet(rest.size());
                                    return Completable.complete();
                                }
                                return addAll(service, id, playlist.id, rest, copied, copiedVideos, failed);
                            });
                }))
                .onErrorComplete(error -> {
                    Log.e(TAG, "Copy %s: %s", title, error.getMessage());
                    failed.addAndGet(videos.size());
                    return true;
                });
    }

    /** A playlist copied before: just the videos it got since. */
    private static Completable addToCopied(MediaItemService service, LocalPlaylists.LocalPlaylist playlist,
                                           List<Video> videos, Copied copied, AtomicInteger copiedVideos,
                                           AtomicInteger failed) {
        String known = copied.remoteId(playlist.id);
        if (known != null) {
            return addAll(service, known, playlist.id, videos, copied, copiedVideos, failed);
        }
        // Copied while empty, so its id was never seen: find it by name among this video's list.
        return service.getPlaylistsInfoObserve(videos.get(0).videoId)
                .firstOrError()
                .flatMapCompletable(infos -> {
                    String id = findByTitle(infos, playlist.title);
                    if (id == null) {
                        failed.addAndGet(videos.size());
                        return Completable.complete();
                    }
                    copied.putPlaylist(playlist.id, id);
                    return addAll(service, id, playlist.id, videos, copied, copiedVideos, failed);
                })
                .onErrorComplete(error -> {
                    Log.e(TAG, "Add to %s: %s", playlist.title, error.getMessage());
                    failed.addAndGet(videos.size());
                    return true;
                });
    }

    /** One video at a time, in order; one YouTube refuses is counted and the rest still go. */
    private static Completable addAll(MediaItemService service, String playlistId, String localId, List<Video> videos,
                                      Copied copied, AtomicInteger copiedVideos, AtomicInteger failed) {
        List<Completable> adds = new ArrayList<>();
        for (Video video : videos) {
            adds.add(service.addToPlaylistObserve(playlistId, video.videoId)
                    .ignoreElements()
                    .doOnComplete(() -> {
                        copiedVideos.incrementAndGet();
                        copied.putVideo(localId, video.videoId);
                    })
                    .onErrorComplete(error -> {
                        failed.incrementAndGet();
                        return true;
                    }));
        }
        return Completable.concat(adds);
    }

    @NonNull
    private static List<Video> notCopied(@NonNull Copied copied, @NonNull String localId, @NonNull List<Video> videos) {
        List<Video> result = new ArrayList<>();
        for (Video video : videos) {
            if (!copied.hasVideo(localId, video.videoId)) {
                result.add(video);
            }
        }
        return result;
    }

    /**
     * The id of the playlist just created as {@code title}: among the playlists holding its first
     * video, the one with that name. Watch later (the first entry) is never it. More than one match
     * means the account already had a playlist of that name holding this video; either is a fair
     * home for the rest, and the last one is taken.
     */
    @Nullable
    @VisibleForTesting
    static String findCreated(@Nullable List<PlaylistInfo> infos, @NonNull String title) {
        return find(infos, title, true);
    }

    /** By name alone, for a playlist copied while it was empty (it holds none of these videos). */
    @Nullable
    @VisibleForTesting
    static String findByTitle(@Nullable List<PlaylistInfo> infos, @NonNull String title) {
        return find(infos, title, false);
    }

    @Nullable
    private static String find(@Nullable List<PlaylistInfo> infos, @NonNull String title, boolean mustHoldVideo) {
        if (infos == null) {
            return null;
        }
        String found = null;
        for (int i = 1; i < infos.size(); i++) {
            PlaylistInfo info = infos.get(i);
            if (info != null && (!mustHoldVideo || info.isSelected())
                    && title.trim().equals(info.getTitle() != null ? info.getTitle().trim() : null)) {
                found = info.getPlaylistId();
            }
        }
        return found;
    }

    /** Which account the copy is for: the same phone lists may go to two accounts, once each. */
    @NonNull
    private static String accountKey() {
        Account account = YouTubeSignInService.instance().getSelectedAccount();
        if (account == null) {
            return "none";
        }
        String email = account.getEmail();
        return email != null && !email.isEmpty() ? email : String.valueOf(account.getName());
    }

    /**
     * What one account has been given: phone playlist id to its account playlist id ("" while not
     * known), and "playlistId|videoId" for each video ({@code wl} for Watch later).
     */
    @VisibleForTesting
    static final class Copied {
        private final SharedPreferences mPrefs;
        private final String mKey;
        private final Map<String, String> mPlaylists = new HashMap<>();
        private final Set<String> mVideos = new HashSet<>();

        private Copied(SharedPreferences prefs, String key) {
            mPrefs = prefs;
            mKey = key;
        }

        static Copied load(@NonNull Context context, @NonNull String accountKey) {
            Copied copied = new Copied(context.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), accountKey);
            String json = copied.mPrefs.getString(accountKey, null);
            if (json != null) {
                try {
                    JSONObject root = new JSONObject(json);
                    JSONObject playlists = root.optJSONObject("playlists");
                    if (playlists != null) {
                        for (java.util.Iterator<String> it = playlists.keys(); it.hasNext(); ) {
                            String id = it.next();
                            copied.mPlaylists.put(id, playlists.optString(id, ""));
                        }
                    }
                    JSONArray videos = root.optJSONArray("videos");
                    for (int i = 0; videos != null && i < videos.length(); i++) {
                        copied.mVideos.add(videos.optString(i));
                    }
                } catch (JSONException e) {
                    // Forgetting means offering again; nothing is lost.
                }
            }
            return copied;
        }

        synchronized boolean hasPlaylist(String localId) {
            return mPlaylists.containsKey(localId);
        }

        @Nullable
        synchronized String remoteId(String localId) {
            String id = mPlaylists.get(localId);
            return id != null && !id.isEmpty() ? id : null;
        }

        synchronized void putPlaylist(String localId, @Nullable String remoteId) {
            mPlaylists.put(localId, remoteId != null ? remoteId : "");
        }

        synchronized boolean hasVideo(String localId, String videoId) {
            return mVideos.contains(localId + "|" + videoId);
        }

        synchronized void putVideo(String localId, String videoId) {
            mVideos.add(localId + "|" + videoId);
        }

        synchronized void save() {
            try {
                JSONObject playlists = new JSONObject();
                for (Map.Entry<String, String> entry : mPlaylists.entrySet()) {
                    playlists.put(entry.getKey(), entry.getValue());
                }
                JSONObject root = new JSONObject();
                root.put("playlists", playlists);
                root.put("videos", new JSONArray(mVideos));
                mPrefs.edit().putString(mKey, root.toString()).apply();
            } catch (JSONException e) {
                Log.e(TAG, "Can't save what was copied: %s", e.getMessage());
            }
        }
    }
}
