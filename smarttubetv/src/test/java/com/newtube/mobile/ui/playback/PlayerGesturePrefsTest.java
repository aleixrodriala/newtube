package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Tests for the YouTube-stock gestures mode toggle in PlayerGesturePrefs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlayerGesturePrefsTest {
    private Context mContext;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        PlayerGesturePrefs.setStockYouTubeGesturesOn(mContext, false);
        PlayerGesturePrefs.setLevelSwipesOn(mContext, true);
        PlayerGesturePrefs.setSeekSwipeOn(mContext, true);
    }

    @Test
    public void stockYouTubeModeSuppressesCustomSwipesWithoutErasingThem() {
        assertFalse(PlayerGesturePrefs.isStockYouTubeGesturesOn(mContext));
        assertTrue(PlayerGesturePrefs.areLevelSwipesActive(mContext));
        assertTrue(PlayerGesturePrefs.isSeekSwipeActive(mContext));

        PlayerGesturePrefs.setStockYouTubeGesturesOn(mContext, true);
        assertFalse(PlayerGesturePrefs.areLevelSwipesActive(mContext));
        assertFalse(PlayerGesturePrefs.isSeekSwipeActive(mContext));
        assertTrue(PlayerGesturePrefs.isLevelSwipesOn(mContext));
        assertTrue(PlayerGesturePrefs.isSeekSwipeOn(mContext));

        PlayerGesturePrefs.setStockYouTubeGesturesOn(mContext, false);
        assertTrue(PlayerGesturePrefs.areLevelSwipesActive(mContext));
        assertTrue(PlayerGesturePrefs.isSeekSwipeActive(mContext));
    }
}
