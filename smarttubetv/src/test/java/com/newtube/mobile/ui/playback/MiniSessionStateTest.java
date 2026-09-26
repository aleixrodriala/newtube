package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Closing the mini card used to end playback with no way back (beta-tester report: "it stops
 * playing ... other apps have a notification you can play again"). The card's X now parks the
 * session; these are the rules of that state machine.
 */
public class MiniSessionStateTest {
    private static final long T0 = 1_000_000L;

    @Test
    public void closingADockedCardParksInsteadOfEnding() {
        MiniSessionState state = new MiniSessionState();
        state.dock();
        assertTrue(state.isCardVisible());

        assertTrue(state.park(/* resumable= */ true, T0, "v1"));

        assertEquals(MiniSessionState.Phase.PARKED, state.getPhase());
        assertTrue(state.isParked());
        // The card hides, but the hidden player is still in the task under the hosts: back-stack
        // routing (MobileActivity) must keep treating it as present.
        assertFalse(state.isCardVisible());
        assertTrue(state.isBehindHosts());
    }

    @Test
    public void nothingToResumeMeansARealClose() {
        MiniSessionState state = new MiniSessionState();
        state.dock();

        assertFalse(state.park(/* resumable= */ false, T0, "v1"));

        assertEquals(MiniSessionState.Phase.DOCKED, state.getPhase());
    }

    @Test
    public void onlyADockedSessionCanPark() {
        MiniSessionState none = new MiniSessionState();
        assertFalse(none.park(true, T0, "v1"));
        assertEquals(MiniSessionState.Phase.NONE, none.getPhase());

        MiniSessionState parked = new MiniSessionState();
        parked.dock();
        parked.park(true, T0, "v1");
        // A second close (a stale card) must not restart the timeout clock.
        assertFalse(parked.park(true, T0 + 5_000, "v1"));
        assertTrue(parked.isParkExpired(T0 + MiniSessionState.PARK_TIMEOUT_MS));
    }

    @Test
    public void playFromTheNotificationBringsTheCardBack() {
        MiniSessionState state = new MiniSessionState();
        state.dock();
        state.park(true, T0, "v1");

        assertTrue(state.resume());

        assertEquals(MiniSessionState.Phase.DOCKED, state.getPhase());
        assertTrue(state.isCardVisible());
        // Resumed sessions never time out, and a later close parks with a fresh clock.
        assertFalse(state.isParkExpired(T0 + MiniSessionState.PARK_TIMEOUT_MS * 2));
        assertTrue(state.park(true, T0 + 60_000, "v1"));
        assertFalse(state.isParkExpired(T0 + MiniSessionState.PARK_TIMEOUT_MS));
        assertTrue(state.isParkExpired(T0 + 60_000 + MiniSessionState.PARK_TIMEOUT_MS));
    }

    @Test
    public void resumeIsANoOpUnlessParked() {
        MiniSessionState docked = new MiniSessionState();
        docked.dock();
        assertFalse(docked.resume());
        assertEquals(MiniSessionState.Phase.DOCKED, docked.getPhase());

        assertFalse(new MiniSessionState().resume());
    }

    @Test
    public void dismissingTheNotificationEndsOnlyAParkedSession() {
        MiniSessionState state = new MiniSessionState();
        assertFalse(state.endsOnNotificationDismiss());

        state.dock();
        // A docked (or backgrounded) session whose paused notification is swiped keeps living; the
        // card or the reopened app still reach it.
        assertFalse(state.endsOnNotificationDismiss());

        state.park(true, T0, "v1");
        assertTrue(state.endsOnNotificationDismiss());
    }

    @Test
    public void parkExpiresAfterTheSystemPausedMediaTimeout() {
        MiniSessionState state = new MiniSessionState();
        state.dock();
        state.park(true, T0, "v1");

        assertFalse(state.isParkExpired(T0 + MiniSessionState.PARK_TIMEOUT_MS - 1));
        assertTrue(state.isParkExpired(T0 + MiniSessionState.PARK_TIMEOUT_MS));
        assertEquals(10 * 60 * 1000L, MiniSessionState.PARK_TIMEOUT_MS);
    }

    @Test
    public void clearEndsEverything() {
        MiniSessionState state = new MiniSessionState();
        state.dock();
        state.park(true, T0, "v1");

        state.clear();

        assertEquals(MiniSessionState.Phase.NONE, state.getPhase());
        assertFalse(state.isBehindHosts());
        assertFalse(state.isParkExpired(T0 + MiniSessionState.PARK_TIMEOUT_MS));
        assertFalse(state.endsOnNotificationDismiss());
    }

    @Test
    public void canParkNeedsALoadedStreamAndNoCastSession() {
        assertTrue(MiniSessionState.canPark(true, true, false, false));

        assertFalse("no player", MiniSessionState.canPark(false, true, false, false));
        assertFalse("idle: failed or nothing loaded",
                MiniSessionState.canPark(true, false, false, false));
        assertFalse("ended", MiniSessionState.canPark(true, true, true, false));
        assertFalse("the TV owns (or is about to own) playback",
                MiniSessionState.canPark(true, true, false, true));
    }

    /**
     * Review P1: the deadline is real time. The Activity feeds elapsedRealtime, which keeps counting
     * through deep sleep, so a check at the first wake after a night of sleep ends the session even
     * though only seconds of uptime (and no Handler delay) passed.
     */
    @Test
    public void deadlineIsRealTimeSoAWakeAfterSleepEndsIt() {
        MiniSessionState state = new MiniSessionState();
        state.dock();
        state.park(true, T0, "v1");
        assertEquals(MiniSessionState.PARK_TIMEOUT_MS, state.remainingMs(T0));

        long eightHoursLater = T0 + 8 * 60 * 60 * 1000L;
        assertTrue(state.shouldEnd(eightHoursLater, /* playWhenReady= */ false));
        assertEquals(0, state.remainingMs(eightHoursLater));

        assertFalse(state.shouldEnd(T0 + 60_000, false));
        assertEquals(MiniSessionState.PARK_TIMEOUT_MS - 60_000, state.remainingMs(T0 + 60_000));
        assertEquals(-1, new MiniSessionState().remainingMs(T0));
    }

    /** Review P1: the timeout never tears down a player that is playing. */
    @Test
    public void neverEndsWhilePlaying() {
        MiniSessionState state = new MiniSessionState();
        state.dock();
        state.park(true, T0, "v1");

        long late = T0 + MiniSessionState.PARK_TIMEOUT_MS * 3;
        assertFalse(state.shouldEnd(late, /* playWhenReady= */ true));
        assertTrue(state.shouldEnd(late, /* playWhenReady= */ false));
    }

    /**
     * Review P1: Next/Previous on the parked notification loads another video. That restarts the
     * clock (and its playback un-parks the session), so the original X's deadline cannot end it.
     */
    @Test
    public void nextFromTheNotificationRestartsTheClock() {
        MiniSessionState state = new MiniSessionState();
        state.dock();
        state.park(true, T0, "v1");

        long later = T0 + MiniSessionState.PARK_TIMEOUT_MS - 1_000;
        assertFalse("same video is not a change", state.onVideoChanged("v1", later));
        assertTrue(state.onVideoChanged("v2", later));
        assertFalse(state.shouldEnd(T0 + MiniSessionState.PARK_TIMEOUT_MS, false));
        assertTrue(state.shouldEnd(later + MiniSessionState.PARK_TIMEOUT_MS, false));

        // Its playback then leaves PARKED entirely.
        assertTrue(state.resume());
        assertFalse(state.shouldEnd(later + MiniSessionState.PARK_TIMEOUT_MS * 2, false));
    }

    @Test
    public void videoChangesOutsideAParkAreIgnored() {
        MiniSessionState docked = new MiniSessionState();
        docked.dock();
        assertFalse(docked.onVideoChanged("v2", T0));
        assertFalse(new MiniSessionState().onVideoChanged("v2", T0));
    }
}
