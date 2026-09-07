package com.newtube.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Scheduling/readiness regressions with no timers, real format fetches or external requests. */
public class SessionWarmupGateTest {
    @Test
    public void historicalSetupFlagDoesNotPreventIdleRefreshOfExpiredCaches() {
        SessionWarmupGate gate = new SessionWarmupGate();
        gate.restore(true);

        assertTrue(gate.isWarm());
        assertTrue(gate.tryScheduleFallback());
        assertTrue(gate.trySchedule());
        assertTrue(gate.tryBeginFetch());
    }

    @Test
    public void selectingVideoDuringDelaySuppressesWarmupEvenBeforeFirstFrame() {
        SessionWarmupGate gate = new SessionWarmupGate();
        gate.restore(true);
        assertTrue(gate.trySchedule());
        gate.onPlaybackRequested();

        assertFalse(gate.tryBeginFetch());
        assertFalse(gate.tryScheduleFallback());
    }

    @Test
    public void directPlaybackBeforeFeedPaintSuppressesBothSpeculativeJobs() {
        SessionWarmupGate gate = new SessionWarmupGate();
        gate.onPlaybackRequested();

        assertFalse(gate.tryScheduleFallback());
        assertFalse(gate.trySchedule());
        assertFalse(gate.tryBeginFetch());
        assertFalse(gate.isWarm()); // keep first-run hint while real playback initializes
    }

    @Test
    public void currentPlaybackReadinessCancelsWarmupEvenOnPreviouslyWarmInstall() {
        SessionWarmupGate gate = new SessionWarmupGate();
        gate.restore(true);
        assertTrue(gate.trySchedule());
        assertFalse(gate.markWarm()); // already persisted, but now ready in this process too

        assertFalse(gate.tryBeginFetch());
    }

    @Test
    public void freshInstallStillRunsOneBackgroundWarmupAndBecomesReady() {
        SessionWarmupGate gate = new SessionWarmupGate();
        gate.restore(false);

        assertFalse(gate.isWarm());
        assertTrue(gate.tryScheduleFallback());
        assertTrue(gate.trySchedule()); // feed paint wins before the fallback wakes
        assertFalse(gate.trySchedule()); // fallback cannot schedule a duplicate
        assertTrue(gate.tryBeginFetch()); // worker's post-delay check
        assertFalse(gate.tryBeginFetch());
        assertTrue(gate.markWarm());
        assertTrue(gate.isWarm());
    }

    @Test
    public void realPlaybackDuringFeedDelaySuppressesThePendingFetch() {
        SessionWarmupGate gate = new SessionWarmupGate();
        assertTrue(gate.trySchedule());
        gate.markWarm(); // a real video completes setup during the 1.2-second delay

        assertFalse(gate.tryBeginFetch());
        assertFalse(gate.tryScheduleFallback());
        assertTrue(gate.isWarm());
    }

    @Test
    public void realPlaybackBeforeFallbackWakesPreventsScheduling() {
        SessionWarmupGate gate = new SessionWarmupGate();
        assertTrue(gate.tryScheduleFallback());
        gate.markWarm();

        assertFalse(gate.trySchedule());
        assertFalse(gate.tryBeginFetch());
    }

    @Test
    public void readinessCannotBeDowngradedByAnOlderPreferenceRead() {
        SessionWarmupGate gate = new SessionWarmupGate();
        assertTrue(gate.markWarm());
        gate.restore(false);

        assertTrue(gate.isWarm());
        assertFalse(gate.markWarm());
        assertFalse(gate.trySchedule());
    }

    @Test
    public void fallbackIsNotCreatedIfFeedAlreadyScheduledTheWorker() {
        SessionWarmupGate gate = new SessionWarmupGate();
        assertTrue(gate.trySchedule());
        assertFalse(gate.tryScheduleFallback());
    }

    @Test
    public void unsuccessfulWarmupRetainsExistingOneShotBehavior() {
        SessionWarmupGate gate = new SessionWarmupGate();
        assertTrue(gate.trySchedule());
        assertTrue(gate.tryBeginFetch());
        // A failed/empty fetch does not mark readiness or start a retry loop.
        assertFalse(gate.isWarm());
        assertFalse(gate.trySchedule());
        assertFalse(gate.tryBeginFetch());
        assertTrue(gate.markWarm()); // the next real playback can still complete setup
    }

    @Test
    public void concurrentFeedAndFallbackTriggersClaimExactlyOneWorker() throws Exception {
        SessionWarmupGate gate = new SessionWarmupGate();
        assertEquals(1, race(gate::trySchedule));
        assertEquals(1, race(gate::tryBeginFetch));
    }

    @Test
    public void concurrentInitializationClaimsExactlyOneLaunchFallback() throws Exception {
        SessionWarmupGate gate = new SessionWarmupGate();
        assertEquals(1, race(gate::tryScheduleFallback));
    }

    private static int race(BooleanSupplier claim) throws Exception {
        int callers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(3, TimeUnit.SECONDS));
                    return claim.getAsBoolean();
                }));
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get(3, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            return winners;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
