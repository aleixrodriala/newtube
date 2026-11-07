package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

/**
 * NEWTUBE(prev-restart): decides whether the previous action (player button, notification, media
 * session) restarts the current video or goes to the previous one.
 * <p>
 * The inherited rule compared the raw position with 10 s. A restart that SponsorBlock follows
 * with an intro skip (music videos' "Non-music section") lands past 10 s at once, so every tap
 * restarted the video again and the previous one could never be reached. What the threshold is
 * meant to measure is how long the user has been in this video since it (re)started, so this
 * counts the time it has actually played since then: an automatic jump adds nothing, and
 * neither do loading, buffering or pause.
 * <p>
 * Pure bookkeeping on caller-supplied clock values, so it is unit-testable.
 */
final class PreviousRestartGate {
    /** Watch time (and position) past which the previous action restarts the current video. */
    static final long THRESHOLD_MS = 10_000;
    private long mPlayedMs;
    /** Clock value when the current playing stretch began, or -1 while not playing. */
    private long mPlayingSinceMs = -1;

    /** A new video, or a restart of the current one: watch time starts again from zero. */
    void reset(long nowMs, boolean playing) {
        mPlayedMs = 0;
        mPlayingSinceMs = playing ? nowMs : -1;
    }

    /** Playback is running (ready and playing). Repeated calls keep the stretch open. */
    void onPlaying(long nowMs) {
        if (mPlayingSinceMs < 0) {
            mPlayingSinceMs = nowMs;
        }
    }

    /** Playback stopped running: pause, buffering, end, engine release. */
    void onStopped(long nowMs) {
        if (mPlayingSinceMs >= 0) {
            mPlayedMs += Math.max(0, nowMs - mPlayingSinceMs);
            mPlayingSinceMs = -1;
        }
    }

    long getWatchedMs(long nowMs) {
        return mPlayedMs + (mPlayingSinceMs >= 0 ? Math.max(0, nowMs - mPlayingSinceMs) : 0);
    }

    /**
     * @param positionMs  current playback position
     * @param hasPrevious whether there is a previous video to go to
     * @return true to restart the current video, false to pass the action on (go to previous)
     */
    boolean shouldRestart(long positionMs, long nowMs, boolean hasPrevious) {
        if (positionMs <= THRESHOLD_MS) {
            return false; // near the start already (the inherited rule)
        }

        if (!hasPrevious) {
            return true; // nothing to go back to: restarting is the only useful answer
        }

        return getWatchedMs(nowMs) > THRESHOLD_MS;
    }
}
