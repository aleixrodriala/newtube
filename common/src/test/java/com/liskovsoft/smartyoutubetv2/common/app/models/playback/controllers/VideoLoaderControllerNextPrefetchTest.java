package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.content.Context;
import android.os.Looper;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The autoplay-next /player prefetch follows a playhead deadline after real playback, retries a
 * failure once, follows a changed candidate and never stacks requests.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoLoaderControllerNextPrefetchTest {
    private final List<String> resolutions = new ArrayList<>();
    private final List<MediaServiceManager.OnFormatInfo> answers = new ArrayList<>();
    private final List<MediaServiceManager.OnError> failures = new ArrayList<>();
    private VideoLoaderController controller;
    private Video current;
    private Video next;
    private boolean playing = true;
    private long durationMs = 600_000;
    private long positionMs;
    private float speed = 1f;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        PlayerData.instance(context).setPlaybackMode(PlayerConstants.PLAYBACK_MODE_ALL);
        current = video("current");
        next = video("next");
        PlaybackView player = (PlaybackView) Proxy.newProxyInstance(
                PlaybackView.class.getClassLoader(), new Class<?>[] {PlaybackView.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getVideo": return current;
                        case "isEmbed": return false;
                        case "isPlaying": return playing;
                        case "getDurationMs": return durationMs;
                        case "getPositionMs": return positionMs;
                        case "getSpeed": return speed;
                        default: throw new AssertionError("Unexpected player call: " + method.getName());
                    }
                });
        SuggestionsController suggestions = new SuggestionsController() {
            @Override public Video getNext() { return next; }
        };
        controller = new VideoLoaderController() {
            @Override public PlaybackView getPlayer() { return player; }
            @Override public Video getVideo() { return current; }
            @Override public Context getContext() { return context; }
            @Override protected <T extends PlayerEventListener> T getController(Class<T> type) {
                return type == SuggestionsController.class ? type.cast(suggestions) : null;
            }
            @Override protected void loadNextFormatInfo(Video video,
                    MediaServiceManager.OnFormatInfo onFormatInfo, MediaServiceManager.OnError onError) {
                resolutions.add(video.videoId);
                answers.add(onFormatInfo);
                failures.add(onError);
            }
        };
        controller.onInit();
    }

    @Test
    public void resolvesTheNextVideoTwentySecondsBeforeTheEnd() {
        positionMs = 540_000; // 60 s left -> due in 40 s
        controller.onPlay();

        advance(39_000);
        assertEquals(0, resolutions.size());
        positionMs = 580_000;
        advance(1_000);
        assertEquals(1, resolutions.size());
        assertEquals("next", resolutions.get(0));
    }

    @Test
    public void repeatedEventsTheTickAndTheRecheckNeverStackRequests() {
        watchThenJumpTo(590_000);
        controller.onPlay();
        controller.onSeekEnd();
        controller.onTickle();
        advance(10_000); // rechecks while in flight
        assertEquals(1, resolutions.size());
        answerLast();
        advance(30_000);
        assertEquals(1, resolutions.size());
    }

    @Test
    public void failedPrefetchIsRetriedOnceAfterTheBackoff() {
        watchThenJumpTo(590_000);
        assertEquals(1, resolutions.size());
        failLast();
        advance(4_000);
        assertEquals(1, resolutions.size());
        advance(2_000); // the recheck after the 5 s backoff
        assertEquals(2, resolutions.size());
        assertEquals("next", resolutions.get(1));
        failLast();
        advance(20_000);
        assertEquals(2, resolutions.size());
    }

    @Test
    public void changedCandidateAfterTheDeadlineIsPickedUpWithoutTheMinuteTick() {
        watchThenJumpTo(590_000);
        answerLast();
        next = video("queue-reordered"); // a queue edit / late shuffle pick
        advance(2_000);
        assertEquals(2, resolutions.size());
        assertEquals("queue-reordered", resolutions.get(1));
    }

    @Test
    public void pauseCancelsAndPlayRearms() {
        positionMs = 540_000;
        controller.onPlay();
        advance(10_000);
        controller.onPause();
        playing = false;
        advance(60_000);
        assertEquals(0, resolutions.size());

        playing = true;
        positionMs = 580_000;
        controller.onPlay();
        assertEquals(1, resolutions.size());
    }

    @Test
    public void seekIntoTheLastSecondsAfterWatchingResolvesAtOnce() {
        positionMs = 100_000;
        controller.onPlay();
        advance(10_000);
        positionMs = 595_000;
        controller.onSeekEnd();
        assertEquals(1, resolutions.size());
    }

    @Test
    public void seekingToTheEndOfAClipIsNotWatchingIt() {
        durationMs = 15_000;
        positionMs = 1_000;
        controller.onPlay();
        advance(1_000);
        positionMs = 14_000; // skim: the playhead is at the end, 1 s was played
        controller.onSeekEnd();
        assertEquals(0, resolutions.size());
        advance(3_900);
        assertEquals(0, resolutions.size());
        advance(100);
        assertEquals(1, resolutions.size());
    }

    @Test
    public void rebufferingIsNotWatching() {
        durationMs = 15_000;
        controller.onPlay();
        advance(2_000);
        playing = false; // BUFFERING: not READY
        controller.onBuffering();
        advance(10_000);
        assertEquals(0, resolutions.size());
        playing = true;
        controller.onPlay();
        advance(2_900);
        assertEquals(0, resolutions.size());
        advance(100);
        assertEquals(1, resolutions.size());
    }

    @Test
    public void speedChangeMovesTheDeadline() {
        positionMs = 500_000; // 100 s of content = 80 s to wait at 1x
        controller.onPlay();
        speed = 2f; // 100 s of content = 50 s of wall time -> due in 30 s
        controller.onSpeedChanged(2f);
        advance(29_000);
        assertEquals(0, resolutions.size());
        positionMs = 560_000;
        advance(1_000);
        assertEquals(1, resolutions.size());
    }

    @Test
    public void unknownNextTargetIsLookedUpAgainShortly() {
        next = null; // /next still in flight
        watchThenJumpTo(590_000);
        assertEquals(0, resolutions.size());
        next = video("late");
        advance(2_000);
        assertEquals(1, resolutions.size());
        advance(10_000);
        assertEquals(1, resolutions.size()); // in flight: no second request
    }

    @Test
    public void noAutoplayModeNeverResolves() {
        PlayerData.instance(RuntimeEnvironment.getApplication())
                .setPlaybackMode(PlayerConstants.PLAYBACK_MODE_ONE);
        watchThenJumpTo(590_000);
        advance(30_000);
        assertEquals(0, resolutions.size());
    }

    @Test
    public void liveNeverSchedules() {
        current.isLive = true;
        watchThenJumpTo(590_000);
        advance(30_000);
        assertEquals(0, resolutions.size());
    }

    @Test
    public void recheckStopsOncePlaybackStops() {
        watchThenJumpTo(590_000);
        answerLast();
        playing = false; // ENDED / paused by the system: isPlaying() is false
        next = video("changed-after-end");
        advance(30_000);
        assertEquals(1, resolutions.size());
    }

    /** Play 10 s from the middle, then seek to {@code position}: the seek is due at once. */
    private void watchThenJumpTo(long position) {
        positionMs = 300_000;
        controller.onPlay();
        advance(10_000);
        positionMs = position;
        controller.onSeekEnd();
    }

    private void answerLast() {
        answers.get(answers.size() - 1).onFormatInfo(unplayableInfo());
    }

    private void failLast() {
        failures.get(failures.size() - 1).onError(new java.io.IOException("timeout"));
    }

    private static MediaItemFormatInfo unplayableInfo() {
        return (MediaItemFormatInfo) Proxy.newProxyInstance(MediaItemFormatInfo.class.getClassLoader(),
                new Class<?>[] {MediaItemFormatInfo.class}, (proxy, method, args) -> {
                    Class<?> type = method.getReturnType();
                    if (method.getName().equals("isUnplayable")) return true;
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == float.class) return 1f;
                    return null;
                });
    }

    private static void advance(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private static Video video(String id) {
        Video video = new Video();
        video.videoId = id;
        return video;
    }
}
