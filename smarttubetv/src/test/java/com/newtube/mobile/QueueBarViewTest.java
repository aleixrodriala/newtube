package com.newtube.mobile;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.playback.QueueBarView;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class QueueBarViewTest implements LifecycleOwner {
    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private QueueBarView bar;
    private Playlist queue;
    private Video a, b, c;
    private final AtomicInteger opened = new AtomicInteger();
    @Override public Lifecycle getLifecycle() { return lifecycle; }
    private static Video video(String id) {
        Video v = new Video(); v.videoId = id; v.title = id; return v;
    }
    private String label() { return ((TextView) bar.findViewById(R.id.queue_bar_label)).getText().toString(); }
    @Before public void before() {
        Application app = RuntimeEnvironment.getApplication(); app.setTheme(R.style.Theme_NewTube);
        queue = Playlist.instance(); queue.clear();
        a = video("aaaaaaaaaaa"); b = video("bbbbbbbbbbb"); c = video("ccccccccccc");
        bar = new QueueBarView(app, null);
        bar.bind(this, opened::incrementAndGet);
        lifecycle.setCurrentState(Lifecycle.State.STARTED);
    }
    @After public void after() {
        lifecycle.setCurrentState(Lifecycle.State.DESTROYED); queue.clear();
        shadowOf(Looper.getMainLooper()).idle();
    }
    private int listeners() throws Exception {
        java.lang.reflect.Field f = Playlist.class.getDeclaredField("mListeners"); f.setAccessible(true);
        return ((java.util.List<?>) f.get(queue)).size();
    }
    @Test public void detachingDropsSubscriptionsAndLifecycleReferencesThenReattachesCleanly() throws Exception {
        org.robolectric.android.controller.ActivityController<android.app.Activity> host =
                org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup();
        android.widget.FrameLayout root = new android.widget.FrameLayout(host.get());
        host.get().setContentView(root);
        root.addView(bar); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, listeners());
        int observers = lifecycle.getObserverCount();
        root.removeView(bar); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, listeners()); assertEquals(observers - 1, lifecycle.getObserverCount());
        queue.addAll(Arrays.asList(a, b)); queue.setCurrent(a);
        root.addView(bar); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, listeners()); assertEquals(View.VISIBLE, bar.getVisibility());
        root.removeView(bar); host.pause().stop().destroy();
        assertEquals(0, listeners());
    }
    @Test public void destroyedBarCannotOpenOrRefresh() {
        queue.addAll(Arrays.asList(a, b)); queue.setCurrent(a);
        lifecycle.setCurrentState(Lifecycle.State.DESTROYED);
        String before = label();
        queue.next(c); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(before, label()); bar.performClick(); assertEquals(0, opened.get());
    }
    @Test public void rebindingRemovesTheOldOwnerAndItsCallbacks() {
        final LifecycleRegistry[] next = new LifecycleRegistry[1];
        LifecycleOwner owner = () -> next[0]; next[0] = new LifecycleRegistry(owner);
        AtomicInteger second = new AtomicInteger();
        next[0].setCurrentState(Lifecycle.State.STARTED);
        bar.bind(owner, second::incrementAndGet);
        lifecycle.setCurrentState(Lifecycle.State.DESTROYED);
        bar.performClick(); assertEquals(0, opened.get()); assertEquals(1, second.get());
        next[0].setCurrentState(Lifecycle.State.DESTROYED);
        bar.performClick(); assertEquals(1, second.get());
    }
    @Test public void reflectsAddReorderAdvanceAndClear() {
        assertEquals(View.GONE, bar.getVisibility());
        queue.addAll(Arrays.asList(a, b, c)); queue.setCurrent(a);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.VISIBLE, bar.getVisibility()); assertTrue(label().contains(b.title));
        queue.move(2, 1); shadowOf(Looper.getMainLooper()).idle();
        assertTrue(label().contains(c.title));
        queue.setCurrent(c); shadowOf(Looper.getMainLooper()).idle();
        assertTrue(label().contains(b.title));
        queue.clear(); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.GONE, bar.getVisibility());
    }
    @Test public void queueBeforePlaybackIsVisibleWithOneOrSeveralItems() {
        queue.next(a); shadowOf(Looper.getMainLooper()).idle();
        assertNull(queue.getCurrent()); assertEquals(View.VISIBLE, bar.getVisibility());
        assertTrue(label().contains("1 video remaining")); assertTrue(label().contains(a.title));
        queue.next(b); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertTrue(label().contains("2 videos remaining")); assertTrue(label().contains(b.title));
        queue.setCurrent(b); shadowOf(Looper.getMainLooper()).idle();
        assertTrue(label().contains("1 video remaining")); assertTrue(label().contains(a.title));
        queue.clear(); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.GONE, bar.getVisibility());
    }
    @Test public void movingCurrentPastQueuedItemsKeepsTheQueueAccessible() {
        b.fromQueue = true; c.fromQueue = true;
        queue.addAll(Arrays.asList(a, b, c)); queue.setCurrent(a);
        queue.move(0, 2); shadowOf(Looper.getMainLooper()).idle();
        assertSame(a, queue.getCurrent()); assertEquals(View.VISIBLE, bar.getVisibility());
        assertTrue(label().contains("Queue finished"));
    }
    @Test public void playbackHistoryAloneDoesNotShowAQueue() {
        queue.addAll(Arrays.asList(a, b)); queue.setCurrent(b);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.GONE, bar.getVisibility());
    }
    @Test public void restoredCursorIsShownAsTheNextSelectableVideoRatherThanSkipped() {
        queue.restoreQueue(Arrays.asList(a, b, c), 1);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertTrue(label().contains(b.title));
        assertTrue(label().contains("2 videos remaining"));
        assertTrue(queue.isRestoredQueue());
        queue.clearExceptCurrent(); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.GONE, bar.getVisibility()); assertTrue(queue.getAll().isEmpty());
    }
    @Test public void lastQueuedVideoKeepsBarAfterBackgroundAdvanceConsumesLaunchFlag() {
        queue.add(a); queue.setCurrent(a);
        b.fromQueue = true; queue.next(b);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.VISIBLE, bar.getVisibility());
        lifecycle.setCurrentState(Lifecycle.State.CREATED);
        // VideoLoaderController consumes this routing flag before setting the playing item.
        b.fromQueue = false; queue.setCurrent(b);
        shadowOf(Looper.getMainLooper()).idle();
        lifecycle.setCurrentState(Lifecycle.State.STARTED);
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertTrue(label().contains("Queue finished"));
        bar.performClick(); assertEquals(1, opened.get());
        queue.remove(a); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.VISIBLE, bar.getVisibility()); // an explicit queue may contain only current
        queue.clearExceptCurrent(); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.GONE, bar.getVisibility());
    }
    @Test public void hiddenWhileFullscreenOrPipAndRestoredWithoutPolling() {
        queue.addAll(Arrays.asList(a, b)); queue.setCurrent(a);
        shadowOf(Looper.getMainLooper()).idle();
        bar.setAvailable(false); assertEquals(View.GONE, bar.getVisibility());
        bar.setAvailable(true); assertEquals(View.VISIBLE, bar.getVisibility());
        bar.performClick(); assertEquals(1, opened.get());
    }
    @Test public void stoppedScreenDropsPendingWorkAndResyncsOnStart() {
        queue.addAll(Arrays.asList(a, b)); queue.setCurrent(a);
        lifecycle.setCurrentState(Lifecycle.State.CREATED);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.GONE, bar.getVisibility());
        queue.next(c); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.GONE, bar.getVisibility());
        lifecycle.setCurrentState(Lifecycle.State.STARTED);
        assertEquals(View.VISIBLE, bar.getVisibility()); assertTrue(label().contains(c.title));
    }
}
