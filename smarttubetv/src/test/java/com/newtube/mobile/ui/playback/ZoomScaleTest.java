package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Tests for bounded continuous pinch-zoom math (Fit 1.0x up to Fill). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ZoomScaleTest {
    private static final float WIDE_16_9 = 16f / 9f;

    @Test
    public void fillScaleMatchesFoldableAndPhoneBoxes() {
        // Honor Magic V5 inner screen in landscape: 2352x2172 with 16:9 video -> ~1.64x.
        assertEquals(2172f / (2352f / WIDE_16_9),
                MobilePlaybackActivity.fillScaleFor(2352, 2172, WIDE_16_9), 1e-3f);
        // 20:9 phone in landscape: 2400x1080 with 16:9 video -> 1.25x.
        assertEquals(1.25f,
                MobilePlaybackActivity.fillScaleFor(2400, 1080, WIDE_16_9), 1e-3f);
        // Exact 16:9 box: Fill == Fit.
        assertEquals(1f,
                MobilePlaybackActivity.fillScaleFor(1920, 1080, WIDE_16_9), 1e-3f);
    }

    @Test
    public void fillScaleGuardsInvalidInputs() {
        assertEquals(1f, MobilePlaybackActivity.fillScaleFor(0, 1080, WIDE_16_9), 0f);
        assertEquals(1f, MobilePlaybackActivity.fillScaleFor(1920, 0, WIDE_16_9), 0f);
        assertEquals(1f, MobilePlaybackActivity.fillScaleFor(1920, 1080, 0f), 0f);
        assertEquals(1f, MobilePlaybackActivity.fillScaleFor(1920, 1080, Float.NaN), 0f);
    }

    @Test
    public void clampStopsAtFitAndFill() {
        assertEquals(1f, MobilePlaybackActivity.clampZoomScale(0.75f, 1.64f), 0f);
        assertEquals(1f, MobilePlaybackActivity.clampZoomScale(Float.NaN, 1.64f), 0f);
        assertEquals(1.35f, MobilePlaybackActivity.clampZoomScale(1.35f, 1.64f), 1e-4f);
        assertEquals(1.64f, MobilePlaybackActivity.clampZoomScale(2.50f, 1.64f), 1e-4f);
    }
}
