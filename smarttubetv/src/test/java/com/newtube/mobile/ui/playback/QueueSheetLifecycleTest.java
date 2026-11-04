package com.newtube.mobile.ui.playback;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import android.view.WindowManager;
import androidx.fragment.app.FragmentActivity;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import java.lang.reflect.Field;
import java.util.Arrays;
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
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class QueueSheetLifecycleTest {
    private ActivityController<FragmentActivity> controller;
    private FragmentActivity activity;
    private Playlist queue;
    private BottomSheetDialog dialog;
    private int shows;
    private int initialListeners;
    private final QueueSheet.Host host = new QueueSheet.Host() {
        public void playFromQueue(Video video) { }
        public void onQueueChanged() { }
        public void showQueueSheet(BottomSheetDialog value) { shows++; dialog = value; value.show(); }
    };
    private static Video video(String id) {
        Video v = new Video(); v.videoId = id; v.title = id; return v;
    }
    private int listeners() throws Exception {
        Field field = Playlist.class.getDeclaredField("mListeners"); field.setAccessible(true);
        return ((List<?>) field.get(queue)).size();
    }
    @Before public void before() throws Exception {
        controller = Robolectric.buildActivity(FragmentActivity.class).setup();
        activity = controller.get(); activity.setTheme(R.style.Theme_NewTube);
        queue = Playlist.instance(); queue.clear();
        Video a = video("aaaaaaaaaaa");
        queue.addAll(Arrays.asList(a, video("bbbbbbbbbbb"))); queue.setCurrent(a);
        initialListeners = listeners();
    }
    @After public void after() {
        if (dialog != null) dialog.dismiss();
        if (!activity.isDestroyed()) controller.pause().stop().destroy();
        queue.clear();
        shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void repeatedOpenDismissLeavesNoQueueSubscriptions() throws Exception {
        for (int i = 0; i < 10; i++) {
            QueueSheet.show(activity, host); shadowOf(Looper.getMainLooper()).idle();
            assertEquals(initialListeners + 1, listeners());
            queue.next(video("ccccccccccc")); // pending refresh must be cancelled on dismissal
            dialog.dismiss(); shadowOf(Looper.getMainLooper()).idle();
            assertEquals(initialListeners, listeners());
        }
        assertEquals(10, shows);
    }
    @Test public void duplicateOpenAndClosingHostAreIgnored() {
        QueueSheet.show(activity, host); QueueSheet.show(activity, host);
        assertEquals(1, shows);
        dialog.dismiss(); activity.finish();
        QueueSheet.show(activity, host); assertEquals(1, shows);
    }
    @Test public void finishingHostRejectsLatePlayAndEditActions() {
        QueueSheet.show(activity, host); shadowOf(Looper.getMainLooper()).idle();
        androidx.recyclerview.widget.RecyclerView list = dialog.findViewById(R.id.queue_sheet_list);
        QueueSheet.Adapter adapter = (QueueSheet.Adapter) list.getAdapter();
        java.util.List<Video> before = new java.util.ArrayList<>(queue.getAll());
        activity.finish();
        assertFalse(adapter.play(1)); assertFalse(adapter.move(1, 0));
        adapter.remove(1); adapter.clearAllButCurrent();
        assertEquals(before, queue.getAll());
    }
    @Test public void emptyQueueDoesNotOpenAnEmptyDialog() {
        queue.clear(); QueueSheet.show(activity, host); assertEquals(0, shows);
    }
    @Test public void stopUnsubscribesAndStartResynchronizes() throws Exception {
        QueueSheet.show(activity, host); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(initialListeners + 1, listeners());
        controller.pause().stop();
        assertEquals(initialListeners, listeners());
        queue.next(video("ccccccccccc")); shadowOf(Looper.getMainLooper()).idle();
        controller.start().resume(); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(initialListeners + 1, listeners());
    }
    @Test public void destroyingHostClosesTheSheetAndReleasesItsSubscription() throws Exception {
        QueueSheet.show(activity, host); shadowOf(Looper.getMainLooper()).idle();
        controller.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle();
        assertFalse(dialog.isShowing()); assertEquals(initialListeners, listeners());
        QueueSheet.show(activity, host); assertEquals(1, shows);
    }
    @Test public void losingWindowTokenDoesNotCrashOrLeaveAListener() throws Exception {
        QueueSheet.show(activity, new QueueSheet.Host() {
            public void playFromQueue(Video video) { }
            public void onQueueChanged() { }
            public void showQueueSheet(BottomSheetDialog value) {
                throw new WindowManager.BadTokenException("host window closed");
            }
        });
        shadowOf(Looper.getMainLooper()).idle(); assertEquals(initialListeners, listeners());
        QueueSheet.show(activity, host); assertEquals(1, shows);
    }
}
