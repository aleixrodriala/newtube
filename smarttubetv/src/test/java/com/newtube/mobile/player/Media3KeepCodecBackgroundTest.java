package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ExoTimeoutException;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowSystemProperties;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Background audio never keeps a decoder for the next open: a player with media relies on media3's
 * reselection reset (no call next to live audio), an idle one drops foreground mode, and the only
 * error that drop can cause is swallowed instead of starting a recovery reload.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class Media3KeepCodecBackgroundTest {
    private final List<Boolean> foregroundCalls = new ArrayList<>();
    private final List<String> engineErrors = new ArrayList<>();
    private int state = Player.STATE_IDLE;
    private Media3PlayerController controller;

    @Before
    public void setUp() {
        ShadowSystemProperties.override("debug.arc.keep_codec", "");
        PlayerEventListener listener = (PlayerEventListener) Proxy.newProxyInstance(
                PlayerEventListener.class.getClassLoader(), new Class<?>[] {PlayerEventListener.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("onEngineError")) {
                        engineErrors.add(String.valueOf(args[2]));
                    }
                    return null;
                });
        controller = new Media3PlayerController(RuntimeEnvironment.getApplication(), listener);
        ExoPlayer player = (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(),
                new Class<?>[] {ExoPlayer.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getPlaybackState": return state;
                        case "setForegroundMode": foregroundCalls.add((Boolean) args[0]); return null;
                        case "addListener":
                        case "addAnalyticsListener": return null;
                        case "getPlayWhenReady": return true;
                        case "getMediaItemCount": return 0;
                        default: throw new AssertionError("Unexpected player call: " + method.getName());
                    }
                });
        controller.setPlayer(player);
    }

    @After
    public void tearDown() {
        ShadowSystemProperties.override("debug.arc.keep_codec", "");
    }

    @Test
    public void idlePlayerDropsAndRestoresForegroundMode() {
        controller.onBackgroundAudio(true);
        controller.onBackgroundAudio(true); // repeated onStop: one drop
        controller.onBackgroundAudio(false);
        controller.onBackgroundAudio(false); // repeated onResume: one restore
        assertEquals(java.util.Arrays.asList(false, true), foregroundCalls);
    }

    @Test
    public void playingPlayerIsNeverBlockedOn() {
        for (int playing : new int[] {Player.STATE_READY, Player.STATE_BUFFERING, Player.STATE_ENDED}) {
            state = playing;
            controller.onBackgroundAudio(true);
            controller.onBackgroundAudio(false);
        }
        assertTrue(foregroundCalls.isEmpty());
    }

    @Test
    public void debugSwitchOffMeansNothingToDrop() {
        ShadowSystemProperties.override("debug.arc.keep_codec", "0");
        controller.onBackgroundAudio(true);
        controller.onBackgroundAudio(false);
        assertTrue(foregroundCalls.isEmpty());
    }

    @Test
    public void releaseTimeoutOfTheDropIsNotAPlaybackFailure() {
        ExoPlaybackException timeout = ExoPlaybackException.createForUnexpected(
                new ExoTimeoutException(ExoTimeoutException.TIMEOUT_OPERATION_SET_FOREGROUND_MODE),
                PlaybackException.ERROR_CODE_TIMEOUT);
        assertTrue(Media3PlayerController.isKeepCodecReleaseTimeout(timeout));
        controller.onPlayerError(timeout);
        assertTrue(engineErrors.isEmpty());
    }

    @Test
    public void otherTimeoutsStillReachRecovery() {
        ExoPlaybackException releaseTimeout = ExoPlaybackException.createForUnexpected(
                new ExoTimeoutException(ExoTimeoutException.TIMEOUT_OPERATION_RELEASE),
                PlaybackException.ERROR_CODE_TIMEOUT);
        assertFalse(Media3PlayerController.isKeepCodecReleaseTimeout(releaseTimeout));
        controller.onPlayerError(releaseTimeout);
        assertEquals(1, engineErrors.size());
    }
}
