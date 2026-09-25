package com.liskovsoft.smartyoutubetv2.common.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ReminderPollScheduleTest {
    private static final long SEC = 1_000;
    private static final long MIN = 60 * SEC;
    private static final long T0 = 1_800_000_000_000L - (1_800_000_000_000L % MIN); // a minute boundary

    private final ReminderPollSchedule mSchedule = new ReminderPollSchedule(LiveStartPollPolicy.REMINDER);

    @Test
    public void newReminderIsDueImmediately() {
        assertTrue(mSchedule.isDue("a", T0));
    }

    @Test
    public void floorDelayIsDueOnTheVeryNextTickDespiteJitter() {
        mSchedule.onNotLive("a", T0, 0);
        assertFalse(mSchedule.isDue("a", T0 + 30 * SEC));
        assertTrue(mSchedule.isDue("a", T0 + MIN - 3)); // the handler fired a few ms early
        assertTrue(mSchedule.isDue("a", T0 + MIN));
    }

    @Test
    public void farStartCostsFewChecksAndStillFiresOnTime() {
        long start = T0 + 6 * 60 * MIN;
        List<Long> checks = simulateTicks("a", T0, start + 2 * MIN, start);

        // Every minute for 6 h used to be 362 checks.
        assertTrue("checks=" + checks.size(), checks.size() < 40);
        // Prompt near the start: every tick of the last minutes and right after it.
        assertTrue(checks.contains(start));
        assertTrue(checks.contains(start - MIN));
        assertTrue(checks.contains(start + MIN));
    }

    @Test
    public void unknownStartBacksOffAfterRepeatedNotLiveAnswers() {
        List<Long> checks = simulateTicks("a", T0, T0 + 3 * 60 * MIN, 0);

        assertEquals(T0 + 9 * MIN, (long) checks.get(9)); // first ten at every tick
        assertEquals(2 * MIN, checks.get(11) - checks.get(10));
        assertEquals(5 * MIN, checks.get(checks.size() - 1) - checks.get(checks.size() - 2));
        assertTrue("checks=" + checks.size(), checks.size() <= 60); // was 181
    }

    @Test
    public void failedCheckKeepsTheKnownStart() {
        long start = T0 + 60 * MIN;
        mSchedule.onNotLive("a", T0, start);
        long delay = mSchedule.onNotLive("a", T0 + 15 * MIN, 0); // e.g. no network: no start info
        assertEquals(start, mSchedule.getScheduledStartMs("a"));
        assertEquals((start - (T0 + 15 * MIN)) / 4, delay);
        assertEquals(2, mSchedule.getNotLiveAnswers("a"));
    }

    @Test
    public void removedRemindersForgetTheirState() {
        mSchedule.onNotLive("a", T0, T0 + 60 * MIN);
        mSchedule.onNotLive("b", T0, T0 + 60 * MIN);
        mSchedule.retainOnly(Collections.singleton("b"));
        assertTrue(mSchedule.isDue("a", T0 + SEC));
        assertEquals(0, mSchedule.getNotLiveAnswers("a"));
        assertFalse(mSchedule.isDue("b", T0 + SEC));

        mSchedule.remove("b");
        assertTrue(mSchedule.isDue("b", T0 + SEC));
    }

    /** Minute ticks from {@code from} to {@code until}; every due check answers "not live". */
    private List<Long> simulateTicks(String videoId, long from, long until, long scheduledStart) {
        List<Long> checks = new ArrayList<>();
        for (long tick = from; tick <= until; tick += MIN) {
            if (mSchedule.isDue(videoId, tick)) {
                checks.add(tick);
                mSchedule.onNotLive(videoId, tick, scheduledStart);
            }
        }
        return checks;
    }
}
