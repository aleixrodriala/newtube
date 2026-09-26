package com.newtube.mobile.player;

import androidx.media3.common.C;

/**
 * NEWTUBE(resume-seek): the decision state for starting a history ("continue watching") resume at
 * the keyframe at or before the saved position instead of exactly on it.
 *
 * <p>Why the player-wide snapping never applied: media3 can only snap a DASH seek once the
 * representation's segment index is known ({@code DefaultDashChunkSource.getAdjustedSeekPositionUs}
 * returns the position unchanged while {@code segmentIndex == null}), and for our SegmentBase
 * manifests that index (sidx/cues) arrives WITH the initialization segment - still in flight when
 * the resume seek is issued ({@code onTracksChanged} -&gt; {@code onVideoLoaded}). The first media
 * request is then the segment containing the position, and the decoder decodes, without showing,
 * every frame from that segment's keyframe up to the position. YouTube video segments start
 * ~5.3 s apart (median of 1,780 debug chunk loads; p10 4.9 s, p90 5.5 s); at 1080p60 that decode
 * took up to 1.36 s on the Pixel 9 (6eVUNJg3XLo: chunk done +494, first frame +1857).</p>
 *
 * <p>The mechanism: the resume seek stays exact (it makes media3 request the right segment). When
 * THAT segment starts loading, its {@code MediaLoadData} carries the segment start taken from the
 * real index; if it lies {@link #MIN_EARLY_MS}..{@link #MAX_EARLY_MS} before the target, the seek is
 * repeated with {@code PREVIOUS_SYNC}, which media3 resolves to exactly that segment start - so it
 * lands on a keyframe inside the chunk already loading (no new video request), never on an
 * arbitrary "target - tolerance" position. Everything else stays exact and cancels a pending snap:
 * any seek that is not ours (scrubs, SponsorBlock/chapter jumps, share-link timestamps, the media
 * session, the double-tap overlay), a new open, and READY (after that a snap would be an audible
 * jump back, e.g. background audio that came back to video).</p>
 *
 * <p>History: until playback has passed the original target again, the position reported for
 * history/resume is the target itself ({@link #historyPositionMs}), so opening and leaving at once
 * never costs the user the up-to-one-segment the snap rewound.</p>
 */
final class ResumeSeekSnap {
    /** Snap decisions for {@link #onVideoChunkStart}. */
    static final int NONE = 0;
    static final int SNAP = 1;
    static final int SKIP_NEAR = 2;
    static final int SKIP_FAR = 3;
    static final int DROPPED_MOVED = 4;
    static final int SKIP_AUDIO = 5;

    /** Below this the decode is cheaper than possibly restarting the audio chunk load. */
    static final long MIN_EARLY_MS = 500;
    /** Above this the segment is unusually long: keep the exact position rather than rewind far. */
    static final long MAX_EARLY_MS = 8_000;
    /** Near the end a resume is an "ended" state, not a place to snap back from. */
    static final long END_GUARD_MS = 1_000;
    /** Belt and braces next to the seek tracking: the playhead moved away from the target. */
    static final long MOVED_TOLERANCE_MS = 1_000;
    /**
     * The snap also seeks the audio stream. With no audio samples buffered yet media3 cancels the
     * audio request and starts it again at the snapped position - losing only the time that
     * request had been in flight (the video request is the one it was issued next to, so normally
     * a few ms). Past this, the audio response may already be arriving and the restart could cost
     * more than the decode it saves: keep the exact position.
     */
    static final long MAX_AUDIO_IN_FLIGHT_MS = 250;

    private long mArmedTargetMs = C.TIME_UNSET;
    private long mAwaitingTargetMs = C.TIME_UNSET;
    private long mHistoryFloorMs = C.TIME_UNSET;
    private long mOwnSeekTargetMs = C.TIME_UNSET;
    private int mOwnSeeksPending;

    /** New source prepared (or reset): nothing carries over. */
    void onPrepare() {
        mArmedTargetMs = C.TIME_UNSET;
        mAwaitingTargetMs = C.TIME_UNSET;
        mHistoryFloorMs = C.TIME_UNSET;
        mOwnSeekTargetMs = C.TIME_UNSET;
        mOwnSeeksPending = 0;
    }

    /** @return whether this resume is armed for a snap (else seek exactly, nothing more). */
    boolean onResumeRequest(long targetMs, long durationMs, boolean live) {
        mArmedTargetMs = C.TIME_UNSET;
        mAwaitingTargetMs = C.TIME_UNSET;
        mHistoryFloorMs = C.TIME_UNSET;
        if (!isEligible(targetMs, durationMs, live)) {
            return false;
        }
        mArmedTargetMs = targetMs;
        return true;
    }

    static boolean isEligible(long targetMs, long durationMs, boolean live) {
        if (live || targetMs <= 0) {
            return false;
        }
        return durationMs <= 0 || targetMs < durationMs - END_GUARD_MS;
    }

    /** Record a seek WE are about to issue, so its discontinuity is not taken for someone else's. */
    void noteOwnSeek(long targetMs) {
        if (targetMs != mOwnSeekTargetMs) {
            mOwnSeeksPending = 0;
        }
        mOwnSeekTargetMs = targetMs;
        mOwnSeeksPending++;
    }

    /**
     * A {@code DISCONTINUITY_REASON_SEEK} was reported (media3 reports the requested position).
     *
     * @return true when it was not one of ours - the snap (and history floor) are then cancelled
     */
    boolean onSeekDiscontinuity(long requestedPositionMs) {
        if (mOwnSeeksPending > 0 && requestedPositionMs == mOwnSeekTargetMs) {
            mOwnSeeksPending--;
            return false;
        }
        boolean hadState = isArmed() || mHistoryFloorMs != C.TIME_UNSET;
        onOtherSeek();
        return hadState;
    }

    /**
     * The first loads of video media for this source: {@code [chunkStartMs, chunkEndMs)} is the
     * segment the loader requested, taken from the real segment index.
     */
    int onVideoChunkStart(long chunkStartMs, long chunkEndMs, long currentPositionMs) {
        return onVideoChunkStart(chunkStartMs, chunkEndMs, currentPositionMs, /* audioInFlightMs= */ -1);
    }

    /** @param audioInFlightMs how long this open's first audio request has run; -1 = not started */
    int onVideoChunkStart(long chunkStartMs, long chunkEndMs, long currentPositionMs,
            long audioInFlightMs) {
        long target = mArmedTargetMs;
        if (target == C.TIME_UNSET) {
            return NONE;
        }
        if (Math.abs(currentPositionMs - target) > MOVED_TOLERANCE_MS) {
            mArmedTargetMs = C.TIME_UNSET;
            return DROPPED_MOVED;
        }
        if (chunkStartMs == C.TIME_UNSET || chunkStartMs > target
                || (chunkEndMs != C.TIME_UNSET && target >= chunkEndMs)) {
            return NONE; // not the segment of our target (stale/other request): stay armed
        }
        mArmedTargetMs = C.TIME_UNSET;
        long earlyMs = target - chunkStartMs;
        if (earlyMs < MIN_EARLY_MS) {
            return SKIP_NEAR;
        }
        if (earlyMs > MAX_EARLY_MS) {
            return SKIP_FAR;
        }
        if (audioInFlightMs > MAX_AUDIO_IN_FLIGHT_MS) {
            return SKIP_AUDIO;
        }
        mAwaitingTargetMs = target;
        mHistoryFloorMs = target;
        return SNAP;
    }

    /** READY: from here a snap would be an audible jump back. */
    boolean onReady() {
        boolean wasArmed = isArmed();
        mArmedTargetMs = C.TIME_UNSET;
        return wasArmed;
    }

    /** Any seek that is not ours: exact wins, and history reports the real position again. */
    void onOtherSeek() {
        mArmedTargetMs = C.TIME_UNSET;
        mAwaitingTargetMs = C.TIME_UNSET;
        mHistoryFloorMs = C.TIME_UNSET;
    }

    /** The position history/resume should store: never earlier than an unwatched snapped-over target. */
    long historyPositionMs(long currentPositionMs) {
        long floor = mHistoryFloorMs;
        if (floor == C.TIME_UNSET) {
            return currentPositionMs;
        }
        if (currentPositionMs >= floor) {
            mHistoryFloorMs = C.TIME_UNSET; // watched past it: the real position is authoritative
            return currentPositionMs;
        }
        return floor;
    }

    long armedTargetMs() {
        return mArmedTargetMs;
    }

    long awaitingTargetMs() {
        return mAwaitingTargetMs;
    }

    /** The outcome was reported (adjustment discontinuity, or the first frame without one). */
    void onReported() {
        mAwaitingTargetMs = C.TIME_UNSET;
    }

    boolean isArmed() {
        return mArmedTargetMs != C.TIME_UNSET;
    }
}
