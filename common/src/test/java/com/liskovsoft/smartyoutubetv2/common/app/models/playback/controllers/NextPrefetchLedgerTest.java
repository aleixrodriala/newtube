package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** What one playback may spend on autoplay-next /player resolutions. */
public class NextPrefetchLedgerTest {
    private final NextPrefetchLedger ledger = new NextPrefetchLedger();

    @Test
    public void oneRequestPerTargetWhileItIsInFlightOrDone() {
        assertTrue(ledger.tryStart(1, "a", 0));
        assertFalse(ledger.tryStart(1, "a", 1_000)); // in flight
        ledger.onSuccess(1, "a", 1_200);
        assertFalse(ledger.tryStart(1, "a", 60_000)); // answered and fresh
    }

    @Test
    public void failureGetsExactlyOneRetryAfterTheBackoff() {
        assertTrue(ledger.tryStart(1, "a", 0));
        ledger.onFailure(1, "a", 20_000);
        assertTrue(ledger.canRetry(1, "a"));
        assertFalse(ledger.tryStart(1, "a", 24_999));
        assertTrue(ledger.tryStart(1, "a", 25_000));
        ledger.onFailure(1, "a", 26_000);
        assertFalse(ledger.canRetry(1, "a"));
        assertFalse(ledger.tryStart(1, "a", 60_000));
    }

    @Test
    public void silentFlightCountsAsFailedAfterTheStaleWindow() {
        assertTrue(ledger.tryStart(1, "a", 0));
        assertFalse(ledger.tryStart(1, "a", NextPrefetchLedger.IN_FLIGHT_STALE_MS - 1));
        assertTrue(ledger.tryStart(1, "a", NextPrefetchLedger.IN_FLIGHT_STALE_MS));
        // ...and that was the retry: no third attempt.
        assertFalse(ledger.tryStart(1, "a", 3 * NextPrefetchLedger.IN_FLIGHT_STALE_MS));
    }

    @Test
    public void oldAnswerIsRefreshedAfterALongSeekBack() {
        assertTrue(ledger.tryStart(1, "a", 0));
        ledger.onSuccess(1, "a", 500);
        assertFalse(ledger.tryStart(1, "a", 500 + NextPrefetchLedger.FRESH_MS - 1));
        assertTrue(ledger.tryStart(1, "a", 500 + NextPrefetchLedger.FRESH_MS));
    }

    @Test
    public void changedCandidateIsANewTargetUpToTheCap() {
        assertTrue(ledger.tryStart(1, "a", 0));
        assertTrue(ledger.tryStart(1, "b", 100)); // queue edit / late shuffle pick
        assertTrue(ledger.tryStart(1, "c", 200));
        assertFalse(ledger.tryStart(1, "d", 300)); // churning queue: capped
        assertFalse(ledger.tryStart(1, "a", 400));
    }

    @Test
    public void aNewPlaybackStartsAFreshBudget() {
        assertTrue(ledger.tryStart(1, "a", 0));
        ledger.onFailure(1, "a", 0);
        ledger.tryStart(1, "a", 10_000);
        assertTrue(ledger.tryStart(2, "a", 10_001));
    }

    @Test
    public void lateCallbacksOfAnotherPlaybackOrTargetAreIgnored() {
        assertTrue(ledger.tryStart(1, "a", 0));
        assertTrue(ledger.tryStart(2, "b", 100));
        ledger.onSuccess(1, "a", 200);      // previous playback
        ledger.onFailure(2, "a", 200);      // not the current target
        assertFalse(ledger.tryStart(2, "b", 300)); // still in flight
        assertFalse(ledger.canRetry(2, "b"));
    }

    @Test
    public void nullTargetNeverRequests() {
        assertFalse(ledger.tryStart(1, null, 0));
    }
}
