package com.newtube.mobile.player;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * How long the FIRST media request of a source may wait for response headers before the source
 * layer abandons that transport and tries another path (see {@link StartupFailoverDataSource}).
 *
 * <p>The historical wait was the media3 connection timeout, 8 s, on every link. On a validated
 * link whose recent googlevideo first-byte times are a few hundred ms, an 8 s silence is not
 * "slow", it is a dead route: measured on the owner's Pixel 9 (LTE, downKbps ~9400) one
 * unanswering edge host cost ~14 s to first frame (8 s of silence, then a client-blaming remint).
 * So a link with positive evidence of being fast gets a short budget, proportional to what that
 * link has recently shown; a link without that evidence (unvalidated, captive, 2G/EDGE, very low
 * downstream estimate, slow recent first bytes, or simply nothing known yet) keeps the long budget
 * so slow-but-alive links are never hammered.</p>
 *
 * <p>Bounded: at most one early failover per open (episode). Once an episode has spent it, every
 * later startup request of that episode waits the long budget again.</p>
 *
 * <p>Pure logic (no Android types) so the policy is unit-testable; the caller feeds it
 * {@link Evidence} and first-byte samples.</p>
 */
final class StartupDeadlinePolicy {

    /** The pre-existing wait (== DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS). */
    static final int LONG_BUDGET_MS = 8_000;
    static final int MIN_EARLY_BUDGET_MS = 2_500;
    static final int MAX_EARLY_BUDGET_MS = 3_500;
    /** Early budget = MIN + this x the worst recent first byte, clamped to [MIN, MAX]. */
    static final int FIRST_BYTE_MULTIPLIER = 3;

    /** Every recent first byte on this network at or under this proves a fast, alive route. */
    static final long FAST_FIRST_BYTE_MS = 1_000;
    /** Any recent first byte at or over this is evidence of a slow link: keep the long budget. */
    static final long SLOW_FIRST_BYTE_MS = 1_500;
    static final long SAMPLE_MAX_AGE_MS = 10 * 60_000L;
    static final int MAX_SAMPLES_CONSIDERED = 5;
    private static final int MAX_SAMPLES_STORED = 16;

    /** Radio/link downstream estimate that counts as fast on its own (LTE CA reports ~9400). */
    static final int FAST_DOWN_KBPS = 5_000;
    /** Below this (2G/EDGE report tens to a few hundred kbps) the link is treated as slow. */
    static final int SLOW_DOWN_KBPS = 1_500;
    /** A MEASURED bandwidth estimate at or over this counts as fast evidence. */
    static final long FAST_MEASURED_BPS = 3_000_000;
    /** A MEASURED bandwidth estimate under this is slow evidence. */
    static final long SLOW_MEASURED_BPS = 1_500_000;

    /** Snapshot of what is known about the active network at decision time. */
    static final class Evidence {
        /** Credential-free network identity (NetPath.networkId); null/"unknown" = no network. */
        @Nullable final String networkKey;
        final boolean validated;
        final boolean captive;
        /** NetworkCapabilities#getLinkDownstreamBandwidthKbps; 0 = unknown. */
        final int downKbps;
        /** Bandwidth-meter estimate backed by real transfers on this network; 0 = none. */
        final long measuredBps;

        Evidence(@Nullable String networkKey, boolean validated, boolean captive, int downKbps,
                long measuredBps) {
            this.networkKey = networkKey;
            this.validated = validated;
            this.captive = captive;
            this.downKbps = downKbps;
            this.measuredBps = measuredBps;
        }
    }

    static final class Decision {
        final int budgetMs;
        final boolean early;
        final String reason;
        /** Worst recent first byte used (-1 = none), for the NetPath line. */
        final long worstFirstByteMs;
        final int samples;
        /** The open this decision belongs to; an early failover spends THIS episode. */
        @Nullable final String episodeKey;
        /** The network the primary request started on: a fault can only be pinned on it. */
        @Nullable final String networkKey;

        Decision(int budgetMs, boolean early, String reason, long worstFirstByteMs, int samples,
                @Nullable String episodeKey, @Nullable String networkKey) {
            this.budgetMs = budgetMs;
            this.early = early;
            this.reason = reason;
            this.worstFirstByteMs = worstFirstByteMs;
            this.samples = samples;
            this.episodeKey = episodeKey;
            this.networkKey = networkKey;
        }
    }

    private static final class Sample {
        final String networkKey;
        final long firstByteMs;
        final long atMs;

        Sample(String networkKey, long firstByteMs, long atMs) {
            this.networkKey = networkKey;
            this.firstByteMs = firstByteMs;
            this.atMs = atMs;
        }
    }

    private final ArrayDeque<Sample> mSamples = new ArrayDeque<>();
    /**
     * The latest episode that spent its early failover. Episodes are monotonic, so one slot is
     * enough: a newer episode never needs to know about an older one's failover.
     */
    @Nullable private String mSpentEpisode;

    /** A startup request got response headers after {@code firstByteMs} on this network. */
    synchronized void recordFirstByte(@Nullable String networkKey, long firstByteMs, long nowMs) {
        if (!isKnownNetwork(networkKey) || firstByteMs < 0) {
            return;
        }
        mSamples.addLast(new Sample(networkKey, firstByteMs, nowMs));
        while (mSamples.size() > MAX_SAMPLES_STORED) {
            mSamples.removeFirst();
        }
    }

    /**
     * Claims this episode's early failover. Requests already running with an early budget still
     * fail over (the audio and video init requests of one open start together); every later
     * decision in the episode gets the long budget.
     */
    synchronized void markEarlyFailover(@Nullable String episodeKey) {
        mSpentEpisode = episodeKey;
    }

    synchronized boolean isSpent(@Nullable String episodeKey) {
        return episodeKey != null && episodeKey.equals(mSpentEpisode);
    }

    synchronized Decision decide(Evidence evidence, @Nullable String episodeKey, long nowMs) {
        // Collect the recent first bytes of THIS network (newest first, at most N, not stale).
        long worst = -1;
        int count = 0;
        if (isKnownNetwork(evidence.networkKey)) {
            Iterator<Sample> newestFirst = mSamples.descendingIterator();
            while (newestFirst.hasNext() && count < MAX_SAMPLES_CONSIDERED) {
                Sample sample = newestFirst.next();
                if (!sample.networkKey.equals(evidence.networkKey)) {
                    continue;
                }
                if (nowMs - sample.atMs > SAMPLE_MAX_AGE_MS || nowMs < sample.atMs) {
                    break; // older ones are staler still
                }
                worst = Math.max(worst, sample.firstByteMs);
                count++;
            }
        }

        if (!isKnownNetwork(evidence.networkKey)) {
            return longBudget("no-network", worst, count, episodeKey, evidence.networkKey);
        }
        if (!evidence.validated) {
            return longBudget("unvalidated", worst, count, episodeKey, evidence.networkKey);
        }
        if (evidence.captive) {
            return longBudget("captive", worst, count, episodeKey, evidence.networkKey);
        }
        if (isSpent(episodeKey)) {
            return longBudget("spent", worst, count, episodeKey, evidence.networkKey);
        }
        // Slow evidence wins over fast evidence: a patient wait costs time, a premature failover
        // on a slow-but-alive link costs time AND load.
        if (count > 0 && worst >= SLOW_FIRST_BYTE_MS) {
            return longBudget("slow-first-byte", worst, count, episodeKey, evidence.networkKey);
        }
        if (evidence.downKbps > 0 && evidence.downKbps < SLOW_DOWN_KBPS) {
            return longBudget("low-downKbps", worst, count, episodeKey, evidence.networkKey);
        }
        if (evidence.measuredBps > 0 && evidence.measuredBps < SLOW_MEASURED_BPS) {
            return longBudget("low-estimate", worst, count, episodeKey, evidence.networkKey);
        }

        if (count > 0 && worst <= FAST_FIRST_BYTE_MS) {
            return new Decision(earlyBudgetFor(worst), true, "first-byte", worst, count, episodeKey,
                    evidence.networkKey);
        }
        if (evidence.downKbps >= FAST_DOWN_KBPS) {
            return new Decision(MAX_EARLY_BUDGET_MS, true, "downKbps", worst, count, episodeKey,
                    evidence.networkKey);
        }
        if (evidence.measuredBps >= FAST_MEASURED_BPS) {
            return new Decision(MAX_EARLY_BUDGET_MS, true, "estimate", worst, count, episodeKey,
                    evidence.networkKey);
        }
        return longBudget("no-evidence", worst, count, episodeKey, evidence.networkKey);
    }

    static Decision longBudget(String reason, long worstFirstByteMs, int samples,
            @Nullable String episodeKey, @Nullable String networkKey) {
        return new Decision(LONG_BUDGET_MS, false, reason, worstFirstByteMs, samples, episodeKey,
                networkKey);
    }

    /** Both sides of a comparison saw the same, known network (no handover in between). */
    static boolean sameKnownNetwork(@Nullable String before, @Nullable String after) {
        return isKnownNetwork(before) && before.equals(after);
    }

    static int earlyBudgetFor(long worstFirstByteMs) {
        long budget = MIN_EARLY_BUDGET_MS + FIRST_BYTE_MULTIPLIER * Math.max(0, worstFirstByteMs);
        return (int) Math.max(MIN_EARLY_BUDGET_MS, Math.min(MAX_EARLY_BUDGET_MS, budget));
    }

    static boolean isKnownNetwork(@Nullable String networkKey) {
        return networkKey != null && !networkKey.isEmpty() && !"unknown".equals(networkKey)
                && !networkKey.startsWith("none");
    }
}
