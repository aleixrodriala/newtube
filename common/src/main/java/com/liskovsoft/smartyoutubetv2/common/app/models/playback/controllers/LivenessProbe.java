package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * NEWTUBE(recovery-probe): tells the capped player the moment the server answers again.
 *
 * A tunnel/lift outage keeps Android's default network VALIDATED (HANDOFF §13), so no connectivity
 * edge ever fires and recovery waits on the escalating timer (retries at +5, +20, +65, +185 and
 * +485 s after the cap). A 70 s outage therefore resumed ~2 min after the link came back. This
 * sends a tiny request on a short period and reports only a FAILED -> ANSWERED transition: a link
 * that answers from the start proves nothing about why playback died (a slow-but-alive link, a
 * CDN problem), and re-reporting it would turn the probe into the reload hammer the retry budget
 * exists to prevent. One report per start; the caller spends a normal retry for it.
 */
final class LivenessProbe {
    interface Transport {
        /** True when the server answered at all. Blocking; runs on the probe thread. */
        boolean probe();
    }

    interface Scheduler {
        void schedule(Runnable task, long delayMs);
    }

    static final long FIRST_DELAY_MS = 3_000;
    static final long FAST_PERIOD_MS = 5_000;
    static final long SLOW_PERIOD_MS = 15_000;
    /** Fast probing covers the typical tunnel/platform outage; slower after that. */
    static final long FAST_WINDOW_MS = 60_000;
    /** Matches the ~8 min the retry ladder itself covers, plus slack. */
    static final long MAX_LIFETIME_MS = 10 * 60_000;

    private final Transport mTransport;
    private final Scheduler mScheduler;
    private final Consumer<Runnable> mMainPoster;
    private final Runnable mOnRecovered;
    private final Clock mClock;
    private int mGeneration;
    private long mStartedAtMs;
    private boolean mSawFailure;
    private int mProbeCount;

    interface Clock {
        long nowMs();
    }

    LivenessProbe(Transport transport, Scheduler scheduler, Consumer<Runnable> mainPoster,
                  Clock clock, Runnable onRecovered) {
        mTransport = transport;
        mScheduler = scheduler;
        mMainPoster = mainPoster;
        mClock = clock;
        mOnRecovered = onRecovered;
    }

    static long nextDelayMs(long elapsedMs) {
        if (elapsedMs >= MAX_LIFETIME_MS) {
            return -1;
        }
        return elapsedMs < FAST_WINDOW_MS ? FAST_PERIOD_MS : SLOW_PERIOD_MS;
    }

    synchronized void start() {
        start(false);
    }

    /**
     * @param failureAlreadySeen NEWTUBE(offline-wait): the episode began with the device reporting
     *                           no validated network. That IS the failure half of the transition,
     *                           so the first answer already means recovery. It matters on a network
     *                           that reaches YouTube but never earns VALIDATED (a VPN, a network
     *                           blocking Android's validation check): no network edge ever fires
     *                           there, and requiring a failed probe first left the player waiting
     *                           for good.
     */
    synchronized void start(boolean failureAlreadySeen) {
        int generation = ++mGeneration;
        mStartedAtMs = mClock.nowMs();
        mSawFailure = failureAlreadySeen;
        mProbeCount = 0;
        mScheduler.schedule(() -> runProbe(generation), FIRST_DELAY_MS);
    }

    synchronized void stop() {
        mGeneration++;
    }

    synchronized boolean isRunning(int generation) {
        return generation == mGeneration;
    }

    synchronized int getProbeCount() {
        return mProbeCount;
    }

    private void runProbe(int generation) {
        if (!isRunning(generation)) {
            return;
        }

        boolean answered;
        try {
            answered = mTransport.probe();
        } catch (RuntimeException e) {
            answered = false;
        }

        long delayMs;
        synchronized (this) {
            if (generation != mGeneration) {
                return;
            }
            mProbeCount++;
            if (!answered) {
                mSawFailure = true;
            } else if (mSawFailure) {
                // One report per start. The generation is re-checked on the main thread: stop() or a
                // new capped episode starting before delivery must drop this report, or it would
                // spend the NEXT episode's retry on an old recovery.
                mMainPoster.accept(() -> deliver(generation));
                return;
            }
            delayMs = nextDelayMs(mClock.nowMs() - mStartedAtMs);
            if (delayMs < 0) {
                mGeneration++;
                return;
            }
        }
        mScheduler.schedule(() -> runProbe(generation), delayMs);
    }

    private void deliver(int generation) {
        synchronized (this) {
            if (generation != mGeneration) {
                return;
            }
            mGeneration++;
        }
        mOnRecovered.run();
    }

    /** Production scheduler: one daemon thread, low priority, shared by every probe instance. */
    static Scheduler backgroundScheduler() {
        return Holder.SCHEDULER;
    }

    private static final class Holder {
        private static final ScheduledExecutorService EXECUTOR =
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "recovery-probe");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                });
        static final Scheduler SCHEDULER = (task, delayMs) ->
                EXECUTOR.schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }
}
