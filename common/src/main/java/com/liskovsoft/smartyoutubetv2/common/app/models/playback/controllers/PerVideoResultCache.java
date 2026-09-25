package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

/**
 * NEWTUBE(ryd-cache): one result per video, remembered for as long as that video stays current.
 * Built for Return YouTube Dislike: {@code syncCurrentVideo} re-runs on every live /next refresh
 * (once a minute), and each run used to open a fresh connection to the third-party host for
 * counts that were already on screen.
 *
 * <p>Eviction timeline (single slot): only a fetch or lookup for a DIFFERENT video replaces the
 * slot, and a write is accepted only for the video that owns the slot - a late answer for the
 * previous video can never overwrite the current one. A failed fetch is not cached, but the
 * number of attempts per video is capped so a dead host is not re-asked every minute.</p>
 *
 * <p>Main thread only.</p>
 */
final class PerVideoResultCache<T> {
    private final int mMaxFetchesPerVideo;
    private String mVideoId;
    private T mValue;
    private int mFetches;

    PerVideoResultCache(int maxFetchesPerVideo) {
        mMaxFetchesPerVideo = maxFetchesPerVideo;
    }

    /** The cached result for this video, or null. */
    T get(String videoId) {
        return videoId != null && videoId.equals(mVideoId) ? mValue : null;
    }

    /**
     * Claims a fetch for {@code videoId}. False when the result is already cached or the
     * per-video attempt budget is spent. A null id cannot be cached and is always allowed.
     */
    boolean tryBeginFetch(String videoId) {
        if (videoId == null) {
            return true;
        }

        if (!videoId.equals(mVideoId)) {
            mVideoId = videoId;
            mValue = null;
            mFetches = 0;
        }

        if (mValue != null || mFetches >= mMaxFetchesPerVideo) {
            return false;
        }

        mFetches++;
        return true;
    }

    /** Stores a result, only if the slot still belongs to {@code videoId}. */
    void put(String videoId, T value) {
        if (value != null && videoId != null && videoId.equals(mVideoId)) {
            mValue = value;
        }
    }

    void clear() {
        mVideoId = null;
        mValue = null;
        mFetches = 0;
    }
}
