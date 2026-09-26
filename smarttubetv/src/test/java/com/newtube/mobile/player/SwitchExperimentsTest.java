package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowSystemProperties;

/** The touch-prefetch experiment is off unless explicitly set, and bounded when it is. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SwitchExperimentsTest {
    @After
    public void tearDown() {
        ShadowSystemProperties.override("debug.arc.touch_prefetch_ms", "");
    }

    @Test
    public void offByDefault() {
        ShadowSystemProperties.override("debug.arc.touch_prefetch_ms", "");
        assertEquals(0, SwitchExperiments.touchPrefetchStillMs());
    }

    @Test
    public void explicitValueIsUsedAndClamped() {
        ShadowSystemProperties.override("debug.arc.touch_prefetch_ms", "60");
        assertEquals(60, SwitchExperiments.touchPrefetchStillMs());
        ShadowSystemProperties.override("debug.arc.touch_prefetch_ms", "9000");
        assertEquals(500, SwitchExperiments.touchPrefetchStillMs());
        ShadowSystemProperties.override("debug.arc.touch_prefetch_ms", "-5");
        assertEquals(0, SwitchExperiments.touchPrefetchStillMs());
        ShadowSystemProperties.override("debug.arc.touch_prefetch_ms", "junk");
        assertEquals(0, SwitchExperiments.touchPrefetchStillMs());
    }
}
