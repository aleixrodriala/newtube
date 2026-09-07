package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

/** Tracks an explicit retry of a pre-media denial; never schedules work or bypasses service caches. */
final class PreMediaRetryGate {
    private String mDeniedVideoId;
    private boolean mRetryInFlight;

    void onResult(String videoId, boolean botDenied) {
        mDeniedVideoId = botDenied ? videoId : null;
        mRetryInFlight = false;
    }

    boolean tryBeginRetry(String videoId) {
        if (videoId == null || !videoId.equals(mDeniedVideoId) || mRetryInFlight) {
            return false;
        }
        mRetryInFlight = true;
        return true;
    }

    void clear() {
        mDeniedVideoId = null;
        mRetryInFlight = false;
    }
}
