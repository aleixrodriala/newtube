package com.newtube.mobile.player;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;

import com.liskovsoft.smartyoutubetv2.common.misc.MediaStartupTimeoutException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * NEWTUBE(startup-failover): leaf media {@link DataSource} that bounds how long the FIRST request
 * of a source waits for response headers, and fails over to a different transport inside that
 * same open instead of surfacing a player error.
 *
 * <p>One instance exists per media3 chunk source (per track per source build), so "the first
 * network open of this instance" is the startup request - the DASH init/index range on a fresh
 * source. Only googlevideo {@code /videoplayback} GETs are treated this way.</p>
 *
 * <pre>
 *   primary (Cronet, budget from StartupDeadlinePolicy)  --no headers in budgetMs-->
 *   fallback (OkHttp, same DataSpec, bounded so the total never exceeds the old 8 s wait)
 *     answered  -> playback continues, no player error, this source stays on the fallback
 *     silent    -> MediaStartupTimeoutException (a SocketTimeoutException) surfaces through the
 *                  existing startup-init-timeout source failover; ErrorFixerController reads the
 *                  marker as a transport verdict (fresh URLs, no /player client circuit-break)
 * </pre>
 *
 * <p>On links without evidence of being fast the policy returns the long budget, which is exactly
 * the previous behavior (the Cronet connection timeout), only with the transport verdict marked.
 * When there is no alternate path (Cronet unavailable or bypassed) there is no early failover.</p>
 */
@UnstableApi
final class StartupFailoverDataSource implements DataSource {

    /** A path whose pending {@code open()} another thread can abort (it then throws). */
    interface AbortableLeg {
        DataSource source();

        /** Thread-safe. After this, the pending and any later open of this leg fail. */
        void abort();
    }

    /** Transport construction, supplied by {@link Media3SourceFactory} (fakes in tests). */
    interface Transports {
        /** A new primary DataSource that gives up after {@code headersBudgetMs} without headers. */
        DataSource primary(int headersBudgetMs);

        /** Whether {@link #fallback()} offers a different path at all. */
        boolean canFailOver();

        /** A new leg on a different path for the same URL, or null when there is none. */
        @Nullable
        AbortableLeg fallback();
    }

    /** Evidence, episode bookkeeping and NetPath logging; see Media3SourceFactory. */
    interface Callbacks {
        StartupDeadlinePolicy.Decision decide(DataSpec dataSpec);

        void onFirstByte(long firstByteMs);

        void onEarlyTimeout(DataSpec dataSpec, StartupDeadlinePolicy.Decision decision,
                long waitedMs, IOException cause);

        void onFallbackResult(DataSpec dataSpec, StartupDeadlinePolicy.Decision decision,
                boolean answered, boolean deadlineHit, long legMs, long totalMs,
                @Nullable IOException cause);

        /**
         * The primary transport got no headers for a request the fallback then got an HTTP answer
         * for (served it, or any status): the only evidence against the primary transport itself
         * (as opposed to the host). Never called when the fallback was silent too - that is a dead
         * host. {@code decision.networkKey} is the network the primary stalled on; a fault is only
         * pinned if nothing changed since. {@code primaryFailure} is how the primary gave up (for
         * Cronet it carries the phase it was stuck in).
         */
        void onPrimaryTransportFault(DataSpec dataSpec, StartupDeadlinePolicy.Decision decision,
                IOException primaryFailure);
    }

    /** The fallback leg always gets at least this long, even if the primary overran its budget. */
    static final long MIN_FALLBACK_LEG_MS = 2_000;

    private static final int LEG_PENDING = 0;
    private static final int LEG_DONE = 1;
    private static final int LEG_EXPIRED = 2;

    private final Transports mTransports;
    private final Callbacks mCallbacks;
    private final ScheduledExecutorService mScheduler;
    private final LongSupplier mClock;
    private final int mLongBudgetMs;
    /**
     * Whether this source's primary transport is the NORMAL one for this device (Cronet, or
     * OkHttp when Cronet does not exist). A bypassed-to-OkHttp primary and the failover leg
     * measure a degraded host/path, not the link: their first bytes must not steer later budgets.
     */
    private final boolean mRecordsLinkEvidence;
    private final List<TransferListener> mListeners = new ArrayList<>();

    @Nullable private DataSource mPrimary;          // long-budget primary, reused after startup
    @Nullable private DataSource mStickyFallback;   // fallback that answered; reused afterwards
    private boolean mUseFallback;                   // this source failed over once: stay there
    private boolean mStartupPending = true;
    @Nullable private DataSource mCurrent;

    StartupFailoverDataSource(Transports transports, Callbacks callbacks,
            ScheduledExecutorService scheduler, LongSupplier clock, int longBudgetMs,
            boolean recordsLinkEvidence) {
        mTransports = transports;
        mCallbacks = callbacks;
        mScheduler = scheduler;
        mClock = clock;
        mLongBudgetMs = longBudgetMs;
        mRecordsLinkEvidence = recordsLinkEvidence;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        mListeners.add(transferListener);
        if (mPrimary != null) mPrimary.addTransferListener(transferListener);
        if (mStickyFallback != null) mStickyFallback.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        boolean startup = mStartupPending && isGoogleVideoMedia(dataSpec);
        if (!startup) {
            mCurrent = mUseFallback ? stickyFallback() : primary();
            return mCurrent.open(dataSpec);
        }

        if (mUseFallback) {
            // A retry after this source already failed over: patient, but still classified.
            return openStartup(stickyFallback(), dataSpec, null, /* everyTransportTried= */ true,
                    /* linkEvidence= */ false);
        }
        if (!mTransports.canFailOver()) {
            // Nowhere to fail over to: keep the transport's own timeouts, classify the verdict.
            return openStartup(primary(), dataSpec, null, false, mRecordsLinkEvidence);
        }

        StartupDeadlinePolicy.Decision decision = mCallbacks.decide(dataSpec);
        if (!decision.early) {
            return openStartup(primary(), dataSpec, null, false, mRecordsLinkEvidence);
        }
        return openStartup(withListeners(mTransports.primary(decision.budgetMs)), dataSpec, decision,
                false, mRecordsLinkEvidence);
    }

    private long openStartup(DataSource source, DataSpec dataSpec,
            @Nullable StartupDeadlinePolicy.Decision earlyDecision, boolean everyTransportTried,
            boolean linkEvidence) throws IOException {
        mCurrent = source;
        long startMs = mClock.getAsLong();
        try {
            long length = source.open(dataSpec);
            mStartupPending = false;
            if (linkEvidence) {
                mCallbacks.onFirstByte(mClock.getAsLong() - startMs);
            }
            return length;
        } catch (IOException e) {
            long waitedMs = mClock.getAsLong() - startMs;
            if (!isNoResponseTimeout(e)) {
                throw e;
            }
            if (earlyDecision == null) {
                throw startupTimeout(dataSpec, waitedMs, e, everyTransportTried);
            }
            // Budget spent without a single header: abandon this transport's request (close
            // cancels a pending Cronet UrlRequest) and try the other path for the same bytes.
            closeQuietly(source);
            mCallbacks.onEarlyTimeout(dataSpec, earlyDecision, waitedMs, e);
            AbortableLeg leg = mTransports.fallback();
            if (leg == null) {
                throw startupTimeout(dataSpec, waitedMs, e, false);
            }
            mUseFallback = true;
            return openFallbackLeg(leg, dataSpec, earlyDecision, startMs, waitedMs, e);
        }
    }

    private long openFallbackLeg(AbortableLeg leg, DataSpec dataSpec,
            StartupDeadlinePolicy.Decision decision, long startMs, long waitedMs,
            IOException primaryFailure) throws IOException {
        DataSource source = withListeners(leg.source());
        mCurrent = source;
        long legBudgetMs = Math.max(MIN_FALLBACK_LEG_MS, mLongBudgetMs - waitedMs);
        AtomicInteger state = new AtomicInteger(LEG_PENDING);
        ScheduledFuture<?> deadline = mScheduler.schedule(() -> {
            if (state.compareAndSet(LEG_PENDING, LEG_EXPIRED)) {
                leg.abort();
            }
        }, legBudgetMs, TimeUnit.MILLISECONDS);
        long legStartMs = mClock.getAsLong();
        long length;
        try {
            length = source.open(dataSpec);
        } catch (IOException e) {
            boolean expired = !state.compareAndSet(LEG_PENDING, LEG_DONE);
            deadline.cancel(false);
            long legMs = mClock.getAsLong() - legStartMs;
            long totalMs = mClock.getAsLong() - startMs;
            boolean noResponse = expired || isNoResponseTimeout(e);
            mCallbacks.onFallbackResult(dataSpec, decision, false, expired, legMs, totalMs, e);
            // An aborted leg cancels every call it makes from now on; a retry needs a fresh one.
            mStickyFallback = null;
            if (!noResponse && responseCode(e) > 0) {
                // NEWTUBE(media-path): the fallback got an HTTP answer where the primary got no
                // headers at all - the same evidence against the primary transport as a 200.
                // Measured (Pixel 9, LTE): a signed-in head's 403 reached OkHttp after Cronet's
                // TLS stall, no fault was recorded, and the recovery reload paid the stall again.
                // The error itself still surfaces unchanged (a 403 is fatal for the source).
                mCallbacks.onPrimaryTransportFault(dataSpec, decision, primaryFailure);
            }
            throw noResponse ? startupTimeout(dataSpec, totalMs, e, true) : e;
        }
        if (!state.compareAndSet(LEG_PENDING, LEG_DONE)) {
            // The deadline won the race with the headers: the call is being cancelled under us.
            closeQuietly(source);
            long totalMs = mClock.getAsLong() - startMs;
            mCallbacks.onFallbackResult(dataSpec, decision, false, true,
                    mClock.getAsLong() - legStartMs, totalMs, null);
            mStickyFallback = null;
            throw startupTimeout(dataSpec, totalMs, null, true);
        }
        deadline.cancel(false);
        long legMs = mClock.getAsLong() - legStartMs;
        mStickyFallback = source;
        mStartupPending = false;
        // Deliberately NOT a first-byte sample: this request already stalled on the primary, so
        // its timing describes that host/path (e.g. an IPv6 handshake stall), not the link.
        mCallbacks.onFallbackResult(dataSpec, decision, true, false, legMs,
                mClock.getAsLong() - startMs, null);
        mCallbacks.onPrimaryTransportFault(dataSpec, decision, primaryFailure);
        return length;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        DataSource current = mCurrent;
        if (current == null) {
            throw new IOException("read on a closed startup-failover source");
        }
        return current.read(buffer, offset, length);
    }

    private DataSource primary() {
        if (mPrimary == null) {
            mPrimary = withListeners(mTransports.primary(mLongBudgetMs));
        }
        return mPrimary;
    }

    private DataSource stickyFallback() throws IOException {
        if (mStickyFallback == null) {
            AbortableLeg leg = mTransports.fallback();
            if (leg == null) {
                // Cannot happen (mUseFallback is only set with a leg); stay on the primary.
                return primary();
            }
            mStickyFallback = withListeners(leg.source());
        }
        return mStickyFallback;
    }

    private DataSource withListeners(DataSource source) {
        for (TransferListener listener : mListeners) {
            source.addTransferListener(listener);
        }
        return source;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return mCurrent != null ? mCurrent.getUri() : null;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return mCurrent != null ? mCurrent.getResponseHeaders() : Collections.emptyMap();
    }

    @Override
    public void close() throws IOException {
        DataSource current = mCurrent;
        mCurrent = null;
        if (current != null) {
            current.close();
        }
    }

    private static void closeQuietly(DataSource source) {
        try {
            source.close();
        } catch (IOException | RuntimeException ignored) {
            // Best effort: the request is being abandoned either way.
        }
    }

    /**
     * Byte-range addressed googlevideo media (VOD). Sequence-addressed segments (live, OTF,
     * post-live) are left out: googlevideo may legitimately hold a live-edge request open until
     * the segment exists, so silence there is not evidence of a dead host - they keep exactly
     * the previous behavior.
     */
    static boolean isGoogleVideoMedia(DataSpec dataSpec) {
        if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET) {
            return false;
        }
        Uri uri = dataSpec.uri;
        String host = uri.getHost();
        String path = uri.getPath();
        if (host == null || !host.endsWith(".googlevideo.com")
                || path == null || !path.startsWith("/videoplayback") || path.contains("/sq/")) {
            return false;
        }
        try {
            return uri.getQueryParameter("sq") == null
                    && !"yt_live_broadcast".equals(uri.getQueryParameter("source"));
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }

    /**
     * True when the request produced no HTTP answer at all before timing out: a socket/connection
     * timeout (Cronet's own connection timeout surfaces as a bare SocketTimeoutException) or a
     * Cronet timed-out error, with no HTTP status anywhere in the chain.
     */
    static boolean isNoResponseTimeout(Throwable error) {
        boolean timeout = false;
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 12; cause = cause.getCause(), depth++) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                return false;
            }
            if (cause instanceof SocketTimeoutException) {
                timeout = true;
            }
            String message = cause.getMessage();
            if (message != null && (message.contains("ERR_TIMED_OUT")
                    || message.contains("ERR_CONNECTION_TIMED_OUT"))) {
                timeout = true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return timeout;
    }

    /** The HTTP status somewhere in {@code error}'s chain, or -1 when no response arrived. */
    static int responseCode(@Nullable Throwable error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 12; cause = cause.getCause(), depth++) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                return ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return -1;
    }

    /**
     * @param everyTransportTried the fallback path was silent too: a dead host, which must not
     *                            be blamed on (and switch off) the primary transport.
     */
    static HttpDataSource.HttpDataSourceException startupTimeout(DataSpec dataSpec, long waitedMs,
            @Nullable IOException cause, boolean everyTransportTried) {
        return new HttpDataSource.HttpDataSourceException(
                new MediaStartupTimeoutException(
                        "media host sent no response headers in " + waitedMs + " ms", cause,
                        everyTransportTried),
                dataSpec,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN);
    }
}
