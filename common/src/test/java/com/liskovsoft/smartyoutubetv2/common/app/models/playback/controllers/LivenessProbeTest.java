package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

public class LivenessProbeTest {
    private long mNowMs;
    private final Deque<long[]> mDelays = new ArrayDeque<>();
    private final Deque<Runnable> mTasks = new ArrayDeque<>();
    private final Deque<Runnable> mMain = new ArrayDeque<>();
    private int mRecovered;

    private LivenessProbe probe(Boolean... answers) {
        Iterator<Boolean> results = Arrays.asList(answers).iterator();
        return new LivenessProbe(() -> results.hasNext() ? results.next() : true,
                (task, delayMs) -> {
                    mDelays.add(new long[]{delayMs});
                    mTasks.add(task);
                },
                mMain::add, () -> mNowMs, () -> mRecovered++);
    }

    /** Runs the next scheduled probe after its delay, then drains main-thread posts. */
    private void step() {
        mNowMs += mDelays.poll()[0];
        mTasks.poll().run();
        while (!mMain.isEmpty()) {
            mMain.poll().run();
        }
    }

    @Test
    public void reportsOnlyAFailedThenAnsweredTransition() {
        LivenessProbe probe = probe(false, false, true);
        probe.start();
        step();
        step();
        assertEquals(0, mRecovered);
        step();
        assertEquals(1, mRecovered);
        assertEquals("one report per start: nothing further is scheduled", 0, mTasks.size());
    }

    @Test
    public void aLinkThatAnswersFromTheStartIsNeverReported() {
        LivenessProbe probe = probe(true, true, true, true);
        probe.start();
        for (int i = 0; i < 4; i++) {
            step();
        }
        assertEquals(0, mRecovered);
        assertEquals("keeps watching for a later outage", 1, mTasks.size());
    }

    @Test
    public void stopCancelsPendingAndInFlightWork() {
        LivenessProbe probe = probe(false, true);
        probe.start();
        step();
        probe.stop();
        step(); // the queued probe runs but belongs to a stopped generation
        assertEquals(0, mRecovered);
        assertEquals(0, mTasks.size());
    }

    @Test
    public void restartForgetsEarlierFailures() {
        LivenessProbe probe = probe(false, true);
        probe.start();
        step(); // failure recorded
        probe.start(); // a new capped episode
        mTasks.poll(); // drop the stale task from the first start
        mDelays.poll();
        step(); // answered, but no failure seen in THIS episode
        assertEquals(0, mRecovered);
    }

    @Test
    public void aReportStillQueuedForTheMainThreadIsDroppedByStopOrRestart() {
        LivenessProbe probe = probe(false, true);
        probe.start();
        mNowMs += mDelays.poll()[0];
        mTasks.poll().run(); // failure
        mNowMs += mDelays.poll()[0];
        mTasks.poll().run(); // answered: report queued, not yet delivered
        assertEquals(1, mMain.size());
        probe.stop();
        probe.start(); // a later capped episode
        mMain.poll().run();
        assertEquals("the old episode's report must not reach the new one", 0, mRecovered);
    }

    @Test
    public void scheduleIsFastThenSlowThenStops() {
        assertEquals(LivenessProbe.FAST_PERIOD_MS, LivenessProbe.nextDelayMs(0));
        assertEquals(LivenessProbe.FAST_PERIOD_MS, LivenessProbe.nextDelayMs(59_999));
        assertEquals(LivenessProbe.SLOW_PERIOD_MS, LivenessProbe.nextDelayMs(60_000));
        assertEquals(-1, LivenessProbe.nextDelayMs(LivenessProbe.MAX_LIFETIME_MS));
    }

    @Test
    public void givesUpAfterItsLifetime() {
        Boolean[] failures = new Boolean[200];
        Arrays.fill(failures, false);
        LivenessProbe probe = probe(failures);
        probe.start();
        int probes = 0;
        while (!mTasks.isEmpty()) {
            step();
            probes++;
        }
        List<Integer> bounds = Arrays.asList(40, 60);
        assertEquals("bounded number of probes: " + probes, true,
                probes >= bounds.get(0) && probes <= bounds.get(1));
        assertEquals(0, mRecovered);
    }

    @Test
    public void aThrowingTransportCountsAsNoAnswer() {
        Iterator<Boolean> results = Arrays.asList(true).iterator();
        final boolean[] first = {true};
        LivenessProbe probe = new LivenessProbe(() -> {
            if (first[0]) {
                first[0] = false;
                throw new IllegalStateException("no route");
            }
            return results.next();
        }, (task, delayMs) -> {
            mDelays.add(new long[]{delayMs});
            mTasks.add(task);
        }, mMain::add, () -> mNowMs, () -> mRecovered++);
        probe.start();
        step();
        step();
        assertEquals(1, mRecovered);
    }
}
