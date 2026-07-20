package com.newtube.mobile.casting.castv2;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Parsed snapshot of one Cast MEDIA_STATUS entry.
 *
 * <p>Kept as a standalone pure-JSON class so the parsing is unit-testable without a socket. The
 * wire format uses <b>seconds as doubles</b>; this class converts to <b>milliseconds at the
 * parse boundary</b> and nothing above it ever sees seconds - a seconds-vs-ms mix-up already cost
 * this codebase a debugging round once (Lounge sender), so the unit conversion lives in exactly
 * one place.</p>
 */
public final class MediaStatus {

    /** {@code mediaSessionId} - required on every transport command; -1 until the receiver assigns one. */
    public final int mediaSessionId;
    /** "PLAYING", "PAUSED", "BUFFERING" or "IDLE"; null when the entry carried no state. */
    @Nullable
    public final String playerState;
    /** Playback position in ms; -1 when the entry carried no {@code currentTime}. */
    public final long positionMs;
    /** Media duration in ms; -1 when unknown (missing {@code media.duration}, e.g. pre-buffer). */
    public final long durationMs;
    /** Why the player is IDLE ("FINISHED", "CANCELLED", "ERROR", "INTERRUPTED"); null otherwise. */
    @Nullable
    public final String idleReason;

    private MediaStatus(int mediaSessionId, @Nullable String playerState, long positionMs,
                        long durationMs, @Nullable String idleReason) {
        this.mediaSessionId = mediaSessionId;
        this.playerState = playerState;
        this.positionMs = positionMs;
        this.durationMs = durationMs;
        this.idleReason = idleReason;
    }

    /**
     * Parse the first entry of a MEDIA_STATUS payload:
     * {@code {"type":"MEDIA_STATUS","status":[{...}]}}. The status array is legitimately empty
     * (e.g. after the media session ended, or on a rejected LOAD) - that returns {@code null},
     * not an error.
     */
    @Nullable
    public static MediaStatus parseFirst(JSONObject payload) {
        JSONArray statusArray = payload.optJSONArray("status");
        if (statusArray == null || statusArray.length() == 0) {
            return null;
        }
        JSONObject status = statusArray.optJSONObject(0);
        if (status == null) {
            return null;
        }

        int mediaSessionId = status.optInt("mediaSessionId", -1);
        String playerState = emptyToNull(status.optString("playerState"));
        String idleReason = emptyToNull(status.optString("idleReason"));

        long positionMs = status.has("currentTime")
                ? secondsToMs(status.optDouble("currentTime", 0)) : -1;

        long durationMs = -1;
        JSONObject media = status.optJSONObject("media");
        if (media != null && media.has("duration")) {
            double duration = media.optDouble("duration", -1);
            if (duration >= 0) {
                durationMs = secondsToMs(duration);
            }
        }

        return new MediaStatus(mediaSessionId, playerState, positionMs, durationMs, idleReason);
    }

    /** The one seconds-to-ms conversion for the whole Cast v2 stack. */
    static long secondsToMs(double seconds) {
        return Math.round(seconds * 1000d);
    }

    @Nullable
    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    @NonNull
    @Override
    public String toString() {
        return "MediaStatus{session=" + mediaSessionId + ", " + playerState
                + (idleReason != null ? "/" + idleReason : "")
                + ", " + positionMs + "/" + durationMs + "ms}";
    }
}
