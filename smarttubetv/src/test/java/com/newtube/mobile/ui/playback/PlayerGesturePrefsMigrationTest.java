package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * NEWTUBE(gestures): the brightness and volume swipes had one switch ("level_swipes") before each
 * got its own. Whoever turned that one off keeps both off, and turning one back on leaves the other.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlayerGesturePrefsMigrationTest {

    private Context mContext;
    private SharedPreferences mStored;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        mStored = mContext.getSharedPreferences("newtube_gestures", Context.MODE_PRIVATE);
        mStored.edit().clear().commit();
    }

    @Test
    public void bothOnByDefault() {
        assertTrue(PlayerGesturePrefs.isBrightnessSwipeOn(mContext));
        assertTrue(PlayerGesturePrefs.isVolumeSwipeOn(mContext));
    }

    @Test
    public void oldSwitchOffReadsBothOff() {
        mStored.edit().putBoolean("level_swipes", false).commit();

        assertFalse(PlayerGesturePrefs.isBrightnessSwipeOn(mContext));
        assertFalse(PlayerGesturePrefs.isVolumeSwipeOn(mContext));
    }

    @Test
    public void brightnessBackOnLeavesVolumeOff() {
        mStored.edit().putBoolean("level_swipes", false).commit();

        PlayerGesturePrefs.setBrightnessSwipeOn(mContext, true);

        assertTrue(PlayerGesturePrefs.isBrightnessSwipeOn(mContext));
        assertFalse(PlayerGesturePrefs.isVolumeSwipeOn(mContext));
    }

    @Test
    public void volumeBackOnLeavesBrightnessOff() {
        mStored.edit().putBoolean("level_swipes", false).commit();

        PlayerGesturePrefs.setVolumeSwipeOn(mContext, true);

        assertFalse(PlayerGesturePrefs.isBrightnessSwipeOn(mContext));
        assertTrue(PlayerGesturePrefs.isVolumeSwipeOn(mContext));
    }
}
