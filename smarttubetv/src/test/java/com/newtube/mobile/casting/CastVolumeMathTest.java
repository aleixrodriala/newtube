package com.newtube.mobile.casting;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Volume-key routing math: clamping to the 0-100 wire range and delta application with the
 * unknown-state baseline (both routes take ABSOLUTE volume, so the first press before any volume
 * event starts from the documented baseline).
 */
public class CastVolumeMathTest {

    @Test
    public void clampsToWireRange() {
        assertEquals(0, CastSessionManager.clampVolumePercent(-5));
        assertEquals(0, CastSessionManager.clampVolumePercent(0));
        assertEquals(45, CastSessionManager.clampVolumePercent(45));
        assertEquals(100, CastSessionManager.clampVolumePercent(100));
        assertEquals(100, CastSessionManager.clampVolumePercent(105));
    }

    @Test
    public void appliesDeltaToKnownVolume() {
        assertEquals(50, CastSessionManager.applyVolumeDelta(45, 5));
        assertEquals(40, CastSessionManager.applyVolumeDelta(45, -5));
    }

    @Test
    public void deltaClampsAtTheEdges() {
        assertEquals(100, CastSessionManager.applyVolumeDelta(98, 5));
        assertEquals(0, CastSessionManager.applyVolumeDelta(3, -5));
    }

    @Test
    public void unknownVolumeStartsFromBaseline() {
        assertEquals(CastSessionManager.VOLUME_BASELINE_PERCENT + 5,
                CastSessionManager.applyVolumeDelta(-1, 5));
        assertEquals(CastSessionManager.VOLUME_BASELINE_PERCENT - 5,
                CastSessionManager.applyVolumeDelta(-1, -5));
    }
}
