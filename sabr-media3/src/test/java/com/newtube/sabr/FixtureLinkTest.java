package com.newtube.sabr;

import static org.junit.Assert.*;
import org.junit.Test;

public class FixtureLinkTest {
    @Test public void audioAndVideoConsumeOneBudgetWithoutPerTrackBursts() {
        FixtureLink link = new FixtureLink(80, 1000); // 125,000 bytes/second shared.
        long now = 1_000_000_000L;
        assertEquals(now + 100_000_000L, link.reserve(12500, now));
        assertEquals(now + 200_000_000L, link.reserve(12500, now));
        assertEquals(now + 200_008_000L, link.reserve(1, now));
    }

    @Test public void idleTimeDoesNotAccumulateUnlimitedBurstCredit() {
        FixtureLink link = new FixtureLink(80, 1000);
        link.reserve(12500, 1_000_000_000L);
        assertEquals(10_100_000_000L, link.reserve(12500, 10_000_000_000L));
    }

    @Test public void roundsUpRatherThanDroppingTinyTransfers() {
        FixtureLink link = new FixtureLink(0, 20000);
        assertEquals(400, link.reserve(1, 0));
        assertEquals(800, link.reserve(1, 0));
    }
}
