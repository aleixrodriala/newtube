package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(watch-later): Watch later for a signed-out phone. Signed in, "Save to Watch later" goes to
 * the account's playlist; signed out there is no account to keep it, so the videos are kept here, on
 * the phone, newest first, and shown in the You tab's Watch later section ({@link #SECTION_ID}).
 *
 * <p>A private preferences file, so the app's backup (which copies shared_prefs) carries it. Each
 * entry is a {@link Video#toString()} spec, the form pinned sections already persist.</p>
 */
public final class LocalWatchLater {
    /**
     * Browse section id of the local Watch later. Clear of the {@code MediaGroup.TYPE_*} range and
     * next to {@link VideoDownloads#SECTION_ID} (200).
     */
    public static final int SECTION_ID = 201;

    private static final String PREFS_NAME = "newtube_watch_later";
    private static final String KEY_VIDEOS = "videos";
    /** Far more than anyone keeps; the oldest go first past it, so the file stays small. */
    private static final int MAX_VIDEOS = 2000;

    private static volatile LocalWatchLater sInstance;

    private final SharedPreferences mPrefs;
    /** The saved videos, newest first; loaded once. */
    private List<Video> mVideos;

    public static LocalWatchLater instance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (LocalWatchLater.class) {
                if (sInstance == null) {
                    sInstance = new LocalWatchLater(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    /** Tests: the next {@link #instance} reads the file again. */
    @androidx.annotation.VisibleForTesting
    static void resetForTests() {
        sInstance = null;
    }

    private LocalWatchLater(Context context) {
        mPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** True for what can be saved: an ordinary video (not a channel, a playlist card or a download). */
    public static boolean canSave(@Nullable Video video) {
        return video != null && video.videoId != null && video.hasVideo() && !video.isLocal()
                && !video.isPlaylistAsChannel();
    }

    public synchronized boolean contains(@Nullable String videoId) {
        return videoId != null && indexOf(videoId) >= 0;
    }

    /** Saves {@code video} at the top (moving it there if it was already saved). */
    public synchronized void add(@NonNull Video video) {
        if (!canSave(video)) {
            return;
        }
        List<Video> videos = videos();
        int index = indexOf(video.videoId);
        if (index >= 0) {
            videos.remove(index);
        }
        videos.add(0, entryFor(video));
        while (videos.size() > MAX_VIDEOS) {
            videos.remove(videos.size() - 1);
        }
        persist();
    }

    public synchronized void remove(@Nullable String videoId) {
        int index = videoId != null ? indexOf(videoId) : -1;
        if (index >= 0) {
            videos().remove(index);
            persist();
        }
    }

    /**
     * Cards for the section, newest first: copies, so a screen that changes one changes nothing here
     * (made with {@link #entryFor}, as {@link Video#copy()} leaves the duration badge behind).
     */
    @NonNull
    public synchronized List<Video> list() {
        List<Video> result = new ArrayList<>();
        for (Video video : videos()) {
            Video card = entryFor(video);
            card.sectionId = SECTION_ID;
            result.add(card);
        }
        return result;
    }

    public synchronized int size() {
        return videos().size();
    }

    /**
     * What is kept of a video: the card (title, channel, thumbnail, duration), not where it was
     * found. A playlist or a feed position would replay that context instead of this one video.
     */
    @NonNull
    private static Video entryFor(@NonNull Video video) {
        Video entry = new Video();
        entry.videoId = video.videoId;
        entry.title = video.title;
        entry.secondTitle = video.getSecondTitle();
        entry.channelId = video.channelId;
        entry.cardImageUrl = video.cardImageUrl;
        entry.bgImageUrl = video.bgImageUrl;
        entry.badge = video.badge;
        entry.isLive = video.isLive;
        return entry;
    }

    private int indexOf(@NonNull String videoId) {
        List<Video> videos = videos();
        for (int i = 0; i < videos.size(); i++) {
            if (videoId.equals(videos.get(i).videoId)) {
                return i;
            }
        }
        return -1;
    }

    @NonNull
    private List<Video> videos() {
        if (mVideos == null) {
            mVideos = new ArrayList<>();
            String json = mPrefs.getString(KEY_VIDEOS, null);
            if (json != null) {
                try {
                    JSONArray specs = new JSONArray(json);
                    for (int i = 0; i < specs.length(); i++) {
                        Video video = Video.fromString(specs.optString(i, null));
                        if (video != null && video.videoId != null) {
                            mVideos.add(video);
                        }
                    }
                } catch (JSONException | RuntimeException e) {
                    // A damaged file starts over rather than taking the app down with it.
                    mVideos.clear();
                }
            }
        }
        return mVideos;
    }

    private void persist() {
        JSONArray specs = new JSONArray();
        for (Video video : videos()) {
            specs.put(video.toString());
        }
        mPrefs.edit().putString(KEY_VIDEOS, specs.toString()).apply();
    }
}
