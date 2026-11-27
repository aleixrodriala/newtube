package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * Tests {@link PlayerGesturePrefs} persistence and bounds handling for double-tap seek duration.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlayerGesturePrefsTest {

    private Application context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("newtube_gestures", Application.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void defaultsToTenSeconds() {
        assertEquals(10, PlayerGesturePrefs.getDoubleTapSeekSeconds(context));
    }

    @Test
    public void persistsSupportedOptions() {
        for (int seconds : PlayerGesturePrefs.DOUBLE_TAP_SEEK_OPTIONS) {
            PlayerGesturePrefs.setDoubleTapSeekSeconds(context, seconds);
            assertEquals(seconds, PlayerGesturePrefs.getDoubleTapSeekSeconds(context));
        }
    }

    @Test
    public void nonPositiveDurationFallsBackToDefault() {
        PlayerGesturePrefs.setDoubleTapSeekSeconds(context, 30);
        assertEquals(30, PlayerGesturePrefs.getDoubleTapSeekSeconds(context));

        PlayerGesturePrefs.setDoubleTapSeekSeconds(context, 0);
        assertEquals(10, PlayerGesturePrefs.getDoubleTapSeekSeconds(context));

        PlayerGesturePrefs.setDoubleTapSeekSeconds(context, -5);
        assertEquals(10, PlayerGesturePrefs.getDoubleTapSeekSeconds(context));
    }
}
