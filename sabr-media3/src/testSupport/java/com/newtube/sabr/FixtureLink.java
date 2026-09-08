package com.newtube.sabr;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-only response link: per-request latency and ONE shared downlink budget for audio/video.
 * Both protocols use identical 8 KiB receive blocks, so byte-at-a-time UMP control reads do not
 * incur a scheduler sleep each. No sockets, DNS, TLS, HTTP headers, packet loss or server ABR are
 * modeled. POST bodies are counted but not throttled; response bodies consume the shared budget.
 */
public final class FixtureLink {
    public final AtomicLong responseBytes = new AtomicLong();
    public final AtomicLong requestBodyBytes = new AtomicLong();
    public final AtomicInteger requests = new AtomicInteger();
    private final long latencyNs;
    private final long bytesPerSecond;
    private long nextByteNs;
    private volatile long pausedUntilNs;

    public FixtureLink(long latencyMs, long rateKbps) {
        if (latencyMs < 0 || latencyMs > 2000 || rateKbps < 1 || rateKbps > 1_000_000) {
            throw new IllegalArgumentException("Invalid fixture link");
        }
        latencyNs = latencyMs * 1_000_000;
        bytesPerSecond = rateKbps * 125;
    }

    // Package-private deterministic clock seam for tests of the aggregate budget.
    synchronized long reserve(int count, long nowNs) {
        nextByteNs = Math.max(Math.max(nowNs, nextByteNs), pausedUntilNs)
                + (count * 1_000_000_000L + bytesPerSecond - 1) / bytesPerSecond;
        return nextByteNs;
    }

    public void pauseFor(long durationMs) {
        if (durationMs < 0 || durationMs > 10000) throw new IllegalArgumentException("Bounded fixture stall");
        pausedUntilNs = System.nanoTime() + durationMs * 1_000_000;
    }

    public DataSource.Factory wrap(DataSource.Factory upstream) {
        return () -> new PacedSource(upstream.createDataSource());
    }

    private final class PacedSource extends BaseDataSource {
        private final DataSource upstream;
        private final byte[] received = new byte[8192];
        private int position, limit;
        private volatile boolean closed;
        private boolean started;

        PacedSource(DataSource upstream) { super(true); this.upstream = upstream; }

        @Override public long open(DataSpec spec) throws IOException {
            closed = false;
            position = limit = 0;
            transferInitializing(spec);
            requests.incrementAndGet();
            if (spec.httpBody != null) requestBodyBytes.addAndGet(spec.httpBody.length);
            waitFor(System.nanoTime() + latencyNs);
            long length = upstream.open(spec);
            started = true;
            transferStarted(spec);
            return length;
        }

        @Override public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (closed) throw new InterruptedIOException("Fixture link closed");
            if (position == limit) {
                int count = upstream.read(received, 0, received.length);
                if (count == C.RESULT_END_OF_INPUT) return count;
                if (count == 0) return 0;
                waitFor(reserve(count, System.nanoTime()));
                responseBytes.addAndGet(count);
                bytesTransferred(count);
                position = 0;
                limit = count;
            }
            int count = Math.min(length, limit - position);
            System.arraycopy(received, position, target, offset, count);
            position += count;
            return count;
        }

        private void waitFor(long deadlineNs) throws InterruptedIOException {
            while (true) {
                if (closed || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Fixture link canceled");
                }
                long remaining = Math.max(deadlineNs, pausedUntilNs) - System.nanoTime();
                if (remaining <= 0) return;
                long sleep = Math.min(remaining, 10_000_000);
                try { Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000)); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Fixture link interrupted");
                }
            }
        }

        @Override public synchronized void close() throws IOException {
            closed = true;
            try { upstream.close(); }
            finally {
                if (started) { started = false; transferEnded(); }
            }
        }
        @Override public Uri getUri() { return upstream.getUri(); }
        @Override public Map<String, List<String>> getResponseHeaders() { return upstream.getResponseHeaders(); }
    }
}
