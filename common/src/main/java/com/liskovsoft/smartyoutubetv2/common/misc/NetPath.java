package com.liskovsoft.smartyoutubetv2.common.misc;

import android.os.SystemClock;

/**
 * "NetPath" diagnostics: one-line open-path timing milestones (open -&gt; info -&gt; prepare -&gt;
 * first-frame, plus player errors) under the single logcat tag {@link #TAG}. Always on (~5 lines
 * per video open, negligible) - answers "where is it hanging" without a debugger.
 *
 * <p>Uses {@link android.util.Log} DIRECTLY (not the com.liskovsoft mylogger wrapper, whose logcat
 * visibility is unreliable/rolls out of buffer): NetPath lines must always reach logcat.</p>
 *
 * <p>State is deliberately minimal: a single volatile t0 for the current open. Elapsed values are
 * approximate by design - this is diagnostics, not telemetry.</p>
 */
public final class NetPath {
    public static final String TAG = "NetPath";

    /** t0 of the current video open ({@link SystemClock#elapsedRealtime()} based). */
    private static volatile long sOpenT0;

    private NetPath() {
    }

    /** Milestone 1: the app kicked off loading a video. Starts the timing window. */
    public static void logOpen(String videoId, String title) {
        sOpenT0 = SystemClock.elapsedRealtime();
        android.util.Log.d(TAG, "open " + safeId(videoId) + " \"" + trunc(title, 40) + "\"");
    }

    /** Milestone 2: the InnerTube metadata/streamingData response landed. */
    public static void logInfo(String videoId, int dashFormats, boolean hls, boolean sabr, boolean live) {
        android.util.Log.d(TAG, "info " + safeId(videoId) + " +" + elapsedMs() + " dash=" + dashFormats
                + " hls=" + yn(hls) + " sabr=" + yn(sabr) + " live=" + yn(live));
    }

    /** Milestone 3: media source handed to the player (setMediaSource+prepare), pipeline known. */
    public static void logPrepare(String videoId, String type) {
        android.util.Log.d(TAG, "prepare " + safeId(videoId) + " +" + elapsedMs() + " type=" + type);
    }

    /** Milestone 4: first video frame rendered. */
    public static void logFirstFrame(String videoId) {
        android.util.Log.d(TAG, "first-frame " + safeId(videoId) + " +" + elapsedMs());
    }

    /** Milestone 5: fatal player error surfaced ({@code onPlayerError}). */
    public static void logError(String videoId, Throwable error) {
        String errorClass = error != null ? error.getClass().getSimpleName() : "null";
        String errorMessage = error != null ? error.getMessage() : null;
        android.util.Log.w(TAG, "error " + safeId(videoId) + " +" + elapsedMs() + " "
                + errorClass + ": " + trunc(errorMessage, 120));
    }

    /** Elapsed ms since the last {@link #logOpen}; -1 if no open has been seen yet. */
    public static long elapsedMs() {
        long t0 = sOpenT0;
        return t0 == 0 ? -1 : SystemClock.elapsedRealtime() - t0;
    }

    /** Null-safe truncation for dense one-line logging. */
    public static String trunc(String value, int maxLen) {
        if (value == null) {
            return "null";
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen) + "…";
    }

    private static String safeId(String videoId) {
        return videoId != null ? videoId : "?";
    }

    private static String yn(boolean value) {
        return value ? "y" : "n";
    }
}
