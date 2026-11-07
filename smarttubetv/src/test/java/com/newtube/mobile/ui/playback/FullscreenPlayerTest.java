package com.newtube.mobile.ui.playback;

import static org.junit.Assert.*;

import android.app.Application;
import android.graphics.Rect;
import android.view.View;
import android.widget.ImageView;

import androidx.fragment.app.FragmentActivity;

import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemStoryboard;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.List;
import java.lang.reflect.Proxy;

import io.reactivex.rxjava3.core.Observable;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class FullscreenPlayerTest {
    @Test
    public void descriptionChaptersPreserveTitlesAndHourTimestamps() {
        List<Video> chapters = DescriptionChapters.parse(
                "A course\n0:00 Intro\n1:02 - Basics\n1:01:03 Advanced topics", 4_000_000);
        assertEquals(3, chapters.size());
        assertEquals(62_000, chapters.get(1).startTimeMs);
        assertEquals("Basics", chapters.get(1).title);
        assertEquals(3_663_000, chapters.get(2).startTimeMs);
        assertTrue(chapters.get(2).isChapter);
    }

    @Test
    public void isolatedOrAmbiguousDescriptionTimestampsDoNotBecomeChapters() {
        for (String description : new String[] {null, "A link at 0:00", "0:00 Intro",
                "0:10 Intro\n1:00 Topic", "0:00 Intro\n0:00 Duplicate",
                "0:00 Intro\n1:00 Topic\n0:30 Earlier", "0:00 Intro\n1:70 Invalid"}) {
            assertTrue(String.valueOf(description), DescriptionChapters.parse(description, 100_000).isEmpty());
        }
        assertTrue(DescriptionChapters.parse("0:00 Intro\n2:00 Beyond the video", 100_000).isEmpty());
    }

    @Test
    public void previewSelectsTheRightTileAndWrapsAtTheNextSprite() {
        assertEquals(new Rect(0, 0, 100, 50), StoryboardPreview.frameRect(-10, 1000, 3, 2, 300, 100));
        assertEquals(new Rect(200, 0, 300, 50), StoryboardPreview.frameRect(2000, 1000, 3, 2, 300, 100));
        assertEquals(new Rect(0, 50, 100, 100), StoryboardPreview.frameRect(3000, 1000, 3, 2, 300, 100));
        assertEquals(new Rect(0, 0, 100, 50), StoryboardPreview.frameRect(6000, 1000, 3, 2, 300, 100));
        assertNull(StoryboardPreview.frameRect(1000, 0, 3, 2, 300, 100));
        assertNull(StoryboardPreview.frameRect(1000, 1000, 0, 2, 300, 100));
        assertNull(StoryboardPreview.frameRect(1000, 1000, 3, 2, 1, 1));
    }

    @Test
    public void previewRequestCanBeClearedAfterActivityDestruction() {
        ActivityController<FragmentActivity> controller = Robolectric.buildActivity(FragmentActivity.class).setup();
        FragmentActivity activity = controller.get();
        ImageView view = new ImageView(activity);
        activity.setContentView(view);
        MediaItemStoryboard.Size size = (MediaItemStoryboard.Size) Proxy.newProxyInstance(
                MediaItemStoryboard.Size.class.getClassLoader(), new Class<?>[] {MediaItemStoryboard.Size.class},
                (proxy, method, args) -> method.getName().equals("getDurationEachMS") ? 1000 : 1);
        MediaItemStoryboard storyboard = (MediaItemStoryboard) Proxy.newProxyInstance(
                MediaItemStoryboard.class.getClassLoader(), new Class<?>[] {MediaItemStoryboard.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getGroupSize")) return size;
                    if (method.getName().equals("getGroupUrl")) return "android.resource://android/drawable/ic_media_play";
                    throw new AssertionError(method.getName());
                });
        MediaItemService service = (MediaItemService) Proxy.newProxyInstance(
                MediaItemService.class.getClassLoader(), new Class<?>[] {MediaItemService.class},
                (proxy, method, args) -> {
                    assertEquals("getStoryboardObserve", method.getName());
                    return Observable.just(storyboard);
                });
        StoryboardPreview preview = new StoryboardPreview(view, service);
        preview.reset("video");
        preview.show(1000);
        controller.pause().stop().destroy();
        assertTrue(activity.isDestroyed());
        preview.reset(null);
        assertNull(view.getDrawable());
        assertEquals(View.GONE, view.getVisibility());
    }
}
