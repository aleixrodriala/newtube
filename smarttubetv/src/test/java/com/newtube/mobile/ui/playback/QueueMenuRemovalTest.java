package com.newtube.mobile.ui.playback;

import static org.junit.Assert.*;

import android.app.Application;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.VideoMenuPresenter;
import java.util.Arrays;
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
import org.robolectric.util.ReflectionHelpers;

/** Exercise the actual menu's offered action and callback, without showing a remote menu. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class QueueMenuRemovalTest {
    private Playlist queue;
    private Video a, b, c;
    private VideoMenuPresenter menu;
    private AppDialogPresenter dialog;

    private static Video video(String id) {
        Video video = Video.from(id);
        video.title = id;
        video.fromQueue = true;
        return video;
    }

    @Before public void before() {
        Application app = RuntimeEnvironment.getApplication();
        queue = Playlist.instance();
        queue.clear();
        a = video("aaaaaaaaaaa"); b = video("bbbbbbbbbbb"); c = video("ccccccccccc");
        queue.addAll(Arrays.asList(a, b, c));
        queue.setCurrent(a);
        dialog = AppDialogPresenter.instance(app);
        dialog.onFinish();
        menu = VideoMenuPresenter.instance(app);
        ReflectionHelpers.setField(menu, "mIsAddToPlaybackQueueButtonEnabled", true);
    }

    @After public void after() {
        dialog.onFinish();
        queue.clear();
    }

    private List<OptionCategory> offerRemoval(Video video) {
        ReflectionHelpers.setField(menu, "mVideo", video);
        ReflectionHelpers.callInstanceMethod(menu, "appendRemoveFromPlaybackQueueButton");
        return ReflectionHelpers.getField(dialog, "mCategories");
    }

    @Test public void currentQueueItemDoesNotOfferRemoval() {
        assertTrue("The menu must protect the playing row", offerRemoval(a).isEmpty());
        assertSame(a, queue.getCurrent());
    }

    @Test public void menuOpenedBeforePlaybackAdvanceCannotRemoveTheNewCurrent() {
        List<OptionCategory> categories = offerRemoval(b);
        assertEquals(1, categories.size());
        OptionItem remove = categories.get(0).options.get(0);
        queue.setCurrent(b); // Auto-advance while the menu remains open.
        remove.onSelect(true);
        assertSame("A stale menu must preserve the live playing item", b, queue.getCurrent());
        assertEquals(3, queue.getAll().size());
        assertSame(c, queue.getNext());
    }

    @Test public void pendingItemCanStillBeRemovedThroughTheMenu() {
        List<OptionCategory> categories = offerRemoval(b);
        assertEquals(1, categories.size());
        categories.get(0).options.get(0).onSelect(true);
        assertSame(a, queue.getCurrent());
        assertSame(c, queue.getNext());
        assertEquals(2, queue.getAll().size());
    }

    @Test public void restoredCursorCanBeRemovedBeforePlaybackStarts() {
        queue.restoreQueue(Arrays.asList(a, b, c), 1);
        List<OptionCategory> categories = offerRemoval(b);
        assertEquals(1, categories.size());
        categories.get(0).options.get(0).onSelect(true);
        assertEquals(Arrays.asList(a, c), queue.getAll());
        assertTrue(queue.isRestoredQueue());
    }

    @Test public void equalDuplicateMenuDoesNotHideTheRemovableOccurrence() {
        Video otherA = video(a.videoId);
        queue.restoreQueue(Arrays.asList(a, b, otherA), 0);
        queue.setCurrent(a);
        List<OptionCategory> categories = offerRemoval(otherA);
        assertEquals(1, categories.size());
        categories.get(0).options.get(0).onSelect(true);
        assertSame(a, queue.getCurrent());
        assertSame(b, queue.getNext());
        assertEquals(2, queue.getAll().size());
    }
}
