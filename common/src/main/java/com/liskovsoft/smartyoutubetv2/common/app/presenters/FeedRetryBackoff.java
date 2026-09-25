package com.liskovsoft.smartyoutubetv2.common.app.presenters;

/**
 * NEWTUBE(feed-retry): re-poll schedule for a Browse section whose load failed. It used to be a
 * fixed 30 s re-poll forever, including while the app sat in the background. Now:
 * <ul>
 *     <li>escalating {@link #DELAYS_MS} per consecutive failure of the same section, reset by a
 *     success, by another section failing (a new episode), or by a network edge;</li>
 *     <li>the timer is owned by the presenter's view lifecycle; on resume the section is retried
 *     after {@link #resumeDelayMs} - a short debounce, but never closer than
 *     {@link #MIN_RETRY_GAP_MS} to the failure it answers.</li>
 * </ul>
 * Pure state + arithmetic on a caller-supplied monotonic clock, so the whole schedule is
 * unit-tested; main thread only.
 */
final class FeedRetryBackoff {
    static final int NO_SECTION = Integer.MIN_VALUE;
    static final long[] DELAYS_MS = {30_000, 60_000, 120_000, 300_000};
    /** Debounce for the retry on resume: a resume that is paused again at once costs nothing. */
    static final long RESUME_DEBOUNCE_MS = 1_000;
    /** A resume right after a failure waits this long from that failure (no instant repeat). */
    static final long MIN_RETRY_GAP_MS = 5_000;

    private int mSectionId = NO_SECTION;
    private int mFailures;
    private long mLastFailureAtMs;

    /**
     * Records a failed load of {@code sectionId} and returns the delay before re-polling it.
     */
    long onFailure(int sectionId, long nowMs) {
        if (sectionId != mSectionId) {
            mSectionId = sectionId;
            mFailures = 0;
        }

        long delayMs = DELAYS_MS[Math.min(mFailures, DELAYS_MS.length - 1)];
        mFailures++;
        mLastFailureAtMs = nowMs;
        return delayMs;
    }

    /** @return true if a section was in error (i.e. this success is a recovery worth logging) */
    boolean onSuccess() {
        boolean wasInError = isInError();
        clear();
        return wasInError;
    }

    /** A validated network appeared after an outage: failures on the old link say nothing now. */
    void resetEscalation() {
        mFailures = 0;
    }

    void clear() {
        mSectionId = NO_SECTION;
        mFailures = 0;
        mLastFailureAtMs = 0;
    }

    boolean isInError() {
        return mSectionId != NO_SECTION;
    }

    boolean isInError(int sectionId) {
        return mSectionId != NO_SECTION && mSectionId == sectionId;
    }

    int getSectionId() {
        return mSectionId;
    }

    /** Consecutive failures of the current error section (0 when not in error). */
    int getFailures() {
        return mFailures;
    }

    long resumeDelayMs(long nowMs) {
        long sinceFailureMs = nowMs - mLastFailureAtMs;
        return Math.max(RESUME_DEBOUNCE_MS, MIN_RETRY_GAP_MS - sinceFailureMs);
    }
}
