package com.liskovsoft.smartyoutubetv2.common.app.presenters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelUploadsView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelView;
import com.liskovsoft.smartyoutubetv2.common.utils.LoadFailure;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowNetworkCapabilities;

import java.lang.reflect.Proxy;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * NEWTUBE(page-load-errors): the channel and uploads/playlist presenters against a recording
 * ContentService - every failure shape ends in a visible state, retry asks for the same thing
 * again, a refresh never blanks rows it can't replace, and a failed next page keeps its key.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ChannelPagesLoadFailureTest {
    /** "channel:<id>", "group:<playlistId>", "continue:<nextPageKey>" in call order. */
    private final List<String> requests = new ArrayList<>();
    private final List<PublishSubject<List<MediaGroup>>> channelResponses = new ArrayList<>();
    private final List<PublishSubject<MediaGroup>> groupResponses = new ArrayList<>();
    private final List<PublishSubject<MediaGroup>> continueResponses = new ArrayList<>();
    private ContentService service;

    @Before
    public void setUp() {
        GlobalPreferences.instance(RuntimeEnvironment.getApplication()); // VideoGroup.add -> blocked channels
        service = (ContentService) Proxy.newProxyInstance(
                ContentService.class.getClassLoader(), new Class<?>[] {ContentService.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getChannelObserve": {
                            Object key = args[0] instanceof MediaItem ? ((MediaItem) args[0]).getChannelId() : args[0];
                            requests.add("channel:" + key);
                            PublishSubject<List<MediaGroup>> response = PublishSubject.create();
                            channelResponses.add(response);
                            return response;
                        }
                        case "getGroupObserve": {
                            requests.add("group:" + ((MediaItem) args[0]).getPlaylistId());
                            PublishSubject<MediaGroup> response = PublishSubject.create();
                            groupResponses.add(response);
                            return response;
                        }
                        case "continueGroupObserve": {
                            requests.add("continue:" + ((MediaGroup) args[0]).getNextPageKey());
                            PublishSubject<MediaGroup> response = PublishSubject.create();
                            continueResponses.add(response);
                            return response;
                        }
                        default:
                            throw new AssertionError("Unexpected service call: " + method.getName());
                    }
                });
    }

    // ---------------------------------------------------------------------------------------
    // ChannelPresenter
    // ---------------------------------------------------------------------------------------

    @Test
    public void channelTransportFailureShowsNoConnectionInsteadOfABlankPage() {
        FakeChannelView view = openChannel(true);

        channelResponses.get(0).onError(new IllegalStateException(new UnknownHostException("www.youtube.com")));

        assertEquals(Collections.singletonList(LoadFailure.NO_CONNECTION), view.failures);
        assertFalse(view.progress);
    }

    @Test
    public void channelCompletingWithNothingIsNeutralEmptyAndStopsTheSpinner() {
        // getChannelObserve reports a null answer as a bare onComplete - the spinner used to stay up.
        FakeChannelView view = openChannel(true);

        channelResponses.get(0).onComplete();

        assertEquals(Collections.singletonList(LoadFailure.EMPTY), view.failures);
        assertFalse(view.progress);
    }

    @Test
    public void channelNullAnswerWhileOfflineIsNoConnection() {
        FakeChannelView view = openChannel(false);

        channelResponses.get(0).onComplete();

        assertEquals(Collections.singletonList(LoadFailure.NO_CONNECTION), view.failures);
    }

    @Test
    public void channelOtherErrorIsAnError() {
        FakeChannelView view = openChannel(true);

        channelResponses.get(0).onError(new RuntimeException("unexpected json"));

        assertEquals(Collections.singletonList(LoadFailure.ERROR), view.failures);
    }

    @Test
    public void channelRowsArrivingReportNoFailure() {
        FakeChannelView view = openChannel(true);

        channelResponses.get(0).onNext(Collections.singletonList(group("Videos", "k1", "a", "b")));
        channelResponses.get(0).onComplete();

        assertTrue(view.failures.isEmpty());
        assertEquals(Arrays.asList("a", "b"), ids(view.lastUpdate));
        assertFalse(view.progress);
    }

    @Test
    public void channelRetryAsksForTheSameChannelFromABlankPage() {
        ChannelPresenter presenter = newChannelPresenter();
        FakeChannelView view = channelView(true);
        presenter.setView(view);
        presenter.openChannel("UC1");
        channelResponses.get(0).onComplete();
        view.events.clear();

        assertTrue(presenter.reload(false));

        assertEquals(Arrays.asList("channel:UC1", "channel:UC1"), requests);
        assertEquals("clear", view.events.get(0)); // the failure state goes before the new load
        channelResponses.get(1).onNext(Collections.singletonList(group("Videos", "k1", "a", "b")));
        assertEquals(Arrays.asList("a", "b"), ids(view.lastUpdate));
        assertEquals(1, view.failures.size()); // only the first attempt's
    }

    @Test
    public void channelRefreshKeepsRowsUntilFreshOnesLandAndKeepsThemWhenItFails() {
        ChannelPresenter presenter = newChannelPresenter();
        FakeChannelView view = channelView(true);
        presenter.setView(view);
        presenter.openChannel("UC1");
        channelResponses.get(0).onNext(Collections.singletonList(group("Videos", "k1", "a", "b")));
        channelResponses.get(0).onComplete();
        view.events.clear();

        assertTrue(presenter.reload(true));
        channelResponses.get(1).onError(new IllegalStateException(new UnknownHostException("www.youtube.com")));

        assertFalse(view.events.contains("clear")); // rows stay; the view decides how to say it failed
        assertEquals(Collections.singletonList(LoadFailure.NO_CONNECTION), view.failures);

        view.events.clear();
        assertTrue(presenter.reload(true));
        assertFalse(view.events.contains("clear"));
        channelResponses.get(2).onNext(Collections.singletonList(group("Videos", "k2", "c")));

        assertEquals("clear", view.events.get(view.events.indexOf("update") - 1)); // swapped in one go
        assertEquals(1, Collections.frequency(view.events, "clear"));
        assertEquals(Collections.singletonList("c"), ids(view.lastUpdate));
    }

    @Test
    public void channelFailedNextPageKeepsRowsAndRetriesTheSameKey() {
        ChannelPresenter presenter = newChannelPresenter();
        FakeChannelView view = channelView(true);
        presenter.setView(view);
        presenter.openChannel("UC1");
        channelResponses.get(0).onNext(Collections.singletonList(group("Videos", "k1", "a", "b")));
        channelResponses.get(0).onComplete();
        Video last = view.lastUpdate.getVideos().get(1);
        view.events.clear();

        presenter.onScrollEnd(last);
        continueResponses.get(0).onError(new IllegalStateException(new UnknownHostException("www.youtube.com")));

        assertEquals(Collections.singletonList("loadMore"), filter(view.events, "loadMore", "clear", "failure"));
        assertFalse(view.progress);

        presenter.onScrollEnd(last); // the footer's Try again
        continueResponses.get(1).onNext(group("Videos", "k2", "c"));

        assertEquals(Arrays.asList("channel:UC1", "continue:k1", "continue:k1"), requests);
        assertEquals(Arrays.asList("a", "b", "c"), ids(view.lastUpdate));
    }

    @Test
    public void channelLastPageDoesNotAskForMore() {
        ChannelPresenter presenter = newChannelPresenter();
        FakeChannelView view = channelView(true);
        presenter.setView(view);
        presenter.openChannel("UC1");
        channelResponses.get(0).onNext(Collections.singletonList(group("Videos", null, "a")));

        presenter.onScrollEnd(view.lastUpdate.getVideos().get(0));

        assertEquals(Collections.singletonList("channel:UC1"), requests);
        assertTrue(view.failures.isEmpty());
    }

    @Test
    public void handedInChannelReloadsTheWayItWasFetched() {
        // MediaServiceManager.chooseChannelPresenter: clear() + setChannel(card) + updateRows(rows).
        ChannelPresenter presenter = newChannelPresenter();
        FakeChannelView view = channelView(true);
        presenter.setView(view);
        presenter.clear();
        presenter.setChannel(Video.from(mediaItem(null, null, "UC9")));
        presenter.updateRows(Collections.singletonList(group("Videos", "k1", "a")));

        assertTrue(presenter.reload(true));

        assertEquals(Collections.singletonList("channel:UC9"), requests);
    }

    @Test
    public void nothingToReloadWithoutAChannel() {
        ChannelPresenter presenter = newChannelPresenter();
        presenter.setView(channelView(true));
        presenter.clear();
        presenter.setChannel(Video.from(mediaItem("v1", null))); // a video whose channel is unknown

        assertFalse(presenter.reload(false));
        assertTrue(requests.isEmpty());
    }

    // ---------------------------------------------------------------------------------------
    // ChannelUploadsPresenter
    // ---------------------------------------------------------------------------------------

    @Test
    public void emptyPlaylistIsNeutralEmptyAndStopsTheSpinner() {
        FakeUploadsView view = uploadsView(true);
        ChannelUploadsPresenter presenter = newUploadsPresenter();
        presenter.setView(view);
        presenter.openChannel(playlist("PL1"));

        groupResponses.get(0).onNext(group("Mix", null));

        assertEquals(Collections.singletonList(LoadFailure.EMPTY), view.failures);
        assertFalse(view.progress);
    }

    @Test
    public void failedPlaylistShowsTheFailureAndRetryRefetchesTheSameList() {
        FakeUploadsView view = uploadsView(true);
        ChannelUploadsPresenter presenter = newUploadsPresenter();
        presenter.setView(view);
        presenter.openChannel(playlist("PL1"));

        groupResponses.get(0).onError(new IllegalStateException(new UnknownHostException("www.youtube.com")));

        assertEquals(Collections.singletonList(LoadFailure.NO_CONNECTION), view.failures);
        assertFalse(view.progress);

        view.events.clear();
        assertTrue(presenter.reload(false));
        assertEquals("clear", view.events.get(0));
        groupResponses.get(1).onNext(group("Mix", "k1", "a", "b"));

        assertEquals(Arrays.asList("group:PL1", "group:PL1"), requests);
        assertEquals(Arrays.asList("a", "b"), ids(view.lastUpdate));
        assertEquals(1, view.failures.size());
    }

    @Test
    public void playlistNullAnswerIsNeutralEmpty() {
        FakeUploadsView view = uploadsView(true);
        ChannelUploadsPresenter presenter = newUploadsPresenter();
        presenter.setView(view);
        presenter.openChannel(playlist("PL1"));

        groupResponses.get(0).onError(new IllegalStateException("fromNullable result is null"));

        assertEquals(Collections.singletonList(LoadFailure.EMPTY), view.failures);
    }

    @Test
    public void playlistRefreshReplacesTheListInsteadOfAppendingToIt() {
        FakeUploadsView view = uploadsView(true);
        ChannelUploadsPresenter presenter = newUploadsPresenter();
        presenter.setView(view);
        presenter.openChannel(playlist("PL1"));
        groupResponses.get(0).onNext(group("Mix", "k1", "a", "b"));
        view.events.clear();

        assertTrue(presenter.reload(true));
        assertFalse(view.events.contains("clear"));
        groupResponses.get(1).onNext(group("Mix", "k2", "b", "c"));

        assertEquals(1, Collections.frequency(view.events, "clear"));
        assertEquals(Arrays.asList("b", "c"), ids(view.lastUpdate));
    }

    @Test
    public void playlistRefreshFailureKeepsTheList() {
        FakeUploadsView view = uploadsView(true);
        ChannelUploadsPresenter presenter = newUploadsPresenter();
        presenter.setView(view);
        presenter.openChannel(playlist("PL1"));
        groupResponses.get(0).onNext(group("Mix", "k1", "a", "b"));
        view.events.clear();

        assertTrue(presenter.reload(true));
        groupResponses.get(1).onError(new RuntimeException("unexpected json"));

        assertFalse(view.events.contains("clear"));
        assertEquals(Collections.singletonList(LoadFailure.ERROR), view.failures);
    }

    @Test
    public void playlistFailedNextPageKeepsItemsAndRetriesTheSameKey() {
        FakeUploadsView view = uploadsView(true);
        ChannelUploadsPresenter presenter = newUploadsPresenter();
        presenter.setView(view);
        presenter.openChannel(playlist("PL1"));
        groupResponses.get(0).onNext(group("Mix", "k1", "a", "b"));
        Video last = view.lastUpdate.getVideos().get(1);
        view.events.clear();

        presenter.onScrollEnd(last);
        continueResponses.get(0).onError(new IllegalStateException(new UnknownHostException("www.youtube.com")));

        assertEquals(Collections.singletonList("loadMore"), filter(view.events, "loadMore", "clear", "failure"));
        assertFalse(view.progress);

        presenter.onScrollEnd(last);
        continueResponses.get(1).onNext(group("Mix", "k2", "c"));

        assertEquals(Arrays.asList("group:PL1", "continue:k1", "continue:k1"), requests);
        assertEquals(Arrays.asList("a", "b", "c"), ids(view.lastUpdate));
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private FakeChannelView openChannel(boolean online) {
        ChannelPresenter presenter = newChannelPresenter();
        FakeChannelView view = channelView(online);
        presenter.setView(view);
        presenter.openChannel("UC1");
        assertEquals(Collections.singletonList("channel:UC1"), requests);
        assertTrue(view.progress);
        return view;
    }

    private ChannelPresenter newChannelPresenter() {
        return new ChannelPresenter(RuntimeEnvironment.getApplication()) {
            @Override
            protected ContentService getContentService() {
                return service;
            }
        };
    }

    private ChannelUploadsPresenter newUploadsPresenter() {
        return new ChannelUploadsPresenter(RuntimeEnvironment.getApplication()) {
            @Override
            protected ContentService getContentService() {
                return service;
            }
        };
    }

    private static FakeChannelView channelView(boolean online) {
        FakeChannelView view = Robolectric.buildActivity(FakeChannelView.class).setup().get();
        setOnline(view, online);
        return view;
    }

    private static FakeUploadsView uploadsView(boolean online) {
        FakeUploadsView view = Robolectric.buildActivity(FakeUploadsView.class).setup().get();
        setOnline(view, online);
        return view;
    }

    private static void setOnline(Context context, boolean online) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        assertNotNull(cm.getActiveNetwork());
        NetworkCapabilities caps = ShadowNetworkCapabilities.newInstance();
        if (online) {
            Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.getActiveNetwork(), caps);
        assertEquals(online, LoadFailure.hasValidatedNetwork(context));
    }

    private static Video playlist(String playlistId) {
        return Video.from(mediaItem(null, playlistId));
    }

    private static MediaGroup group(String title, String nextPageKey, String... videoIds) {
        List<MediaItem> items = new ArrayList<>();
        for (String videoId : videoIds) {
            items.add(mediaItem(videoId, null));
        }

        return (MediaGroup) Proxy.newProxyInstance(
                MediaGroup.class.getClassLoader(), new Class<?>[] {MediaGroup.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getMediaItems": return items;
                        case "getTitle": return title;
                        case "getNextPageKey": return nextPageKey;
                        case "isEmpty": return items.isEmpty();
                        case "getType": return MediaGroup.TYPE_CHANNEL_UPLOADS;
                        default: return defaultValue(proxy, method, args);
                    }
                });
    }

    private static MediaItem mediaItem(String videoId, String playlistId) {
        return mediaItem(videoId, playlistId, null);
    }

    private static MediaItem mediaItem(String videoId, String playlistId, String channelId) {
        return (MediaItem) Proxy.newProxyInstance(
                MediaItem.class.getClassLoader(), new Class<?>[] {MediaItem.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getVideoId": return videoId;
                        case "getPlaylistId": return playlistId;
                        case "getChannelId": return channelId;
                        case "getTitle": return videoId != null ? videoId : playlistId != null ? playlistId : channelId;
                        case "getPercentWatched": return -1;
                        default: return defaultValue(proxy, method, args);
                    }
                });
    }

    private static Object defaultValue(Object proxy, java.lang.reflect.Method method, Object[] args) {
        switch (method.getName()) {
            case "hashCode": return System.identityHashCode(proxy);
            case "equals": return proxy == args[0];
            case "toString": return "Fake" + method.getDeclaringClass().getSimpleName();
        }

        Class<?> type = method.getReturnType();
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    private static List<String> ids(VideoGroup group) {
        List<String> result = new ArrayList<>();
        for (Video video : group.getVideos()) {
            result.add(video.videoId);
        }
        return result;
    }

    private static List<String> filter(List<String> events, String... keep) {
        List<String> result = new ArrayList<>();
        for (String event : events) {
            for (String prefix : keep) {
                if (event.startsWith(prefix)) {
                    result.add(event);
                }
            }
        }
        return result;
    }

    /** Records what the presenter asks of the page. A live Activity, because BasePresenter drops dead views. */
    public static class FakeChannelView extends Activity implements ChannelView {
        final List<String> events = new ArrayList<>();
        final List<Integer> failures = new ArrayList<>();
        VideoGroup lastUpdate;
        boolean progress;

        @Override public void update(VideoGroup videoGroup) { events.add("update"); lastUpdate = videoGroup; }
        @Override public void setPosition(int index) { }
        @Override public void showProgressBar(boolean show) { progress = show; }
        @Override public void clear() { events.add("clear"); }
        @Override public void showLoadFailure(int state) { events.add("failure:" + state); failures.add(state); }
        @Override public void showLoadMoreFailure() { events.add("loadMore"); }
    }

    public static class FakeUploadsView extends Activity implements ChannelUploadsView {
        final List<String> events = new ArrayList<>();
        final List<Integer> failures = new ArrayList<>();
        VideoGroup lastUpdate;
        boolean progress;

        @Override public void update(VideoGroup videoGroup) { events.add("update"); lastUpdate = videoGroup; }
        @Override public void showProgressBar(boolean show) { progress = show; }
        @Override public void clear() { events.add("clear"); }
        @Override public void showLoadFailure(int state) { events.add("failure:" + state); failures.add(state); }
        @Override public void showLoadMoreFailure() { events.add("loadMore"); }
    }
}
