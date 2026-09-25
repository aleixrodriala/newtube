package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PerVideoResultCacheTest {
    private final PerVideoResultCache<String> mCache = new PerVideoResultCache<>(2);

    @Test
    public void oneFetchPerVideoOnceTheResultIsIn() {
        assertTrue(mCache.tryBeginFetch("live"));
        mCache.put("live", "counts");

        // Every later live refresh of the same video is served from the slot.
        for (int refresh = 0; refresh < 10; refresh++) {
            assertEquals("counts", mCache.get("live"));
            assertFalse(mCache.tryBeginFetch("live"));
        }
    }

    @Test
    public void failedFetchesAreRetriedOnlyUpToTheBudget() {
        assertTrue(mCache.tryBeginFetch("live"));
        assertTrue(mCache.tryBeginFetch("live")); // first attempt failed or was disposed
        assertFalse(mCache.tryBeginFetch("live"));
        assertNull(mCache.get("live"));
    }

    @Test
    public void anotherVideoTakesTheSlotAndALateAnswerCannotOverwriteIt() {
        assertTrue(mCache.tryBeginFetch("old"));
        assertTrue(mCache.tryBeginFetch("new"));
        mCache.put("old", "stale counts"); // the previous video's answer lands late
        assertNull(mCache.get("new"));
        assertNull(mCache.get("old"));

        mCache.put("new", "counts");
        assertEquals("counts", mCache.get("new"));

        // Back to the first video: a fresh budget.
        assertTrue(mCache.tryBeginFetch("old"));
        assertNull(mCache.get("new"));
    }

    @Test
    public void nullIdsAreNeverCachedButStillFetch() {
        assertTrue(mCache.tryBeginFetch(null));
        mCache.put(null, "counts");
        assertNull(mCache.get(null));
        assertTrue(mCache.tryBeginFetch(null));
    }

    @Test
    public void nullResultDoesNotCount() {
        assertTrue(mCache.tryBeginFetch("live"));
        mCache.put("live", null);
        assertNull(mCache.get("live"));
        assertTrue(mCache.tryBeginFetch("live"));
    }

    @Test
    public void clearEmptiesTheSlot() {
        mCache.tryBeginFetch("live");
        mCache.put("live", "counts");
        mCache.clear();
        assertNull(mCache.get("live"));
        assertTrue(mCache.tryBeginFetch("live"));
    }
}
