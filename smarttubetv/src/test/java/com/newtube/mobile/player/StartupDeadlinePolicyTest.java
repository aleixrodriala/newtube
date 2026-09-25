package com.newtube.mobile.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Budget policy for the first media request of a source (see StartupDeadlinePolicy). */
public class StartupDeadlinePolicyTest {
    private static final String LTE = "cell:102";
    private static final String WIFI = "wifi:7";
    private static final String EP1 = "ep=3 video=as11YKO3WL0";
    private static final String EP2 = "ep=4 video=as11YKO3WL0";

    /** The evidence case: owner's Pixel 9 on validated LTE CA, downKbps ~9400. */
    private static StartupDeadlinePolicy.Evidence fastLte() {
        return new StartupDeadlinePolicy.Evidence(LTE, true, false, 9_416, 0);
    }

    @Test
    public void fastValidatedLinkWithFastFirstBytesGetsShortProportionalBudget() {
        StartupDeadlinePolicy policy = new StartupDeadlinePolicy();
        policy.recordFirstByte(LTE, 120, 1_000);
        policy.recordFirstByte(LTE, 240, 2_000);
        policy.recordFirstByte(LTE, 90, 3_000);

        StartupDeadlinePolicy.Decision decision = policy.decide(fastLte(), EP1, 4_000);

        assertTrue(decision.early);
        assertEquals("first-byte", decision.reason);
        assertEquals(240, decision.worstFirstByteMs);
        assertEquals(3, decision.samples);
        // 2500 + 3 x 240
        assertEquals(3_220, decision.budgetMs);
        assertEquals(EP1, decision.episodeKey);
        assertEquals(LTE, decision.networkKey); // where the primary request starts
    }

    @Test
    public void aFaultIsOnlyPinnedWithoutAHandover() {
        assertTrue(StartupDeadlinePolicy.sameKnownNetwork(LTE, LTE));
        assertFalse(StartupDeadlinePolicy.sameKnownNetwork(WIFI, LTE)); // Wi-Fi -> cellular
        assertFalse(StartupDeadlinePolicy.sameKnownNetwork(null, LTE));
        assertFalse(StartupDeadlinePolicy.sameKnownNetwork("none", "none"));
    }

    @Test
    public void earlyBudgetStaysInsideTheTargetWindow() {
        assertEquals(2_500, StartupDeadlinePolicy.earlyBudgetFor(0));
        assertEquals(2_800, StartupDeadlinePolicy.earlyBudgetFor(100));
        assertEquals(3_500, StartupDeadlinePolicy.earlyBudgetFor(900));
        assertEquals(3_500, StartupDeadlinePolicy.earlyBudgetFor(60_000));
    }

    @Test
    public void fastDownstreamEstimateAloneGetsTheTopOfTheShortWindow() {
        // First open after process start: no samples yet, the radio says 9.4 Mbps.
        StartupDeadlinePolicy.Decision decision =
                new StartupDeadlinePolicy().decide(fastLte(), EP1, 1_000);

        assertTrue(decision.early);
        assertEquals("downKbps", decision.reason);
        assertEquals(StartupDeadlinePolicy.MAX_EARLY_BUDGET_MS, decision.budgetMs);
    }

    @Test
    public void measuredBandwidthCountsAsFastEvidence() {
        StartupDeadlinePolicy.Decision decision = new StartupDeadlinePolicy().decide(
                new StartupDeadlinePolicy.Evidence(WIFI, true, false, 0, 12_000_000), EP1, 1_000);

        assertTrue(decision.early);
        assertEquals("estimate", decision.reason);
    }

    @Test
    public void unvalidatedCaptiveOrMissingNetworkKeepsTheLongWait() {
        StartupDeadlinePolicy policy = new StartupDeadlinePolicy();
        policy.recordFirstByte(WIFI, 50, 1_000);

        assertLong(policy.decide(new StartupDeadlinePolicy.Evidence(WIFI, false, false, 866_000, 0),
                EP1, 2_000), "unvalidated");
        assertLong(policy.decide(new StartupDeadlinePolicy.Evidence(WIFI, true, true, 866_000, 0),
                EP1, 2_000), "captive");
        assertLong(policy.decide(new StartupDeadlinePolicy.Evidence("none", true, false, 866_000, 0),
                EP1, 2_000), "no-network");
        assertLong(policy.decide(new StartupDeadlinePolicy.Evidence(null, true, false, 866_000, 0),
                EP1, 2_000), "no-network");
    }

    @Test
    public void edgeAndLowDownstreamLinksKeepTheLongWait() {
        // 2G/EDGE: ~200 kbps downstream. Slow but alive - never cut short.
        assertLong(new StartupDeadlinePolicy().decide(
                new StartupDeadlinePolicy.Evidence(LTE, true, false, 236, 0), EP1, 1_000),
                "low-downKbps");
        // A measured slow link overrides a generous radio estimate.
        assertLong(new StartupDeadlinePolicy().decide(
                new StartupDeadlinePolicy.Evidence(LTE, true, false, 9_416, 400_000), EP1, 1_000),
                "low-estimate");
    }

    @Test
    public void unknownLinkWithoutAnyEvidenceKeepsTheLongWait() {
        assertLong(new StartupDeadlinePolicy().decide(
                new StartupDeadlinePolicy.Evidence(LTE, true, false, 0, 0), EP1, 1_000),
                "no-evidence");
        // In-between estimates prove nothing either way.
        assertLong(new StartupDeadlinePolicy().decide(
                new StartupDeadlinePolicy.Evidence(LTE, true, false, 3_000, 2_000_000), EP1, 1_000),
                "no-evidence");
    }

    @Test
    public void aRecentSlowFirstByteBeatsAFastRadioEstimate() {
        StartupDeadlinePolicy policy = new StartupDeadlinePolicy();
        policy.recordFirstByte(LTE, 150, 1_000);
        policy.recordFirstByte(LTE, 2_100, 2_000);

        assertLong(policy.decide(fastLte(), EP1, 3_000), "slow-first-byte");
    }

    @Test
    public void samplesFromAnotherNetworkOrTooOldAreIgnored() {
        StartupDeadlinePolicy policy = new StartupDeadlinePolicy();
        policy.recordFirstByte(WIFI, 2_500, 1_000); // slow Wi-Fi: not this LTE link
        policy.recordFirstByte(LTE, 3_000, 1_000);  // slow LTE, but 11 minutes old

        long now = 1_000 + StartupDeadlinePolicy.SAMPLE_MAX_AGE_MS + 60_000;
        StartupDeadlinePolicy.Decision decision = policy.decide(fastLte(), EP1, now);

        assertTrue(decision.early);
        assertEquals("downKbps", decision.reason);
        assertEquals(0, decision.samples);
    }

    @Test
    public void onlyTheNewestSamplesOfTheNetworkCount() {
        StartupDeadlinePolicy policy = new StartupDeadlinePolicy();
        policy.recordFirstByte(LTE, 2_000, 1_000); // pushed out by five newer fast samples
        for (int i = 0; i < StartupDeadlinePolicy.MAX_SAMPLES_CONSIDERED; i++) {
            policy.recordFirstByte(LTE, 100, 2_000 + i);
        }

        StartupDeadlinePolicy.Decision decision = policy.decide(fastLte(), EP1, 3_000);

        assertTrue(decision.early);
        assertEquals(StartupDeadlinePolicy.MAX_SAMPLES_CONSIDERED, decision.samples);
        assertEquals(100, decision.worstFirstByteMs);
    }

    @Test
    public void oneEarlyFailoverPerOpen() {
        StartupDeadlinePolicy policy = new StartupDeadlinePolicy();
        assertTrue(policy.decide(fastLte(), EP1, 1_000).early);

        policy.markEarlyFailover(EP1);

        // Same open (e.g. a retry of the init load, or a track selected later): patient again.
        assertLong(policy.decide(fastLte(), EP1, 2_000), "spent");
        assertTrue(policy.isSpent(EP1));
        // The next open (the recovery reload is its own episode) is judged on evidence again.
        assertTrue(policy.decide(fastLte(), EP2, 3_000).early);
        assertFalse(policy.isSpent(EP2));
    }

    private static void assertLong(StartupDeadlinePolicy.Decision decision, String reason) {
        assertFalse(decision.early);
        assertEquals(StartupDeadlinePolicy.LONG_BUDGET_MS, decision.budgetMs);
        assertEquals(reason, decision.reason);
    }
}
