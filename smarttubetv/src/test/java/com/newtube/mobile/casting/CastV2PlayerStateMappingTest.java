package com.newtube.mobile.casting;

import com.liskovsoft.mediaserviceinterfaces.RemoteControlService;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Route A state mapping: Cast MEDIA_STATUS playerState strings onto the Lounge
 * RemoteControlService.STATE_* model the overlay/notification already speak.
 */
public class CastV2PlayerStateMappingTest {

    @Test
    public void playingMapsToPlaying() {
        assertEquals(RemoteControlService.STATE_PLAYING,
                CastSessionManager.mapCastV2PlayerState("PLAYING"));
    }

    /**
     * Deliberate: BUFFERING reads as "playing" so the overlay's play/pause button stays honest
     * (position drift is bounded by the 5s MEDIA_STATUS poll - see mapCastV2PlayerState javadoc).
     */
    @Test
    public void bufferingReadsAsPlaying() {
        assertEquals(RemoteControlService.STATE_PLAYING,
                CastSessionManager.mapCastV2PlayerState("BUFFERING"));
    }

    @Test
    public void pausedMapsToPaused() {
        assertEquals(RemoteControlService.STATE_PAUSED,
                CastSessionManager.mapCastV2PlayerState("PAUSED"));
    }

    @Test
    public void idleMapsToIdle() {
        assertEquals(RemoteControlService.STATE_IDLE,
                CastSessionManager.mapCastV2PlayerState("IDLE"));
    }

    @Test
    public void unknownStatesFailSafeToIdle() {
        assertEquals(RemoteControlService.STATE_IDLE,
                CastSessionManager.mapCastV2PlayerState("LOADING"));
        assertEquals(RemoteControlService.STATE_IDLE,
                CastSessionManager.mapCastV2PlayerState(""));
        assertEquals(RemoteControlService.STATE_IDLE,
                CastSessionManager.mapCastV2PlayerState(null));
    }
}
