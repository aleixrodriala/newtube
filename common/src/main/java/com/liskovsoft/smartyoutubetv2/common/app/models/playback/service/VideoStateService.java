package com.liskovsoft.smartyoutubetv2.common.app.models.playback.service;

import android.annotation.SuppressLint;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs.ProfileChangeListener;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

public class VideoStateService implements ProfileChangeListener {
    @SuppressLint("StaticFieldLeak")
    private static VideoStateService sInstance;
    private static final int LOW_RAM_STATE_MAX_SIZE = 50;
    private static final int HIGH_RAM_STATE_MAX_SIZE = 300;
    private static final long PERSIST_DELAY_MS = 10_000;
    // Don't store state inside Video object.
    // As one video might correspond to multiple Video objects.
    //private final Map<String, State> mStates = Helpers.createLRUMap(MAX_PERSISTENT_STATE_SIZE);
    private final List<State> mStates;
    private final int mMaxStates;
    private final AppPrefs mPrefs;
    private static final String DELIM = "&si;";
    private boolean mIsHistoryBroken;
    private final Runnable mPersistStateInt = () -> {
        try {
            persistStateInt();
        } catch (OutOfMemoryError e) {
            // NOP
        }
    };
    private long mSessionStartTimeMs;

    private VideoStateService(Context context) {
        mPrefs = AppPrefs.instance(context);
        mPrefs.addListener(this);
        mMaxStates = Utils.isEnoughRam() ? HIGH_RAM_STATE_MAX_SIZE : LOW_RAM_STATE_MAX_SIZE;
        mStates = Helpers.createSafeLRUList(mMaxStates);
        restoreState();
    }

    public static VideoStateService instance(Context context) {
        if (sInstance == null && context != null) {
            sInstance = new VideoStateService(context.getApplicationContext());
        }

        return sInstance;
    }

    public List<State> getStates() {
        return mStates;
    }

    public @Nullable State getLastState() {
        if (isEmpty()) {
            return null;
        }

        return mStates.get(mStates.size() - 1);
    }

    public State getByVideoId(String videoId) {
        for (State state : mStates) {
            if (Helpers.equals(videoId, state.video.videoId)) {
                return state;
            }
        }

        return null;
    }

    public void removeByVideoId(String videoId) {
        Helpers.removeIf(mStates, state -> Helpers.equals(state.video.videoId, videoId));
        persistState();
    }

    public boolean isEmpty() {
        return mStates.isEmpty();
    }

    public void save(State state) {
        mStates.add(state);
        persistState();
    }

    public void clear() {
        mStates.clear();
        persistState();
    }

    public void setHistoryBroken(boolean isBroken) {
        mIsHistoryBroken = isBroken;
    }

    public boolean isHistoryBroken() {
        return mIsHistoryBroken;
    }

    public long getSessionStartTimeMs() {
        return mSessionStartTimeMs;
    }

    private void restoreState() {
        mStates.clear();
        String data = mPrefs.getStateUpdaterData();

        String[] split = Helpers.splitData(data);

        setStateData(Helpers.parseStr(split, 0));
        mIsHistoryBroken = Helpers.parseBoolean(split, 1);
        mSessionStartTimeMs = System.currentTimeMillis();
    }

    private void persistStateInt() {
        if (mIsHistoryBroken) {
            mPrefs.setStateUpdaterData(Helpers.mergeData(getStateData(), mIsHistoryBroken));
        } else {
            // Eliminate additional string creation with the merge
            mPrefs.setStateUpdaterData(getStateData());
        }
    }

    public void persistNow() {
        Utils.post(mPersistStateInt);
    }

    private void persistState() {
        // Improve memory and disc usage
        Utils.postDelayed(mPersistStateInt, PERSIST_DELAY_MS);
    }

    public static class State {
        private static final String DELIM = "&sf;";
        public final Video video;
        public final long positionMs;
        public final long durationMs;
        public final float speed;
        public final long timestamp = System.currentTimeMillis();

        public State(Video video, long positionMs) {
            this(video, positionMs, -1);
        }

        public State(Video video, long positionMs, long durationMs) {
            this(video, positionMs, durationMs, 1.0f);
        }

        public State(Video video, long positionMs, long durationMs, float speed) {
            this.video = video;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.speed = speed;
        }

        public static State from(String spec) {
            if (spec == null) {
                return null;
            }

            String[] split = Helpers.split(spec, DELIM);

            String videoId = Helpers.parseStr(split, 0);
            long positionMs = Helpers.parseLong(split, 1);
            long lengthMs = Helpers.parseLong(split, 2);
            float speed = Helpers.parseFloat(split, 3);

            Video video = Video.fromString(videoId);

            // backward compatibility
            if (video == null) {
                video = new Video();
                video.videoId = videoId;
            }

            video.percentWatched = (positionMs * 100f) / lengthMs;

            return new State(video, positionMs, lengthMs, speed);
        }

        @NonNull
        @Override
        public String toString() {
            return Helpers.merge(DELIM, video, positionMs, durationMs, speed);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (obj instanceof State) {
                return Helpers.equals(video, ((State) obj).video);
            }

            return false;
        }
    }

    @Override
    public void onProfileChanged() {
        restoreState();
    }

    private void setStateDataSafe(String data) {
        try {
            setStateData(data);
        } catch (ArrayIndexOutOfBoundsException e) { // weird issue (NVidia Shield)
            e.printStackTrace();
        }
    }

    private void setStateData(String data) {
        if (data != null) {
            String[] split = Helpers.split(data, DELIM);

            List<State> parsed = new ArrayList<>(split.length);

            for (String spec : split) {
                State state = State.from(spec);

                if (state != null) {
                    parsed.add(state);
                }
            }

            // NEWTUBE(perf): this restore runs on the main thread inside SplashActivity.onCreate on
            // every cold start. Adding the ~300 persisted states one by one through the LRU list is
            // quadratic twice over: each add() scans the list with State.equals (two Video.hashCode
            // computations per comparison) and then copies the whole CopyOnWriteArrayList. Collapse
            // the history in one pass instead and append it with a single copy - same contents,
            // same order, same size cap (restoreState always starts from an empty list).
            mStates.addAll(collapseLru(parsed, mMaxStates));
        }
    }

    /**
     * The list that add()-ing {@code states} one by one into an empty
     * {@link Helpers#createSafeLRUList}{@code (maxEntries)} would produce, computed in O(n):
     * a repeated state replaces its earlier occurrence at the end, and the oldest entry is
     * dropped whenever the list already holds more than {@code maxEntries} before an add.
     */
    static List<State> collapseLru(List<State> states, int maxEntries) {
        LinkedHashMap<Object, State> byIdentity = new LinkedHashMap<>();

        for (State state : states) {
            Object key = identityOf(state);
            byIdentity.remove(key);

            if (byIdentity.size() > maxEntries) {
                Iterator<Object> eldest = byIdentity.keySet().iterator();
                eldest.next();
                eldest.remove();
            }

            byIdentity.put(key, state);
        }

        return new ArrayList<>(byIdentity.values());
    }

    private static final Object NULL_VIDEO_KEY = new Object();

    /** {@link State#equals}: Helpers.equals on the videos, i.e. Video.equals = same hashCode and same isMix. */
    private static Object identityOf(State state) {
        Video video = state.video;

        if (video == null) {
            return NULL_VIDEO_KEY;
        }

        return ((long) video.hashCode() << 1) | (video.isMix() ? 1 : 0);
    }

    private String getStateData() {
        StringBuilder sb = new StringBuilder();

        for (State state : mStates) {
            if (sb.length() != 0) {
                sb.append(DELIM);
            }

            sb.append(state);
        }

        return sb.toString();
    }
}
