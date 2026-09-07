package com.newtube.mobile.sabrproof;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Synthetic booleans/identifiers only: no Android initialization, requests or account data. */
public class SabrProofGateTest {
    @Test
    public void acceptsConfirmedExistingTvVodWithMatchingIdentityAndRequiredMetadata() {
        assertTrue(new Input().accepts());
    }

    @Test
    public void loginRequiredIsRejectedEvenWhenEndpointAndConfigArePresent() {
        Input input = new Input();
        input.rawStatus = "LOGIN_REQUIRED";
        assertFalse(input.accepts());
    }

    @Test
    public void absentUnknownAndOtherNonOkStatusesAreRejected() {
        for (String status : new String[]{null, "", "ok", "UNKNOWN", "UNPLAYABLE", "ERROR",
                "AGE_CHECK_REQUIRED", "CONTENT_CHECK_REQUIRED"}) {
            Input input = new Input();
            input.rawStatus = status;
            assertFalse("status=" + status, input.accepts());
        }
    }

    @Test
    public void explicitBotCheckOverridesAnOkStatus() {
        Input input = new Input();
        input.botCheck = true;
        assertFalse(input.accepts());
    }

    @Test
    public void serverSignedOutIsRejected() {
        Input input = new Input();
        input.serverAuth = false;
        assertFalse(input.accepts());
    }

    @Test
    public void missingServerAuthenticationVerdictIsRejected() {
        Input input = new Input();
        input.serverAuth = null;
        assertFalse(input.accepts());
    }

    @Test
    public void mismatchedVideoIdentityIsRejected() {
        Input input = new Input();
        input.actualVideoId = "fixture0002";
        assertFalse(input.accepts());
    }

    @Test
    public void absentOrEmptyVideoIdentitiesAreRejected() {
        Input input = new Input();
        input.actualVideoId = null;
        assertFalse(input.accepts());
        input.expectedVideoId = null;
        assertFalse(input.accepts());
        input.expectedVideoId = "";
        input.actualVideoId = "";
        assertFalse(input.accepts());
    }

    @Test
    public void currentlyLiveContentIsRejectedIndependently() {
        Input input = new Input();
        input.isLive = true;
        assertFalse(input.accepts());
    }

    @Test
    public void completedLiveRecordingIsRejectedEvenWhenNotCurrentlyLive() {
        Input input = new Input();
        input.isLiveContent = true;
        assertFalse(input.accepts());
    }

    @Test
    public void missingEndpointIsRejected() {
        Input input = new Input();
        input.hasEndpoint = false;
        assertFalse(input.accepts());
    }

    @Test
    public void missingConfigIsRejected() {
        Input input = new Input();
        input.hasConfig = false;
        assertFalse(input.accepts());
    }

    @Test
    public void nonExistingTvRouteIsRejectedDespiteOtherwiseValidMetadata() {
        Input input = new Input();
        input.existingTv = false;
        assertFalse(input.accepts());
    }

    private static final class Input {
        String rawStatus = "OK";
        boolean botCheck;
        Boolean serverAuth = true;
        String expectedVideoId = "fixture0001";
        String actualVideoId = "fixture0001";
        boolean isLive;
        boolean isLiveContent;
        boolean hasEndpoint = true;
        boolean hasConfig = true;
        boolean existingTv = true;

        boolean accepts() {
            return SabrProofGate.accepts(rawStatus, botCheck, serverAuth, expectedVideoId,
                    actualVideoId, isLive, isLiveContent, hasEndpoint, hasConfig, existingTv);
        }
    }
}
