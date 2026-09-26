package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.Application;
import android.view.MotionEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.ArrayList;
import java.util.List;

/** A resting finger becomes a prefetch; a scroll, a quick tap or a cancel never does. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PressIntentDetectorTest {
    private final List<Runnable> scheduled = new ArrayList<>();
    private int intents;
    private PressIntentDetector detector;

    @Before
    public void setUp() {
        detector = new PressIntentDetector(20f, 60, new PressIntentDetector.Scheduler() {
            @Override
            public void postDelayed(Runnable task, long delayMs) {
                assertEquals(60, delayMs);
                scheduled.add(task);
            }

            @Override
            public void remove(Runnable task) {
                scheduled.remove(task);
            }
        }, () -> intents++);
    }

    @Test
    public void restingFingerFiresOnce() {
        assertFalse(touch(MotionEvent.ACTION_DOWN, 100, 100));
        assertFalse(touch(MotionEvent.ACTION_MOVE, 105, 110)); // jitter inside the slop
        runScheduled();
        runScheduled();
        assertEquals(1, intents);
        touch(MotionEvent.ACTION_UP, 105, 110); // the click that follows does its own work
        assertEquals(1, intents);
    }

    @Test
    public void scrollPastTheSlopNeverFires() {
        touch(MotionEvent.ACTION_DOWN, 100, 100);
        touch(MotionEvent.ACTION_MOVE, 100, 130);
        runScheduled();
        assertEquals(0, intents);
    }

    @Test
    public void quickTapLeavesTheWorkToTheClick() {
        touch(MotionEvent.ACTION_DOWN, 100, 100);
        touch(MotionEvent.ACTION_UP, 100, 100);
        runScheduled();
        assertEquals(0, intents);
    }

    @Test
    public void parentTakeoverCancels() {
        touch(MotionEvent.ACTION_DOWN, 100, 100);
        touch(MotionEvent.ACTION_CANCEL, 100, 100);
        runScheduled();
        assertEquals(0, intents);
    }

    @Test
    public void everyNewPressIsJudgedAlone() {
        touch(MotionEvent.ACTION_DOWN, 100, 100);
        touch(MotionEvent.ACTION_MOVE, 100, 200);
        touch(MotionEvent.ACTION_DOWN, 300, 300);
        runScheduled();
        assertEquals(1, intents);
    }

    @Test
    public void staleTimerAfterDisarmDoesNothing() {
        touch(MotionEvent.ACTION_DOWN, 100, 100);
        Runnable stale = scheduled.get(0);
        detector.disarm();
        stale.run(); // a Handler that already dequeued it
        assertEquals(0, intents);
    }

    private boolean touch(int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, x, y, 0);
        try {
            return detector.onTouch(event);
        } finally {
            event.recycle();
        }
    }

    private void runScheduled() {
        List<Runnable> due = new ArrayList<>(scheduled);
        scheduled.clear();
        for (Runnable task : due) {
            task.run();
        }
    }
}
