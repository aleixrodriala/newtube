package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The autoplay-next /player deadline: wall time before the end, after real playback. */
public class NextPrefetchPolicyTest {
    private static final long WATCHED = 60_000;

    @Test
    public void longVideoIsDueTwentyWallSecondsBeforeTheEnd() {
        // 10 min video at 5:00: 300 s remain, 280 s until the 20 s lead.
        assertEquals(280_000, NextPrefetchPolicy.delayUntilDueMs(600_000, 300_000, 1f, WATCHED));
        assertFalse(NextPrefetchPolicy.isDue(600_000, 579_999, 1f, WATCHED));
        assertTrue(NextPrefetchPolicy.isDue(600_000, 580_000, 1f, WATCHED));
    }

    @Test
    public void speedShrinksTheWallTimeLeft() {
        // 30 s of content at 2x is 15 s of wall time: already inside the 20 s lead.
        assertTrue(NextPrefetchPolicy.isDue(600_000, 570_000, 2f, WATCHED));
        // 100 s of content at 2x = 50 s wall -> due in 30 s of WALL time.
        assertEquals(30_000, NextPrefetchPolicy.delayUntilDueMs(600_000, 500_000, 2f, WATCHED));
        // 0.5x stretches it: 30 s of content = 60 s wall -> 40 s to wait.
        assertEquals(40_000, NextPrefetchPolicy.delayUntilDueMs(600_000, 570_000, 0.5f, WATCHED));
    }

    @Test
    public void shortVideoWaitsForRealPlaybackFirst() {
        // A 15 s clip is "within the lead" from its first frame; skimming must not resolve its next.
        assertEquals(5_000, NextPrefetchPolicy.delayUntilDueMs(15_000, 0, 1f, 0));
        assertTrue(NextPrefetchPolicy.isDue(15_000, 5_000, 1f, 5_000));
        // Tiny clips: half the video is enough.
        assertEquals(3_000, NextPrefetchPolicy.delayUntilDueMs(6_000, 0, 1f, 0));
        // At 2x the clip lasts 7.5 s of wall time: half of it, 3.75 s, is enough.
        assertEquals(3_750, NextPrefetchPolicy.delayUntilDueMs(15_000, 0, 2f, 0));
    }

    @Test
    public void aSeekIsNotPlayback() {
        // Watch 1 s of a 15 s clip, seek to 14 s: the playhead says "done", the viewer did not.
        assertEquals(4_000, NextPrefetchPolicy.delayUntilDueMs(15_000, 14_000, 1f, 1_000));
        // Open a long video and jump straight to its last seconds: still wait for 5 s of playing.
        assertEquals(5_000, NextPrefetchPolicy.delayUntilDueMs(600_000, 595_000, 1f, 0));
    }

    @Test
    public void seekIntoTheLastSecondsAfterWatchingIsDueAtOnce() {
        assertEquals(0, NextPrefetchPolicy.delayUntilDueMs(600_000, 595_000, 1f, WATCHED));
        assertEquals(0, NextPrefetchPolicy.delayUntilDueMs(600_000, 600_000, 1f, WATCHED));
        assertEquals(0, NextPrefetchPolicy.delayUntilDueMs(600_000, 700_000, 1f, WATCHED));
    }

    @Test
    public void unknownTimelineNeverSchedules() {
        assertEquals(NextPrefetchPolicy.NEVER, NextPrefetchPolicy.delayUntilDueMs(-1, 0, 1f, WATCHED));
        assertEquals(NextPrefetchPolicy.NEVER, NextPrefetchPolicy.delayUntilDueMs(0, 0, 1f, WATCHED));
        assertEquals(NextPrefetchPolicy.NEVER, NextPrefetchPolicy.delayUntilDueMs(600_000, -1, 1f, WATCHED));
        assertFalse(NextPrefetchPolicy.isDue(-1, 0, 1f, WATCHED));
    }

    @Test
    public void unknownSpeedCountsAsNormal() {
        assertEquals(280_000, NextPrefetchPolicy.delayUntilDueMs(600_000, 300_000, -1f, WATCHED));
        assertEquals(280_000, NextPrefetchPolicy.delayUntilDueMs(600_000, 300_000, 0f, WATCHED));
    }
}
