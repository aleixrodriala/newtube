package com.newtube.mobile.ui.common;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FeedRunwayTest {
    private static final int LOOKAHEAD = 16;
    private static final int NO_POSITION = -1;

    @Test
    public void anEmptyGridAlwaysNeedsMore() {
        // Home's first pages were all Shorts/channel shelves: nothing laid out, nothing to scroll.
        assertTrue(FeedRunway.isShort(NO_POSITION, 0, LOOKAHEAD));
    }

    @Test
    public void aGridSmallerThanAScreenPlusTheLookaheadNeedsMore() {
        assertTrue(FeedRunway.isShort(2, 10, LOOKAHEAD));
        assertTrue(FeedRunway.isShort(NO_POSITION, 12, LOOKAHEAD)); // not laid out yet
        assertTrue(FeedRunway.isShort(2, 18, LOOKAHEAD));
    }

    @Test
    public void aFullFirstPageAtTheTopDoesNot() {
        assertFalse(FeedRunway.isShort(2, 40, LOOKAHEAD));
        assertFalse(FeedRunway.isShort(NO_POSITION, 40, LOOKAHEAD));
        assertFalse(FeedRunway.isShort(2, 19, LOOKAHEAD));
    }

    @Test
    public void scrollingToWithinTheLookaheadOfTheEndNeedsMore() {
        assertFalse(FeedRunway.isShort(23, 40, LOOKAHEAD));
        assertTrue(FeedRunway.isShort(24, 40, LOOKAHEAD));
        assertTrue(FeedRunway.isShort(39, 40, LOOKAHEAD));
    }
}
