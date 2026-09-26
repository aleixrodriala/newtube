package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * NEWTUBE(no-host-minimize): the swipe-down minimize fades the player's backdrop to uncover the
 * screen underneath - but after a cold share link there is no screen of ours underneath, and the
 * fade uncovered the launcher with the shrinking video floating over it like a system PiP window
 * (Pixel 9 baseline, ~0.7 s before Home appeared). That drag keeps its backdrop opaque.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MinimizeBackdropTest {
    private static final float EPS = 1e-6f;

    @Test
    public void withAHostBeneathTheBackdropFadesWithTheMorph() {
        assertEquals(1f, MobilePlaybackActivity.morphBackdropAlpha(0f, false), EPS);
        assertEquals(0.6f, MobilePlaybackActivity.morphBackdropAlpha(0.4f, false), EPS);
        assertEquals(0f, MobilePlaybackActivity.morphBackdropAlpha(1f, false), EPS);
    }

    @Test
    public void withNothingOfOursBeneathTheBackdropNeverUncoversTheLauncher() {
        assertEquals(1f, MobilePlaybackActivity.morphBackdropAlpha(0f, true), EPS);
        assertEquals(1f, MobilePlaybackActivity.morphBackdropAlpha(0.4f, true), EPS);
        assertEquals(1f, MobilePlaybackActivity.morphBackdropAlpha(1f, true), EPS);
    }
}
