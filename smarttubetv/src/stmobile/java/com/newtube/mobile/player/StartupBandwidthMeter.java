package com.newtube.mobile.player;

import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.util.Clock;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.upstream.BandwidthMeter;

import java.util.function.IntToLongFunction;

/**
 * Conservative only until real transfers establish the link. Stock Media3 remains the steady-state
 * estimator; a completed, substantial early sample avoids waiting for its 512 KiB / 2 s bootstrap
 * before increasing quality. Fixed/manual track selections do not consult this estimate.
 */
final class StartupBandwidthMeter implements BandwidthMeter, TransferListener {
    static final long DEFAULT_BOOTSTRAP_BPS = 1_000_000;
    static final long MAX_RECENT_SEED_BPS = 4_000_000;
    static final long SEED_MAX_AGE_MS = 5 * 60_000;
    static final long MIN_RATE_BPS = 100_000;
    static final long MAX_RATE_BPS = 50_000_000;
    private static final long SAMPLE_MAX_IDLE_MS = 30_000;
    private static final long MIN_EARLY_SAMPLE_BYTES = 32 * 1024;
    private static final int MIN_EARLY_SAMPLE_MS = 50;
    // These are the stock DefaultBandwidthMeter 1.10.1 confidence thresholds.
    private static final long STOCK_SAMPLE_BYTES = 512 * 1024;
    private static final long STOCK_SAMPLE_MS = 2_000;

    interface SampleSink {
        void onEstimate(int networkType, long bitrate);
    }

    private final BandwidthMeter delegate;
    private final TransferListener transferListener;
    private final IntToLongFunction seed;
    private final SampleSink sink;
    private final Clock clock;
    private final boolean enabled;
    private int networkType;
    private long totalBytes;
    private long totalElapsedMs;
    private long earlyEstimate;
    private long lastSampleMs = -1;
    private boolean stockReady;

    StartupBandwidthMeter(BandwidthMeter delegate, IntToLongFunction seed, SampleSink sink,
            int networkType, boolean enabled, Clock clock) {
        this.delegate = delegate;
        this.transferListener = delegate.getTransferListener();
        this.seed = seed;
        this.sink = sink;
        this.networkType = networkType;
        this.enabled = enabled;
        this.clock = clock;
        delegate.addEventListener(new Handler(Looper.getMainLooper()), this::onSample);
    }

    static long seedFor(long savedBitrate, long savedAtMs, long nowMs) {
        if (savedBitrate < MIN_RATE_BPS || savedBitrate > MAX_RATE_BPS || savedAtMs <= 0
                || nowMs < savedAtMs || nowMs - savedAtMs > SEED_MAX_AGE_MS) {
            return DEFAULT_BOOTSTRAP_BPS;
        }
        return Math.min(savedBitrate, MAX_RECENT_SEED_BPS);
    }

    synchronized void onNetworkTypeChanged(int newType) {
        if (newType != networkType) {
            networkType = newType;
            clearSample();
        }
    }

    synchronized void onSample(int elapsedMs, long bytes, long bitrate) {
        if (!enabled) return;
        // Stock emits a zero-data event when it resets for a different network.
        if (elapsedMs == 0 && bytes == 0) {
            clearSample();
            return;
        }
        if (elapsedMs <= 0 || bytes <= 0) return;
        long now = clock.elapsedRealtime();
        if (lastSampleMs >= 0 && now - lastSampleMs > SAMPLE_MAX_IDLE_MS) clearSample();
        totalElapsedMs += elapsedMs;
        totalBytes += bytes;
        lastSampleMs = now;
        if (bytes >= MIN_EARLY_SAMPLE_BYTES && elapsedMs >= MIN_EARLY_SAMPLE_MS) {
            earlyEstimate = clamp((long) (bytes * 8000d / elapsedMs));
        }
        stockReady = totalBytes >= MIN_EARLY_SAMPLE_BYTES
                && (totalBytes >= STOCK_SAMPLE_BYTES || totalElapsedMs >= STOCK_SAMPLE_MS);
        // Never save an initial seed as if it were a measurement, nor trust tiny/error bodies.
        if (stockReady && totalBytes >= MIN_EARLY_SAMPLE_BYTES) {
            sink.onEstimate(networkType, clamp(bitrate));
        }
    }

    @Override public synchronized long getBitrateEstimate() {
        if (!enabled) return delegate.getBitrateEstimate();
        if (lastSampleMs >= 0 && clock.elapsedRealtime() - lastSampleMs > SAMPLE_MAX_IDLE_MS) {
            clearSample();
        }
        if (stockReady) return delegate.getBitrateEstimate();
        return earlyEstimate > 0 ? earlyEstimate : seed.applyAsLong(networkType);
    }

    private void clearSample() {
        totalBytes = totalElapsedMs = earlyEstimate = 0;
        lastSampleMs = -1;
        stockReady = false;
    }

    private static long clamp(long bitrate) {
        return Math.max(MIN_RATE_BPS, Math.min(MAX_RATE_BPS, bitrate));
    }

    @Override public long getTimeToFirstByteEstimateUs() {
        return delegate.getTimeToFirstByteEstimateUs();
    }
    @Override public TransferListener getTransferListener() { return this; }
    @Override public void addEventListener(Handler handler, EventListener listener) {
        delegate.addEventListener(handler, listener);
    }
    @Override public void removeEventListener(EventListener listener) {
        delegate.removeEventListener(listener);
    }
    @Override public void onTransferInitializing(DataSource source, DataSpec spec, boolean network) {
        if (transferListener != null) transferListener.onTransferInitializing(source, spec, network);
    }
    @Override public void onTransferStart(DataSource source, DataSpec spec, boolean network) {
        if (transferListener != null) transferListener.onTransferStart(source, spec, network);
    }
    @Override public void onBytesTransferred(DataSource source, DataSpec spec, boolean network, int bytes) {
        if (transferListener != null) transferListener.onBytesTransferred(source, spec, network, bytes);
    }
    @Override public void onTransferEnd(DataSource source, DataSpec spec, boolean network) {
        if (transferListener != null) transferListener.onTransferEnd(source, spec, network);
    }
}
