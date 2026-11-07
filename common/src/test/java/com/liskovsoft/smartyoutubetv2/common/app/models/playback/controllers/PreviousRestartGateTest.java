package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PreviousRestartGateTest {
    private final PreviousRestartGate gate = new PreviousRestartGate();

    @Test
    public void nearTheStartAlwaysGoesToPrevious() {
        gate.reset(0, true);
        assertFalse(gate.shouldRestart(10_000, 60_000, true));
        assertFalse(gate.shouldRestart(5_000, 60_000, false));
    }

    @Test
    public void watchedPastThresholdRestarts() {
        gate.reset(0, true);
        assertTrue(gate.shouldRestart(31_600, 31_600, true));
    }

    @Test
    public void automaticJumpPastThresholdStillGoesToPrevious() {
        // Restart, then SponsorBlock skips the intro to 13 s after ~1 s of playback.
        gate.reset(0, false);
        gate.onPlaying(500);
        gate.onStopped(1_000); // the skip's buffering
        gate.onPlaying(1_800);
        assertEquals(500 + 2_200, gate.getWatchedMs(4_000));
        assertFalse(gate.shouldRestart(15_200, 4_000, true));
    }

    @Test
    public void noPreviousRestartsPastThreshold() {
        gate.reset(0, true);
        assertTrue(gate.shouldRestart(13_000, 1_000, false));
    }

    @Test
    public void pauseAndBufferingAreNotWatchTime() {
        gate.reset(0, true);
        gate.onStopped(4_000);   // pause
        gate.onPlaying(64_000);  // resume a minute later
        gate.onStopped(68_000);  // buffering
        gate.onPlaying(90_000);
        assertEquals(8_000, gate.getWatchedMs(90_000));
        assertFalse(gate.shouldRestart(40_000, 90_000, true));
        assertTrue(gate.shouldRestart(43_000, 93_000, true));
    }

    @Test
    public void repeatedPlayingCallsKeepTheStretchOpen() {
        gate.reset(0, false);
        gate.onPlaying(1_000);
        gate.onPlaying(5_000); // READY again after a rebuffer the engine didn't report
        assertEquals(11_000, gate.getWatchedMs(12_000));
    }

    @Test
    public void resetStartsOverAndRemembersPlayingState() {
        gate.reset(0, true);
        assertTrue(gate.shouldRestart(20_000, 20_000, true));
        gate.reset(20_000, true); // the restart itself
        assertFalse(gate.shouldRestart(13_000, 23_000, true));
        gate.reset(30_000, false);
        assertEquals(0, gate.getWatchedMs(90_000));
    }

    @Test
    public void stoppedWithoutPlayingIsHarmless() {
        gate.reset(0, false);
        gate.onStopped(5_000);
        assertEquals(0, gate.getWatchedMs(10_000));
    }
}
