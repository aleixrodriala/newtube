package com.newtube.mobile.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Exercises the actual stash used by controller publication and sample-preload ownership. */
public class SourceStashTest {
    private final SourceStash<Object> stash = new SourceStash<>();
    private final Object first = new Object();
    private final Object second = new Object();

    @Test
    public void duplicateSameVideoPublicationRetainsTheExactPreloadingSource() {
        assertTrue(stash.offerIfAbsent("next", first));
        assertFalse(stash.offerIfAbsent("next", second));
        assertTrue(stash.containsSource(first));
        assertFalse(stash.containsSource(second));
        assertSame(first, stash.take("next"));
    }

    @Test
    public void differentVideoReplacesThePrediction() {
        stash.offerIfAbsent("next", first);
        assertTrue(stash.offerIfAbsent("different", second));
        assertNull(stash.take("next"));
        assertSame(second, stash.take("different"));
    }

    @Test
    public void exactMatchIsConsumedAtMostOnceAndMismatchDoesNotConsume() {
        stash.offerIfAbsent("next", first);
        assertNull(stash.take(null));
        assertNull(stash.take("different"));
        assertTrue(stash.hasSource());
        assertSame(first, stash.take("next"));
        assertNull(stash.take("next"));
        assertFalse(stash.hasSource());
    }

    @Test
    public void matchingResetPreservesSourceButUnrelatedOrUnknownResetDropsIt() {
        stash.offerIfAbsent("next", first);
        stash.dropExcept("next");
        assertTrue(stash.containsSource(first));
        stash.dropExcept("different");
        assertFalse(stash.hasSource());
        stash.offerIfAbsent("next", second);
        stash.dropExcept(null);
        assertFalse(stash.hasSource());
    }

    @Test
    public void oldPreloadCancellationCannotDiscardANewerPrediction() {
        stash.offerIfAbsent("next", first);
        stash.offerIfAbsent("different", second);
        stash.discard(first);
        assertSame(second, stash.take("different"));
    }

    @Test
    public void cancelledPreparedSourceAllowsFreshXmlOnlyFallback() {
        stash.offerIfAbsent("next", first);
        stash.discard(first);
        assertFalse(stash.hasSource());
        assertTrue(stash.offerIfAbsent("next", second));
        assertSame(second, stash.take("next"));
    }

    @Test
    public void oldGenerationCannotRepublishAfterClearOrConsume() {
        SourceBuildGeneration generation = new SourceBuildGeneration();
        int old = generation.current();
        generation.publishIfCurrent(old, () -> stash.offerIfAbsent("next", first));
        generation.invalidate(stash::clear);
        generation.publishIfCurrent(old, () -> stash.offerIfAbsent("next", second));
        assertFalse(stash.hasSource());
        generation.publishIfCurrent(generation.current(), () -> stash.offerIfAbsent("next", first));
        assertSame(first, stash.take("next"));
        generation.next();
        generation.publishIfCurrent(old, () -> stash.offerIfAbsent("next", second));
        assertFalse(stash.hasSource());
    }

    @Test
    public void queuedSameGenerationDuplicateCannotReplaceCompletedMatchingStash() {
        SourceBuildGeneration generation = new SourceBuildGeneration();
        int ticket = generation.current();
        generation.publishIfCurrent(ticket, () -> stash.offerIfAbsent("next", first));
        generation.publishIfCurrent(ticket, () -> stash.offerIfAbsent("next", second));
        generation.invalidate(() -> stash.dropExcept("next"));
        generation.publishIfCurrent(ticket, () -> stash.offerIfAbsent("next", second));
        assertSame(first, stash.take("next"));
    }
}
