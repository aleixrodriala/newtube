package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import androidx.media3.common.Player;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.newtube.mobile.player.TtffFixtureActivity.Snapshot;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** Real SurfaceView/decoder stability test; fixture-only results are NOT YouTube TTFF evidence. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class TtffFixturePlaybackTest {
    @Test
    public void sustainedPlaybackPauseSeekAndSourceSwitchStayHealthy() {
        try (ActivityScenario<TtffFixtureActivity> scenario = ActivityScenario.launch(TtffFixtureActivity.class)) {
            await(scenario, s -> s.initialized, 10_000, "surface and engine");
            scenario.onActivity(a -> a.openFixture("cold"));
            awaitReadyFrame(scenario, 1);

            Snapshot before = snapshot(scenario);
            report("cold-ready", before, 0);
            long sustainedStart = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() - sustainedStart < 45_000) {
                SystemClock.sleep(500);
                assertHealthy(snapshot(scenario));
            }
            Snapshot after = snapshot(scenario);
            assertTrue("45s soak must advance at least40s", after.positionMs - before.positionMs >= 40_000);
            assertTrue("decoder must continue rendering", after.frames - before.frames >= 900);
            Log.i(TtffFixtureActivity.TAG, "event=soak-passed wallMs="
                    + (SystemClock.elapsedRealtime() - sustainedStart)
                    + " progressMs=" + (after.positionMs - before.positionMs)
                    + " frames=" + (after.frames - before.frames) + " dropped=" + after.dropped);
            report("sustained-passed", after, after.positionMs - before.positionMs);

            scenario.onActivity(TtffFixtureActivity::pauseFixture);
            SystemClock.sleep(250);
            Snapshot paused = snapshot(scenario);
            SystemClock.sleep(1_000);
            Snapshot stillPaused = snapshot(scenario);
            assertFalse("pause must stop playback", stillPaused.playing);
            assertTrue("paused position drift", Math.abs(stillPaused.positionMs - paused.positionMs) < 150);
            assertHealthy(stillPaused);

            scenario.onActivity(TtffFixtureActivity::resumeFixture);
            await(scenario, s -> s.playing && s.positionMs >= paused.positionMs + 1_000,
                    5_000, "resume progress");

            int framesBeforeSeek = snapshot(scenario).frames;
            scenario.onActivity(a -> a.seekFixture(10_000));
            await(scenario, s -> s.state == Player.STATE_READY && s.playing
                            && s.positionMs >= 10_300 && s.positionMs < 15_000
                            && s.frames >= framesBeforeSeek + 3,
                    10_000, "seek decoded progress");
            scenario.onActivity(TtffFixtureActivity::finishExpectedTransition);
            assertHealthy(snapshot(scenario));

            scenario.onActivity(a -> a.openFixture("switch"));
            awaitReadyFrame(scenario, 2);
            Snapshot switched = snapshot(scenario);
            report("switch-ready", switched, 0);
            await(scenario, s -> s.playing && s.positionMs >= switched.positionMs + 3_000
                            && s.frames >= switched.frames + 60,
                    8_000, "switched-source progress");
            assertHealthy(snapshot(scenario));
            Log.i(TtffFixtureActivity.TAG, "event=all-phases-passed episodes=2");
        } // ActivityScenario finishes the only activity and releases the engine even on assertion failure.
    }

    private static void awaitReadyFrame(ActivityScenario<TtffFixtureActivity> scenario, int episode) {
        await(scenario, s -> s.episode == episode && s.state == Player.STATE_READY && s.playing
                        && s.firstFrameMs >= 0 && s.readyMs >= 0 && s.frames >= 3,
                20_000, "episode" + episode + " ready and rendered frame");
        scenario.onActivity(TtffFixtureActivity::finishExpectedTransition);
        assertHealthy(snapshot(scenario));
    }

    private static void await(ActivityScenario<TtffFixtureActivity> scenario, Predicate<Snapshot> condition,
            long timeoutMs, String description) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        Snapshot current;
        do {
            current = snapshot(scenario);
            assertEquals("media error while waiting for" + description + ":" + current.error, 0, current.errors);
            if (condition.test(current)) return;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Timed out waiting for" + description + "; state=" + current.state
                + " positionMs=" + current.positionMs + " frames=" + current.frames);
    }

    private static Snapshot snapshot(ActivityScenario<TtffFixtureActivity> scenario) {
        AtomicReference<Snapshot> value = new AtomicReference<>();
        scenario.onActivity(a -> value.set(a.snapshot()));
        return value.get();
    }

    private static void report(String phase, Snapshot snapshot, long progressMs) {
        Bundle metrics = new Bundle();
        metrics.putString("fixturePhase", phase);
        metrics.putLong("firstFrameMs", snapshot.firstFrameMs);
        metrics.putLong("readyMs", snapshot.readyMs);
        metrics.putLong("positionMs", snapshot.positionMs);
        metrics.putLong("progressMs", progressMs);
        metrics.putInt("frames", snapshot.frames);
        metrics.putInt("dropped", snapshot.dropped);
        metrics.putInt("errors", snapshot.errors);
        metrics.putInt("rebuffers", snapshot.unexpectedBuffering);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, metrics);
    }

    private static void assertHealthy(Snapshot snapshot) {
        assertEquals("unexpected media error:" + snapshot.error, 0, snapshot.errors);
        assertEquals("unplanned rebuffer", 0, snapshot.unexpectedBuffering);
    }
}
