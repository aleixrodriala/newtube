package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The info -> prepare split printed by the source-build NetPath line. */
public class SourceBuildTimingTest {
    @Test
    public void describesEveryStage() {
        SourceBuildTiming timing = new SourceBuildTiming();
        timing.queuedAtMs = 1_000;
        timing.queuedAtOpenMs = 281;
        timing.startedAtMs = 1_004;
        timing.genMs = 9;
        timing.parseMs = 14;
        timing.builtAtMs = 1_028;
        assertEquals("queuedAt=+281 queueMs=4 genMs=9 parseMs=14 buildMs=24 deliverMs=17", timing.describe(1_045));
    }

    @Test
    public void unsetStagesAreMinusOne() {
        SourceBuildTiming timing = new SourceBuildTiming();
        timing.queuedAtMs = 1_000;
        timing.startedAtMs = 1_002;
        timing.builtAtMs = 1_010;
        // A non-MPD builder (merged/HLS) has no XML split.
        assertEquals("queuedAt=-1 queueMs=2 genMs=-1 parseMs=-1 buildMs=8 deliverMs=-1", timing.describe(900));
        assertEquals("queuedAt=-1 queueMs=-1 genMs=-1 parseMs=-1 buildMs=-1 deliverMs=-1",
                new SourceBuildTiming().describe(5_000));
    }
}
