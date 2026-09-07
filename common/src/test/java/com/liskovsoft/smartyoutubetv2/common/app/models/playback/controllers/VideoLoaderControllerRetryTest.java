package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.os.Looper;

import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.subjects.PublishSubject;

/** Real denial, Play/Pause, new-video and release callbacks against a local recording service. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoLoaderControllerRetryTest {
    private final List<String> requests = new ArrayList<>();
    private final List<PublishSubject<MediaItemFormatInfo>> responses = new ArrayList<>();
    private final List<Boolean> playWrites = new ArrayList<>();
    private VideoLoaderController controller;
    private Video current;
    private boolean containsMedia;
    private int suggestionsCancelled;
    private int transportErrors;
    private int sourcesOpened;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        current = video("denied-video");
        MediaItemService service = (MediaItemService) Proxy.newProxyInstance(
                MediaItemService.class.getClassLoader(), new Class<?>[] {MediaItemService.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getFormatInfoObserve")) {
                        throw new AssertionError("Unexpected service call: " + method.getName());
                    }
                    requests.add((String) args[0]);
                    PublishSubject<MediaItemFormatInfo> response = PublishSubject.create();
                    responses.add(response);
                    return response;
                });
        PlaybackView player = (PlaybackView) Proxy.newProxyInstance(
                PlaybackView.class.getClassLoader(), new Class<?>[] {PlaybackView.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getVideo": return current;
                        case "isEngineInitialized": return true;
                        case "containsMedia": return containsMedia;
                        case "setVideo": current = (Video) args[0]; return null;
                        case "resetPlayerState": containsMedia = false; return null;
                        case "setPlayWhenReady": playWrites.add((boolean) args[0]); return null;
                        case "isEmbed": return false;
                        case "showProgressBar":
                        case "showPlaybackNotice":
                        case "showOverlay":
                        case "showBackground":
                        case "setTitle": return null;
                        case "openUrlList": sourcesOpened++; containsMedia = true; return null;
                        default: throw new AssertionError("Unexpected player call: " + method.getName());
                    }
                });
        SuggestionsController suggestions = new SuggestionsController() {
            @Override public void cancelPendingSuggestions() { suggestionsCancelled++; }
        };
        ErrorFixerController errors = new ErrorFixerController() {
            @Override public void runFormatErrorAction(Throwable error) { transportErrors++; }
        };
        controller = new VideoLoaderController() {
            @Override public PlaybackView getPlayer() { return player; }
            @Override public Video getVideo() { return current; }
            @Override public Context getContext() { return context; }
            @Override protected MediaItemService getMediaItemService() { return service; }
            @Override protected <T extends PlayerEventListener> T getController(Class<T> type) {
                return type.cast(type == SuggestionsController.class ? suggestions : errors);
            }
        };
        controller.onInit();
    }

    @Test
    public void playAndPauseRetryTheNormalServiceOnceWhileRequestIsInFlight() {
        openAndDeny();
        controller.onPlayClicked();
        controller.onPauseClicked();
        controller.onPlayClicked();

        assertEquals(List.of("denied-video", "denied-video"), requests);
        assertEquals(List.of(true), playWrites);
        assertTrue(responses.get(1).hasObservers());
        assertEquals(0, sourcesOpened);
    }

    @Test
    public void firstPauseToggleAlsoActsAsAnExplicitRetry() {
        openAndDeny();
        controller.onPauseClicked();

        assertEquals(2, requests.size());
        assertEquals(List.of(true), playWrites);
    }

    @Test
    public void repeatedCachedDenialDoesNotScheduleAnAutomaticRequest() {
        openAndDeny();
        controller.onPlayClicked();
        complete(1, true); // The unchanged service may return the same negative/cooldown result.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1));

        assertEquals(2, requests.size());
        assertEquals(2, suggestionsCancelled);
        assertEquals(0, transportErrors);
        controller.onPlayClicked();
        assertEquals(3, requests.size()); // A new user action is the only trigger.
    }

    @Test
    public void playableResultClearsTheDenialRetryState() {
        openAndDeny();
        controller.onPlayClicked();
        complete(1, false);
        assertEquals(1, sourcesOpened);
        containsMedia = false; // Even without a source, the old denial must stay cleared.
        controller.onPlayClicked();

        assertEquals(2, requests.size());
    }

    @Test
    public void newVideoAndEngineReleaseDisposeAndClearThePendingRetry() {
        openAndDeny();
        controller.onPlayClicked();
        current = video("other-video");
        controller.onNewVideo(current);
        assertFalse(responses.get(1).hasObservers());
        controller.onPlayClicked();
        assertEquals(3, requests.size());

        complete(2, true);
        controller.onPlayClicked();
        assertEquals(4, requests.size());
        controller.onEngineReleased();
        assertFalse(responses.get(3).hasObservers());
        controller.onPlayClicked();
        assertEquals(4, requests.size());
    }

    @Test
    public void transportFailureHandsOffToExistingErrorHandlingAndClearsDenial() {
        openAndDeny();
        controller.onPlayClicked();
        responses.get(1).onError(new IOException("local test failure"));
        controller.onPlayClicked();

        assertEquals(1, transportErrors);
        assertEquals(2, requests.size());
    }

    private void openAndDeny() {
        controller.onNewVideo(current);
        complete(0, true);
        assertEquals(1, requests.size());
        assertEquals(1, suggestionsCancelled);
    }

    private void complete(int index, boolean denied) {
        responses.get(index).onNext(formatInfo(denied));
        responses.get(index).onComplete();
    }

    private MediaItemFormatInfo formatInfo(boolean denied) {
        return (MediaItemFormatInfo) Proxy.newProxyInstance(
                MediaItemFormatInfo.class.getClassLoader(), new Class<?>[] {MediaItemFormatInfo.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getVideoId": return current.videoId;
                        case "getTitle": return "Local test";
                        case "getPlayabilityReason": return denied ? "Sign in to confirm" : null;
                        case "isUnplayable":
                        case "isBotCheckRequired": return denied;
                        case "containsUrlFormats": return !denied;
                        case "createUrlList": return Collections.singletonList("https://example.invalid/media");
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == float.class) return 1f;
                    if (type == double.class) return 0d;
                    return null;
                });
    }

    private static Video video(String id) {
        Video video = new Video();
        video.videoId = id;
        video.fromQueue = true; // Playlist insertion is unrelated to the retry callbacks.
        return video;
    }
}
