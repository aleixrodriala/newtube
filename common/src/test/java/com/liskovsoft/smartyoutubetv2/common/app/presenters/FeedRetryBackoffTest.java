package com.liskovsoft.smartyoutubetv2.common.app.presenters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FeedRetryBackoffTest {
    private static final int HOME = 1;
    private static final int SUBS = 2;

    private final FeedRetryBackoff mBackoff = new FeedRetryBackoff();

    @Test
    public void sameSectionEscalatesAndStaysAtTheCap() {
        assertEquals(30_000, mBackoff.onFailure(HOME, 0));
        assertEquals(60_000, mBackoff.onFailure(HOME, 30_000));
        assertEquals(120_000, mBackoff.onFailure(HOME, 90_000));
        assertEquals(300_000, mBackoff.onFailure(HOME, 210_000));
        assertEquals(300_000, mBackoff.onFailure(HOME, 510_000));
        assertEquals(5, mBackoff.getFailures());
        assertTrue(mBackoff.isInError(HOME));
        assertFalse(mBackoff.isInError(SUBS));
    }

    @Test
    public void successEndsTheEpisode() {
        assertFalse(mBackoff.onSuccess()); // nothing to recover from
        mBackoff.onFailure(HOME, 0);
        mBackoff.onFailure(HOME, 30_000);
        assertTrue(mBackoff.onSuccess());
        assertFalse(mBackoff.isInError());
        assertEquals(0, mBackoff.getFailures());
        assertEquals(30_000, mBackoff.onFailure(HOME, 100_000));
    }

    @Test
    public void anotherSectionStartsItsOwnEpisode() {
        mBackoff.onFailure(HOME, 0);
        mBackoff.onFailure(HOME, 30_000);
        assertEquals(30_000, mBackoff.onFailure(SUBS, 40_000));
        assertTrue(mBackoff.isInError(SUBS));
        assertFalse(mBackoff.isInError(HOME));
    }

    @Test
    public void networkEdgeRefillsButKeepsTheErrorState() {
        mBackoff.onFailure(HOME, 0);
        mBackoff.onFailure(HOME, 30_000);
        mBackoff.onFailure(HOME, 90_000);
        mBackoff.resetEscalation();
        assertTrue(mBackoff.isInError(HOME));
        assertEquals(30_000, mBackoff.onFailure(HOME, 100_000));
    }

    @Test
    public void resumeRetryIsPromptButNotAnInstantRepeat() {
        mBackoff.onFailure(HOME, 10_000);
        assertEquals(5_000, mBackoff.resumeDelayMs(10_000)); // the failure just happened
        assertEquals(2_000, mBackoff.resumeDelayMs(13_000));
        assertEquals(1_000, mBackoff.resumeDelayMs(14_500)); // debounce floor
        assertEquals(1_000, mBackoff.resumeDelayMs(600_000));
    }

    @Test
    public void clearForgetsTheSection() {
        mBackoff.onFailure(HOME, 0);
        mBackoff.clear();
        assertFalse(mBackoff.isInError());
        assertEquals(FeedRetryBackoff.NO_SECTION, mBackoff.getSectionId());
    }
}
