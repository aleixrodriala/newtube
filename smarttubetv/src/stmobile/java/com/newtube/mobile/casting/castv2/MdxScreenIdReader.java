package com.newtube.mobile.casting.castv2;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.mylogger.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The "mdx shim" (CASTING.md step 3): pull a YouTube Lounge {@code screenId} out of a Google Cast
 * device so Route B can drive its YouTube app - Chromecast dongles dropped DIAL, so this Cast v2
 * detour is the only way to discover their screenId.
 *
 * <p>Flow (as in the ytcast Go project and pychromecast's YouTubeController; manually verified
 * against a real device on this LAN): open a {@link CastV2Channel}, GET_STATUS / LAUNCH the
 * YouTube receiver app ({@link #YOUTUBE_APP_ID}), connect to its transportId, then ask
 * {@code {"type":"getMdxSessionStatus"}} on the mdx namespace; the receiver answers
 * {@code {"type":"mdxSessionStatus","data":{"screenId":"..."}}}. The screenId then goes through
 * the normal Lounge pairing path (existing Route B stack) - nothing plays over Cast.</p>
 *
 * <p><b>Deliberately NO receiver STOP on teardown:</b> the YouTube app must stay open on the TV so
 * the Lounge session can attach to it; only the virtual connections are closed. (Contrast
 * {@link CastV2Session#close()}, which does stop its app.)</p>
 */
public final class MdxScreenIdReader {

    /** Result callback. Fires exactly once, on an internal thread - post to main for UI. */
    public interface Callback {
        void onScreenId(String screenId);

        void onError(String reason);
    }

    private static final String TAG = MdxScreenIdReader.class.getSimpleName();

    /** The YouTube Cast receiver app. */
    static final String YOUTUBE_APP_ID = "233637DE";
    static final String NS_MDX = "urn:x-cast:com.google.youtube.mdx";

    /** A cold YouTube-app start needs a few asks before mdx answers; re-ask until timeout. */
    private static final long STATUS_RETRY_INTERVAL_MS = 2_000;

    private final long mTimeoutMs;
    private final Callback mCallback;
    private final CastV2Channel mChannel;
    private final AtomicBoolean mDone = new AtomicBoolean();
    private final AtomicBoolean mLaunchSent = new AtomicBoolean();
    private final AtomicBoolean mTransportConnected = new AtomicBoolean();
    private final AtomicInteger mRequestId = new AtomicInteger(1);
    private final ScheduledExecutorService mScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "MdxScreenIdReader");
        thread.setDaemon(true);
        return thread;
    });

    @Nullable
    private volatile String mTransportId;

    private MdxScreenIdReader(String host, int port, long timeoutMs, Callback callback) {
        mTimeoutMs = timeoutMs;
        mCallback = callback;
        mChannel = new CastV2Channel(host, port, new ChannelListener());
    }

    /**
     * Read the Lounge screenId of the Cast device at {@code host:port} (Cast port is 8009).
     * Launches the TV's YouTube app if it isn't already running and leaves it running.
     * The callback fires exactly once, within {@code timeoutMs}, on an internal thread.
     */
    public static void readScreenId(String host, int port, long timeoutMs, Callback callback) {
        new MdxScreenIdReader(host, port, timeoutMs, callback).begin();
    }

    private void begin() {
        mChannel.open();
        mScheduler.schedule(() -> finishError("Timed out reading screenId"), mTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private class ChannelListener implements CastV2Channel.Listener {
        @Override
        public void onOpened() {
            mChannel.connect(CastV2Channel.RECEIVER_ID);
            // Status first: if the YouTube app is already running (user is watching), a LAUNCH
            // would reload it and interrupt playback - reuse the running instance instead.
            sendReceiver("{\"type\":\"GET_STATUS\",\"requestId\":" + mRequestId.getAndIncrement() + "}");
        }

        @Override
        public void onMessage(CastMessage message) {
            String payloadUtf8 = message.getPayloadUtf8();
            if (payloadUtf8 == null) {
                return;
            }
            JSONObject payload;
            try {
                payload = new JSONObject(payloadUtf8);
            } catch (JSONException e) {
                return;
            }
            String type = payload.optString("type");
            if (CastV2Session.NS_RECEIVER.equals(message.getNamespace())) {
                if ("RECEIVER_STATUS".equals(type)) {
                    handleReceiverStatus(payload);
                } else if ("LAUNCH_ERROR".equals(type)) {
                    finishError("YouTube app launch failed: " + payload.optString("reason", "unknown"));
                }
            } else if (NS_MDX.equals(message.getNamespace()) && "mdxSessionStatus".equals(type)) {
                String screenId = parseScreenId(payload);
                if (screenId != null) {
                    finishSuccess(screenId);
                }
            }
        }

        @Override
        public void onError(String reason) {
            finishError(reason);
        }

        @Override
        public void onClosed() {
            // Normal when we closed after success; anything earlier already reported an error.
        }
    }

    private void handleReceiverStatus(JSONObject payload) {
        CastV2Session.AppSession app = CastV2Session.findApp(payload, YOUTUBE_APP_ID);
        if (app == null) {
            // Not running yet: launch it (once). Later RECEIVER_STATUS broadcasts carry the ids.
            if (mLaunchSent.compareAndSet(false, true)) {
                sendReceiver("{\"type\":\"LAUNCH\",\"appId\":\"" + YOUTUBE_APP_ID + "\",\"requestId\":"
                        + mRequestId.getAndIncrement() + "}");
            }
            return;
        }
        mTransportId = app.transportId;
        if (mTransportConnected.compareAndSet(false, true)) {
            mChannel.connect(app.transportId);
            // Ask immediately, then keep re-asking: a just-launched app takes a while before its
            // mdx channel answers, and the requests are idempotent.
            askMdxStatus();
            mScheduler.scheduleWithFixedDelay(this::askMdxStatus,
                    STATUS_RETRY_INTERVAL_MS, STATUS_RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void askMdxStatus() {
        String transportId = mTransportId;
        if (mDone.get() || transportId == null) {
            return;
        }
        mChannel.send(CastV2Channel.SENDER_ID, transportId, NS_MDX, "{\"type\":\"getMdxSessionStatus\"}");
    }

    private void sendReceiver(String jsonPayload) {
        mChannel.send(CastV2Channel.SENDER_ID, CastV2Channel.RECEIVER_ID, CastV2Session.NS_RECEIVER, jsonPayload);
    }

    // ---------------------------------------------------------------------------------
    // Completion (exactly once)
    // ---------------------------------------------------------------------------------

    private void finishSuccess(String screenId) {
        if (!mDone.compareAndSet(false, true)) {
            return;
        }
        Log.d(TAG, "Got screenId from mdx");
        cleanup();
        dispatch(() -> mCallback.onScreenId(screenId));
    }

    private void finishError(String reason) {
        if (!mDone.compareAndSet(false, true)) {
            return;
        }
        Log.e(TAG, "screenId read failed: " + reason);
        cleanup();
        dispatch(() -> mCallback.onError(reason));
    }

    /** Close the channel (virtual-connection CLOSEs only - never a receiver STOP, see class javadoc). */
    private void cleanup() {
        mChannel.close();
        mScheduler.shutdown();
    }

    private void dispatch(Runnable callback) {
        try {
            callback.run();
        } catch (Exception e) {
            Log.e(TAG, "Callback failed: " + e);
        }
    }

    // ---------------------------------------------------------------------------------
    // Parsing (static for unit tests)
    // ---------------------------------------------------------------------------------

    /**
     * Extract the screenId from an {@code mdxSessionStatus} payload:
     * {@code {"type":"mdxSessionStatus","data":{"screenId":"...", ...}}}. Null when absent/empty.
     */
    @Nullable
    static String parseScreenId(JSONObject payload) {
        if (!"mdxSessionStatus".equals(payload.optString("type"))) {
            return null;
        }
        JSONObject data = payload.optJSONObject("data");
        if (data == null) {
            return null;
        }
        String screenId = data.optString("screenId");
        return screenId.isEmpty() ? null : screenId;
    }
}
