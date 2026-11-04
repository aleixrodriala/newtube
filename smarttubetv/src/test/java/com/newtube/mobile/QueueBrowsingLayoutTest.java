package com.newtube.mobile;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Dialog;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import androidx.fragment.app.FragmentActivity;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.playback.QueueBarView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

/** Inflate real browsing layouts without starting presenters or making network requests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 35}, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class QueueBrowsingLayoutTest {
    @Test public void searchReservesQueueSpaceIncludingWithKeyboardSizedViewport() throws Exception {
        verify(R.layout.activity_mobile_search);
    }
    @Test public void channelReservesQueueSpace() throws Exception {
        verify(R.layout.activity_mobile_channel);
    }
    @Test public void playlistAndUploadsReserveQueueSpace() throws Exception {
        verify(R.layout.activity_mobile_channel_uploads);
    }
    private int listeners() throws Exception {
        java.lang.reflect.Field field = Playlist.class.getDeclaredField("mListeners");
        field.setAccessible(true);
        return ((java.util.List<?>) field.get(Playlist.instance())).size();
    }
    private void verify(int layout) throws Exception {
        Playlist queue = Playlist.instance(); queue.clear();
        int baseline = listeners();
        var host = Robolectric.buildActivity(FragmentActivity.class).setup();
        FragmentActivity activity = host.get(); activity.setTheme(R.style.Theme_NewTube);
        Dialog sheet = null;
        try {
            activity.setContentView(layout);
            QueueBarView.bindBrowsing(activity);
            QueueBarView bar = activity.findViewById(R.id.mobile_queue_bar);
            View mini = activity.findViewById(R.id.mobile_mini_player);
            assertEquals(View.GONE, bar.getVisibility());
            Video video = new Video(); video.videoId = "aaaaaaaaaaa"; video.title = "Queued";
            video.fromQueue = true; queue.add(video);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(View.VISIBLE, bar.getVisibility());
            assertEquals(baseline + 1, listeners());
            mini.setVisibility(View.VISIBLE);
            ViewGroup root = (ViewGroup) bar.getParent();
            for (int height : new int[]{800, 360}) {
                root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, 400, height);
                assertTrue(bar.getHeight() > 0);
                assertEquals(height, bar.getBottom());
                // Mini is inside the weighted content frame; it cannot overlap the footer.
                View content = root.getChildAt(0);
                assertTrue(content.getBottom() <= bar.getTop());
                assertTrue(mini.getBottom() <= content.getHeight());
            }
            bar.performClick(); shadowOf(Looper.getMainLooper()).idle();
            sheet = ShadowDialog.getLatestDialog();
            assertNotNull(sheet); assertTrue(sheet.isShowing());
            sheet.dismiss(); sheet = null;
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(baseline + 1, listeners());
            host.pause().stop();
            assertEquals(baseline, listeners());
            queue.clear(); host.start().resume();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(View.GONE, bar.getVisibility());
        } finally {
            if (sheet != null) sheet.dismiss();
            host.pause().stop().destroy(); queue.clear();
            shadowOf(Looper.getMainLooper()).idle();
        }
        assertEquals(baseline, listeners());
    }
}
