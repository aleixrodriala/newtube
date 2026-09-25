package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertEquals;

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

import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import io.reactivex.rxjava3.subjects.PublishSubject;

/** The "has the scheduled stream started yet?" poll, against a local recording service. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoLoaderControllerUpcomingPollTest {
    private final List<String> requests = new ArrayList<>();
    private final List<PublishSubject<MediaItemFormatInfo>> responses = new ArrayList<>();
    private VideoLoaderController controller;
    private Video current;
    private boolean inPip;
    private int suggestionRequests;
    private String startTimestamp;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        current = video("upcoming-video");
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
                        case "containsMedia": return false;
                        case "isInPIPMode": return inPip;
                        case "getPositionMs": return 0L;
                        case "setVideo": current = (Video) args[0]; return null;
                        case "isEmbed": return false;
                        case "resetPlayerState":
                        case "showProgressBar":
                        case "showPlaybackNotice":
                        case "showOverlay":
                        case "showBackground":
                        case "setTitle": return null;
                        default: throw new AssertionError("Unexpected player call: " + method.getName());
                    }
                });
        SuggestionsController suggestions = new SuggestionsController() {
            @Override public void loadSuggestionsOnce(Video video) { suggestionRequests++; }
            @Override public void loadSuggestions(Video video) {
                throw new AssertionError("an upcoming poll must go through loadSuggestionsOnce");
            }
        };
        ErrorFixerController errors = new ErrorFixerController() {
            @Override public void runFormatErrorAction(Throwable error) {
                throw new AssertionError("Unexpected transport error");
            }
        };
        controller = new VideoLoaderController() {
            @Override public PlaybackView getPlayer() { return player; }
            @Override public Video getVideo() { return current; }
            @Override public Context getContext() { return context; }
            @Override protected MediaItemService getMediaItemService() { return service; }
            @Override protected <T extends PlayerEventListener> T getController(Class<T> type) {
                return type.cast(type == SuggestionsController.class ? suggestions : errors);
            }
            @Override protected PlayerEventListener getMainController() {
                VideoLoaderController self = this;
                // PlaybackPresenter fans the reload's onNewVideo out to every controller.
                return (PlayerEventListener) Proxy.newProxyInstance(
                        PlayerEventListener.class.getClassLoader(), new Class<?>[] {PlayerEventListener.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("onNewVideo")) {
                                Video video = (Video) args[0];
                                video.fromQueue = true; // Playlist insertion is unrelated here
                                self.onNewVideo(video);
                                return null;
                            }
                            throw new AssertionError("Unexpected main-controller call: " + method.getName());
                        });
            }
        };
        controller.onInit();
        controller.onViewResumed();
    }

    @Test
    public void unknownStartPollsEvery30sThenSlowsAndNeverRefetchesSuggestionsItself() {
        controller.onNewVideo(current);
        answerNotLive(0);

        // The first ten answers (0..9) are each followed by a 30 s wait.
        for (int poll = 1; poll <= 10; poll++) {
            idle(Duration.ofSeconds(29));
            assertEquals(poll, requests.size());
            idle(Duration.ofSeconds(1));
            assertEquals(poll + 1, requests.size());
            answerNotLive(poll);
        }

        // The eleventh not-live answer is followed by a 60 s wait.
        idle(Duration.ofSeconds(59));
        assertEquals(11, requests.size());
        idle(Duration.ofSeconds(1));
        assertEquals(12, requests.size());
        assertEquals(11, suggestionRequests); // every poll asks the once-per-video gate, never /next itself
    }

    @Test
    public void knownStartDrivesTheDelay() {
        startTimestamp = iso(System.currentTimeMillis() + 20 * 60_000);
        controller.onNewVideo(current);
        answerNotLive(0); // a quarter of the 20 minutes left

        idle(Duration.ofSeconds(290));
        assertEquals(1, requests.size());
        idle(Duration.ofSeconds(10));
        assertEquals(2, requests.size());
    }

    @Test
    public void hiddenSlateSlowsThePollAndReturningBringsItBackIn() {
        controller.onNewVideo(current);
        controller.onViewPaused(); // screen off / background audio
        answerNotLive(0);

        idle(Duration.ofSeconds(40));
        assertEquals(1, requests.size()); // 60 s while hidden, not 30 s

        controller.onViewResumed(); // the foreground due time already passed
        idle(Duration.ofSeconds(1));
        assertEquals(2, requests.size());
    }

    @Test
    public void pipWindowCountsAsWatching() {
        controller.onNewVideo(current);
        controller.onViewPaused();
        inPip = true;
        answerNotLive(0);

        idle(Duration.ofSeconds(30));
        assertEquals(2, requests.size());
    }

    private void answerNotLive(int index) {
        responses.get(index).onNext(upcomingFormatInfo());
        responses.get(index).onComplete();
    }

    private static void idle(Duration duration) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(duration);
    }

    /** No media, not unplayable: the "future live translation" branch. */
    private MediaItemFormatInfo upcomingFormatInfo() {
        return (MediaItemFormatInfo) Proxy.newProxyInstance(
                MediaItemFormatInfo.class.getClassLoader(), new Class<?>[] {MediaItemFormatInfo.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getVideoId": return current.videoId;
                        case "getTitle": return "Upcoming";
                        case "getPlayabilityReason": return "Live in 20 minutes";
                        case "getStartTimestamp": return startTimestamp;
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

    private static String iso(long timeMs) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'+00:00'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(timeMs));
    }

    private static Video video(String id) {
        Video video = new Video();
        video.videoId = id;
        video.fromQueue = true; // Playlist insertion is unrelated to the poll
        return video;
    }
}
