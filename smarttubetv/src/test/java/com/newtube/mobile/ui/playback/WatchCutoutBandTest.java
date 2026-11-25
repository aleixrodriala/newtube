package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Tests vertical band intersection for foldable side display-cutout insets. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class WatchCutoutBandTest {
    @Test
    public void cutoutOutsideVideoBoxLeavesControlsFullWidth() {
        // Magic V5 ROTATION_270: camera hole sits at y=1588..1664 inside the comments area,
        // below the video area (y=105..1369).
        assertFalse(MobilePlaybackActivity.cutoutOverlapsBand(1588, 1664, 105, 1369));
        assertFalse(MobilePlaybackActivity.cutoutOverlapsBand(1369, 1445, 105, 1369));
    }

    @Test
    public void cutoutInsideVideoBoxInsetsControls() {
        // Magic V5 ROTATION_90: camera hole sits at y=508..584 inside the video area.
        assertTrue(MobilePlaybackActivity.cutoutOverlapsBand(508, 584, 105, 1369));
        assertTrue(MobilePlaybackActivity.cutoutOverlapsBand(90, 120, 105, 1369));
    }
}
