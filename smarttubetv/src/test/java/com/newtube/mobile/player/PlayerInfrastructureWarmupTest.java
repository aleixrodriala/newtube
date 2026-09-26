package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlayerInfrastructureWarmupTest {
    @Test
    public void queuesOnceWithoutRunningInitializationOnCaller() {
        List<Runnable> queue = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        PlayerInfrastructureWarmup warmup = new PlayerInfrastructureWarmup(
                queue::add, calls::incrementAndGet, calls::incrementAndGet);
        warmup.schedule();
        warmup.schedule();
        assertEquals(1, queue.size());
        assertEquals(0, calls.get());
        queue.get(0).run();
        assertEquals(2, calls.get());
        warmup.schedule();
        assertEquals(1, queue.size());
    }

    @Test
    public void concurrentSchedulesShareOneWorker() throws Exception {
        AtomicInteger workers = new AtomicInteger();
        PlayerInfrastructureWarmup warmup = new PlayerInfrastructureWarmup(
                ignored -> workers.incrementAndGet(), () -> {}, () -> {});
        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Thread caller = new Thread(warmup::schedule);
            callers.add(caller);
            caller.start();
        }
        for (Thread caller : callers) {
            caller.join();
        }
        assertEquals(1, workers.get());
    }

    @Test
    public void missingNativeTransportStillWarmsCache() {
        AtomicInteger cacheCalls = new AtomicInteger();
        new PlayerInfrastructureWarmup(Runnable::run,
                () -> { throw new UnsatisfiedLinkError("test"); },
                cacheCalls::incrementAndGet).schedule();
        assertEquals(1, cacheCalls.get());
    }

    @Test
    public void cacheFailureDoesNotRestartCompletedTransport() {
        AtomicInteger transportCalls = new AtomicInteger();
        PlayerInfrastructureWarmup warmup = new PlayerInfrastructureWarmup(Runnable::run,
                transportCalls::incrementAndGet,
                () -> { throw new IllegalStateException("test"); });
        warmup.schedule();
        warmup.schedule();
        assertEquals(1, transportCalls.get());
    }

    @Test
    public void rejectedSchedulingCanBeRetried() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        Executor rejectFirst = task -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RejectedExecutionException("test");
            }
            task.run();
        };
        PlayerInfrastructureWarmup warmup = new PlayerInfrastructureWarmup(
                rejectFirst, calls::incrementAndGet, calls::incrementAndGet);
        warmup.schedule();
        warmup.schedule();
        assertEquals(2, attempts.get());
        assertEquals(2, calls.get());
    }
    @Test
    public void decoderListWarmsLastAndIndependently() {
        List<String> order = new ArrayList<>();
        new PlayerInfrastructureWarmup(Runnable::run,
                () -> order.add("transport"),
                () -> { order.add("cache"); throw new IllegalStateException("test"); },
                () -> order.add("codecs")).schedule();
        assertEquals(java.util.Arrays.asList("transport", "cache", "codecs"), order);
    }

    @Test
    public void decoderListFailureIsSwallowed() {
        AtomicInteger calls = new AtomicInteger();
        PlayerInfrastructureWarmup warmup = new PlayerInfrastructureWarmup(Runnable::run,
                calls::incrementAndGet, calls::incrementAndGet,
                () -> { throw new NoClassDefFoundError("test"); });
        warmup.schedule();
        assertEquals(2, calls.get());
    }

    @Test
    public void decoderListCoversEveryYouTubeCodec() {
        List<String> mimes = java.util.Arrays.asList(PlayerInfrastructureWarmup.DECODER_MIME_TYPES);
        for (String mime : new String[] {"video/x-vnd.on2.vp9", "video/av01", "video/avc",
                "audio/opus", "audio/mp4a-latm"}) {
            org.junit.Assert.assertTrue(mime, mimes.contains(mime));
        }
    }

    @Test
    public void decoderScanStopsBeforeTheNextTypeOncePlaybackPrepares() {
        List<String> warmed = new ArrayList<>();
        boolean[] preparing = {false};
        int count = PlayerInfrastructureWarmup.warmDecoderInfos(
                PlayerInfrastructureWarmup.DECODER_MIME_TYPES, () -> preparing[0], mime -> {
                    warmed.add(mime);
                    if (warmed.size() == 2) {
                        preparing[0] = true; // the first source is handed over mid-scan
                    }
                });
        assertEquals(2, count);
        // The types a YouTube DASH answer nearly always carries went first.
        assertEquals(java.util.Arrays.asList("video/x-vnd.on2.vp9", "audio/opus"), warmed);
    }

    @Test
    public void decoderScanCoversEverythingWhenNothingPrepares() {
        List<String> warmed = new ArrayList<>();
        assertEquals(PlayerInfrastructureWarmup.DECODER_MIME_TYPES.length,
                PlayerInfrastructureWarmup.warmDecoderInfos(
                        PlayerInfrastructureWarmup.DECODER_MIME_TYPES, () -> false, warmed::add));
        assertEquals(PlayerInfrastructureWarmup.DECODER_MIME_TYPES.length, warmed.size());
    }

    @Test
    public void realDecoderWarmupRunsWithoutADecoder() {
        // Robolectric has no codecs: media3 must answer an empty list, never throw to the caller.
        PlayerInfrastructureWarmup.warmDecoderInfos();
    }
}

