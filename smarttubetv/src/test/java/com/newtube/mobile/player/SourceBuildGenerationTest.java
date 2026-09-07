package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Exercises queued build/delivery order without player initialization or network requests. */
public class SourceBuildGenerationTest {
    @Test
    public void rapidSelectionsSkipObsoleteBuildersAndPublishOnlyLatest() {
        Pipeline player = new Pipeline();
        player.open("first");
        player.open("second");
        player.open("latest");

        player.worker.drain();
        player.main.drain();

        assertEquals(Arrays.asList("latest"), player.built);
        assertEquals(Arrays.asList("latest"), player.prepared);
    }

    @Test
    public void resetSkipsWorkStillWaitingOnTheBuildExecutor() {
        Pipeline player = new Pipeline();
        player.open("abandoned");
        player.reset();

        player.worker.drain();
        player.main.drain();

        assertTrue(player.built.isEmpty());
        assertTrue(player.prepared.isEmpty());
    }

    @Test
    public void newerOpenDropsAnOlderResultAlreadyWaitingOnMain() {
        Pipeline player = new Pipeline();
        player.open("old");
        player.worker.drain();
        player.open("latest");
        player.worker.drain();
        player.main.drain();

        assertEquals(Arrays.asList("old", "latest"), player.built);
        assertEquals(Arrays.asList("latest"), player.prepared);
    }

    @Test
    public void resetDuringRunningBuildPreventsItsLaterDelivery() {
        Pipeline player = new Pipeline();
        player.open(() -> {
            player.reset();
            return "became-obsolete-during-build";
        });
        player.worker.drain();
        player.main.drain();

        assertEquals(1, player.built.size());
        assertTrue(player.prepared.isEmpty());
    }

    @Test
    public void obsoleteQueuedPrebuildDoesNotDelayNewForegroundSource() {
        Pipeline player = new Pipeline();
        player.prebuild(() -> "old-next-video");
        player.open("selected-video");
        player.worker.drain();
        player.main.drain();

        assertEquals(Arrays.asList("selected-video"), player.built);
        assertEquals(Arrays.asList("selected-video"), player.prepared);
        assertNull(player.stash);
    }

    @Test
    public void runningPrebuildCannotRestoreStashAfterReset() {
        Pipeline player = new Pipeline();
        player.prebuild(() -> {
            player.reset();
            return "obsolete-next-video";
        });
        player.worker.drain();

        assertNull(player.stash);
        player.prebuild(() -> "current-next-video");
        player.worker.drain();
        assertEquals("current-next-video", player.stash);
    }

    @Test
    public void releaseDropsBothQueuedPrebuildAndPendingMainDelivery() {
        Pipeline player = new Pipeline();
        player.open("prepared-later");
        player.worker.drain();
        player.prebuild(() -> "next-video");
        player.reset(); // release uses the same atomic invalidation plus unconditional cleanup
        player.worker.drain();
        player.main.drain();

        assertEquals(Arrays.asList("prepared-later"), player.built);
        assertTrue(player.prepared.isEmpty());
        assertNull(player.stash);
    }

    @Test
    public void preservingCompletedStashDoesNotPermitAnOlderWorkerToReplaceIt() {
        Pipeline player = new Pipeline();
        player.prebuild(() -> "completed-matching-video");
        player.worker.drain();
        int oldGeneration = player.generation.current();
        player.generation.invalidate(() -> {
            // The controller retains an already-completed matching entry during auto-advance.
        });
        player.generation.publishIfCurrent(oldGeneration, () -> player.stash = "obsolete-result");

        assertEquals("completed-matching-video", player.stash);
    }

    @Test
    public void sharedExecutorKeepsCancellationLocalToEachPlayer() {
        QueueExecutor worker = new QueueExecutor();
        Pipeline first = new Pipeline(worker);
        Pipeline second = new Pipeline(worker);
        first.open("released-player");
        second.open("other-player");
        first.reset();
        worker.drain();
        first.main.drain();
        second.main.drain();

        assertTrue(first.built.isEmpty());
        assertEquals(Arrays.asList("other-player"), second.prepared);
    }

    @Test
    public void buildDoesNotHoldGenerationLockAndBlockCancellation() throws Exception {
        SourceBuildGeneration generation = new SourceBuildGeneration();
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch finishBuild = new CountDownLatch(1);
        AtomicReference<String> stash = new AtomicReference<>();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        int ticket = generation.current();
        try {
            Future<?> build = threads.submit(generation.guard(ticket, () -> {
                building.countDown();
                await(finishBuild);
                generation.publishIfCurrent(ticket, () -> stash.set("stale"));
            }));
            assertTrue(building.await(3, TimeUnit.SECONDS));
            Future<?> reset = threads.submit(() -> generation.invalidate(() -> stash.set(null)));
            reset.get(3, TimeUnit.SECONDS); // must finish while the expensive builder is blocked
            finishBuild.countDown();
            build.get(3, TimeUnit.SECONDS);

            assertNull(stash.get());
        } finally {
            finishBuild.countDown();
            threads.shutdownNow();
        }
    }

    @Test
    public void publicationCannotWriteAfterConcurrentCleanup() throws Exception {
        SourceBuildGeneration generation = new SourceBuildGeneration();
        AtomicReference<String> stash = new AtomicReference<>();
        CountDownLatch publicationEntered = new CountDownLatch(1);
        CountDownLatch resetStarted = new CountDownLatch(1);
        CountDownLatch finishPublication = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        int ticket = generation.current();
        try {
            Future<?> publish = threads.submit(() -> generation.publishIfCurrent(ticket, () -> {
                publicationEntered.countDown();
                await(finishPublication);
                stash.set("source");
            }));
            assertTrue(publicationEntered.await(3, TimeUnit.SECONDS));
            Future<?> reset = threads.submit(() -> {
                resetStarted.countDown();
                generation.invalidate(() -> stash.set(null));
            });
            assertTrue(resetStarted.await(3, TimeUnit.SECONDS));
            assertFalse(reset.isDone());
            finishPublication.countDown();
            publish.get(3, TimeUnit.SECONDS);
            reset.get(3, TimeUnit.SECONDS);

            assertNull(stash.get());
        } finally {
            finishPublication.countDown();
            threads.shutdownNow();
        }
    }

    // A drop is a legitimate outcome, but a SILENT drop is indistinguishable from a player that
    // was never asked to open anything: both leave a spinner at 00:00 and nothing in logcat. The
    // 2026-09-07 Pixel stall cost a full investigation for exactly that reason, so every discard
    // path has to be observable.

    @Test
    public void queuedBuildDroppedBeforeStartingIsReported() {
        SourceBuildGeneration generation = new SourceBuildGeneration();
        List<String> drops = new ArrayList<>();
        generation.setDropListener((stage, gen, current) -> drops.add(stage + ":" + gen + "->" + current));

        int stale = generation.next();
        generation.next(); // a newer open supersedes it while it is still queued
        generation.guard(stale, "build", () -> { throw new AssertionError("must not run"); }).run();

        assertEquals(Arrays.asList("build:1->2"), drops);
    }

    @Test
    public void resultDroppedOnDeliveryIsReported() {
        SourceBuildGeneration generation = new SourceBuildGeneration();
        List<String> drops = new ArrayList<>();
        generation.setDropListener((stage, gen, current) -> drops.add(stage));

        int ticket = generation.next();
        Runnable delivery = generation.guard(ticket, "deliver", () -> {
            throw new AssertionError("must not prepare");
        });
        generation.invalidate(() -> { });
        delivery.run();

        assertEquals(Arrays.asList("deliver"), drops);
    }

    @Test
    public void discardedStashPublicationIsReported() {
        SourceBuildGeneration generation = new SourceBuildGeneration();
        List<String> drops = new ArrayList<>();
        generation.setDropListener((stage, gen, current) -> drops.add(stage));

        int ticket = generation.current();
        generation.invalidate(() -> { });
        generation.publishIfCurrent(ticket, "prebuild-publish", () -> {
            throw new AssertionError("must not publish");
        });

        assertEquals(Arrays.asList("prebuild-publish"), drops);
    }

    @Test
    public void workThatRunsNormallyReportsNoDrop() {
        SourceBuildGeneration generation = new SourceBuildGeneration();
        List<String> drops = new ArrayList<>();
        generation.setDropListener((stage, gen, current) -> drops.add(stage));

        int ticket = generation.next();
        List<String> ran = new ArrayList<>();
        generation.guard(ticket, "build", () -> ran.add("built")).run();
        generation.publishIfCurrent(ticket, "prebuild-publish", () -> ran.add("published"));

        assertEquals(Arrays.asList("built", "published"), ran);
        assertTrue(drops.isEmpty());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Same two-stage execution used by the controller, with builders returning source names. */
    private static final class Pipeline {
        final SourceBuildGeneration generation = new SourceBuildGeneration();
        final QueueExecutor worker;
        final QueueExecutor main = new QueueExecutor();
        final List<String> built = new ArrayList<>();
        final List<String> prepared = new ArrayList<>();
        String stash;

        Pipeline() {
            this(new QueueExecutor());
        }

        Pipeline(QueueExecutor worker) {
            this.worker = worker;
        }

        void open(String source) {
            open(() -> source);
        }

        void open(Supplier<String> builder) {
            int ticket = generation.next();
            worker.execute(generation.guard(ticket, () -> {
                String source = builder.get();
                built.add(source);
                main.execute(generation.guard(ticket, () -> prepared.add(source)));
            }));
        }

        void prebuild(Supplier<String> builder) {
            int ticket = generation.current();
            worker.execute(generation.guard(ticket, () -> {
                String source = builder.get();
                built.add(source);
                generation.publishIfCurrent(ticket, () -> stash = source);
            }));
        }

        void reset() {
            generation.invalidate(() -> stash = null);
        }
    }

    private static final class QueueExecutor implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void drain() {
            while (!tasks.isEmpty()) {
                tasks.remove().run();
            }
        }
    }
}
