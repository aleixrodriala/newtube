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
import io.reactivex.rxjava3.core.Single;
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
 * <p>YouTube's "create playlist" answers with no id, so each new playlist is found again among the
 * playlists that now hold its first video: the one with its title that wasn't there before
 * ({@link #findCreated}), and the rest are added to it.</p>
 *
 * <p>MediaServiceCore drops YouTube's answer to an add, so a video YouTube refuses (a full or
 * deleted playlist, a private video, too many requests) looks added. Only what YouTube's own list of
 * a video's playlists shows ({@link #holds}) counts as copied; the rest stays to copy.</p>
 *
 * <p>What was copied is saved as it happens, not at the end: a copy cut short (the process killed,
 * a lookup failed) picks up where it stopped, and never makes a playlist twice.</p>
 */
public final class LocalPlaylistsCopy {
    private static final String TAG = LocalPlaylistsCopy.class.getSimpleName();
    private static final String PREFS_NAME = "newtube_playlist_copies";
    private static final String WATCH_LATER = "wl";
    /** The account's Watch later: first in every video's "add to playlist" list from YouTube. */
    private static final String YOUTUBE_WATCH_LATER = "WL";
    /** Per account: the phone's save count when it last had nothing to be offered ({@link #whatToOffer}). */
    private static final String KEY_SETTLED = "settled:";

    public interface Callback {
        /** On the main thread. */
        void onDone(@NonNull Outcome outcome);
    }

    /** What a copy did. Filled in as it goes: its steps run one after another, on more than one thread. */
    public static final class Outcome {
        final AtomicInteger playlists = new AtomicInteger();
        final AtomicInteger videos = new AtomicInteger();
        final AtomicInteger failedPlaylists = new AtomicInteger();
        final AtomicInteger failedVideos = new AtomicInteger();
        volatile boolean stopped;

        /** Playlists made on the account. */
        public int playlists() {
            return playlists.get();
        }

        /** Videos the account was given (Watch later and playlists) and shows. */
        public int videos() {
            return videos.get();
        }

        /** Playlists that couldn't be made, or found once made: they stay to copy. */
        public int failedPlaylists() {
            return failedPlaylists.get();
        }

        /** Videos YouTube didn't take, or doesn't show: they stay to copy. */
        public int failedVideos() {
            return failedVideos.get();
        }

        /** Another account was signed in (or none) partway, so the copy stopped there. */
        public boolean stopped() {
            return stopped;
        }
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

    /**
     * What to offer the signed-in account ({@link #countNew}), or null when there is nothing to ask:
     * nothing new for it, or nothing saved on the phone since it last had nothing new or was answered
     * "Keep on phone" ({@link #keepOnPhone}). Per account: copying to one says nothing about another.
     * A copy that didn't get everything leaves something new, so it is offered again.
     */
    @Nullable
    public static int[] whatToOffer(@NonNull Context context) {
        return whatToOffer(context, accountKey());
    }

    @Nullable
    @VisibleForTesting
    static int[] whatToOffer(@NonNull Context context, @NonNull String accountKey) {
        long saves = saves(context);
        if (saves <= prefs(context).getLong(KEY_SETTLED + accountKey, 0)) {
            return null; // the usual answer, and a cheap one: Browse asks on every visit
        }
        int[] counts = countNew(context, accountKey);
        if (counts[0] == 0 && counts[1] == 0) {
            settle(context, accountKey, saves); // all of it is on this account already
            return null;
        }
        return counts;
    }

    /** "Keep on phone": this account isn't asked again until something more is saved. */
    public static void keepOnPhone(@NonNull Context context) {
        keepOnPhone(context, accountKey());
    }

    @VisibleForTesting
    static void keepOnPhone(@NonNull Context context, @NonNull String accountKey) {
        settle(context, accountKey, saves(context));
    }

    private static void settle(@NonNull Context context, @NonNull String accountKey, long saves) {
        prefs(context).edit().putLong(KEY_SETTLED + accountKey, saves).apply();
    }

    /** Every save on the phone so far, Watch later and playlists: it only grows. */
    private static long saves(@NonNull Context context) {
        return LocalPlaylists.instance(context).saves() + LocalWatchLater.instance(context).saves();
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @NonNull
    public static Disposable copyToAccount(@NonNull Context context, @NonNull Callback callback) {
        String account = accountKey();
        Outcome outcome = new Outcome();
        return copy(context.getApplicationContext(), YouTubeMediaItemService.instance(), account, outcome)
                // Each request goes to whoever is signed in when it is sent: past a switch, the rest
                // would land on the other account and be recorded as this one's.
                .ambWith(untilAccountChanges(account))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        () -> callback.onDone(outcome),
                        error -> {
                            if (error instanceof AccountChanged) {
                                outcome.stopped = true;
                            } else { // each step handles its own errors; this is a safety net
                                Log.e(TAG, "Copy failed: %s", error.getMessage());
                                outcome.failedVideos.incrementAndGet();
                            }
                            callback.onDone(outcome);
                        });
    }

    /** Fails with {@link AccountChanged} once another account, or none, is signed in. */
    private static Completable untilAccountChanges(@NonNull String account) {
        return Completable.create(emitter -> {
            MediaServiceManager.AccountChangeListener listener = changed -> {
                if (!account.equals(accountKey())) {
                    emitter.tryOnError(new AccountChanged());
                }
            };
            MediaServiceManager.instance().addAccountListener(listener);
            emitter.setCancellable(() -> MediaServiceManager.instance().removeAccountListener(listener));
        });
    }

    /** The account a copy was for is no longer the signed-in one. */
    private static final class AccountChanged extends Exception {
        AccountChanged() {
            super("The signed-in account changed");
        }
    }

    /** The copy to {@code account}: Watch later, then the playlists, oldest first. */
    @NonNull
    @VisibleForTesting
    static Completable copy(@NonNull Context app, @NonNull MediaItemService service, @NonNull String account,
                            @NonNull Outcome outcome) {
        return Completable.defer(() -> {
            Copied copied = Copied.load(app, account);
            LocalPlaylists store = LocalPlaylists.instance(app);
            List<LocalPlaylists.LocalPlaylist> playlists = store.playlists();

            List<Completable> steps = new ArrayList<>();
            List<Video> watchLater = notCopied(copied, WATCH_LATER, LocalWatchLater.instance(app).oldestFirst());
            if (!watchLater.isEmpty()) {
                steps.add(addAll(service, YOUTUBE_WATCH_LATER, WATCH_LATER, watchLater, copied, outcome));
            }
            // The list is newest first; making the oldest first keeps the account's order the same.
            for (int i = playlists.size() - 1; i >= 0; i--) {
                LocalPlaylists.LocalPlaylist playlist = playlists.get(i);
                List<Video> videos = notCopied(copied, playlist.id, store.videos(playlist.id));
                if (!copied.hasPlaylist(playlist.id)) {
                    steps.add(copyPlaylist(service, playlist, videos, copied, outcome));
                } else if (!videos.isEmpty()) {
                    steps.add(addToCopied(service, playlist, videos, copied, outcome));
                }
            }
            return Completable.concat(steps);
        });
    }

    /**
     * A playlist this account doesn't have yet: note the account's playlists, make it with its first
     * video, find its id (the one with its name that wasn't there before), add the rest. It is saved
     * as made the moment the create returns, before the lookup, so a run that stops in between
     * finds it by name next time ({@link #addToCopied}) instead of making a second one.
     */
    private static Completable copyPlaylist(MediaItemService service, LocalPlaylists.LocalPlaylist playlist,
                                            List<Video> videos, Copied copied, Outcome outcome) {
        String title = playlist.title;
        if (videos.isEmpty()) {
            return service.createPlaylistObserve(title, (com.liskovsoft.mediaserviceinterfaces.data.MediaItem) null)
                    .ignoreElements()
                    .doOnComplete(() -> {
                        outcome.playlists.incrementAndGet();
                        copied.putPlaylist(playlist.id, null); // its id is found when it gets a video
                        copied.save();
                    })
                    .onErrorComplete(error -> {
                        Log.e(TAG, "Create %s: %s", title, error.getMessage());
                        outcome.failedPlaylists.incrementAndGet();
                        return true;
                    });
        }

        Video first = videos.get(0);
        List<Video> rest = videos.subList(1, videos.size());
        return playlistsOf(service, first.videoId)
                .flatMapCompletable(before -> {
                    if (!answered(before)) {
                        Log.e(TAG, "Copy %s: no answer from YouTube", title);
                        outcome.failedPlaylists.incrementAndGet();
                        return Completable.complete();
                    }
                    return service.createPlaylistObserve(title, first.videoId)
                            .ignoreElements()
                            .andThen(Completable.defer(() -> {
                                copied.putPlaylist(playlist.id, null);
                                copied.save();
                                return playlistsOf(service, first.videoId).flatMapCompletable(after -> {
                                    String id = findCreated(after, title, before);
                                    if (id == null) {
                                        Log.e(TAG, "Created %s but can't find it", title);
                                        if (answered(after)) {
                                            // MediaServiceCore reports no refusal: not in YouTube's
                                            // own list is not made, so it is made again next time.
                                            copied.forgetPlaylist(playlist.id);
                                            copied.save();
                                        }
                                        outcome.failedPlaylists.incrementAndGet();
                                        return Completable.complete();
                                    }
                                    outcome.playlists.incrementAndGet();
                                    outcome.videos.incrementAndGet();
                                    copied.putPlaylist(playlist.id, id);
                                    copied.putVideo(playlist.id, first.videoId);
                                    copied.save();
                                    return addAll(service, id, playlist.id, rest, copied, outcome);
                                });
                            }));
                })
                .onErrorComplete(error -> {
                    Log.e(TAG, "Copy %s: %s", title, error.getMessage());
                    outcome.failedPlaylists.incrementAndGet();
                    return true;
                });
    }

    /** A playlist copied before: just the videos it got since. */
    private static Completable addToCopied(MediaItemService service, LocalPlaylists.LocalPlaylist playlist,
                                           List<Video> videos, Copied copied, Outcome outcome) {
        String known = copied.remoteId(playlist.id);
        if (known != null) {
            return addAll(service, known, playlist.id, videos, copied, outcome);
        }
        // Made, but its id never seen (made empty, or the last run stopped before finding it): find
        // it by name among this video's list.
        Video first = videos.get(0);
        return playlistsOf(service, first.videoId)
                .flatMapCompletable(infos -> {
                    if (!answered(infos)) {
                        outcome.failedVideos.addAndGet(videos.size());
                        return Completable.complete();
                    }
                    String id = findByTitle(infos, playlist.title);
                    if (id == null) {
                        // Not on the account after all: the create was refused. Made again now.
                        copied.forgetPlaylist(playlist.id);
                        copied.save();
                        return copyPlaylist(service, playlist, videos, copied, outcome);
                    }
                    copied.putPlaylist(playlist.id, id);
                    List<Video> rest = videos;
                    if (holds(infos, id)) { // made with it by the run that stopped
                        outcome.videos.incrementAndGet();
                        copied.putVideo(playlist.id, first.videoId);
                        rest = videos.subList(1, videos.size());
                    }
                    copied.save();
                    return addAll(service, id, playlist.id, rest, copied, outcome);
                })
                .onErrorComplete(error -> {
                    Log.e(TAG, "Add to %s: %s", playlist.title, error.getMessage());
                    outcome.failedVideos.addAndGet(videos.size());
                    return true;
                });
    }

    /**
     * One video at a time, in order. Each counts as copied once YouTube's own list of the video's
     * playlists shows it in {@code playlistId}; one that isn't is counted and the rest still go.
     */
    private static Completable addAll(MediaItemService service, String playlistId, String localId, List<Video> videos,
                                      Copied copied, Outcome outcome) {
        List<Completable> adds = new ArrayList<>();
        for (Video video : videos) {
            adds.add(Completable.defer(() -> service.addToPlaylistObserve(playlistId, video.videoId)
                    .ignoreElements()
                    .andThen(playlistsOf(service, video.videoId))
                    .flatMapCompletable(infos -> {
                        if (holds(infos, playlistId)) {
                            outcome.videos.incrementAndGet();
                            copied.putVideo(localId, video.videoId);
                            copied.save();
                        } else {
                            outcome.failedVideos.incrementAndGet();
                        }
                        return Completable.complete();
                    })
                    .onErrorComplete(error -> {
                        outcome.failedVideos.incrementAndGet();
                        return true;
                    })));
        }
        return Completable.concat(adds);
    }

    /** YouTube's "add to playlist" list for a video: the account's playlists, each saying if it holds it. */
    private static Single<List<PlaylistInfo>> playlistsOf(MediaItemService service, String videoId) {
        return service.getPlaylistsInfoObserve(videoId).firstOrError();
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
     * True for an answer that came from YouTube. MediaServiceCore's PlaylistServiceWrapper mixes its
     * own phone-side copies of playlists into the list, and when YouTube's answer fails it answers
     * with those alone, each "holding" what it was last told to add. YouTube's own answer starts with
     * Watch later; that list starts with the playlist touched last.
     */
    @VisibleForTesting
    static boolean answered(@Nullable List<PlaylistInfo> infos) {
        return infos != null && !infos.isEmpty() && infos.get(0) != null
                && YOUTUBE_WATCH_LATER.equals(infos.get(0).getPlaylistId());
    }

    /** True when YouTube's answer for a video shows it in {@code playlistId}. */
    @VisibleForTesting
    static boolean holds(@Nullable List<PlaylistInfo> infos, @NonNull String playlistId) {
        if (!answered(infos)) {
            return false;
        }
        for (PlaylistInfo info : infos) {
            if (info != null && playlistId.equals(info.getPlaylistId())) {
                return info.isSelected();
            }
        }
        return false;
    }

    /**
     * The id of the playlist just made as {@code title}: in YouTube's answer for its first video, the
     * one with that name, holding the video, that wasn't among the playlists {@code before} it was
     * made. Not the order: an older playlist of the same name may hold the same video, and the list
     * puts no promise on which comes first.
     */
    @Nullable
    @VisibleForTesting
    static String findCreated(@Nullable List<PlaylistInfo> infos, @NonNull String title,
                              @Nullable List<PlaylistInfo> before) {
        if (!answered(infos)) {
            return null;
        }
        Set<String> known = new HashSet<>();
        for (int i = 0; before != null && i < before.size(); i++) {
            if (before.get(i) != null) {
                known.add(before.get(i).getPlaylistId());
            }
        }
        for (PlaylistInfo info : infos) {
            if (info != null && info.isSelected() && isAccountPlaylistId(info.getPlaylistId())
                    && !known.contains(info.getPlaylistId()) && hasTitle(info, title)) {
                return info.getPlaylistId();
            }
        }
        return null;
    }

    /**
     * By name, for a playlist copied while it was empty: one holding the video if there is one,
     * else the first of that name.
     */
    @Nullable
    @VisibleForTesting
    static String findByTitle(@Nullable List<PlaylistInfo> infos, @NonNull String title) {
        if (!answered(infos)) {
            return null;
        }
        String found = null;
        for (PlaylistInfo info : infos) {
            if (info != null && isAccountPlaylistId(info.getPlaylistId()) && hasTitle(info, title)) {
                if (info.isSelected()) {
                    return info.getPlaylistId();
                }
                if (found == null) {
                    found = info.getPlaylistId();
                }
            }
        }
        return found;
    }

    private static boolean hasTitle(@NonNull PlaylistInfo info, @NonNull String title) {
        return title.trim().equals(info.getTitle() != null ? info.getTitle().trim() : null);
    }

    /**
     * A playlist someone made on YouTube: its id starts with "PL" (18 or 34 characters). The
     * wrapper's phone-side copy of a playlist YouTube refused to make has a made-up 12-character id,
     * and must never be taken for the one just made.
     */
    private static boolean isAccountPlaylistId(@Nullable String id) {
        return id != null && id.startsWith("PL") && id.length() > 12;
    }

    /** Which account the copy is for: the same phone lists may go to two accounts, once each. */
    @NonNull
    public static String accountKey() {
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

        synchronized void forgetPlaylist(String localId) {
            mPlaylists.remove(localId);
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
