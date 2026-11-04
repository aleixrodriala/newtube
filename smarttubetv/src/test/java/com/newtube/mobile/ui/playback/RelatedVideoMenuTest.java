package com.newtube.mobile.ui.playback;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 35}, application = android.app.Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class RelatedVideoMenuTest {
    private ActivityController<Activity> controller;
    private RelatedVideoAdapter adapter;
    private RelatedVideoAdapter.RelatedViewHolder holder;
    private final List<Video> opened = new ArrayList<>();
    private final List<Video> menus = new ArrayList<>();
    private Video first;
    private long down;

    @Before public void before() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = controller.get();
        activity.setTheme(com.liskovsoft.smartyoutubetv2.tv.R.style.Theme_NewTube);
        FrameLayout root = new FrameLayout(activity);
        activity.setContentView(root);
        adapter = new RelatedVideoAdapter((v, image) -> opened.add(v), v -> {
            menus.add(v);
            return true;
        });
        holder = adapter.onCreateViewHolder(root, 0);
        root.addView(holder.itemView);
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1000, 500);
        first = video("aaaaaaaaaaa");
        holder.bind(first, false);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static Video video(String id) {
        Video video = new Video(); video.videoId = id; video.title = id; return video;
    }

    private void touch(int action) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 20, 20, 0);
        holder.itemView.dispatchTouchEvent(event);
        event.recycle();
    }

    @After public void after() {
        controller.pause().stop().destroy();
    }

    @Test public void holdingOpensMenuAndReleasingDoesNotPlay() {
        down = SystemClock.uptimeMillis(); touch(MotionEvent.ACTION_DOWN);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        touch(MotionEvent.ACTION_UP);
        assertEquals(1, menus.size()); assertSame(first, menus.get(0));
        assertTrue(opened.isEmpty());
    }

    @Test public void shortTapPlaysWithoutMenu() {
        down = SystemClock.uptimeMillis(); touch(MotionEvent.ACTION_DOWN);
        // Deliver the lift before draining frames: API 24's simulated rendering can advance
        // uptime by more than 500 ms while idling a nominal 50 ms, turning it into a real hold.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(50));
        touch(MotionEvent.ACTION_UP);
        // API 24 can defer View's posted click to the next traversal/frame.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        assertEquals(1, opened.size()); assertSame(first, opened.get(0));
        assertTrue(menus.isEmpty());
    }

    @Test public void cancelledPressDoesNothing() {
        down = SystemClock.uptimeMillis(); touch(MotionEvent.ACTION_DOWN);
        touch(MotionEvent.ACTION_CANCEL);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertTrue(menus.isEmpty()); assertTrue(opened.isEmpty());
    }

    @Test public void recycledRowCannotOpenStaleVideo() {
        adapter.onViewRecycled(holder);
        assertFalse(holder.itemView.performLongClick());
        holder.itemView.performClick();
        assertTrue(menus.isEmpty()); assertTrue(opened.isEmpty());
    }

    @Test public void rebindUsesNewVideoForMenu() {
        Video second = video("bbbbbbbbbbb"); holder.bind(second, true);
        assertTrue(holder.itemView.performLongClick());
        assertEquals(1, menus.size()); assertSame(second, menus.get(0));
        assertTrue(opened.isEmpty());
    }
}
