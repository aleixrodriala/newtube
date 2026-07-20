package com.newtube.mobile.casting;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Response;

/**
 * DIAL discovery for Route B (CASTING.md): find TVs whose YouTube app can be driven over the
 * Lounge protocol.
 *
 * <p>Flow per device: SSDP M-SEARCH (ST {@code urn:dial-multiscreen-org:service:dial:1}) &rarr;
 * GET the LOCATION device description (friendlyName + {@code Application-URL} header) &rarr;
 * GET {@code <AppURL>/YouTube} for the app state; a running app usually reports its Lounge
 * {@code screenId} inside {@code <additionalData>}. A device whose YouTube app is installed but
 * not running is still surfaced (target present, needs launch) - {@link #launchYouTube} POSTs the
 * DIAL launch and re-polls for the screenId (bounded).</p>
 *
 * <p>All network work runs on a private worker thread; listener callbacks post to main. The exact
 * XML/param details are isolated in the small {@code extractTag}/parse helpers on purpose - a
 * protocol spec for the Lounge/DIAL handshake is being produced in parallel and may adjust them.</p>
 */
public class DialDiscovery {

    public interface Listener {
        /** A DIAL device with a YouTube app was found (main thread). May fire per device, incrementally. */
        void onTargetFound(CastTarget target);

        /** The collect window elapsed and every response was processed (main thread). */
        void onDiscoveryFinished();
    }

    /** Result callback for {@link #launchYouTube} (main thread). Target carries the screenId on success. */
    public interface LaunchCallback {
        void onLaunchResult(@Nullable CastTarget target);
    }

    private static final String TAG = DialDiscovery.class.getSimpleName();

    private static final String SSDP_ADDRESS = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String SSDP_ST = "urn:dial-multiscreen-org:service:dial:1";
    /** MX (max response delay) per brief: 2-3s. */
    private static final int SSDP_MX = 3;
    /** Total collect window for SSDP responses. */
    private static final long COLLECT_WINDOW_MS = 4_000;
    private static final int RECEIVE_TIMEOUT_MS = 500;
    /** Launch flow: POST then poll for the screenId. A cold TV-app start can take tens of seconds
     *  (ytcast polls every 3s up to 1min); 30s covers most sets without feeling stuck. */
    private static final long LAUNCH_POLL_TOTAL_MS = 30_000;
    private static final long LAUNCH_POLL_INTERVAL_MS = 2_000;
    /** DIAL app name on the receiver. */
    private static final String DIAL_APP_NAME = "YouTube";
    /** Required on the YouTube app-state/launch calls; some receivers reject requests without it. */
    private static final String ORIGIN = "https://www.youtube.com";

    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    /** Short LAN timeouts instead of the API client's long ones (a dead IP must not stall discovery). */
    private final OkHttpClient mHttpClient;

    private volatile boolean mStopped;
    private Thread mWorker;
    private WifiManager.MulticastLock mMulticastLock;

    public DialDiscovery(Context context) {
        mContext = context.getApplicationContext();
        mHttpClient = OkHttpManager.instance().getClient().newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .build();
    }

    /** Begin a discovery round. Safe to call once per instance; results stream to the listener. */
    public void start(Listener listener) {
        mStopped = false;
        mWorker = new Thread(() -> runDiscovery(listener), "CastDialDiscovery");
        mWorker.setDaemon(true);
        mWorker.start();
    }

    /** Stop discovery (picker dismissed). Idempotent; silences any late callbacks. */
    public void stop() {
        mStopped = true;
        Thread worker = mWorker;
        if (worker != null) {
            worker.interrupt();
        }
        mWorker = null;
    }

    // ---------------------------------------------------------------------------------
    // SSDP
    // ---------------------------------------------------------------------------------

    private void runDiscovery(Listener listener) {
        // Multicast on Android is filtered in hardware unless a MulticastLock is held. Some stacks
        // deliver the (unicast) M-SEARCH responses regardless, but the lock is the documented way.
        acquireMulticastLock();
        Set<String> locations = new HashSet<>();
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(RECEIVE_TIMEOUT_MS);

            byte[] request = buildSearchRequest();
            InetAddress group = InetAddress.getByName(SSDP_ADDRESS);
            DatagramPacket packet = new DatagramPacket(request, request.length, group, SSDP_PORT);
            // Two sends ~1s apart: SSDP is UDP, first packets get eaten on sleepy Wi-Fi radios.
            socket.send(packet);

            long deadline = System.currentTimeMillis() + COLLECT_WINDOW_MS;
            boolean resent = false;
            byte[] buffer = new byte[4096];
            while (!mStopped && System.currentTimeMillis() < deadline) {
                if (!resent && System.currentTimeMillis() > deadline - COLLECT_WINDOW_MS + 1_000) {
                    resent = true;
                    try {
                        socket.send(new DatagramPacket(request, request.length, group, SSDP_PORT));
                    } catch (Exception e) {
                        // best-effort resend
                    }
                }
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(response);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String message = new String(response.getData(), response.getOffset(),
                        response.getLength(), StandardCharsets.UTF_8);
                String location = extractHeader(message, "LOCATION");
                if (!TextUtils.isEmpty(location) && locations.add(location)) {
                    // Probe each new device on its own short-lived thread so rows appear
                    // incrementally AND a slow/dead device can't eat the SSDP collect window.
                    Thread probe = new Thread(() -> probeDevice(location, listener), "CastDialProbe");
                    probe.setDaemon(true);
                    probe.start();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "SSDP discovery failed: " + e);
        } finally {
            if (socket != null) {
                socket.close();
            }
            releaseMulticastLock();
        }

        if (!mStopped) {
            mMainHandler.post(listener::onDiscoveryFinished);
        }
    }

    private static byte[] buildSearchRequest() {
        String search = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: " + SSDP_MX + "\r\n"
                + "ST: " + SSDP_ST + "\r\n"
                + "\r\n";
        return search.getBytes(StandardCharsets.UTF_8);
    }

    @Nullable
    private static String extractHeader(String message, String header) {
        for (String line : message.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && header.equalsIgnoreCase(line.substring(0, colon).trim())) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------
    // Device description + YouTube app state
    // ---------------------------------------------------------------------------------

    /** GET the device description, then the YouTube app state; report a target when YouTube exists. */
    private void probeDevice(String location, Listener listener) {
        try {
            String friendlyName = null;
            String appUrl = null;
            Response deviceResponse = OkHttpManager.instance().doRequest(location, mHttpClient);
            if (deviceResponse != null) {
                try {
                    // DIAL 1.7: the app endpoint base arrives as the Application-URL response header.
                    appUrl = deviceResponse.header("Application-URL");
                    if (appUrl == null) {
                        appUrl = deviceResponse.header("Application-DIAL-URL");
                    }
                    if (deviceResponse.body() != null) {
                        friendlyName = extractTag(deviceResponse.body().string(), "friendlyName");
                    }
                } finally {
                    deviceResponse.close();
                }
            }

            if (mStopped || TextUtils.isEmpty(appUrl)) {
                return;
            }
            if (TextUtils.isEmpty(friendlyName)) {
                friendlyName = hostOf(location);
            }

            AppState state = queryAppState(appUrl);
            if (state == null || !state.installed) {
                return; // no YouTube app on this device
            }

            CastTarget target = CastTarget.fromDial(friendlyName, state.screenId, appUrl, location);
            if (!mStopped) {
                mMainHandler.post(() -> listener.onTargetFound(target));
            }
        } catch (Exception e) {
            Log.e(TAG, "DIAL probe failed for " + location + ": " + e);
        }
    }

    /** State of the receiver's YouTube DIAL app. */
    private static class AppState {
        boolean installed;
        boolean running;
        @Nullable String screenId;
    }

    @Nullable
    private AppState queryAppState(String appUrl) {
        String stateUrl = joinUrl(appUrl, DIAL_APP_NAME);
        Response response = null;
        try {
            response = mHttpClient.newCall(new okhttp3.Request.Builder()
                    .url(stateUrl)
                    .header("Origin", ORIGIN)
                    .build()).execute();
            AppState state = new AppState();
            if (response.code() == 404) {
                state.installed = false;
                return state;
            }
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            String xml = response.body().string();
            state.installed = true;
            String appState = extractTag(xml, "state");
            state.running = appState != null && appState.toLowerCase(Locale.US).startsWith("running");
            // The Lounge screenId rides in <additionalData><screenId>..</screenId></additionalData>.
            // Treat a missing id as "present but needs launch/pairing", never as an error.
            state.screenId = extractTag(xml, "screenId");
            return state;
        } catch (Exception e) {
            Log.e(TAG, "DIAL state query failed: " + e);
            return null;
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Launch + screenId poll (for targets discovered without a screenId)
    // ---------------------------------------------------------------------------------

    /**
     * POST the DIAL launch for the YouTube app, then re-poll the app state until a screenId shows
     * up (bounded at ~10s). Runs on its own worker; the callback is posted to main with either the
     * target enriched with a screenId or {@code null} on failure/timeout.
     */
    public void launchYouTube(CastTarget target, LaunchCallback callback) {
        String appUrl = target.getDialAppUrl();
        if (TextUtils.isEmpty(appUrl)) {
            callback.onLaunchResult(null);
            return;
        }

        Thread launcher = new Thread(() -> {
            String screenId = launchAndPollScreenId(appUrl);
            CastTarget result = screenId != null ? target.withScreenId(screenId) : null;
            if (!mStopped || result != null) {
                mMainHandler.post(() -> callback.onLaunchResult(result));
            }
        }, "CastDialLaunch");
        launcher.setDaemon(true);
        launcher.start();
    }

    @Nullable
    private String launchAndPollScreenId(String appUrl) {
        String launchUrl = joinUrl(appUrl, DIAL_APP_NAME);
        try {
            // DIAL launch: POST to the app resource. An empty body is the conservative baseline
            // (the protocol-spec task may add pairing params here later); Content-Type per spec.
            Response response = mHttpClient.newCall(new okhttp3.Request.Builder()
                    .url(launchUrl)
                    .post(okhttp3.RequestBody.create(new byte[0], null))
                    .header("Origin", ORIGIN)
                    .build()).execute();
            int code = response.code();
            response.close();
            // Any 2xx = launched (typically 201); anything else and polling is pointless.
            if (code < 200 || code >= 300) {
                Log.e(TAG, "DIAL launch rejected: HTTP " + code);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "DIAL launch failed: " + e);
            return null;
        }

        long deadline = System.currentTimeMillis() + LAUNCH_POLL_TOTAL_MS;
        while (!mStopped && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(LAUNCH_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                return null;
            }
            AppState state = queryAppState(appUrl);
            if (state != null && !TextUtils.isEmpty(state.screenId)) {
                return state.screenId;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    /**
     * First occurrence of {@code <tag>value</tag>}, namespace-prefix tolerant. Deliberately a
     * regex scan, not a full XML parse: DIAL device descriptions in the wild are small, flat and
     * frequently not-quite-valid XML, and keeping this in one place makes the protocol details
     * trivial to adjust when the parallel spec lands.
     */
    @Nullable
    static String extractTag(String xml, String tag) {
        if (TextUtils.isEmpty(xml)) {
            return null;
        }
        Matcher matcher = Pattern.compile(
                "<(?:[\\w-]+:)?" + Pattern.quote(tag) + "(?:\\s[^>]*)?>([^<]*)</(?:[\\w-]+:)?"
                        + Pattern.quote(tag) + ">",
                Pattern.CASE_INSENSITIVE).matcher(xml);
        if (matcher.find()) {
            String value = matcher.group(1);
            return value != null ? value.trim() : null;
        }
        return null;
    }

    private static String joinUrl(String base, String path) {
        return base.endsWith("/") ? base + path : base + "/" + path;
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                mMulticastLock = wifi.createMulticastLock("NewTubeCastDial");
                mMulticastLock.setReferenceCounted(false);
                mMulticastLock.acquire();
            }
        } catch (Exception e) {
            Log.e(TAG, "MulticastLock acquire failed: " + e);
        }
    }

    private void releaseMulticastLock() {
        try {
            if (mMulticastLock != null && mMulticastLock.isHeld()) {
                mMulticastLock.release();
            }
        } catch (Exception e) {
            // ignore
        }
        mMulticastLock = null;
    }
}
