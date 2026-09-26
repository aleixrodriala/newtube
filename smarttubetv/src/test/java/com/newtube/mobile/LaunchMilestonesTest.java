package com.newtube.mobile;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** Deferred startup work runs once: after the first frame, or at its fallback when nothing draws. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class LaunchMilestonesTest {
    @Before
    public void reset() {
        LaunchMilestones.resetForTest();
    }

    @Test
    public void workQueuedAtStartupWaitsForTheFirstFrame() {
        AtomicInteger runs = new AtomicInteger();
        LaunchMilestones.runAfterFirstFrame(4_000, runs::incrementAndGet);

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertEquals(0, runs.get());

        LaunchMilestones.onFirstFrame("MobileBrowseActivity");
        assertEquals(0, runs.get()); // posted, not run inside the draw callback
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, runs.get());

        // The fallback timer that was armed at startup must not run it a second time.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_000));
        assertEquals(1, runs.get());
    }

    @Test
    public void fallbackRunsItWhenNoActivityEverDraws() {
        AtomicInteger runs = new AtomicInteger();
        LaunchMilestones.runAfterFirstFrame(4_000, runs::incrementAndGet);

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3_999));
        assertEquals(0, runs.get());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2));
        assertEquals(1, runs.get());

        // A late first frame does not repeat it.
        LaunchMilestones.onFirstFrame("MobileBrowseActivity");
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, runs.get());
    }

    @Test
    public void workQueuedAfterTheFirstFrameRunsOnTheNextLoop() {
        LaunchMilestones.onFirstFrame("MobilePlaybackActivity");
        AtomicInteger runs = new AtomicInteger();

        LaunchMilestones.runAfterFirstFrame(4_000, runs::incrementAndGet);
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, runs.get());
    }

    @Test
    public void onlyTheFirstFrameCounts() {
        AtomicInteger runs = new AtomicInteger();
        LaunchMilestones.runAfterFirstFrame(4_000, runs::incrementAndGet);

        LaunchMilestones.onFirstFrame("MobileBrowseActivity");
        LaunchMilestones.onFirstFrame("MobileBrowseActivity");
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, runs.get());
    }
}
