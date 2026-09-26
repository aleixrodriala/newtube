package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

/**
 * NEWTUBE(next-prefetch): WHEN the autoplay-next video's format info (/player) is resolved ahead
 * of the natural end, as a deadline instead of a minute tick.
 *
 * <p>It used to ride the wall-clock minute tick ({@code TickleManager}) with an 80 s window, so
 * the prefetch landed anywhere from 20 to 80 s before the end - and not at all when no minute
 * boundary fell inside the window: a video shorter than a minute, 1.5-2x speed (80 s of content is
 * 40-53 s of wall time), or a seek into the last minute. Those autoplay opens paid the whole
 * /player round trip, parse and transform (~250-450 ms on the Pixel) that a hit skips.</p>
 *
 * <p>The deadline is {@link #LEAD_WALL_MS} of WALL time before the end (content remaining divided
 * by the playback speed): long enough for a failing client ring walk (and one retry) to finish,
 * short enough that the googlevideo connection the prefetch warms (MediaHostPreconnect runs off
 * the /player answer) is still open at the transition - Cronet closes an idle QUIC session after
 * about 30 s, and the old up-to-80 s lead left autoplay to handshake again.</p>
 *
 * <p>It also waits for real playback: min({@link #MIN_PLAYED_MS}, half the video) of wall time
 * actually spent PLAYING in this open - not the playhead position, which a seek moves for free -
 * so skimming through clips, or opening a video and seeking to its end, does not resolve every
 * "next". What is spent per playback is bounded by {@link NextPrefetchLedger}.</p>
 */
final class NextPrefetchPolicy {
    static final long LEAD_WALL_MS = 20_000;
    static final long MIN_PLAYED_MS = 5_000;
    /** While inside the lead window, how often the candidate is re-read (a local call). */
    static final long RECHECK_MS = 2_000;
    /** Returned when the duration or position is not known yet (live, unprepared). */
    static final long NEVER = -1;

    private NextPrefetchPolicy() {
    }

    /**
     * Wall-clock milliseconds until the prefetch is due at the current speed; {@code 0} = due now,
     * {@link #NEVER} = cannot be computed (unknown duration/position).
     *
     * @param playedWallMs wall time this open has actually been playing (not paused/buffering)
     */
    static long delayUntilDueMs(long durationMs, long positionMs, float speed, long playedWallMs) {
        if (durationMs <= 0 || positionMs < 0) {
            return NEVER;
        }
        float rate = speed > 0 ? speed : 1f;
        long remainingContentMs = Math.max(0, durationMs - positionMs);
        long untilLeadMs = (long) (remainingContentMs / rate) - LEAD_WALL_MS;
        long requiredPlayedMs = Math.min(MIN_PLAYED_MS, (long) (durationMs / rate) / 2);
        long untilPlayedMs = requiredPlayedMs - Math.max(0, playedWallMs);
        return Math.max(0, Math.max(untilLeadMs, untilPlayedMs));
    }

    static boolean isDue(long durationMs, long positionMs, float speed, long playedWallMs) {
        return delayUntilDueMs(durationMs, positionMs, speed, playedWallMs) == 0;
    }
}
