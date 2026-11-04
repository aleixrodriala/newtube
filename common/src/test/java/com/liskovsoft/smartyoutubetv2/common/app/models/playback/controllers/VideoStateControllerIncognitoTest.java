package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;

/** Real controller entry/close/release callbacks with local history and a recording player. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 35}, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class VideoStateControllerIncognitoTest {
    private Video current;
    private VideoStateService states;
    private VideoStateController controller;
    private boolean containsMedia;
    private long position = 25_000;
    private final List<Long> historySeeks = new ArrayList<>();
    private static final long DURATION = 600_000;

    @Before public void before() {
        Context context = RuntimeEnvironment.getApplication();
        current = Video.from("aaaaaaaaaaa");
        states = VideoStateService.instance(context);
        states.getStates().clear();
        Playlist.instance().clear();
        GeneralData.instance(context).setHistoryState(GeneralData.HISTORY_DISABLED);
        GeneralData.instance(context).setHideWatchedFromNotificationsEnabled(false);
        MediaServiceData.instance().setContentHidden(MediaServiceData.CONTENT_WATCHED_WATCH_LATER, false);
        PlaybackView player = (PlaybackView) Proxy.newProxyInstance(
                PlaybackView.class.getClassLoader(), new Class<?>[] {PlaybackView.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getVideo": return current;
                        case "containsMedia": return containsMedia;
                        case "getHistoryPositionMs":
                        case "getPositionMs": return position;
                        case "getDurationMs": return DURATION;
                        case "getSpeed": return 1.0f;
                        case "getPlayWhenReady": return true;
                        case "setResumePositionMs":
                            historySeeks.add((Long) args[0]);
                            position = (Long) args[0];
                            return null;
                        case "setPositionMs":
                            position = (Long) args[0];
                            return null;
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == float.class) return 0f;
                    return null;
                });
        controller = new VideoStateController() {
            @Override public PlaybackView getPlayer() { return player; }
            @Override public Video getVideo() { return current; }
            @Override public Context getContext() { return context; }
        };
    }

    @After public void after() {
        states.getStates().clear();
        Playlist.instance().clear();
    }

    private void enterPrivate() {
        containsMedia = false;
        current.incognito = true;
        controller.onNewVideo(current);
        containsMedia = true;
        assertTrue(controller.isIncognitoSession());
        assertFalse(current.incognito); // The routing flag has been consumed.
    }

    @Test public void retiringOldAccountCannotWriteItsPositionIntoNewAccount() {
        controller.onNewVideo(current);
        containsMedia = true;
        controller.suppressAccountExitWrites();
        controller.onFinish();
        controller.onEngineReleased();
        assertNull("Old-account final flush must not save using the incoming account", states.getByVideoId(current.videoId));
        current = Video.from("bbbbbbbbbbb");
        containsMedia = false;
        controller.onNewVideo(current);
        containsMedia = true;
        controller.onEngineReleased();
        assertNotNull("New account playback still saves normally", states.getByVideoId(current.videoId));
    }

    @Test public void privatePlaybackDoesNotSaveLocalProgress() {
        enterPrivate();
        controller.onEngineReleased();
        assertNull("Private position must not enter local watch history", states.getByVideoId(current.videoId));
        assertEquals(-1f, current.percentWatched, 0f);
    }

    @Test public void privateCompletionDoesNotMarkTheVideoWatched() {
        enterPrivate();
        position = DURATION;
        controller.onEngineReleased();
        assertNull(states.getByVideoId(current.videoId));
        assertEquals(-1f, current.percentWatched, 0f);
    }

    @Test public void closingPrivatePlayerBeforeEngineReleaseDoesNotSaveProgress() {
        enterPrivate();
        controller.onFinish(); // Mini-player X calls this before Activity destruction releases the engine.
        assertFalse(controller.isIncognitoSession());
        assertNull(controller.getIncognitoVideoId());
        controller.onEngineReleased();
        assertNull("The final engine flush must retain private provenance", states.getByVideoId(current.videoId));
        assertEquals(-1f, current.percentWatched, 0f);
    }

    @Test public void openingPrivateVideoDoesNotResetExistingNormalHistory() {
        State normal = new State(current, DURATION - 1_000, DURATION, 1f);
        states.getStates().add(normal);
        current.percentWatched = 99f;
        enterPrivate();
        assertSame("Entering incognito must leave earlier normal history alone", normal, states.getByVideoId(current.videoId));
        assertEquals(99f, current.percentWatched, 0f);
    }

    @Test public void normalPlaybackAfterClosingPrivatePlayerStillSavesProgress() {
        enterPrivate();
        controller.onFinish();
        containsMedia = false;
        current = Video.from("bbbbbbbbbbb");
        controller.onNewVideo(current);
        containsMedia = true;
        controller.onEngineReleased();
        assertFalse(controller.isIncognitoSession());
        assertNotNull(states.getByVideoId(current.videoId));
        assertEquals(position, states.getByVideoId(current.videoId).positionMs);
        assertTrue(current.percentWatched > 0);
    }

    @Test public void privateVideoIdIsUpdatedBeforeTheNextQueuedVideoIsSelected() {
        enterPrivate();
        assertEquals(current.videoId, controller.getIncognitoVideoId());
        current = Video.from("bbbbbbbbbbb");
        controller.onNewVideo(current); // The private session continues across another open.
        assertTrue(controller.isIncognitoSession());
        assertEquals(current.videoId, controller.getIncognitoVideoId());
    }

    @Test public void completedNormalVideoStartsPrivatelyAtTheBeginningWithoutChangingHistory() {
        State normal = new State(current, DURATION - 1_000, DURATION, 1f);
        states.getStates().add(normal);
        current.percentWatched = 99f;
        enterPrivate();
        position = 0; // Fresh source before the normal onVideoLoaded restoration callback.
        controller.onVideoLoaded(current);
        assertEquals("Private startup must not resume the completed normal position", 0, position);
        assertTrue(historySeeks.isEmpty());
        assertSame(normal, states.getByVideoId(current.videoId));
        assertEquals(99f, current.percentWatched, 0f);
    }

    @Test public void explicitPrivateTimestampStillTakesPrecedenceOverCompletedHistory() {
        State normal = new State(current, DURATION - 1_000, DURATION, 1f);
        states.getStates().add(normal);
        current.pendingPosMs = 12_000;
        enterPrivate();
        position = 0;
        controller.onVideoLoaded(current);
        assertEquals(12_000, position);
        assertEquals(0, current.pendingPosMs);
        assertSame(normal, states.getByVideoId(current.videoId));
    }

    @Test public void privateLiveOpenUsesTheLiveEdgeWithoutDeletingNormalHistory() {
        current.isLive = true;
        com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData.instance(RuntimeEnvironment.getApplication())
                .setRememberPositionOfLiveVideosEnabled(false);
        State normal = new State(current, 120_000, DURATION, 1f);
        states.getStates().add(normal);
        enterPrivate();
        position = 0;
        controller.onVideoLoaded(current);
        assertTrue("Private live playback should start near the edge", position > DURATION - 60_000);
        assertTrue(historySeeks.isEmpty());
        assertSame(normal, states.getByVideoId(current.videoId));
    }
}
