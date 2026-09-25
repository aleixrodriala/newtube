package com.liskovsoft.smartyoutubetv2.common.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LiveStartPollPolicyTest {
    private static final long SEC = 1_000;
    private static final long MIN = 60 * SEC;
    private static final long NOW = 1_800_000_000_000L; // any wall-clock instant
    private final LiveStartPollPolicy mPlayer = LiveStartPollPolicy.PLAYER;
    private final LiveStartPollPolicy mReminder = LiveStartPollPolicy.REMINDER;

    @Test
    public void farFutureStartPollsMinutesApartUpToTheCap() {
        assertEquals(10 * MIN, mPlayer.delayMs(NOW, NOW + 2 * 60 * MIN, 0));
        assertEquals(30 * MIN, mReminder.delayMs(NOW, NOW + 2 * 24 * 60 * MIN, 0));
        assertEquals(5 * MIN, mPlayer.delayMs(NOW, NOW + 20 * MIN, 0));
        assertEquals(15 * MIN, mReminder.delayMs(NOW, NOW + 60 * MIN, 0));
    }

    @Test
    public void nearStartReachesTheFloor() {
        assertEquals(45 * SEC, mPlayer.delayMs(NOW, NOW + 3 * MIN, 5));
        assertEquals(30 * SEC, mPlayer.delayMs(NOW, NOW + MIN, 50)); // streak ignored with a schedule
        assertEquals(60 * SEC, mReminder.delayMs(NOW, NOW + 3 * MIN, 0));
    }

    @Test
    public void knownStartNeverSleepsPastIt() {
        for (LiveStartPollPolicy policy : new LiveStartPollPolicy[] {mPlayer, mReminder}) {
            long floor = policy.delayMs(NOW, NOW - MIN, 0);
            for (long untilStart = SEC; untilStart < 3 * 24 * 60 * MIN; untilStart += 17 * SEC) {
                long delay = policy.delayMs(NOW, NOW + untilStart, 0);
                assertTrue("until=" + untilStart + " delay=" + delay, delay == floor || delay <= untilStart);
            }
        }
    }

    @Test
    public void lateStreamKeepsTheFloorThenSlowsDown() {
        assertEquals(30 * SEC, mPlayer.delayMs(NOW, NOW - 5 * MIN, 20));
        assertEquals(60 * SEC, mPlayer.delayMs(NOW, NOW - 30 * MIN, 20));
        assertEquals(2 * MIN, mPlayer.delayMs(NOW, NOW - 3 * 60 * MIN, 20));

        assertEquals(60 * SEC, mReminder.delayMs(NOW, NOW - 14 * MIN, 0));
        assertEquals(2 * MIN, mReminder.delayMs(NOW, NOW - 16 * MIN, 0));
        assertEquals(5 * MIN, mReminder.delayMs(NOW, NOW - 2 * 60 * MIN, 0));
    }

    @Test
    public void unknownStartEscalatesWithTheNotLiveStreak() {
        for (int answers = 0; answers < 10; answers++) {
            assertEquals(30 * SEC, mPlayer.delayMs(NOW, 0, answers));
            assertEquals(60 * SEC, mReminder.delayMs(NOW, 0, answers));
        }
        assertEquals(60 * SEC, mPlayer.delayMs(NOW, 0, 10));
        assertEquals(60 * SEC, mPlayer.delayMs(NOW, 0, 29));
        assertEquals(2 * MIN, mPlayer.delayMs(NOW, 0, 30));
        assertEquals(2 * MIN, mPlayer.delayMs(NOW, 0, 10_000));

        assertEquals(2 * MIN, mReminder.delayMs(NOW, 0, 10));
        assertEquals(5 * MIN, mReminder.delayMs(NOW, 0, 30));
    }

    @Test
    public void hiddenSlateDoublesWithinBounds() {
        assertEquals(60 * SEC, LiveStartPollPolicy.backgroundDelayMs(30 * SEC));
        assertEquals(60 * SEC, LiveStartPollPolicy.backgroundDelayMs(10 * SEC));
        assertEquals(20 * MIN, LiveStartPollPolicy.backgroundDelayMs(10 * MIN));
        assertEquals(30 * MIN, LiveStartPollPolicy.backgroundDelayMs(20 * MIN));
        assertEquals(40 * MIN, LiveStartPollPolicy.backgroundDelayMs(40 * MIN)); // never shorter
    }

    @Test
    public void startLabel() {
        assertEquals("-", LiveStartPollPolicy.startInLabel(NOW, 0));
        assertEquals("90s", LiveStartPollPolicy.startInLabel(NOW, NOW + 90 * SEC));
        assertEquals("-60s", LiveStartPollPolicy.startInLabel(NOW, NOW - MIN));
    }
}
