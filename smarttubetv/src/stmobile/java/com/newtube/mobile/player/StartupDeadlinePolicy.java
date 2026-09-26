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
 * so slow-but-alive links are never hammered. Android's placeholder estimate on a just-connected
 * cellular network is not a low estimate: it reads as unknown, and on validated LTE/NR as fast
 * enough for the short window ({@link #isPlaceholderBandwidth}).</p>
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

    /**
     * NEWTUBE(startup-budget): a cellular downstream "estimate" at or under this is Android's
     * placeholder, not a measurement: telephony starts a just-connected data network at 14 kbps
     * up and down until the modem/table estimate lands (Pixel 9, Movistar, first LTE open after
     * Wi-Fi: {@code downKbps=14}, then 11278 about 13 s later). Read as "slow", it stretched the
     * first open's budget to 8 s and that open took 13.2 s. Real 2G tables start at 24 kbps
     * (GPRS), so the radio generation decides (see {@link #isPlaceholderBandwidth}).
     */
    static final int PLACEHOLDER_MAX_DOWN_KBPS = 64;
    /** A cellular network first seen this recently, with no sample yet, may still carry it. */
    static final long YOUNG_NETWORK_MS = 10_000;

    /** Radio generation of a cellular network (from its NetworkInfo subtype). */
    static final int RAT_UNKNOWN = 0;
    static final int RAT_2G = 2;
    static final int RAT_3G = 3;
    /** LTE, LTE-CA, NR (5G NSA reports LTE), IWLAN. */
    static final int RAT_4G_PLUS = 4;
    private static final int MAX_NETWORKS_TRACKED = 8;

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
        /** A cellular network (the only kind whose link estimate starts as a placeholder). */
        final boolean cellular;
        /** {@link #RAT_UNKNOWN} .. {@link #RAT_4G_PLUS}; only read on cellular. */
        final int rat;

        Evidence(@Nullable String networkKey, boolean validated, boolean captive, int downKbps,
                long measuredBps) {
            this(networkKey, validated, captive, downKbps, measuredBps, false, RAT_UNKNOWN);
        }

        Evidence(@Nullable String networkKey, boolean validated, boolean captive, int downKbps,
                long measuredBps, boolean cellular, int rat) {
            this.networkKey = networkKey;
            this.validated = validated;
            this.captive = captive;
            this.downKbps = downKbps;
            this.measuredBps = measuredBps;
            this.cellular = cellular;
            this.rat = rat;
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
    /** When this process first decided on each network (a proxy for "just connected"). */
    private final java.util.LinkedHashMap<String, Long> mFirstSeen = new java.util.LinkedHashMap<>();

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
        // NEWTUBE(startup-budget): a placeholder link estimate is UNKNOWN, not slow.
        boolean placeholder = isPlaceholderBandwidth(evidence, count,
                networkAgeMs(evidence.networkKey, nowMs));
        int downKbps = placeholder ? 0 : evidence.downKbps;
        // Slow evidence wins over fast evidence: a patient wait costs time, a premature failover
        // on a slow-but-alive link costs time AND load.
        if (count > 0 && worst >= SLOW_FIRST_BYTE_MS) {
            return longBudget("slow-first-byte", worst, count, episodeKey, evidence.networkKey);
        }
        if (downKbps > 0 && downKbps < SLOW_DOWN_KBPS) {
            return longBudget("low-downKbps", worst, count, episodeKey, evidence.networkKey);
        }
        if (evidence.measuredBps > 0 && evidence.measuredBps < SLOW_MEASURED_BPS) {
            return longBudget("low-estimate", worst, count, episodeKey, evidence.networkKey);
        }

        if (count > 0 && worst <= FAST_FIRST_BYTE_MS) {
            return new Decision(earlyBudgetFor(worst), true, "first-byte", worst, count, episodeKey,
                    evidence.networkKey);
        }
        if (downKbps >= FAST_DOWN_KBPS) {
            return new Decision(MAX_EARLY_BUDGET_MS, true, "downKbps", worst, count, episodeKey,
                    evidence.networkKey);
        }
        if (evidence.measuredBps >= FAST_MEASURED_BPS) {
            return new Decision(MAX_EARLY_BUDGET_MS, true, "estimate", worst, count, episodeKey,
                    evidence.networkKey);
        }
        if (placeholder) {
            // Validated LTE/NR (or a just-connected cellular network) whose real estimate has
            // not landed yet: the top of the short window, as for a fast radio estimate. If the
            // link really is slow, the failover leg still gets the rest of the old 8 s.
            return new Decision(MAX_EARLY_BUDGET_MS, true, "placeholder-downKbps", worst, count,
                    episodeKey, evidence.networkKey);
        }
        return longBudget("no-evidence", worst, count, episodeKey, evidence.networkKey);
    }

    /**
     * Android's just-connected cellular placeholder (see {@link #PLACEHOLDER_MAX_DOWN_KBPS}) on a
     * validated network: always on an LTE/NR radio (no LTE link estimate is that low, so it is
     * ignored and any REAL evidence - first bytes, a measured estimate - still decides), and on an
     * unknown radio only while the network is new here and nothing has been measured on it. A
     * known 2G/3G radio keeps its low reading as slow evidence.
     */
    static boolean isPlaceholderBandwidth(Evidence evidence, int samples, long networkAgeMs) {
        if (!evidence.cellular || !evidence.validated || evidence.captive
                || evidence.downKbps <= 0 || evidence.downKbps > PLACEHOLDER_MAX_DOWN_KBPS) {
            return false;
        }
        if (evidence.rat == RAT_4G_PLUS) {
            return true;
        }
        return evidence.rat == RAT_UNKNOWN && samples == 0 && evidence.measuredBps <= 0
                && networkAgeMs >= 0 && networkAgeMs < YOUNG_NETWORK_MS;
    }

    /** Since this process first decided on {@code networkKey}; 0 on the first decision. */
    private long networkAgeMs(@Nullable String networkKey, long nowMs) {
        if (!isKnownNetwork(networkKey)) {
            return -1;
        }
        Long first = mFirstSeen.get(networkKey);
        if (first == null) {
            mFirstSeen.put(networkKey, nowMs);
            while (mFirstSeen.size() > MAX_NETWORKS_TRACKED) {
                mFirstSeen.remove(mFirstSeen.keySet().iterator().next());
            }
            return 0;
        }
        return Math.max(0, nowMs - first);
    }

    /** TelephonyManager.NETWORK_TYPE_* to a radio generation. */
    static int ratOf(int telephonyNetworkType) {
        switch (telephonyNetworkType) {
            case 1:  // GPRS
            case 2:  // EDGE
            case 4:  // CDMA
            case 7:  // 1xRTT
            case 11: // IDEN
            case 16: // GSM
                return RAT_2G;
            case 3:  // UMTS
            case 5:  // EVDO_0
            case 6:  // EVDO_A
            case 8:  // HSDPA
            case 9:  // HSUPA
            case 10: // HSPA
            case 12: // EVDO_B
            case 14: // EHRPD
            case 15: // HSPAP
            case 17: // TD_SCDMA
                return RAT_3G;
            case 13: // LTE (also 5G NSA)
            case 18: // IWLAN
            case 19: // LTE_CA
            case 20: // NR
                return RAT_4G_PLUS;
            default:
                return RAT_UNKNOWN;
        }
    }

    static String ratName(int rat) {
        switch (rat) {
            case RAT_2G:
                return "2g";
            case RAT_3G:
                return "3g";
            case RAT_4G_PLUS:
                return "4g+";
            default:
                return "unknown";
        }
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
