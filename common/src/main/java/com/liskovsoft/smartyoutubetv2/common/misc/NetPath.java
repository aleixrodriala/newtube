package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicLong;

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
    private static final AtomicLong sEpisodeSequence = new AtomicLong();
    private static volatile long sEpisode;
    private static volatile String sOpenVideoId;
    /** videoId of the last {@link #logTap} so {@link #logOpen} knows whether to keep its t0. */
    private static volatile String sTapVideoId;

    private NetPath() {
    }

    /**
     * Milestone 0: earliest user intent (the tap, before the player activity even starts).
     * Starts the timing window; the following {@link #logOpen} for the SAME video keeps this t0,
     * so the tap-&gt;open overlap (where prefetch-at-tap earns its keep) becomes measurable.
     */
    public static void logTap(String videoId) {
        sOpenT0 = SystemClock.elapsedRealtime();
        sEpisode = sEpisodeSequence.incrementAndGet();
        sOpenVideoId = videoId;
        sTapVideoId = videoId;
        android.util.Log.d(TAG, context() + " tap");
    }

    /** Milestone 1: the app kicked off loading a video. Starts the timing window (unless a tap did). */
    public static void logOpen(String videoId, String title) {
        // Auto-advance / reload opens arrive without a tap: start a fresh window for those.
        if (videoId == null || !videoId.equals(sTapVideoId)) {
            sOpenT0 = SystemClock.elapsedRealtime();
            sEpisode = sEpisodeSequence.incrementAndGet();
        }
        sOpenVideoId = videoId;
        sTapVideoId = null;
        android.util.Log.d(TAG, context() + " open +" + elapsedMs() + " \"" + trunc(title, 40) + "\"");
    }

    /** Milestone 2: the InnerTube metadata/streamingData response landed. */
    public static void logInfo(String videoId, int dashFormats, boolean hls, boolean sabr, boolean live) {
        android.util.Log.d(TAG, context() + " info +" + elapsedMs() + " dash=" + dashFormats
                + " hls=" + yn(hls) + " sabr=" + yn(sabr) + " live=" + yn(live));
    }

    /** Milestone 3: media source handed to the player (setMediaSource+prepare), pipeline known. */
    public static void logPrepare(String videoId, String type) {
        android.util.Log.d(TAG, context() + " prepare +" + elapsedMs() + " type=" + type);
    }

    /** Milestone 4: first video frame rendered. */
    public static void logFirstFrame(String videoId) {
        android.util.Log.d(TAG, context() + " first-frame +" + elapsedMs());
    }

    /**
     * Generic one-off diagnostic line under the NetPath tag. No milestone timing - just
     * guaranteed logcat visibility. Callers must redact signed URLs/tokens first.
     */
    public static void log(String message) {
        android.util.Log.d(TAG, message);
    }

    /** Milestone 5: fatal player error surfaced ({@code onPlayerError}). */
    public static void logError(String videoId, Throwable error) {
        String errorClass = error != null ? error.getClass().getSimpleName() : "null";
        String errorMessage = error != null ? safeErrorMessage(error.getMessage()) : null;
        android.util.Log.w(TAG, context() + " error +" + elapsedMs() + " "
                + errorClass + ": " + trunc(errorMessage, 120)
                + " causes=" + throwableSummary(error));
    }

    /** Stable, credential-free correlation shared by open, source and media-load diagnostics. */
    public static String context() {
        return "ep=" + sEpisode + " video=" + safeId(sOpenVideoId);
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

    /**
     * Credential-free active-network identity for correlating API and media events. The framework
     * network hash changes when Android replaces the default network (for example Wi-Fi to 5G),
     * while exposing no SSID, carrier, IP address or account data.
     */
    public static String networkId(Context context) {
        if (context == null) {
            return "unknown";
        }
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager != null ? manager.getActiveNetwork() : null;
            NetworkCapabilities caps = network != null
                    ? manager.getNetworkCapabilities(network) : null;
            return networkId(network, caps);
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    /** Dense, non-sensitive active-network state used at open/error/recovery boundaries. */
    public static String networkSnapshot(Context context) {
        if (context == null) {
            return "net=unknown";
        }
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager != null ? manager.getActiveNetwork() : null;
            return networkSnapshot(manager, network);
        } catch (RuntimeException e) {
            return "net=unknown error=" + e.getClass().getSimpleName();
        }
    }

    /** Same as {@link #networkSnapshot(Context)}, but for a specific callback network. */
    public static String networkSnapshot(Context context, Network network) {
        if (context == null) {
            return "net=unknown";
        }
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            return networkSnapshot(manager, network);
        } catch (RuntimeException e) {
            return "net=unknown error=" + e.getClass().getSimpleName();
        }
    }

    private static String networkSnapshot(ConnectivityManager manager, Network network) {
        if (manager == null || network == null) {
            return "net=none";
        }
        NetworkCapabilities caps = manager.getNetworkCapabilities(network);
        if (caps == null) {
            return "net=" + network.hashCode() + " caps=none";
        }
        return "net=" + networkId(network, caps)
                + " validated=" + yn(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                + " internet=" + yn(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                + " metered=" + yn(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                + " captive=" + yn(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
                + " downKbps=" + caps.getLinkDownstreamBandwidthKbps()
                + " upKbps=" + caps.getLinkUpstreamBandwidthKbps();
    }

    private static String networkId(Network network, NetworkCapabilities caps) {
        if (network == null || caps == null) {
            return "none";
        }
        String transport = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "vpn"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cell"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ethernet"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ? "bluetooth"
                : "other";
        return transport + ':' + network.hashCode();
    }

    /**
     * Short cause-chain fingerprint for field reports. Messages are printable/truncated and signed
     * URLs are never included: googlevideo exception messages are reduced to their class plus HTTP
     * status when one is present.
     */
    public static String throwableSummary(Throwable error) {
        if (error == null) {
            return "none";
        }
        StringBuilder result = new StringBuilder();
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 6; depth++, cause = cause.getCause()) {
            if (depth > 0) {
                result.append("<-");
            }
            result.append(cause.getClass().getSimpleName());
            String message = cause.getMessage();
            if (message != null) {
                String status = httpStatusFromMessage(message);
                if (status != null) {
                    result.append("(http=").append(status).append(')');
                } else if (!message.contains("http://") && !message.contains("https://")) {
                    result.append('(').append(trunc(printable(message), 80)).append(')');
                }
            }
        }
        return result.toString();
    }

    private static String httpStatusFromMessage(String message) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)(?:response|status)(?:\\s+code)?\\s*[:=]?\\s*(\\d{3})")
                .matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String printable(String value) {
        return value.replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static String safeErrorMessage(String value) {
        if (value == null) {
            return null;
        }
        return printable(value.replaceAll("(?i)https?://\\S+", "[url]"));
    }

    private static String safeId(String videoId) {
        return videoId != null ? videoId : "?";
    }

    private static String yn(boolean value) {
        return value ? "y" : "n";
    }
}
