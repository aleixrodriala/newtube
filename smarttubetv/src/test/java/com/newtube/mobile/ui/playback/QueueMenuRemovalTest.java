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

    private OptionItem offerPlayNext(Video video) {
        ReflectionHelpers.setField(menu, "mVideo", video);
        ReflectionHelpers.setField(menu, "mIsPlayNextButtonEnabled", true);
        ReflectionHelpers.callInstanceMethod(menu, "appendPlayNextButton");
        List<OptionCategory> categories = ReflectionHelpers.getField(dialog, "mCategories");
        assertEquals(1, categories.size());
        return categories.get(0).options.get(0);
    }

    private OptionItem offerAppend(Video video) {
        ReflectionHelpers.setField(menu, "mVideo", video);
        ReflectionHelpers.callInstanceMethod(menu, "appendAddToPlaybackQueueButton");
        List<OptionCategory> categories = ReflectionHelpers.getField(dialog, "mCategories");
        assertEquals(1, categories.size());
        return categories.get(0).options.get(0);
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

    @Test public void playNextOnThePlayingRowPreservesTheSelectedItem() {
        offerPlayNext(a).onSelect(true);
        assertSame(a, queue.getCurrent());
        assertEquals(Arrays.asList(a, b, c), queue.getAll());
        assertSame(b, queue.getNext());
    }

    @Test public void playNextMenuCannotMoveTheNewCurrentAfterPlaybackAdvances() {
        OptionItem next = offerPlayNext(c);
        queue.setCurrent(c);
        next.onSelect(true);
        assertSame(c, queue.getCurrent());
        assertEquals(Arrays.asList(a, b, c), queue.getAll());
    }

    @Test public void staleDuplicateRemovalDoesNotRetargetAnotherOccurrence() {
        Video otherB = video(b.videoId);
        queue.restoreQueue(Arrays.asList(a, b, otherB, c), 0);
        queue.setCurrent(a);
        List<OptionCategory> categories = offerRemoval(b);
        assertEquals(1, categories.size());
        OptionItem remove = categories.get(0).options.get(0);
        queue.remove(b); // The original row disappears while its menu remains open.
        remove.onSelect(true);
        assertEquals(3, queue.getAll().size());
        assertSame(a, queue.getCurrent());
        assertSame(otherB, queue.getNext());
        assertSame(c, queue.getAll().get(2));
    }

    @Test public void aBrowseCardCloneCanStillRemoveTheQueuedOccurrence() {
        List<OptionCategory> categories = offerRemoval(video(b.videoId));
        assertEquals(1, categories.size());
        categories.get(0).options.get(0).onSelect(true);
        assertSame(a, queue.getCurrent());
        assertSame(c, queue.getNext());
        assertEquals(2, queue.getAll().size());
    }

    @Test public void cloneMenuDoesNotRemoveAReplacementAddedAfterItOpened() {
        List<OptionCategory> categories = offerRemoval(video(b.videoId));
        assertEquals(1, categories.size());
        OptionItem remove = categories.get(0).options.get(0);
        Video replacement = video(b.videoId);
        queue.add(replacement);
        remove.onSelect(true);
        assertEquals(3, queue.getAll().size());
        assertSame(a, queue.getCurrent());
        assertSame(c, queue.getNext());
        assertSame(replacement, queue.getAll().get(2));
    }

    private void assertStaleRequeueDoesNotReplaceDuplicate(boolean append) {
        Video otherB = video(b.videoId);
        Video d = video("ddddddddddd");
        queue.restoreQueue(Arrays.asList(a, c, b, otherB, d), 0);
        queue.setCurrent(a);
        OptionItem action = append ? offerAppend(b) : offerPlayNext(b);
        queue.remove(b);
        action.onSelect(true);
        assertEquals(4, queue.getAll().size());
        assertSame(a, queue.getCurrent());
        assertSame(c, queue.getAll().get(1));
        assertSame(otherB, queue.getAll().get(2));
        assertSame(d, queue.getAll().get(3));
    }

    @Test public void staleAppendCannotReplaceAnotherDuplicate() {
        assertStaleRequeueDoesNotReplaceDuplicate(true);
    }

    @Test public void stalePlayNextCannotReplaceAnotherDuplicate() {
        assertStaleRequeueDoesNotReplaceDuplicate(false);
    }

    @Test public void appendingDuplicateBeforeAnEqualLastEntryMovesItsOwnOccurrence() {
        Video otherB = video(b.videoId);
        queue.restoreQueue(Arrays.asList(a, b, c, otherB), 0);
        queue.setCurrent(a);
        offerAppend(b).onSelect(true);
        assertSame(a, queue.getCurrent());
        assertSame(c, queue.getAll().get(1));
        assertSame(otherB, queue.getAll().get(2));
        assertSame(b, queue.getAll().get(3));
    }

    @Test public void playNextDuplicateAfterAnEqualNextEntryMovesItsOwnOccurrence() {
        Video otherB = video(b.videoId);
        queue.restoreQueue(Arrays.asList(a, b, c, otherB), 0);
        queue.setCurrent(a);
        offerPlayNext(otherB).onSelect(true);
        assertSame(a, queue.getCurrent());
        assertSame(otherB, queue.getAll().get(1));
        assertSame(b, queue.getAll().get(2));
        assertSame(c, queue.getAll().get(3));
    }

    @Test public void aNewVideoCanStillBeAppendedFromItsMenu() {
        Video d = video("ddddddddddd");
        offerAppend(d).onSelect(true);
        assertSame(a, queue.getCurrent());
        assertSame(d, queue.getAll().get(3));
    }

    @Test public void aNewVideoAppearingWhileItsMenuIsOpenIsNotRetargeted() {
        Video d = video("ddddddddddd");
        OptionItem append = offerAppend(d);
        Video queuedD = video(d.videoId);
        queue.next(queuedD);
        append.onSelect(true);
        assertSame(a, queue.getCurrent());
        assertSame(queuedD, queue.getNext());
        assertEquals(4, queue.getAll().size());
    }

    @Test public void restoredCursorCanBeAppendedWithoutStartingPlayback() {
        queue.restoreQueue(Arrays.asList(a, b, c), 0);
        offerAppend(a).onSelect(true);
        assertTrue(queue.isRestoredQueue());
        assertEquals(Arrays.asList(b, c, a), queue.getAll());
        assertSame(b, queue.getCurrent());
    }

    @Test public void playNextBeforePlaybackGoesBeforeTheRestoredCursor() {
        queue.restoreQueue(Arrays.asList(a, b, c), 1);
        Video d = video("ddddddddddd");
        offerPlayNext(d).onSelect(true);
        assertTrue(queue.isRestoredQueue());
        assertEquals(Arrays.asList(a, d, b, c), queue.getAll());
        assertSame(d, queue.getCurrent());
    }

    @Test public void restoredCursorDoesNotOfferPlayNextForItself() {
        queue.restoreQueue(Arrays.asList(a, b, c), 1);
        ReflectionHelpers.setField(menu, "mVideo", b);
        ReflectionHelpers.setField(menu, "mIsPlayNextButtonEnabled", true);
        ReflectionHelpers.callInstanceMethod(menu, "appendPlayNextButton");
        List<OptionCategory> categories = ReflectionHelpers.getField(dialog, "mCategories");
        assertTrue(categories.isEmpty());
        assertTrue(queue.isRestoredQueue());
    }
}
