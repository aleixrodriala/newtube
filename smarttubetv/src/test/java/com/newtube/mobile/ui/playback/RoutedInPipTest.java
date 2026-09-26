package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * NEWTUBE(link-while-playing): a second share link opened while the first one plays in the
 * foreground switches to the router's own task, which starts an auto-enter PiP of this player; the
 * new video is then routed back here during that entry and used to stay in a PiP window over the
 * launcher (Pixel 9, r3a). Only a routing that arrives while the player is LEAVING (paused, not yet
 * in PiP) marks the following PiP as unwanted.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class RoutedInPipTest {

    @Test
    public void aVideoRoutedInWhileThePlayerIsLeavingMarksThePipAsUnwanted() {
        assertTrue(MobilePlaybackActivity.routedInWhileLeaving(
                /* resumed= */ false, /* inPip= */ false, /* ownRestoreRequest= */ false));
    }

    @Test
    public void aRoutingIntoTheResumedPlayerIsAPlainSwitch() {
        assertFalse(MobilePlaybackActivity.routedInWhileLeaving(true, false, false));
    }

    @Test
    public void aRoutingIntoAnEnteredPipIsLeftToTheSystem() {
        // A relaunch of an entered PiP task already expands it (the old "third link" case).
        assertFalse(MobilePlaybackActivity.routedInWhileLeaving(false, true, false));
    }

    @Test
    public void ourOwnExpandRequestNeverCounts() {
        assertFalse(MobilePlaybackActivity.routedInWhileLeaving(false, false, true));
    }
}
