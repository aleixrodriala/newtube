package com.newtube.mobile.casting;

import com.liskovsoft.mediaserviceinterfaces.RemoteControlService;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Decision core of the one-tap auto-fallback (Direct cast -> the device's YouTube app): the
 * switch fires only for an ARMED, FAILED (non-null reason) CAST_V2 session. Deliberate teardowns
 * (user disconnect, pre-connect teardown, graceful remote close) all carry a null reason and must
 * never trigger it, and a failing Lounge session - including the fallback's own - never re-falls
 * back.
 */
public class CastFallbackDecisionTest {

    @Test
    public void armedFailedDirectSessionFallsBack() {
        assertTrue(CastSessionManager.shouldAutoFallback(true, "Launch failed: timeout",
                CastTarget.Route.CAST_V2));
    }

    @Test
    public void deliberateTeardownNeverFallsBack() {
        // null reason = user disconnect / teardown-before-connect / graceful remote close.
        assertFalse(CastSessionManager.shouldAutoFallback(true, null, CastTarget.Route.CAST_V2));
    }

    @Test
    public void disarmedSessionNeverFallsBack() {
        // Disarmed = playback was proven (or fallback already used once).
        assertFalse(CastSessionManager.shouldAutoFallback(false, "channel error",
                CastTarget.Route.CAST_V2));
    }

    @Test
    public void laterUnsupportedLoadFallsBackForRecommendedSession() {
        // The first VOD already reached PLAYING (armed=false), but the user entered this direct
        // session through the default one-tap path. A later live load must switch to the TV app.
        assertTrue(CastSessionManager.shouldAutoFallbackForLoad(false, true,
                CastTarget.Route.CAST_V2));
    }

    @Test
    public void explicitDirectSessionKeepsChosenRouteOnLaterLoadFailure() {
        assertFalse(CastSessionManager.shouldAutoFallbackForLoad(false, false,
                CastTarget.Route.CAST_V2));
    }

    @Test
    public void loungeLoadNeverFallsBackAgain() {
        assertFalse(CastSessionManager.shouldAutoFallbackForLoad(true, true,
                CastTarget.Route.LOUNGE_MDX));
    }

    @Test
    public void matchingPausedLoungeLoadNeedsExplicitPlay() {
        assertTrue(CastSessionManager.shouldAutoPlayLoungeLoad("live", "live",
                RemoteControlService.STATE_PAUSED));
        assertFalse(CastSessionManager.shouldAutoPlayLoungeLoad("live", "old-video",
                RemoteControlService.STATE_PAUSED));
        assertFalse(CastSessionManager.shouldAutoPlayLoungeLoad("live", "live",
                RemoteControlService.STATE_PLAYING));
    }

    @Test
    public void loungeSessionsNeverFallBack() {
        assertFalse(CastSessionManager.shouldAutoFallback(true, "bind failed",
                CastTarget.Route.LOUNGE_MDX));
        assertFalse(CastSessionManager.shouldAutoFallback(true, "bind failed",
                CastTarget.Route.LOUNGE_MANUAL));
        assertFalse(CastSessionManager.shouldAutoFallback(true, "bind failed",
                CastTarget.Route.LOUNGE_DIAL));
        assertFalse(CastSessionManager.shouldAutoFallback(true, "bind failed", null));
    }
}
