package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

/**
 * NEWTUBE(next-prefetch): how many autoplay-next /player resolutions one playback may spend, and
 * when. One ledger per controller, reset by a new playback generation (every open).
 *
 * <ul>
 *   <li>One request per target; a failure (or a flight that never answered) gets ONE retry after
 *       {@link #RETRY_AFTER_MS} - a /player that timed out on a flaky link at the deadline would
 *       otherwise leave autoplay to pay the full round trip.</li>
 *   <li>A resolved target is re-resolved only when its answer is older than {@link #FRESH_MS}
 *       (below the service's 5 min format-cache TTL): a long seek back and a re-watch.</li>
 *   <li>A changed candidate (queue edit, late shuffle pick, /next arriving) is a new target, at
 *       most {@link #MAX_TARGETS} per playback so a churning queue can never fan out. Going back
 *       to an earlier target is a new target too; its answer usually still sits in the format
 *       cache, so that request costs no network.</li>
 * </ul>
 */
final class NextPrefetchLedger {
    static final int MAX_ATTEMPTS = 2;
    static final long RETRY_AFTER_MS = 5_000;
    /** A flight with neither answer nor error by then (disposed subscription) counts as failed. */
    static final long IN_FLIGHT_STALE_MS = 15_000;
    static final long FRESH_MS = 4 * 60_000;
    static final int MAX_TARGETS = 3;

    private static final int NONE = 0;
    private static final int IN_FLIGHT = 1;
    private static final int DONE = 2;
    private static final int FAILED = 3;

    private long mGeneration = Long.MIN_VALUE;
    private String mTarget;
    private int mState = NONE;
    private int mAttempts;
    private long mStateAtMs;
    private int mTargets;

    /** True = issue a request for {@code target} now (recorded as in flight). */
    boolean tryStart(long generation, String target, long nowMs) {
        if (target == null) {
            return false;
        }
        if (generation != mGeneration) {
            mGeneration = generation;
            mTarget = null;
            mTargets = 0;
        }
        if (!target.equals(mTarget)) {
            if (mTargets >= MAX_TARGETS) {
                return false;
            }
            mTarget = target;
            mTargets++;
            mState = NONE;
            mAttempts = 0;
        }
        switch (mState) {
            case IN_FLIGHT:
                if (nowMs - mStateAtMs < IN_FLIGHT_STALE_MS || mAttempts >= MAX_ATTEMPTS) {
                    return false;
                }
                break;
            case DONE:
                if (nowMs - mStateAtMs < FRESH_MS) {
                    return false;
                }
                mAttempts = 0; // a refresh of a good answer is not a retry
                break;
            case FAILED:
                if (mAttempts >= MAX_ATTEMPTS || nowMs - mStateAtMs < RETRY_AFTER_MS) {
                    return false;
                }
                break;
            default:
                break;
        }
        mAttempts++;
        mState = IN_FLIGHT;
        mStateAtMs = nowMs;
        return true;
    }

    void onSuccess(long generation, String target, long nowMs) {
        if (isCurrent(generation, target)) {
            mState = DONE;
            mStateAtMs = nowMs;
        }
    }

    void onFailure(long generation, String target, long nowMs) {
        if (isCurrent(generation, target)) {
            mState = FAILED;
            mStateAtMs = nowMs;
        }
    }

    /** Whether a retry of the current target can still happen (for scheduling it). */
    boolean canRetry(long generation, String target) {
        return isCurrent(generation, target) && mState == FAILED && mAttempts < MAX_ATTEMPTS;
    }

    private boolean isCurrent(long generation, String target) {
        return generation == mGeneration && target != null && target.equals(mTarget);
    }
}
