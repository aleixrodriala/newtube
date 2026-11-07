package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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

/** The previous action through the real controller: restart-or-previous, and resume on previous. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoStateControllerPreviousTest {
    private static final long MUSIC_DURATION_MS = 210_000; // under the 6 min "short video" cut
    private final List<Long> seeks = new ArrayList<>();
    private final List<Long> resumeSeeks = new ArrayList<>();
    private VideoStateService states;
    private VideoStateController controller;
    private Video current;
    private long positionMs;
    private long clockMs;
    private boolean playing;
    private boolean hasPrevious = true;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        PlayerTweaksData.instance(context).setRememberPositionOfShortVideosEnabled(false);
        PlayerData.instance(context).setSpeedPerVideoEnabled(false); // a reset removes the state
        states = VideoStateService.instance(context);
        states.getStates().clear();
        current = video("current");

        PlaybackView player = (PlaybackView) Proxy.newProxyInstance(
                PlaybackView.class.getClassLoader(), new Class<?>[] {PlaybackView.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getVideo": return current;
                        case "getDurationMs": return MUSIC_DURATION_MS;
                        case "getPositionMs":
                        case "getHistoryPositionMs": return positionMs;
                        case "isPlaying": return playing;
                        case "setPositionMs":
                            positionMs = (long) args[0];
                            seeks.add(positionMs);
                            return null;
                        case "setResumePositionMs":
                            positionMs = (long) args[0];
                            resumeSeeks.add(positionMs);
                            return null;
                        default: return defaultValue(method.getReturnType());
                    }
                });
        controller = new VideoStateController() {
            @Override public PlaybackView getPlayer() { return player; }
            @Override public Video getVideo() { return current; }
            @Override public Context getContext() { return context; }
            @Override protected boolean hasPrevious() { return hasPrevious; }
            @Override protected long nowMs() { return clockMs; }
        };
    }

    @Test
    public void secondTapAfterRestartGoesBackEvenWhenSponsorBlockSkipsTheIntro() {
        startPlaying();
        playFor(31_600);
        assertTrue(controller.onPreviousClicked()); // restart
        assertEquals(List.of(100L), seeks);

        // SponsorBlock skips "Non-music section" to 13 s right after the restart.
        controller.onBuffering();
        controller.onPlay();
        positionMs = 13_000;
        playFor(2_500);

        assertFalse(controller.onPreviousClicked()); // passed on: go to the previous video
        assertEquals(List.of(100L), seeks);
    }

    @Test
    public void earlyTapGoesToPrevious() {
        startPlaying();
        playFor(8_000);
        assertFalse(controller.onPreviousClicked());
        assertTrue(seeks.isEmpty());
    }

    @Test
    public void resumedFarInButJustOpenedGoesToPrevious() {
        positionMs = 120_000; // continue watching restored it here
        startPlaying();
        playFor(3_000);
        assertFalse(controller.onPreviousClicked());
    }

    @Test
    public void withoutPreviousTheTapRestarts() {
        hasPrevious = false;
        positionMs = 13_000;
        startPlaying();
        playFor(1_000);
        assertTrue(controller.onPreviousClicked());
        assertEquals(List.of(100L), seeks);
    }

    @Test
    public void pausedTimeDoesNotCount() {
        startPlaying();
        playFor(6_000);
        controller.onPause();
        playing = false;
        clockMs += 120_000;
        assertFalse(controller.onPreviousClicked());
        playing = true;
        controller.onPlay();
        playFor(5_000);
        assertTrue(controller.onPreviousClicked());
    }

    @Test
    public void errorRecoveryDoesNotCount() {
        startPlaying();
        playFor(4_000);
        controller.onEngineError(0, 0, new RuntimeException("source error"));
        controller.onNewVideo(current); // ErrorFixer reloads the same video
        clockMs += 15_000;
        positionMs = 30_000;
        assertFalse(controller.onPreviousClicked());
    }

    @Test
    public void previousVideoResumesWhereTheUserLeftIt() {
        Video previous = video("previous");
        remember(previous, 31_600);
        previous.fromPrevious = true; // set by VideoLoaderController.loadPrevious

        open(previous);

        assertEquals(List.of(31_600L), resumeSeeks);
        assertFalse(previous.fromPrevious);
        assertEquals(31_600, states.getByVideoId("previous").positionMs);
    }

    @Test
    public void shortVideoOpenedAnotherWayStillStartsAtZero() {
        Video other = video("other");
        remember(other, 31_600);

        open(other);

        assertTrue(resumeSeeks.isEmpty());
        assertNull(states.getByVideoId("other"));
    }

    @Test
    public void endedPreviousVideoStartsOver() {
        Video previous = video("previous");
        remember(previous, MUSIC_DURATION_MS - 1_000);
        previous.fromPrevious = true;

        open(previous);

        assertTrue(resumeSeeks.isEmpty());
    }

    private void open(Video item) {
        controller.onNewVideo(item); // while the old video is still current, as in the app
        current = item;
        positionMs = 0;
        controller.onVideoLoaded(item);
    }

    private void startPlaying() {
        controller.onNewVideo(current);
        playing = true;
        controller.onPlay();
    }

    private void playFor(long ms) {
        clockMs += ms;
        positionMs += ms;
    }

    private void remember(Video item, long position) {
        states.getStates().add(new State(item, position, MUSIC_DURATION_MS, 1.0f));
    }

    private static Video video(String id) {
        Video video = new Video();
        video.videoId = id;
        return video;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }
}
