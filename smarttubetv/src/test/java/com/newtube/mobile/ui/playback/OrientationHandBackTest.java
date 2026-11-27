package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.res.Configuration;

import org.junit.Test;

/** NEWTUBE(fullscreen): the fullscreen button's forced orientation goes back to the sensor. */
public class OrientationHandBackTest {
    private static final int LANDSCAPE = Configuration.ORIENTATION_LANDSCAPE;
    private static final int PORTRAIT = Configuration.ORIENTATION_PORTRAIT;

    @Test
    public void landscapeIsEitherSidePortraitIsUpright() {
        assertTrue(OrientationHandBack.matches(LANDSCAPE, 90));
        assertTrue(OrientationHandBack.matches(LANDSCAPE, 270));
        assertTrue(OrientationHandBack.matches(LANDSCAPE, 65));
        assertFalse(OrientationHandBack.matches(LANDSCAPE, 0));
        assertFalse(OrientationHandBack.matches(LANDSCAPE, 180));

        assertTrue(OrientationHandBack.matches(PORTRAIT, 0));
        assertTrue(OrientationHandBack.matches(PORTRAIT, 350));
        assertTrue(OrientationHandBack.matches(PORTRAIT, 20));
        assertFalse(OrientationHandBack.matches(PORTRAIT, 90));
        assertFalse(OrientationHandBack.matches(PORTRAIT, 180)); // upside down is not upright
    }

    @Test
    public void flatOrDiagonalMatchesNothing() {
        assertFalse(OrientationHandBack.matches(LANDSCAPE, -1)); // ORIENTATION_UNKNOWN: lying flat
        assertFalse(OrientationHandBack.matches(PORTRAIT, -1));
        assertFalse(OrientationHandBack.matches(LANDSCAPE, 45));
        assertFalse(OrientationHandBack.matches(PORTRAIT, 45));
    }

    @Test
    public void handsBackOnlyAfterThePhoneStaysThere() {
        OrientationHandBack handBack = new OrientationHandBack();
        handBack.arm(LANDSCAPE);

        assertFalse(handBack.onOrientation(0, 0));      // still upright: the force stays
        assertFalse(handBack.onOrientation(270, 1000)); // turned sideways: start settling
        assertFalse(handBack.onOrientation(268, 1000 + OrientationHandBack.SETTLE_MS - 1));
        assertTrue(handBack.onOrientation(272, 1000 + OrientationHandBack.SETTLE_MS));
    }

    @Test
    public void aWobbleRestartsTheSettling() {
        OrientationHandBack handBack = new OrientationHandBack();
        handBack.arm(PORTRAIT);

        assertFalse(handBack.onOrientation(0, 0));
        assertFalse(handBack.onOrientation(80, 300)); // back on its side
        assertFalse(handBack.onOrientation(0, 400));
        assertFalse(handBack.onOrientation(0, 400 + OrientationHandBack.SETTLE_MS - 1));
        assertTrue(handBack.onOrientation(0, 400 + OrientationHandBack.SETTLE_MS));
    }

    @Test
    public void aPhoneHeldStillIsReCheckedWhenTheSettleTimeIsUp() {
        OrientationHandBack handBack = new OrientationHandBack();
        handBack.arm(LANDSCAPE);

        assertFalse(handBack.onOrientation(0, 0));
        assertEquals(-1, handBack.remainingMs(0)); // not there yet: nothing to re-check

        assertFalse(handBack.onOrientation(90, 1000)); // the only reading the phone may send
        assertEquals(OrientationHandBack.SETTLE_MS, handBack.remainingMs(1000));
        assertEquals(100, handBack.remainingMs(1000 + OrientationHandBack.SETTLE_MS - 100));
        assertTrue(handBack.onOrientation(90, 1000 + OrientationHandBack.SETTLE_MS)); // the re-check
    }

    @Test
    public void timeWithoutReadingsDoesNotCountAsHolding() {
        OrientationHandBack handBack = new OrientationHandBack();
        handBack.arm(LANDSCAPE);

        assertFalse(handBack.onOrientation(90, 0));  // the hold starts...
        handBack.pause();                              // ...then PiP / background, 200 ms later
        assertTrue(handBack.isArmed());                // the target survives
        assertEquals(-1, handBack.remainingMs(5_000));

        // Back in front much later: the first reading starts a new hold, it does not hand back.
        assertFalse(handBack.onOrientation(90, 5_000));
        assertEquals(OrientationHandBack.SETTLE_MS, handBack.remainingMs(5_000));
        assertTrue(handBack.onOrientation(90, 5_000 + OrientationHandBack.SETTLE_MS));
    }

    @Test
    public void disarmedNeverHandsBack() {
        OrientationHandBack handBack = new OrientationHandBack();
        assertFalse(handBack.isArmed());
        assertEquals(Configuration.ORIENTATION_UNDEFINED, handBack.getTarget());
        assertFalse(handBack.onOrientation(0, 0));
        assertFalse(handBack.onOrientation(0, 10_000));

        handBack.arm(PORTRAIT);
        assertTrue(handBack.isArmed());
        assertEquals(PORTRAIT, handBack.getTarget());
        handBack.disarm();
        assertEquals(Configuration.ORIENTATION_UNDEFINED, handBack.getTarget());
        assertFalse(handBack.onOrientation(0, 20_000));
    }
}
