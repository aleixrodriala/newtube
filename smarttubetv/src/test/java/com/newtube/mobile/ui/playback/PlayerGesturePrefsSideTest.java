package com.newtube.mobile.ui.playback;

import static com.newtube.mobile.ui.playback.PlayerGesturePrefs.SIDE_BRIGHTNESS;
import static com.newtube.mobile.ui.playback.PlayerGesturePrefs.SIDE_NONE;
import static com.newtube.mobile.ui.playback.PlayerGesturePrefs.SIDE_VOLUME;
import static com.newtube.mobile.ui.playback.PlayerGesturePrefs.sideSwipe;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlayerGesturePrefsSideTest {
    @Test
    public void bothOnSplitTheSides() {
        assertEquals(SIDE_BRIGHTNESS, sideSwipe(true, true, true));
        assertEquals(SIDE_VOLUME, sideSwipe(false, true, true));
    }

    @Test
    public void onlyOneOnWorksOnBothSides() {
        assertEquals(SIDE_BRIGHTNESS, sideSwipe(true, true, false));
        assertEquals(SIDE_BRIGHTNESS, sideSwipe(false, true, false));
        assertEquals(SIDE_VOLUME, sideSwipe(true, false, true));
        assertEquals(SIDE_VOLUME, sideSwipe(false, false, true));
    }

    @Test
    public void bothOffDoNothing() {
        assertEquals(SIDE_NONE, sideSwipe(true, false, false));
        assertEquals(SIDE_NONE, sideSwipe(false, false, false));
    }
}
