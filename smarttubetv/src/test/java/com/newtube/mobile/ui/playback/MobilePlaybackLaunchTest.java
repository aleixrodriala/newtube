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
 * The playback Activity can be brought up by something that is not a video open.
 *
 * <p>Observed on the Pixel 9 on 2026-09-07: an in-place update killed the process
 * ({@code Force removing ActivityRecord{...MobilePlaybackActivity}: app died, no saved state})
 * and SystemUI relaunched the component directly with a bare Intent
 * ({@code START u0 {cmp=.../MobilePlaybackActivity} with LAUNCH_SINGLE_TOP from uid 10244}).
 * The Activity built the whole watch page and parked at 00:00 with an empty title and duration -
 * for 19 minutes, emitting nothing but auto-hide timer ticks. No video was ever requested: the
 * process logged zero {@code ep=} (tap) lines and never constructed a player.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
// Robolectric still runs the real MobileMainApplication.onCreate, which installs the Conscrypt
// security provider (MainApplication:66) - and there is no conscrypt_jni on a JVM test's
// library path. Same reason, same annotation as PlaybackNetworkFailureTest.
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MobilePlaybackLaunchTest {

    /** The regression: a cold launch with no video must not sit on a dead player. */
    @Test
    public void bareLaunchWithoutAVideoBailsOut() {
        assertTrue(MobilePlaybackActivity.shouldFinishWithoutVideo(
                /* hasSavedState= */ false, /* hasPendingTransition= */ false,
                /* presenterHasVideo= */ false));
    }

    /** A real open always parks the video in the presenter before starting this Activity. */
    @Test
    public void normalOpenIsNotTreatedAsEmpty() {
        assertFalse(MobilePlaybackActivity.shouldFinishWithoutVideo(false, false, true));
    }

    /**
     * A tapped card supplies a pending morph snapshot. Treat that as a real open even if the
     * presenter reference has not been observed yet - bouncing a tap to Home would be far worse
     * than briefly showing an empty player.
     */
    @Test
    public void pendingCardTransitionIsTreatedAsARealOpen() {
        assertFalse(MobilePlaybackActivity.shouldFinishWithoutVideo(false, true, false));
    }

    /**
     * A configuration recreate (rotation, PiP resize) always has saved state and keeps its video
     * in the presenter; it must never be mistaken for a bare relaunch.
     */
    @Test
    public void configurationRecreateIsNeverTreatedAsEmpty() {
        assertFalse(MobilePlaybackActivity.shouldFinishWithoutVideo(true, false, false));
        assertFalse(MobilePlaybackActivity.shouldFinishWithoutVideo(true, false, true));
        assertFalse(MobilePlaybackActivity.shouldFinishWithoutVideo(true, true, false));
    }
}
