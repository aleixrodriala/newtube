package com.newtube.mobile;

/** Atomic one-shot scheduling and readiness checks shared by feed, fallback and real playback. */
final class SessionWarmupGate {
    private boolean mWarm;
    private boolean mReadyThisProcess;
    private boolean mPlaybackRequested;
    private boolean mFallbackScheduled;
    private boolean mScheduled;
    private boolean mFetchStarted;

    synchronized void restore(boolean warm) {
        // Initialization must not undo a real playback that has already marked this process warm.
        mWarm |= warm;
    }

    synchronized boolean tryScheduleFallback() {
        if (mReadyThisProcess || mPlaybackRequested || mFallbackScheduled || mScheduled) {
            return false;
        }
        mFallbackScheduled = true;
        return true;
    }

    synchronized boolean trySchedule() {
        if (mReadyThisProcess || mPlaybackRequested || mScheduled) {
            return false;
        }
        mScheduled = true;
        return true;
    }

    synchronized boolean tryBeginFetch() {
        if (mReadyThisProcess || mPlaybackRequested || !mScheduled || mFetchStarted) {
            return false;
        }
        mFetchStarted = true;
        return true;
    }

    synchronized boolean isWarm() {
        return mWarm;
    }

    synchronized void onPlaybackRequested() {
        // Real playback will do any necessary setup itself. A pending speculative fetch must
        // not start afterward and compete for the same setup locks and network connection.
        mPlaybackRequested = true;
    }

    synchronized boolean markWarm() {
        mReadyThisProcess = true;
        if (mWarm) {
            return false;
        }
        mWarm = true;
        return true;
    }
}
