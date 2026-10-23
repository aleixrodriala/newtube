package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;

/**
 * NEWTUBE(autoplay): the player's Autoplay switch, as YouTube's. Off is "Finish the playlist, then
 * stop" ({@link PlayerConstants#PLAYBACK_MODE_LIST}): a video on its own stops at its end, while a
 * playlist and the queue still play on. On puts back what "When a video ends" was before it was
 * turned off (Play the next video, if nothing else).
 */
public final class AutoplaySwitch {
    private static final String PREFS_NAME = "newtube_autoplay";
    private static final String KEY_ON_MODE = "on_mode";

    private AutoplaySwitch() {
    }

    /** Whether {@code mode} plays on into another video after one ends. */
    public static boolean isOn(int mode) {
        return mode == PlayerConstants.PLAYBACK_MODE_ALL
                || mode == PlayerConstants.PLAYBACK_MODE_SHUFFLE
                || mode == PlayerConstants.PLAYBACK_MODE_REVERSE_LIST;
    }

    public static boolean isOn(Context context) {
        return isOn(PlayerData.instance(context).getPlaybackMode());
    }

    /**
     * The mode to switch to. Turning off remembers the mode it leaves, when that is an "on" one;
     * turning on restores it.
     */
    public static int modeFor(boolean on, int currentMode, int rememberedOnMode) {
        if (on) {
            return isOn(currentMode) ? currentMode
                    : isOn(rememberedOnMode) ? rememberedOnMode : PlayerConstants.PLAYBACK_MODE_ALL;
        }
        return PlayerConstants.PLAYBACK_MODE_LIST;
    }

    /** Applies the switch and returns the new mode. */
    public static int set(Context context, boolean on) {
        PlayerData playerData = PlayerData.instance(context);
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int current = playerData.getPlaybackMode();
        if (!on && isOn(current)) {
            prefs.edit().putInt(KEY_ON_MODE, current).apply();
        }
        int mode = modeFor(on, current, prefs.getInt(KEY_ON_MODE, PlayerConstants.PLAYBACK_MODE_ALL));
        if (mode != current) {
            playerData.setPlaybackMode(mode);
        }
        return mode;
    }
}
