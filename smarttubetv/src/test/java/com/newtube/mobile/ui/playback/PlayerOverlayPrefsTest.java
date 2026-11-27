package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Tests for {@link PlayerOverlayPrefs} (#82: optional overlay quality button). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlayerOverlayPrefsTest {

    private Application context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        PlayerOverlayPrefs.setQualityButtonShown(context, false);
    }

    @After
    public void tearDown() {
        PlayerOverlayPrefs.setQualityButtonShown(context, false);
    }

    @Test
    public void qualityButtonIsHiddenByDefaultAndToggles() {
        assertFalse(PlayerOverlayPrefs.isQualityButtonShown(context));
        PlayerOverlayPrefs.setQualityButtonShown(context, true);
        assertTrue(PlayerOverlayPrefs.isQualityButtonShown(context));
        PlayerOverlayPrefs.setQualityButtonShown(context, false);
        assertFalse(PlayerOverlayPrefs.isQualityButtonShown(context));
    }
}
