package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.content.Context;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Exercise the actual Media3-to-presenter event bridge without network or decoder setup. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class,
        shadows = Media3SeekBufferingTest.OfflineSources.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class Media3SeekBufferingTest {
    private final List<String> events = new ArrayList<>();
    private Media3PlayerController controller;
    private int state = Player.STATE_BUFFERING;
    private boolean playing = true;
    private boolean releaseOnSeek;

    @Before
    public void setUp() {
        PlayerEventListener listener = (PlayerEventListener) Proxy.newProxyInstance(
                PlayerEventListener.class.getClassLoader(), new Class<?>[] {PlayerEventListener.class},
                (proxy, method, args) -> {
                    events.add(method.getName());
                    if (releaseOnSeek && method.getName().equals("onSeekEnd")) {
                        ReflectionHelpers.setField(controller, "mPlayer", null);
                    }
                    return null;
                });
        controller = new Media3PlayerController(RuntimeEnvironment.getApplication(), listener);
        ExoPlayer player = (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(),
                new Class<?>[] {ExoPlayer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getPlaybackState")) return state;
                    if (method.getName().equals("getPlayWhenReady")) return playing;
                    throw new AssertionError("Unexpected player call: " + method.getName());
                });
        ReflectionHelpers.setField(controller, "mPlayer", player);
    }

    @Test
    public void playingSeekRearmsBufferingAfterTheReset() {
        seek(Player.DISCONTINUITY_REASON_SEEK);
        assertEquals(Arrays.asList("onSeekEnd", "onBuffering"), events);
    }

    @Test
    public void adjustedSeekAlsoRearmsBuffering() {
        seek(Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT);
        assertEquals(Arrays.asList("onSeekEnd", "onBuffering"), events);
    }

    @Test
    public void pausedSeekDoesNotArmRecovery() {
        playing = false;
        seek(Player.DISCONTINUITY_REASON_SEEK);
        assertEquals(Collections.singletonList("onSeekEnd"), events);
    }

    @Test
    public void readySeekDoesNotArmRecovery() {
        state = Player.STATE_READY;
        seek(Player.DISCONTINUITY_REASON_SEEK);
        assertEquals(Collections.singletonList("onSeekEnd"), events);
    }

    @Test
    public void automaticTransitionDoesNotResetSeekState() {
        seek(Player.DISCONTINUITY_REASON_AUTO_TRANSITION);
        assertEquals(Collections.emptyList(), events);
    }

    @Test
    public void releasedPlayerCannotReceiveStaleBuffering() {
        releaseOnSeek = true;
        seek(Player.DISCONTINUITY_REASON_SEEK);
        assertEquals(Collections.singletonList("onSeekEnd"), events);
    }

    private void seek(int reason) {
        controller.onPositionDiscontinuity(null, null, reason);
    }

    @Implements(Media3SourceFactory.class)
    public static class OfflineSources {
        @Implementation
        protected void __constructor__(Context context) {}
    }
}
