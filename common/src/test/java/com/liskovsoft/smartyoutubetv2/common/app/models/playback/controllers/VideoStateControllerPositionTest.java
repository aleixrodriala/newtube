package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Exercises the real restoration callbacks with a recording player and local saved history. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoStateControllerPositionTest {
    private final Video video = new Video();
    private final List<Long> seeks = new ArrayList<>();
    private final List<Boolean> playWrites = new ArrayList<>();
    private VideoStateService states;
    private VideoStateController controller;
    private long positionMs;
    private final long durationMs = 600_000;
    private float restoredVolume;
    private float restoredPitch;
    private float restoredSpeed;
    private boolean overlayShown;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        video.videoId = "timestamp-video";
        PlayerData prefs = PlayerData.instance(context);
        prefs.setPlayerVolume(0.6f);
        prefs.setPitch(1.1f);
        prefs.setSpeedPerVideoEnabled(true);
        PlayerTweaksData tweaks = PlayerTweaksData.instance(context);
        tweaks.setPlayerAutoVolumeEnabled(false);
        tweaks.setBufferOnStreamsDisabled(false);
        states = VideoStateService.instance(context);
        states.getStates().clear();

        PlaybackView player = (PlaybackView) Proxy.newProxyInstance(
                PlaybackView.class.getClassLoader(), new Class<?>[] {PlaybackView.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getVideo": return video;
                        case "getDurationMs": return durationMs;
                        case "getPositionMs": return positionMs;
                        case "setPositionMs":
                            positionMs = (long) args[0];
                            seeks.add(positionMs);
                            return null;
                        case "setPlayWhenReady": playWrites.add((boolean) args[0]); return null;
                        case "isOverlayShown": return overlayShown;
                        case "showOverlay": overlayShown = (boolean) args[0]; return null;
                        case "setVolume": restoredVolume = (float) args[0]; return null;
                        case "setPitch": restoredPitch = (float) args[0]; return null;
                        case "setSpeed": restoredSpeed = (float) args[0]; return null;
                        default: throw new AssertionError("Unexpected player call: " + method.getName());
                    }
                });
        controller = new VideoStateController() {
            @Override public PlaybackView getPlayer() { return player; }
            @Override public Video getVideo() { return video; }
            @Override public Context getContext() { return context; }
        };
        controller.setPlayEnabled(true);
    }

    @Test
    public void explicitTimestampSkipsHistorySeekAndPreservesOtherRestoration() {
        State saved = remember(259_957);
        video.pendingPosMs = 1_000;

        controller.onVideoLoaded(video);
        controller.onBuffering(); // Existing speed restoration must still read saved history.

        assertEquals(List.of(1_000L), seeks);
        assertEquals(0, video.pendingPosMs);
        assertSame(saved, states.getByVideoId(video.videoId));
        assertEquals(1.5f, restoredSpeed, 0.001f);
        assertEquals(0.6f, restoredVolume, 0.001f);
        assertEquals(1.1f, restoredPitch, 0.001f);
        assertEquals(List.of(true), playWrites);
        assertFalse(overlayShown);
    }

    @Test
    public void absentTimestampKeepsHistoryResume() {
        remember(259_957);
        controller.onVideoLoaded(video);
        assertEquals(List.of(259_957L), seeks);
        assertEquals(0, video.pendingPosMs);
    }

    @Test
    public void negativePendingValueDoesNotSuppressHistoryOrGetConsumed() {
        remember(259_957);
        video.pendingPosMs = -1;
        controller.onVideoLoaded(video);
        assertEquals(List.of(259_957L), seeks);
        assertEquals(-1, video.pendingPosMs);
    }

    @Test
    public void liveWithoutExplicitTimestampStillRestoresBufferedLiveEdge() {
        video.isLive = true;
        remember(durationMs - 1_000);
        controller.onVideoLoaded(video);
        assertEquals(List.of(durationMs - 15_000), seeks);
    }

    @Test
    public void explicitLiveTimestampAvoidsSupersededLiveEdgeSeek() {
        video.isLive = true;
        remember(durationMs - 1_000);
        video.pendingPosMs = 1_000;
        controller.onVideoLoaded(video);
        assertEquals(List.of(1_000L), seeks);
        assertEquals(0, video.pendingPosMs);
    }

    @Test
    public void laterTimestampOnSameVideoIsConsumedIndependently() {
        remember(259_957);
        video.pendingPosMs = 1_000;
        controller.onVideoLoaded(video);
        video.pendingPosMs = 10_000;
        controller.onVideoLoaded(video);
        assertEquals(List.of(1_000L, 10_000L), seeks);
        assertEquals(0, video.pendingPosMs);
    }

    @Test
    public void explicitTimestampStillRespectsBlockedPlay() {
        remember(259_957);
        controller.blockPlay(true);
        video.pendingPosMs = 1_000;
        controller.onVideoLoaded(video);
        assertEquals(List.of(1_000L), seeks);
        assertTrue(playWrites.isEmpty());
        assertEquals(0.6f, restoredVolume, 0.001f);
        assertEquals(1.1f, restoredPitch, 0.001f);
    }

    private State remember(long position) {
        State state = new State(video, position, durationMs, 1.5f);
        states.getStates().add(state); // Seed local history without scheduling persistence work.
        return state;
    }
}
