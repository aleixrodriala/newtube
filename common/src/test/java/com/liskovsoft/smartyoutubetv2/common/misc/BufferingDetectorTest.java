package com.liskovsoft.smartyoutubetv2.common.misc;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;

import java.time.Duration;

/** Runs the real watchdog and main-thread timer through playback callback sequences. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class BufferingDetectorTest {
    private int callbacks;
    private BufferingDetector detector;

    @Before
    public void setUp() {
        detector = new BufferingDetector(() -> callbacks++);
    }

    @After
    public void tearDown() {
        detector.reset();
    }

    @Test
    public void continuousStallFiresOnceAtTwentySeconds() {
        detector.onStartBuffering();
        advance(19_999);
        assertEquals(0, callbacks);
        advance(1);
        assertEquals(1, callbacks);
        advance(60_000);
        assertEquals(1, callbacks);
    }

    @Test
    public void playAndPauseDoNotCountHealthyPlaybackAsAnotherStall() {
        detector.onStartBuffering();
        advance(5_000);
        detector.onStopBuffering(); // onPlay
        advance(10_000);
        detector.onStopBuffering(); // onPause, still no new buffering
        detector.onStartBuffering();
        advance(14_999);
        assertEquals(0, callbacks);
        advance(1);
        assertEquals(1, callbacks);
    }

    @Test
    public void duplicateStopCannotDisableTheNextWatchdogWithANegativeDelay() {
        detector.onStartBuffering();
        advance(5_000);
        detector.onStopBuffering();
        advance(25_000);
        detector.onStopBuffering();
        detector.onStartBuffering();
        advance(15_000);
        assertEquals(1, callbacks);
    }

    @Test
    public void repeatedBufferingStateDoesNotPostponeAnActiveWatchdog() {
        detector.onStartBuffering();
        advance(12_000);
        detector.onStartBuffering();
        advance(7_999);
        assertEquals(0, callbacks);
        advance(1);
        assertEquals(1, callbacks);
    }

    @Test
    public void seekResetCancelsOldTimerAndDiscardsItsDuration() {
        detector.onStartBuffering();
        advance(15_000);
        detector.reset();
        detector.onStopBuffering();
        advance(25_000);
        assertEquals(0, callbacks);
        detector.onStartBuffering();
        advance(19_999);
        assertEquals(0, callbacks);
        advance(1);
        assertEquals(1, callbacks);
    }

    @Test
    public void stallsOutsideTheMinuteWindowStartWithAFreshBudget() {
        detector.onStartBuffering();
        advance(15_000);
        detector.onStopBuffering();
        advance(45_001);
        detector.onStartBuffering();
        advance(19_999);
        assertEquals(0, callbacks);
        advance(1);
        assertEquals(1, callbacks);
    }

    @Test
    public void overdueTimerStillFiresWhenMainThreadCallbacksWereDelayed() {
        detector.onStartBuffering();
        // Advance the clock without dispatching Handler work, as a busy main thread would.
        SystemClock.sleep(21_000);
        detector.onStopBuffering();
        detector.onStartBuffering();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, callbacks);
    }

    private void advance(long milliseconds) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds));
    }
}
