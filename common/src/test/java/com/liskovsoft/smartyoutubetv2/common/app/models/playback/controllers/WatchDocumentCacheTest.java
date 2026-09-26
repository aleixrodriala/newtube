package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/** The kept /next document serves a re-opened page only while it is the same, recent answer. */
public class WatchDocumentCacheTest {
    private static final String DOC_A = "doc-a";
    private static final String DOC_B = "doc-b";

    private static String key(String videoId) {
        return WatchDocumentCache.key(videoId, null, -1, null, "acc");
    }

    @Test
    public void servesTheSameDocumentForTheSameKeyWhileYoung() {
        WatchDocumentCache<String> cache = new WatchDocumentCache<>(1_000);
        cache.put("a", key("a"), DOC_A, 10_000);

        assertSame(DOC_A, cache.get(key("a"), 10_000));
        assertSame("a hit is a peek: a second re-open is served too", DOC_A, cache.get(key("a"), 10_900));
        assertEquals(900, cache.ageMs(10_900));
    }

    @Test
    public void storingTheSameDocumentAgainKeepsItsAge() {
        WatchDocumentCache<String> cache = new WatchDocumentCache<>(1_000);
        cache.put("a", key("a"), DOC_A, 10_000);
        cache.put("a", key("a"), DOC_A, 10_800); // the re-bind is delivered (and stored) again

        assertEquals(800, cache.ageMs(10_800));
        assertNull("a re-bind never extends the answer's lifetime", cache.get(key("a"), 11_001));
    }

    @Test
    public void aNewAnswerForTheSameVideoRestartsTheAge() {
        WatchDocumentCache<String> cache = new WatchDocumentCache<>(1_000);
        cache.put("a", key("a"), DOC_A, 10_000);
        cache.put("a", key("a"), "doc-a-fresh", 10_800);

        assertEquals("doc-a-fresh", cache.get(key("a"), 11_500));
    }

    @Test
    public void expiresAndForgetsAnOldAnswer() {
        WatchDocumentCache<String> cache = new WatchDocumentCache<>(1_000);
        cache.put("a", key("a"), DOC_A, 10_000);

        assertNull(cache.get(key("a"), 11_001));
        assertNull("an expired entry is dropped, not served later", cache.get(key("a"), 10_500));
        assertEquals(-1, cache.ageMs(10_500));
    }

    @Test
    public void clockGoingBackwardsIsTreatedAsExpired() {
        WatchDocumentCache<String> cache = new WatchDocumentCache<>(1_000);
        cache.put("a", key("a"), DOC_A, 10_000);

        assertNull(cache.get(key("a"), 9_000));
    }

    @Test
    public void anotherVideoEvictsTheSlot() {
        WatchDocumentCache<String> cache = new WatchDocumentCache<>(60_000);
        cache.put("a", key("a"), DOC_A, 0);
        cache.put("b", key("b"), DOC_B, 10);

        assertNull("A -> related B -> back -> A must fetch A again", cache.get(key("a"), 20));
        assertSame(DOC_B, cache.get(key("b"), 20));
    }

    @Test
    public void playlistContextAndAccountArePartOfTheKey() {
        WatchDocumentCache<String> cache = new WatchDocumentCache<>(60_000);
        cache.put("a", key("a"), DOC_A, 0);

        assertNull(cache.get(WatchDocumentCache.key("a", "PL1", 3, null, "acc"), 1));
        assertNull(cache.get(WatchDocumentCache.key("a", null, -1, "params", "acc"), 1));
        assertNull(cache.get(WatchDocumentCache.key("a", null, -1, null, "other"), 1));
        assertNull(cache.get(WatchDocumentCache.key("a", null, -1, null, null), 1));
        assertSame(DOC_A, cache.get(key("a"), 1));
        assertNotEquals(WatchDocumentCache.key("a", null, -1, null, null),
                WatchDocumentCache.key("a", null, -1, null, "acc"));
    }

    @Test
    public void invalidateDropsOnlyThatVideo() {
        WatchDocumentCache<String> cache = new WatchDocumentCache<>(60_000);
        cache.put("a", key("a"), DOC_A, 0);

        cache.invalidate("b");
        assertSame(DOC_A, cache.get(key("a"), 1));

        cache.invalidate(null);
        assertSame(DOC_A, cache.get(key("a"), 1));

        cache.invalidate("a");
        assertNull(cache.get(key("a"), 1));
    }

    @Test
    public void nullsAreNeverStored() {
        WatchDocumentCache<String> cache = new WatchDocumentCache<>(60_000);
        cache.put(null, key("a"), DOC_A, 0);
        cache.put("a", null, DOC_A, 0);
        cache.put("a", key("a"), null, 0);

        assertNull(cache.get(key("a"), 1));
        assertNull(cache.get(null, 1));
    }

    @Test
    public void defaultLifetimeIsHomesFreshnessWindow() {
        WatchDocumentCache<String> cache = new WatchDocumentCache<>();
        cache.put("a", key("a"), DOC_A, 0);

        assertSame(DOC_A, cache.get(key("a"), 5 * 60_000L));
        assertNull(cache.get(key("a"), 5 * 60_000L + 1));
    }
}
