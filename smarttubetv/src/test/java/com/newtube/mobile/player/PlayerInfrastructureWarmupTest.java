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
}
