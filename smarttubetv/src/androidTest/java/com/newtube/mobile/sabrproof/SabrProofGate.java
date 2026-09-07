package com.newtube.mobile.sabrproof;

/** Pure eligibility check for the opted-in delivery proof; never changes playback policy. */
public final class SabrProofGate {
    private SabrProofGate() {}

    /**
     * existingTv means the request used the already-selected account through the existing TV route.
     * Endpoint presence is not endpoint validation: the caller must retain its HTTPS/host checks.
     * A missing server authentication verdict is not evidence of an authenticated response.
     */
    public static boolean accepts(String rawStatus, boolean botCheck, Boolean serverAuth,
            String expectedVideoId, String actualVideoId, boolean isLive, boolean isLiveContent,
            boolean hasEndpoint, boolean hasConfig, boolean existingTv) {
        return "OK".equals(rawStatus)
                && !botCheck
                && Boolean.TRUE.equals(serverAuth)
                && expectedVideoId != null && !expectedVideoId.isEmpty()
                && expectedVideoId.equals(actualVideoId)
                && !isLive && !isLiveContent
                && hasEndpoint && hasConfig && existingTv;
    }
}
