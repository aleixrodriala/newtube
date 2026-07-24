package com.newtube.mobile.player;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NEWTUBE(debug-shaper): debug-build-only leaf {@link DataSource} wrapper providing runtime
 * bandwidth shaping and fault injection for on-device network experiments. It exists because
 * neither real knob is available to us: the Pixel 9 is the dev Mac's uplink (radio-level
 * manipulation is off-limits - a 35s GSM flip once wedged cellular DNS for ~12 min), and the
 * emulator's {@code emu network speed} throttle STALLS connections outright instead of shaping
 * them (zero bytes flow, the bandwidth estimator starves).
 *
 * <p>Both knobs are system properties, re-read on every {@code open()} (i.e. roughly per media
 * chunk), so experiments flip at runtime with no rebuild:
 *
 * <pre>
 *   adb shell setprop debug.arc.throttle_kbps 500   # shape reads to ~500 kbit/s (0/unset = off)
 *   adb shell setprop debug.arc.poison_itag 137     # itag=137 opens fail with a synthetic 403 ("" = off)
 *   adb shell setprop debug.arc.poison_once_itag any # fail one playback episode, then recovery can play
 *   adb shell setprop debug.arc.timeout_once_itag any # zero-byte read timeout, then clean recovery
 * </pre>
 *
 * <p>Wired by {@link Media3SourceFactory} around the leaf transport ONLY under
 * {@code BuildConfig.DEBUG} - release builds never construct this class. Sitting below the cache
 * tier means cache hits are never shaped (realistic: a slow network doesn't slow local reads) and
 * the bandwidth meter, attached inside the leaf, sees the paced transfer timing - so ABR reacts
 * exactly as it would to a genuinely slow network.
 */
@UnstableApi
final class DebugMediaShaper implements DataSource {

    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory mUpstream;

        Factory(DataSource.Factory upstream) {
            mUpstream = upstream;
        }

        @Override
        public DataSource createDataSource() {
            return new DebugMediaShaper(mUpstream.createDataSource());
        }
    }

    private static final String TAG = "NetPath"; // shares the experiment log tag on purpose

    private static final String PROP_THROTTLE_KBPS = "debug.arc.throttle_kbps";
    private static final String PROP_POISON_ITAG = "debug.arc.poison_itag";
    private static final String PROP_POISON_ONCE_ITAG = "debug.arc.poison_once_itag";
    private static final String PROP_TIMEOUT_ONCE_ITAG = "debug.arc.timeout_once_itag";
    private static final AtomicBoolean sPoisonOnceDisarmed = new AtomicBoolean();
    private static final AtomicBoolean sTimeoutOnceDisarmed = new AtomicBoolean();

    /** Max un-throttled burst: a quarter second at the configured rate. */
    private static final double BURST_SECONDS = 0.25;
    private static final int MAX_CHUNK_BYTES = 64 * 1024;
    private static final long SLEEP_MS = 20;

    private final DataSource mUpstream;

    // Token bucket (bytes); active only while mBytesPerSec > 0 for the current open().
    private long mBytesPerSec;
    private double mTokens;
    private long mLastRefillNs;
    private long mLastPropCheckNs;
    private boolean mTimeoutThisOpen;

    private DebugMediaShaper(DataSource upstream) {
        mUpstream = upstream;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        String itag = getItag(dataSpec.uri);
        String poisonItag = prop(PROP_POISON_ITAG);
        if (matchesItag(poisonItag, itag)) {
            throw synthetic403(dataSpec, PROP_POISON_ITAG, poisonItag);
        }

        String poisonOnceItag = prop(PROP_POISON_ONCE_ITAG);
        if (!sPoisonOnceDisarmed.get() && matchesItag(poisonOnceItag, itag)) {
            throw synthetic403(dataSpec, PROP_POISON_ONCE_ITAG, poisonOnceItag);
        }

        String timeoutOnceItag = prop(PROP_TIMEOUT_ONCE_ITAG);
        mTimeoutThisOpen = !sTimeoutOnceDisarmed.get()
                && matchesItag(timeoutOnceItag, itag);
        if (mTimeoutThisOpen) {
            android.util.Log.d(TAG, "shaper timeout property=" + PROP_TIMEOUT_ONCE_ITAG
                    + " itag=" + timeoutOnceItag
                    + " -> synthetic zero-byte SocketTimeoutException");
        }

        mBytesPerSec = propInt(PROP_THROTTLE_KBPS, 0) * 125L; // kbit/s -> bytes/s
        if (mBytesPerSec > 0) {
            mTokens = mBytesPerSec * BURST_SECONDS;
            mLastRefillNs = System.nanoTime();
            android.util.Log.d(TAG, "shaper throttle=" + (mBytesPerSec * 8 / 1000) + "kbps "
                    + dataSpec.uri.getLastPathSegment() + " itag=" + getItag(dataSpec.uri));
        }

        return mUpstream.open(dataSpec);
    }

    /**
     * Called when Media3 emits a terminal player error. A configured one-shot 403 remains armed
     * throughout the first playback episode; a configured read timeout terminates the zero-progress
     * initialization load. The automatic reload is deliberately clean so it can validate client/
     * transport rerouting in the same process.
     */
    static void disarmOneShotPoisonForRecovery() {
        String configured = prop(PROP_POISON_ONCE_ITAG);
        if (!configured.isEmpty() && !"none".equalsIgnoreCase(configured)
                && sPoisonOnceDisarmed.compareAndSet(false, true)) {
            android.util.Log.d(TAG, "shaper one-shot disarmed for recovery");
        }
        String timeoutConfigured = prop(PROP_TIMEOUT_ONCE_ITAG);
        if (!timeoutConfigured.isEmpty() && !"none".equalsIgnoreCase(timeoutConfigured)
                && sTimeoutOnceDisarmed.compareAndSet(false, true)) {
            android.util.Log.d(TAG, "shaper one-shot timeout disarmed for recovery");
        }
    }

    private static boolean matchesItag(String configured, @Nullable String actual) {
        return !configured.isEmpty() && !"none".equalsIgnoreCase(configured)
                && ("any".equalsIgnoreCase(configured) || configured.equals(actual));
    }

    private static IOException synthetic403(DataSpec dataSpec, String property, String itag) {
        android.util.Log.d(TAG, "shaper poison property=" + property + " itag=" + itag
                + " -> synthetic 403");
        return new HttpDataSource.InvalidResponseCodeException(
                403,
                "poisoned by " + property,
                /* cause= */ null,
                Collections.<String, List<String>>emptyMap(),
                dataSpec,
                new byte[0]);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (mTimeoutThisOpen) {
            mTimeoutThisOpen = false;
            throw new SocketTimeoutException("synthetic startup read timeout");
        }

        // A starved chunk can take tens of seconds to read, so the throttle prop is re-read
        // mid-transfer (every 500ms) - otherwise a runtime flip only lands on the NEXT chunk
        // open and the experiment timeline smears.
        long now = System.nanoTime();
        if (now - mLastPropCheckNs > 500_000_000L) {
            mLastPropCheckNs = now;
            long bytesPerSec = propInt(PROP_THROTTLE_KBPS, 0) * 125L;
            if (bytesPerSec != mBytesPerSec) {
                android.util.Log.d(TAG, "shaper rate change "
                        + (mBytesPerSec * 8 / 1000) + " -> " + (bytesPerSec * 8 / 1000) + "kbps");
                mBytesPerSec = bytesPerSec;
                mTokens = Math.min(mTokens, bytesPerSec * BURST_SECONDS);
                mLastRefillNs = now;
            }
        }

        if (mBytesPerSec <= 0) {
            return mUpstream.read(buffer, offset, length);
        }

        refill();
        while (mTokens < 1) {
            try {
                Thread.sleep(SLEEP_MS);
            } catch (InterruptedException e) {
                // Loader cancellation path: preserve the flag and bail out like a blocked
                // network read would.
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("shaper interrupted");
            }
            refill();
        }

        int allowed = (int) Math.min(Math.min(length, MAX_CHUNK_BYTES), mTokens);
        int read = mUpstream.read(buffer, offset, allowed);
        if (read > 0) {
            mTokens -= read;
        }
        return read;
    }

    private void refill() {
        long now = System.nanoTime();
        mTokens = Math.min(
                mBytesPerSec * BURST_SECONDS,
                mTokens + (now - mLastRefillNs) / 1e9 * mBytesPerSec);
        mLastRefillNs = now;
    }

    @Nullable
    private static String getItag(@Nullable Uri uri) {
        if (uri == null) {
            return null;
        }
        try {
            return uri.getQueryParameter("itag");
        } catch (UnsupportedOperationException e) { // opaque uri (e.g. data:)
            return null;
        }
    }

    @Override
    @Nullable
    public Uri getUri() {
        return mUpstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return mUpstream.getResponseHeaders();
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        mUpstream.addTransferListener(transferListener);
    }

    @Override
    public void close() throws IOException {
        mBytesPerSec = 0;
        mUpstream.close();
    }

    // ------------------------------------------------------------------------------------------
    // debug.* system-property access. android.os.SystemProperties is hidden API; reflection is
    // at home in this codebase (Helpers.setField et al) and this class only exists in debug
    // builds. Failures degrade to "knob off".
    // ------------------------------------------------------------------------------------------

    static String prop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String value = (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, key, "");
            return value == null ? "" : value.trim();
        } catch (Exception e) {
            return "";
        }
    }

    static int propInt(String key, int defaultValue) {
        String value = prop(key);
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
