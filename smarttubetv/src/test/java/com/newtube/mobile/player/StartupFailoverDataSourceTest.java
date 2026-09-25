package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cronet.CronetDataSource;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

import com.liskovsoft.smartyoutubetv2.common.misc.MediaStartupTimeoutException;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Startup budget + in-open transport failover, replayed with fake legs and a local tarpit. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class StartupFailoverDataSourceTest {
    private static final int LONG_MS = StartupDeadlinePolicy.LONG_BUDGET_MS;
    private static final DataSpec INIT = new DataSpec.Builder()
            .setUri("https://rr8---sn-uxax4vopj5xn-cjoe.googlevideo.com/videoplayback?itag=251&sig=x")
            .setPosition(0).setLength(741).build();
    private static final DataSpec MEDIA = INIT.buildUpon().setPosition(741).setLength(1_000).build();

    private final AtomicLong mClock = new AtomicLong(1_000);
    private final List<Long> mScheduledDelays = new CopyOnWriteArrayList<>();
    /** Real scheduler, 100x faster than requested, recording the requested leg budgets. */
    private final ScheduledThreadPoolExecutor mScheduler = new ScheduledThreadPoolExecutor(1) {
        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            mScheduledDelays.add(unit.toMillis(delay));
            return super.schedule(command, unit.toMillis(delay) / 100, TimeUnit.MILLISECONDS);
        }
    };

    @After
    public void tearDown() {
        mScheduler.shutdownNow();
    }

    @Test
    public void answeringPrimaryNeverTouchesTheFallback() throws IOException {
        FakeTransports transports = new FakeTransports();
        transports.primaryBehavior = Behavior.ANSWER;
        RecordingCallbacks callbacks = new RecordingCallbacks(early(3_100));
        StartupFailoverDataSource source = newSource(transports, callbacks);

        assertEquals(741, source.open(INIT));
        source.close();
        // Later requests of this source run on the patient primary, not the startup one.
        assertEquals(1_000, source.open(MEDIA));

        assertEquals(Arrays.asList(3_100, LONG_MS), transports.primaryBudgets);
        assertEquals(0, transports.fallbacksCreated);
        assertEquals(1, callbacks.decisions);
        assertEquals(Collections.singletonList(40L), callbacks.firstBytes);
    }

    @Test
    public void silentPrimaryFailsOverInsideTheSameOpen() throws IOException {
        FakeTransports transports = new FakeTransports();
        transports.primaryBehavior = Behavior.SILENT;
        transports.fallbackBehavior = Behavior.ANSWER;
        RecordingCallbacks callbacks = new RecordingCallbacks(early(3_100));
        StartupFailoverDataSource source = newSource(transports, callbacks);

        assertEquals(741, source.open(INIT));

        assertEquals(1, callbacks.earlyTimeouts);
        assertEquals(1, transports.primaries.get(0).closes); // Cronet request cancelled
        assertSame(transports.legs.get(0).source, currentSourceAfterRead(source));
        // The fallback leg gets what is left of the old 8 s wait: the total never exceeds it.
        assertEquals(Collections.singletonList((long) LONG_MS - 3_100), mScheduledDelays);
        assertEquals(Collections.singletonList("answered"), callbacks.fallbackOutcomes);
        // OkHttp served the URL Cronet could not: the only evidence that justifies the
        // transport-wide Cronet bypass.
        assertEquals(1, callbacks.primaryFaults);
        // ...but its first byte (Movistar: ~4.27 s behind an IPv6 handshake stall) describes that
        // host/path, not the link: it must not push the next open onto the 8 s budget.
        assertTrue(callbacks.firstBytes.isEmpty());

        // The source stays on the path that answered.
        source.close();
        source.open(MEDIA);
        assertEquals(1, transports.primaries.size());
        assertEquals(2, transports.legs.get(0).source.opens);
    }

    @Test
    public void silentOnEveryPathSurfacesTheTransportVerdict() {
        FakeTransports transports = new FakeTransports();
        transports.primaryBehavior = Behavior.SILENT;
        transports.fallbackBehavior = Behavior.HANG_UNTIL_ABORTED;
        RecordingCallbacks callbacks = new RecordingCallbacks(early(3_100));
        StartupFailoverDataSource source = newSource(transports, callbacks);

        HttpDataSource.HttpDataSourceException failure =
                assertThrows(HttpDataSource.HttpDataSourceException.class, () -> source.open(INIT));

        assertTrue(transports.legs.get(0).aborted);
        assertTrue(MediaStartupTimeoutException.isInChain(failure));
        assertEquals(Collections.singletonList("no-response-deadline"), callbacks.fallbackOutcomes);
        // Silent on both transports = dead edge host: Cronet is not blamed, the verdict says so.
        assertEquals(0, callbacks.primaryFaults);
        assertTrue(MediaStartupTimeoutException.isHostDeadInChain(failure));
        // The existing media3 policy still treats it as a zero-progress startup timeout.
        assertTrue(Media3SourceFactory.FailFastLoadErrorPolicy.isStartupNoProgressTimeout(
                C.DATA_TYPE_MEDIA_INITIALIZATION, 0, failure));
    }

    @Test
    public void longBudgetTimeoutIsMarkedButNeverFailedOverEarly() {
        FakeTransports transports = new FakeTransports();
        transports.primaryBehavior = Behavior.SILENT;
        RecordingCallbacks callbacks = new RecordingCallbacks(
                StartupDeadlinePolicy.longBudget("low-downKbps", -1, 0, "ep=1", "cell:104"));
        StartupFailoverDataSource source = newSource(transports, callbacks);

        IOException failure = assertThrows(IOException.class, () -> source.open(INIT));

        assertEquals(Collections.singletonList(LONG_MS), transports.primaryBudgets);
        assertEquals(0, transports.fallbacksCreated);
        assertEquals(0, callbacks.earlyTimeouts);
        assertTrue(MediaStartupTimeoutException.isInChain(failure));
        // Only Cronet was tried: the pre-existing load-error bypass keeps applying to it.
        assertFalse(MediaStartupTimeoutException.isHostDeadInChain(failure));
        assertEquals(0, callbacks.primaryFaults);
    }

    @Test
    public void anHttpAnswerIsNeverTreatedAsAMissingHost() {
        FakeTransports transports = new FakeTransports();
        transports.primaryBehavior = Behavior.HTTP_403;
        RecordingCallbacks callbacks = new RecordingCallbacks(early(3_100));
        StartupFailoverDataSource source = newSource(transports, callbacks);

        HttpDataSource.InvalidResponseCodeException failure = assertThrows(
                HttpDataSource.InvalidResponseCodeException.class, () -> source.open(INIT));

        assertEquals(403, failure.responseCode);
        assertFalse(MediaStartupTimeoutException.isInChain(failure));
        assertEquals(0, transports.fallbacksCreated);
        assertEquals(0, callbacks.earlyTimeouts);
    }

    @Test
    public void noAlternatePathMeansNoEarlyFailover() {
        FakeTransports transports = new FakeTransports();
        transports.primaryBehavior = Behavior.SILENT;
        transports.hasFallback = false;
        RecordingCallbacks callbacks = new RecordingCallbacks(early(3_100));
        StartupFailoverDataSource source = newSource(transports, callbacks);

        IOException failure = assertThrows(IOException.class, () -> source.open(INIT));

        assertTrue(MediaStartupTimeoutException.isInChain(failure));
        assertEquals(0, callbacks.decisions); // no budget is even asked for
        assertEquals(0, callbacks.earlyTimeouts);
        assertEquals(Collections.singletonList(LONG_MS), transports.primaryBudgets);
        assertTrue(mScheduledDelays.isEmpty());
    }

    @Test
    public void onlyGoogleVideoMediaRequestsAreStartupRequests() throws IOException {
        FakeTransports transports = new FakeTransports();
        transports.primaryBehavior = Behavior.ANSWER;
        RecordingCallbacks callbacks = new RecordingCallbacks(early(3_100));
        StartupFailoverDataSource source = newSource(transports, callbacks);

        source.open(new DataSpec(Uri.parse(
                "https://manifest.googlevideo.com/api/manifest/hls_variant/id/x")));
        source.close();
        source.close();
        source.open(new DataSpec(Uri.parse("https://www.youtube.com/api/timedtext?v=x")));
        source.close();
        // Live-edge segments may legitimately be held open by googlevideo: never cut short.
        source.open(new DataSpec(Uri.parse("https://rr1---sn-x.googlevideo.com/videoplayback"
                + "?itag=140&source=yt_live_broadcast&sq=4312")));
        source.close();
        source.open(new DataSpec(Uri.parse(
                "https://rr1---sn-x.googlevideo.com/videoplayback/id/x/itag/140/sq/4312")));

        assertEquals(0, callbacks.decisions);
        assertEquals(Collections.singletonList(LONG_MS), transports.primaryBudgets);
    }

    @Test
    public void aRetryAfterADeadFailoverStaysOnOkHttpAndKeepsTheHostDeadVerdict()
            throws IOException {
        FakeTransports transports = new FakeTransports();
        transports.primaryBehavior = Behavior.SILENT;
        transports.fallbackBehavior = Behavior.HANG_UNTIL_ABORTED;
        RecordingCallbacks callbacks = new RecordingCallbacks(early(3_100));
        StartupFailoverDataSource source = newSource(transports, callbacks);
        assertThrows(IOException.class, () -> source.open(INIT));
        source.close();

        // media3 retries the load (e.g. a media chunk): a fresh OkHttp leg, no early budget.
        transports.fallbackBehavior = Behavior.SILENT_OKHTTP;
        IOException retry = assertThrows(IOException.class, () -> source.open(INIT));

        assertEquals(1, transports.primaries.size()); // Cronet is not asked again
        assertEquals(2, transports.fallbacksCreated);
        assertEquals(1, callbacks.decisions);
        assertTrue(MediaStartupTimeoutException.isHostDeadInChain(retry));
        assertEquals(0, callbacks.primaryFaults);
    }

    @Test
    public void loadErrorPolicySkipsTheCronetBypassForADeadHost() {
        AtomicLong bypasses = new AtomicLong();
        Media3SourceFactory.FailFastLoadErrorPolicy policy =
                new Media3SourceFactory.FailFastLoadErrorPolicy(bypasses::incrementAndGet);

        // Both transports silent: surfaced for the remint, but Cronet is not switched off.
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(initLoadError(
                StartupFailoverDataSource.startupTimeout(INIT, 7_557, null, true))));
        assertEquals(0, bypasses.get());

        // Only Cronet tried (long budget / no fallback): the pre-existing bypass still applies.
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(initLoadError(
                StartupFailoverDataSource.startupTimeout(INIT, 8_000, null, false))));
        assertEquals(1, bypasses.get());
    }

    private static LoadErrorHandlingPolicy.LoadErrorInfo initLoadError(IOException exception) {
        return new LoadErrorHandlingPolicy.LoadErrorInfo(
                new LoadEventInfo(1, INIT, INIT.uri, Collections.emptyMap(), 0, 8_000, 0),
                new MediaLoadData(C.DATA_TYPE_MEDIA_INITIALIZATION), exception, 1);
    }

    @Test
    public void okHttpStandingInForABypassedCronetFeedsNoLinkEvidence() throws IOException {
        FakeTransports transports = new FakeTransports();
        transports.primaryBehavior = Behavior.ANSWER;
        transports.hasFallback = false; // the bypassed factory's okHttpOnly transports
        RecordingCallbacks callbacks = new RecordingCallbacks(early(3_100));

        newSource(transports, callbacks, /* normalTransport= */ false).open(INIT);
        assertTrue(callbacks.firstBytes.isEmpty());

        // The same transport as the device's only transport (no Cronet at all) does count.
        newSource(transports, callbacks, /* normalTransport= */ true).open(INIT);
        assertEquals(Collections.singletonList(40L), callbacks.firstBytes);
    }

    @Test
    public void theCronetBypassIsProcessWideNotPerPlayer() {
        // Measured: a bypass set by open 2 was gone for open 3 (new player -> new factory).
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        Media3SourceFactory.clearCronetBypass();
        try {
            assertFalse(Media3SourceFactory.shouldBypassCronet(context));
            Media3SourceFactory.markCronetBypass(com.liskovsoft.smartyoutubetv2.common.misc.NetPath
                    .networkId(context), "adaptive", "okhttp-answered");
            // Static state: any factory created afterwards on this network starts on OkHttp.
            assertTrue(Media3SourceFactory.shouldBypassCronet(context));
        } finally {
            Media3SourceFactory.clearCronetBypass();
        }
    }

    @Test
    public void noResponseClassifierRejectsHttpAnswers() {
        assertTrue(StartupFailoverDataSource.isNoResponseTimeout(
                new CronetDataSource.OpenException(new SocketTimeoutException(), INIT, 2002, 11)));
        assertTrue(StartupFailoverDataSource.isNoResponseTimeout(
                new IOException("Exception in CronetUrlRequest: net::ERR_CONNECTION_TIMED_OUT")));
        assertFalse(StartupFailoverDataSource.isNoResponseTimeout(
                new IOException("connection reset")));
        assertFalse(StartupFailoverDataSource.isNoResponseTimeout(forbidden()));
    }

    /** The real OkHttp leg against a host that accepts and never answers: abort unblocks it. */
    @Test
    public void okHttpLegAbortUnblocksAPendingOpen() throws Exception {
        ServerSocket tarpit = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        List<Socket> held = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            executor.submit(() -> {
                while (!tarpit.isClosed()) {
                    held.add(tarpit.accept());
                }
                return null;
            });
            Media3SourceFactory.OkHttpLeg leg = new Media3SourceFactory.OkHttpLeg(
                    new okhttp3.OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build(),
                    null);
            DataSpec spec = new DataSpec(Uri.parse(
                    "http://127.0.0.1:" + tarpit.getLocalPort() + "/videoplayback?itag=251"));
            long start = System.nanoTime();
            Future<Throwable> open = executor.submit(() -> {
                try {
                    leg.source().open(spec);
                    return null;
                } catch (IOException e) {
                    return e;
                }
            });
            Thread.sleep(300);
            assertFalse(open.isDone()); // really pending on the silent host

            leg.abort();

            Throwable failure = open.get(3, TimeUnit.SECONDS);
            assertTrue(failure instanceof HttpDataSource.HttpDataSourceException);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3_000);
        } finally {
            tarpit.close();
            synchronized (held) {
                for (Socket socket : held) {
                    socket.close();
                }
            }
            executor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------------------------

    private StartupFailoverDataSource newSource(FakeTransports transports, RecordingCallbacks callbacks) {
        return newSource(transports, callbacks, true);
    }

    private StartupFailoverDataSource newSource(FakeTransports transports,
            RecordingCallbacks callbacks, boolean normalTransport) {
        return new StartupFailoverDataSource(transports, callbacks, mScheduler, mClock::get, LONG_MS,
                normalTransport);
    }

    private static StartupDeadlinePolicy.Decision early(int budgetMs) {
        return new StartupDeadlinePolicy.Decision(budgetMs, true, "first-byte", 120, 3, "ep=1",
                "cell:104");
    }

    private static DataSource currentSourceAfterRead(StartupFailoverDataSource source) {
        // getUri() is forwarded to the leg that served the open.
        return FakeSource.byUri(source.getUri());
    }

    private static HttpDataSource.InvalidResponseCodeException forbidden() {
        return new HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null,
                Collections.emptyMap(), INIT, new byte[0]);
    }

    private enum Behavior { ANSWER, SILENT, SILENT_OKHTTP, HANG_UNTIL_ABORTED, HTTP_403 }

    private final class FakeTransports implements StartupFailoverDataSource.Transports {
        Behavior primaryBehavior = Behavior.ANSWER;
        Behavior fallbackBehavior = Behavior.ANSWER;
        boolean hasFallback = true;
        final List<Integer> primaryBudgets = new ArrayList<>();
        final List<FakeSource> primaries = new ArrayList<>();
        final List<FakeLeg> legs = new ArrayList<>();
        int fallbacksCreated;

        @Override
        public boolean canFailOver() {
            return hasFallback;
        }

        @Override
        public DataSource primary(int headersBudgetMs) {
            primaryBudgets.add(headersBudgetMs);
            FakeSource source = new FakeSource(primaryBehavior, headersBudgetMs, null);
            primaries.add(source);
            return source;
        }

        @Nullable
        @Override
        public StartupFailoverDataSource.AbortableLeg fallback() {
            if (!hasFallback) {
                return null;
            }
            fallbacksCreated++;
            FakeLeg leg = new FakeLeg(fallbackBehavior);
            legs.add(leg);
            return leg;
        }
    }

    private final class FakeLeg implements StartupFailoverDataSource.AbortableLeg {
        final CountDownLatch abortLatch = new CountDownLatch(1);
        final FakeSource source;
        volatile boolean aborted;

        FakeLeg(Behavior behavior) {
            source = new FakeSource(behavior, 0, abortLatch);
        }

        @Override
        public DataSource source() {
            return source;
        }

        @Override
        public void abort() {
            aborted = true;
            abortLatch.countDown();
        }
    }

    /** Behaves like a transport; "SILENT" = the connection timeout expired with no headers. */
    private final class FakeSource implements DataSource {
        private final Behavior mBehavior;
        private final int mBudgetMs;
        @Nullable private final CountDownLatch mAbortLatch;
        private final Uri mIdentity = Uri.parse("fake://" + System.identityHashCode(this));
        int opens;
        int closes;

        FakeSource(Behavior behavior, int budgetMs, @Nullable CountDownLatch abortLatch) {
            mBehavior = behavior;
            mBudgetMs = budgetMs;
            mAbortLatch = abortLatch;
            REGISTRY.add(this);
        }

        static DataSource byUri(Uri uri) {
            for (FakeSource source : REGISTRY) {
                if (source.mIdentity.equals(uri)) {
                    return source;
                }
            }
            return null;
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            opens++;
            switch (mBehavior) {
                case SILENT:
                    mClock.addAndGet(mBudgetMs);
                    throw new CronetDataSource.OpenException(
                            new SocketTimeoutException(), dataSpec, 2002, 10);
                case HANG_UNTIL_ABORTED:
                    try {
                        assertTrue(mAbortLatch.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new HttpDataSource.HttpDataSourceException(new IOException("Canceled"),
                            dataSpec, 2001, HttpDataSource.HttpDataSourceException.TYPE_OPEN);
                case SILENT_OKHTTP:
                    // OkHttp's own read timeout, before the leg deadline
                    throw new HttpDataSource.HttpDataSourceException(
                            new SocketTimeoutException("timeout"), dataSpec, 2002,
                            HttpDataSource.HttpDataSourceException.TYPE_OPEN);
                case HTTP_403:
                    throw forbidden();
                case ANSWER:
                default:
                    mClock.addAndGet(40);
                    return dataSpec.length != C.LENGTH_UNSET ? dataSpec.length : 741;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return C.RESULT_END_OF_INPUT;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
        }

        @Override
        public Uri getUri() {
            return mIdentity;
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return Collections.emptyMap();
        }

        @Override
        public void close() {
            closes++;
        }
    }

    private static final List<FakeSource> REGISTRY = new CopyOnWriteArrayList<>();

    private static final class RecordingCallbacks implements StartupFailoverDataSource.Callbacks {
        private final StartupDeadlinePolicy.Decision mDecision;
        int decisions;
        int earlyTimeouts;
        int primaryFaults;
        final List<Long> firstBytes = new ArrayList<>();
        final List<String> fallbackOutcomes = new ArrayList<>();

        RecordingCallbacks(StartupDeadlinePolicy.Decision decision) {
            mDecision = decision;
        }

        @Override
        public StartupDeadlinePolicy.Decision decide(DataSpec dataSpec) {
            decisions++;
            return mDecision;
        }

        @Override
        public void onFirstByte(long firstByteMs) {
            firstBytes.add(firstByteMs);
        }

        @Override
        public void onEarlyTimeout(DataSpec dataSpec, StartupDeadlinePolicy.Decision decision,
                long waitedMs, IOException cause) {
            earlyTimeouts++;
            assertEquals(decision.budgetMs, waitedMs);
        }

        @Override
        public void onFallbackResult(DataSpec dataSpec, StartupDeadlinePolicy.Decision decision,
                boolean answered, boolean deadlineHit, long legMs, long totalMs,
                @Nullable IOException cause) {
            fallbackOutcomes.add(answered ? "answered"
                    : deadlineHit ? "no-response-deadline" : "no-response");
        }

        @Override
        public void onPrimaryTransportFault(DataSpec dataSpec,
                StartupDeadlinePolicy.Decision decision) {
            primaryFaults++;
            // The fault is attributed to the network the primary stalled on, not today's.
            assertEquals("cell:104", decision.networkKey);
        }
    }
}
