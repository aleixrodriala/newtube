package com.newtube.mobile.ui.playback;

import static com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.PLAYBACK_MODE_ALL;
import static com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.PLAYBACK_MODE_CLOSE;
import static com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.PLAYBACK_MODE_LIST;
import static com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.PLAYBACK_MODE_ONE;
import static com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.PLAYBACK_MODE_PAUSE;
import static com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.PLAYBACK_MODE_SHUFFLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AutoplaySwitchTest {
    @Test
    public void whichModesPlayOn() {
        assertTrue(AutoplaySwitch.isOn(PLAYBACK_MODE_ALL));
        assertTrue(AutoplaySwitch.isOn(PLAYBACK_MODE_SHUFFLE));
        assertFalse(AutoplaySwitch.isOn(PLAYBACK_MODE_LIST));
        assertFalse(AutoplaySwitch.isOn(PLAYBACK_MODE_PAUSE));
        assertFalse(AutoplaySwitch.isOn(PLAYBACK_MODE_ONE));
        assertFalse(AutoplaySwitch.isOn(PLAYBACK_MODE_CLOSE));
    }

    @Test
    public void offFinishesPlaylistsThenStops() {
        assertEquals(PLAYBACK_MODE_LIST, AutoplaySwitch.modeFor(false, PLAYBACK_MODE_ALL, PLAYBACK_MODE_ALL));
        assertEquals(PLAYBACK_MODE_LIST, AutoplaySwitch.modeFor(false, PLAYBACK_MODE_SHUFFLE, PLAYBACK_MODE_ALL));
    }

    @Test
    public void onBringsBackWhatItWas() {
        assertEquals(PLAYBACK_MODE_SHUFFLE, AutoplaySwitch.modeFor(true, PLAYBACK_MODE_LIST, PLAYBACK_MODE_SHUFFLE));
        assertEquals(PLAYBACK_MODE_ALL, AutoplaySwitch.modeFor(true, PLAYBACK_MODE_PAUSE, PLAYBACK_MODE_PAUSE));
        assertEquals(PLAYBACK_MODE_SHUFFLE, AutoplaySwitch.modeFor(true, PLAYBACK_MODE_SHUFFLE, PLAYBACK_MODE_ALL));
    }
}
