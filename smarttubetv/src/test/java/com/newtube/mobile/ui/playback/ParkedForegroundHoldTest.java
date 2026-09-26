package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The parked-session foreground hold of MobilePlaybackService. Integration review of the merged
 * park feature: Next/Previous on a parked session lost the hold on the media reset's notification
 * cancel, and an error-recovery engine restart cleared it through the real-teardown detach.
 */
public class ParkedForegroundHoldTest {
    @Test
    public void heldPromotesEvenAPausedNotification() {
        ParkedForegroundHold hold = new ParkedForegroundHold();
        assertFalse("paused, no park: demote as always", hold.promotesOnPost(false));
        assertTrue(hold.promotesOnPost(true));

        assertTrue(hold.set(true));
        assertTrue(hold.promotesOnPost(false));
        assertFalse("no change, no re-post", hold.set(true));
    }

    /** Review P1: the media reset of a parked Next/Previous cancels the notification. */
    @Test
    public void parkedVideoSwitchCancelKeepsTheServiceInTheForeground() {
        ParkedForegroundHold hold = new ParkedForegroundHold();
        hold.set(true);

        assertTrue(hold.keepsServiceOnCancel(/* dismissedByUser= */ false));
        assertTrue("still held for the next item's notification", hold.isHeld());
    }

    @Test
    public void userDismissalAndUnheldCancelsTakeTheNormalPath() {
        ParkedForegroundHold hold = new ParkedForegroundHold();
        assertFalse("not parked: stop foreground + stopSelf as before",
                hold.keepsServiceOnCancel(false));

        hold.set(true);
        assertFalse("the user's own dismissal can still end the park",
                hold.keepsServiceOnCancel(/* dismissedByUser= */ true));
    }

    /** Review P2: restartEngine's detach is not a teardown. */
    @Test
    public void engineRestartKeepsTheHold() {
        ParkedForegroundHold hold = new ParkedForegroundHold();
        hold.set(true);

        hold.onDetach(/* realTeardown= */ false);

        assertTrue(hold.isHeld());
        assertTrue(hold.promotesOnPost(false));
    }

    /**
     * The real teardown ends the hold before the notification is cancelled, so that cancel stops
     * the foreground and the service exactly as it always did.
     */
    @Test
    public void realTeardownEndsTheHoldBeforeTheCancel() {
        ParkedForegroundHold hold = new ParkedForegroundHold();
        hold.set(true);

        hold.onDetach(/* realTeardown= */ true);

        assertFalse(hold.isHeld());
        assertFalse(hold.keepsServiceOnCancel(false));
        assertFalse(hold.promotesOnPost(false));
    }

    @Test
    public void resumeReleasesTheHold() {
        ParkedForegroundHold hold = new ParkedForegroundHold();
        hold.set(true);

        assertTrue(hold.set(false));

        assertFalse(hold.keepsServiceOnCancel(false));
        assertFalse(hold.promotesOnPost(false));
    }
}
