package com.newtube.mobile.ui.playback;

import static org.junit.Assert.*;
import android.app.Application;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.dialog.MaxHeightRecyclerView;
import java.util.Arrays;
import java.util.List;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

/** Exercise the real Activity queue binding without starting the decoder or remote presenters. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 35}, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class QueueCardRecoveryTest {
    public static class Host extends MobilePlaybackActivity {
        Video current;
        @Override public Video getVideo() { return current; }
    }
    private Host host;
    private Playlist queue;
    private Video a, b, c;
    private View card;
    private MaxHeightRecyclerView rows;
    private DeferredPlaybackUi gate;

    private static Video video(String id) {
        Video v = new Video(); v.videoId = id; v.title = id; return v;
    }
    private void set(String field, Object value) { ReflectionHelpers.setField(host, field, value); }
    private void refresh() { ReflectionHelpers.callInstanceMethod(host, "refreshQueueSuggestions"); }
    private List<Video> videos() { return ReflectionHelpers.getField(host, "mQueueVideos"); }

    @Before public void before() {
        // attach supplies Activity resources; onCreate would start unrelated network/player work.
        host = Robolectric.buildActivity(Host.class).get();
        host.setTheme(R.style.Theme_NewTube);
        queue = Playlist.instance(); queue.clear();
        a = video("aaaaaaaaaaa"); b = video("bbbbbbbbbbb"); c = video("ccccccccccc");
        queue.add(a); queue.setCurrent(a);
        b.fromQueue = true; queue.add(b); c.fromQueue = true; queue.add(c);
        b.fromQueue = false; c.fromQueue = false;
        host.current = a;
        card = new View(host); rows = new MaxHeightRecyclerView(host);
        set("mQueueCard", card); set("mQueueList", rows);
        set("mQueueTitle", new TextView(host)); set("mQueueSubtitle", new TextView(host));
        set("mQueueChevron", new ImageView(host)); set("mQueueEdit", new View(host));
        set("mQueueAdapter", new RelatedVideoAdapter((v, image) -> {}, v -> true));
        set("mQueueExpanded", true);
        gate = ReflectionHelpers.getField(host, "mRelatedRenderGate"); gate.release();
        refresh(); assertEquals(View.VISIBLE, card.getVisibility());
    }
    @After public void after() { gate.cancelPending(); queue.clear(); }

    @Test public void finalBackgroundAdvanceSurvivesRecommendationClearWithoutRemoteMetadata() {
        queue.setCurrent(c); host.current = c;
        host.clearSuggestions();
        assertEquals(View.VISIBLE, card.getVisibility());
        assertEquals(View.VISIBLE, rows.getVisibility());
        assertEquals(Arrays.asList(a, b, c), videos());
        TextView counter = ReflectionHelpers.getField(host, "mQueueSubtitle");
        assertEquals(host.getString(R.string.mobile_watch_queue_position, 3, 3), counter.getText());
    }

    @Test public void collapsedHeaderRemainsAndCanExpandAfterRefresh() {
        set("mQueueExpanded", false); host.clearSuggestions();
        assertEquals(View.VISIBLE, card.getVisibility());
        assertEquals(View.GONE, rows.getVisibility());
        ReflectionHelpers.callInstanceMethod(host, "toggleQueueExpanded");
        assertEquals(View.VISIBLE, card.getVisibility());
        assertEquals(View.VISIBLE, rows.getVisibility());
    }

    @Test public void removingOneQueuedVideoKeepsOthersAndClearRemovesCard() {
        String title = host.getString(R.string.action_playback_queue);
        VideoGroup group = VideoGroup.from(Arrays.asList(c));
        group.setId(title.hashCode()); group.setType(MediaGroup.TYPE_PLAYBACK_QUEUE);
        queue.remove(c); host.removeSuggestions(group);
        assertEquals(View.VISIBLE, card.getVisibility());
        assertEquals(Arrays.asList(a, b), videos());
        queue.remove(b); host.removeSuggestions(group);
        assertEquals(View.VISIBLE, card.getVisibility());
        assertEquals(Arrays.asList(a), videos());
        queue.clearExceptCurrent(); host.removeSuggestions(group);
        assertEquals(View.GONE, card.getVisibility());
        assertTrue(videos().isEmpty());
    }

    @Test public void deferredBackgroundRefreshRendersOnReleaseWithNoNetworkResult() {
        gate.reset(); queue.setCurrent(c); host.current = c;
        host.clearSuggestions();
        assertEquals(View.VISIBLE, card.getVisibility()); // never lose access while render is held
        gate.release();
        assertEquals(View.VISIBLE, card.getVisibility());
        assertEquals(Arrays.asList(a, b, c), videos());
    }
}
