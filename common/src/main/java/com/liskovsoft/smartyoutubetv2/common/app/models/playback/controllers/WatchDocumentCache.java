package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

/**
 * NEWTUBE(reopen-related): the last /next document a watch page received, kept so a NEW watch page
 * for the same video can bind it instantly instead of showing an empty related list (or waiting
 * ~1 s for an identical /next). The case it exists for: open a video, go back to Home, tap the
 * same card again. The playback Activity is a fresh one with an empty list, but the controllers
 * are process singletons that still remember the video.
 *
 * <p>A document is only reused while it is plausibly what /next would answer again: same video,
 * same playlist context, same signed-in account, and younger than {@link #MAX_AGE_MS}. The owner
 * drops it when the user changes something the document records (like, dislike, subscribe), so a
 * re-bind never shows a stale button state.</p>
 *
 * <p>Eviction timeline (single slot): every delivered document for any video replaces the slot, so
 * after A -> related B -> back -> A the slot holds B and A is fetched again. A reuse does NOT
 * refresh the timestamp: the age is always the age of the network answer. A hit is a peek, not a
 * take - the same document may serve several re-opens inside its lifetime.</p>
 *
 * <p>Main thread only.</p>
 */
final class WatchDocumentCache<T> {
    /** Same window Home uses for "still fresh" (BrowsePresenter.SECTION_FRESH_MS). */
    static final long MAX_AGE_MS = 5 * 60_000L;

    private final long mMaxAgeMs;
    private String mKey;
    private String mVideoId;
    private T mDocument;
    private long mStoredAtMs;

    WatchDocumentCache() {
        this(MAX_AGE_MS);
    }

    WatchDocumentCache(long maxAgeMs) {
        mMaxAgeMs = maxAgeMs;
    }

    /**
     * Everything a /next answer depends on besides time. {@code account} is an opaque id of the
     * signed-in account (null or empty when signed out).
     */
    static String key(String videoId, String playlistId, int playlistIndex, String playlistParams, String account) {
        return videoId + '\u0001' + playlistId + '\u0001' + playlistIndex + '\u0001' + playlistParams
                + '\u0001' + (account != null ? account : "");
    }

    /**
     * Remembers {@code document} for {@code key}, replacing whatever was stored. Storing the SAME
     * document again (a re-bind is delivered through the normal path, which stores it) keeps its
     * original timestamp - otherwise re-opening every few minutes would keep one answer alive
     * forever.
     */
    void put(String videoId, String key, T document, long nowMs) {
        if (videoId == null || key == null || document == null) {
            return;
        }

        if (document == mDocument && key.equals(mKey)) {
            return;
        }

        mVideoId = videoId;
        mKey = key;
        mDocument = document;
        mStoredAtMs = nowMs;
    }

    /** The stored document when it matches {@code key} and is still young enough, else null. */
    T get(String key, long nowMs) {
        if (mDocument == null || key == null || !key.equals(mKey)) {
            return null;
        }

        long ageMs = nowMs - mStoredAtMs;
        if (ageMs < 0 || ageMs > mMaxAgeMs) {
            clear(); // expired (or the clock went backwards): never serve it again
            return null;
        }

        return mDocument;
    }

    /** Age of the stored answer in ms, or -1 when the slot is empty. For logging. */
    long ageMs(long nowMs) {
        return mDocument != null ? nowMs - mStoredAtMs : -1;
    }

    /** Drops the slot if it belongs to {@code videoId} (any playlist context or account). */
    void invalidate(String videoId) {
        if (videoId != null && videoId.equals(mVideoId)) {
            clear();
        }
    }

    void clear() {
        mKey = null;
        mVideoId = null;
        mDocument = null;
        mStoredAtMs = 0;
    }
}
