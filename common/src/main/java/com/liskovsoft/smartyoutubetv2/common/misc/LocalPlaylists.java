package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * NEWTUBE(playlists): playlists for a signed-out phone, kept on the phone (Watch later has its own
 * store, {@link LocalWatchLater}). Signed in, playlists are the account's and these stay put, for
 * signing out again, or for copying to the account ({@link LocalPlaylistsCopy}), which the phone
 * offers once after signing in ({@link #isCopyOfferPending}).
 *
 * <p>Each playlist has an id that can never be a YouTube one ({@link #ID_PREFIX}): its card carries
 * that id as {@code playlistId}, which is how the screens that open a playlist tell a phone
 * playlist from an account one ({@link #isLocalId}). A private preferences file, so the app's
 * backup carries it.</p>
 */
public final class LocalPlaylists {
    /** YouTube playlist ids are letters, digits, '-' and '_'; none starts with this. */
    public static final String ID_PREFIX = "NTLOCAL:";

    private static final String PREFS_NAME = "newtube_playlists";
    private static final String KEY_PLAYLISTS = "playlists";
    private static final String KEY_COPY_OFFER = "copy_offer_pending";
    /** Per playlist, so a mistaken paste can't grow the file without end. */
    private static final int MAX_VIDEOS = 5000;

    /** One playlist: its videos in the order they were added. */
    public static final class LocalPlaylist {
        public final String id;
        public String title;
        final List<Video> videos = new ArrayList<>();

        LocalPlaylist(String id, String title) {
            this.id = id;
            this.title = title;
        }

        public int size() {
            return videos.size();
        }
    }

    private static volatile LocalPlaylists sInstance;

    private final SharedPreferences mPrefs;
    private List<LocalPlaylist> mPlaylists;

    public static LocalPlaylists instance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (LocalPlaylists.class) {
                if (sInstance == null) {
                    sInstance = new LocalPlaylists(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    @VisibleForTesting
    static void resetForTests() {
        sInstance = null;
    }

    private LocalPlaylists(Context context) {
        mPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isLocalId(@Nullable String playlistId) {
        return playlistId != null && playlistId.startsWith(ID_PREFIX);
    }

    /** A phone playlist's card (not a video in one). */
    public static boolean isLocalPlaylistCard(@Nullable Video video) {
        return video != null && video.videoId == null && isLocalId(video.playlistId);
    }

    // ---------------------------------------------------------------------------------------------
    // Playlists
    // ---------------------------------------------------------------------------------------------

    /** Newest first, like YouTube's Playlists page. */
    @NonNull
    public synchronized List<LocalPlaylist> playlists() {
        return Collections.unmodifiableList(new ArrayList<>(playlistsInt()));
    }

    @Nullable
    public synchronized LocalPlaylist get(@Nullable String id) {
        for (LocalPlaylist playlist : playlistsInt()) {
            if (playlist.id.equals(id)) {
                return playlist;
            }
        }
        return null;
    }

    /** A new playlist named {@code title}, first in the list; its id. */
    @NonNull
    public synchronized String create(@NonNull String title, @Nullable Video firstVideo) {
        LocalPlaylist playlist = new LocalPlaylist(ID_PREFIX + UUID.randomUUID(), title.trim());
        if (LocalWatchLater.canSave(firstVideo)) {
            playlist.videos.add(LocalWatchLater.entryFor(firstVideo));
        }
        playlistsInt().add(0, playlist);
        persist(true);
        return playlist.id;
    }

    public synchronized void rename(@Nullable String id, @NonNull String title) {
        LocalPlaylist playlist = get(id);
        if (playlist != null && !title.trim().isEmpty()) {
            playlist.title = title.trim();
            persist(false);
        }
    }

    public synchronized void delete(@Nullable String id) {
        LocalPlaylist playlist = get(id);
        if (playlist != null) {
            playlistsInt().remove(playlist);
            persist(false);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Videos
    // ---------------------------------------------------------------------------------------------

    public synchronized boolean contains(@Nullable String id, @Nullable String videoId) {
        LocalPlaylist playlist = get(id);
        return playlist != null && indexOf(playlist, videoId) >= 0;
    }

    /** True when {@code videoId} is in any phone playlist. */
    public synchronized boolean containsAnywhere(@Nullable String videoId) {
        for (LocalPlaylist playlist : playlistsInt()) {
            if (indexOf(playlist, videoId) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Adds {@code video} at the end, once. */
    public synchronized void add(@Nullable String id, @Nullable Video video) {
        LocalPlaylist playlist = get(id);
        if (playlist == null || !LocalWatchLater.canSave(video) || indexOf(playlist, video.videoId) >= 0
                || playlist.videos.size() >= MAX_VIDEOS) {
            return;
        }
        playlist.videos.add(LocalWatchLater.entryFor(video));
        persist(true);
    }

    public synchronized void remove(@Nullable String id, @Nullable String videoId) {
        LocalPlaylist playlist = get(id);
        int index = playlist != null ? indexOf(playlist, videoId) : -1;
        if (index >= 0) {
            playlist.videos.remove(index);
            persist(false);
        }
    }

    /**
     * A playlist's videos, as cards for its page: copies, each knowing the playlist it is listed in
     * ({@link Video#localPlaylistId}) so its menu can offer "Remove from".
     */
    @NonNull
    public synchronized List<Video> videos(@Nullable String id) {
        List<Video> result = new ArrayList<>();
        LocalPlaylist playlist = get(id);
        if (playlist != null) {
            for (Video video : playlist.videos) {
                Video card = LocalWatchLater.entryFor(video);
                card.localPlaylistId = playlist.id;
                result.add(card);
            }
        }
        return result;
    }

    /** The cards for the Playlists section: one per playlist, newest first. */
    @NonNull
    public synchronized List<Video> cards(@NonNull Context context) {
        List<Video> result = new ArrayList<>();
        for (LocalPlaylist playlist : playlistsInt()) {
            result.add(cardFor(context, playlist));
        }
        return result;
    }

    @NonNull
    public static Video cardFor(@NonNull Context context, @NonNull LocalPlaylist playlist) {
        Video card = new Video();
        card.playlistId = playlist.id;
        card.title = playlist.title;
        card.itemType = MediaItem.TYPE_PLAYLIST;
        int count = playlist.videos.size();
        String videos = context.getResources().getQuantityString(
                R.plurals.local_playlist_videos, count, count);
        card.secondTitle = videos;
        card.badge = videos;
        if (count > 0) {
            Video first = playlist.videos.get(0);
            card.cardImageUrl = first.cardImageUrl;
            card.bgImageUrl = first.bgImageUrl;
        }
        return card;
    }

    // ---------------------------------------------------------------------------------------------
    // Playing
    // ---------------------------------------------------------------------------------------------

    /**
     * Plays {@code videos} in order from {@code start} (shuffled first when asked) through the
     * playback queue: a phone playlist has no YouTube id the player could page through, and the
     * queue is what plays a list of videos one after another.
     */
    public static void play(@NonNull Context context, @NonNull List<Video> videos, @Nullable Video start, boolean shuffle) {
        List<Video> order = new ArrayList<>();
        for (Video video : videos) {
            if (video != null && video.hasVideo()) {
                Video item = LocalWatchLater.entryFor(video);
                item.fromQueue = true;
                order.add(item);
            }
        }
        if (order.isEmpty()) {
            return;
        }
        if (shuffle) {
            Collections.shuffle(order);
        }

        Video first = order.get(0);
        if (!shuffle && start != null) {
            for (Video item : order) {
                if (item.videoId.equals(start.videoId)) {
                    first = item;
                    break;
                }
            }
        }

        Playlist queue = Playlist.instance();
        queue.clear();
        queue.addAll(order);
        queue.setCurrent(first);
        PlaybackPresenter.instance(context).openVideo(first);
    }

    // ---------------------------------------------------------------------------------------------
    // Copying to an account
    // ---------------------------------------------------------------------------------------------

    /** Something was saved on the phone since the last offer to copy it to an account. */
    public boolean isCopyOfferPending() {
        return mPrefs.getBoolean(KEY_COPY_OFFER, false);
    }

    public void setCopyOfferPending(boolean pending) {
        mPrefs.edit().putBoolean(KEY_COPY_OFFER, pending).apply();
    }

    // ---------------------------------------------------------------------------------------------
    // Storage
    // ---------------------------------------------------------------------------------------------

    private static int indexOf(@NonNull LocalPlaylist playlist, @Nullable String videoId) {
        if (videoId == null) {
            return -1;
        }
        for (int i = 0; i < playlist.videos.size(); i++) {
            if (videoId.equals(playlist.videos.get(i).videoId)) {
                return i;
            }
        }
        return -1;
    }

    @NonNull
    private List<LocalPlaylist> playlistsInt() {
        if (mPlaylists == null) {
            mPlaylists = new ArrayList<>();
            String json = mPrefs.getString(KEY_PLAYLISTS, null);
            if (json != null) {
                try {
                    JSONArray array = new JSONArray(json);
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject object = array.optJSONObject(i);
                        String id = object != null ? object.optString("id", null) : null;
                        if (!isLocalId(id)) {
                            continue;
                        }
                        LocalPlaylist playlist = new LocalPlaylist(id, object.optString("title", ""));
                        JSONArray videos = object.optJSONArray("videos");
                        for (int j = 0; videos != null && j < videos.length(); j++) {
                            Video video = Video.fromString(videos.optString(j, null));
                            if (video != null && video.videoId != null) {
                                playlist.videos.add(video);
                            }
                        }
                        mPlaylists.add(playlist);
                    }
                } catch (JSONException | RuntimeException e) {
                    // A damaged file starts over rather than taking the app down with it.
                    mPlaylists.clear();
                }
            }
        }
        return mPlaylists;
    }

    /** @param added something new was saved: offer to copy it to the account at the next sign-in */
    private void persist(boolean added) {
        try {
            JSONArray array = new JSONArray();
            for (LocalPlaylist playlist : playlistsInt()) {
                JSONArray videos = new JSONArray();
                for (Video video : playlist.videos) {
                    videos.put(video.toString());
                }
                JSONObject object = new JSONObject();
                object.put("id", playlist.id);
                object.put("title", playlist.title);
                object.put("videos", videos);
                array.put(object);
            }
            SharedPreferences.Editor editor = mPrefs.edit().putString(KEY_PLAYLISTS, array.toString());
            if (added) {
                editor.putBoolean(KEY_COPY_OFFER, true);
            }
            editor.apply();
        } catch (JSONException e) {
            throw new IllegalStateException(e); // only for NaN numbers, which none of these are
        }
    }
}
