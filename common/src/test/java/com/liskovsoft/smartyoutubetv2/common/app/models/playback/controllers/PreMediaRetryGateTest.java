package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PreMediaRetryGateTest {
    @Test
    public void onlyTheDeniedVideoCanClaimOneExplicitRetry() {
        PreMediaRetryGate gate = new PreMediaRetryGate();
        assertFalse(gate.tryBeginRetry("video"));
        gate.onResult("video", true);

        assertFalse(gate.tryBeginRetry(null));
        assertFalse(gate.tryBeginRetry("other"));
        assertTrue(gate.tryBeginRetry("video"));
        assertFalse(gate.tryBeginRetry("video"));
    }

    @Test
    public void anotherDenialAllowsOnlyANewExplicitRequest() {
        PreMediaRetryGate gate = new PreMediaRetryGate();
        gate.onResult("video", true);
        assertTrue(gate.tryBeginRetry("video"));
        gate.onResult("video", true); // may be the unchanged service's negative cache

        assertTrue(gate.tryBeginRetry("video"));
        assertFalse(gate.tryBeginRetry("video"));
    }

    @Test
    public void playableOrOtherResultClearsTheDeniedState() {
        PreMediaRetryGate gate = new PreMediaRetryGate();
        gate.onResult("video", true);
        assertTrue(gate.tryBeginRetry("video"));
        gate.onResult("video", false);

        assertFalse(gate.tryBeginRetry("video"));
    }

    @Test
    public void newVideoOrReleaseClearsPendingAndInFlightState() {
        PreMediaRetryGate gate = new PreMediaRetryGate();
        gate.onResult("video", true);
        assertTrue(gate.tryBeginRetry("video"));
        gate.clear();

        assertFalse(gate.tryBeginRetry("video"));
        gate.onResult("other", true);
        assertTrue(gate.tryBeginRetry("other"));
    }
}
