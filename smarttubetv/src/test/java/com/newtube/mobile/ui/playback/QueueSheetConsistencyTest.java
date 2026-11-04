package com.newtube.mobile.ui.playback;

import static org.junit.Assert.*;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import static org.robolectric.Shadows.shadowOf;
import org.robolectric.annotation.LooperMode;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class QueueSheetConsistencyTest {
    private Playlist queue;
    private QueueSheet.Adapter adapter;
    private Video a, b, c, d;
    private Video played;
    private static Video video(String id) {
        Video v = new Video(); v.videoId = id; v.title = id; return v;
    }
    @Before public void before() {
        Application app = RuntimeEnvironment.getApplication(); app.setTheme(R.style.Theme_NewTube);
        queue = Playlist.instance(); queue.clear();
        a = video("aaaaaaaaaaa"); b = video("bbbbbbbbbbb");
        c = video("ccccccccccc"); d = video("ddddddddddd");
        queue.addAll(Arrays.asList(a, b, c)); queue.setCurrent(a);
        adapter = new QueueSheet.Adapter(app, new QueueSheet.Host() {
            public void playFromQueue(Video video) { played = video; }
            public void onQueueChanged() { }
            public void showQueueSheet(BottomSheetDialog dialog) { }
        }, new BottomSheetDialog(app));
        adapter.onCountChanged = () -> { };
        adapter.onStaleSnapshot = () -> new Handler(Looper.getMainLooper()).post(adapter::reload);
        adapter.reload();
    }
    @After public void after() {
        adapter.dispose(); queue.clear(); shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void externalInsertCannotMakeDragMoveTheWrongVideo() {
        queue.next(d); // sheet was A B C, live queue is now A D B C
        assertFalse(adapter.matchesLiveQueue());
        assertFalse(adapter.move(2, 1));
        assertEquals(Arrays.asList(a, d, b, c), queue.getAll());
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(adapter.matchesLiveQueue());
    }
    @Test public void replacedCurrentCannotBeRemovedThroughItsOldRow() {
        Video replacement = video(a.videoId);
        queue.add(replacement); // same logical video, new object
        assertSame(replacement, queue.getCurrent());
        adapter.remove(0);
        assertSame(replacement, queue.getCurrent());
        assertEquals(3, queue.getAll().size());
    }
    @Test public void currentChangeResyncsBeforeEditing() {
        queue.setCurrent(b);
        assertFalse(adapter.move(2, 1));
        assertSame(b, queue.getCurrent());
        assertEquals(Arrays.asList(a, b, c), queue.getAll());
    }
    @Test public void staleTapDoesNotStartTheWrongVideo() {
        queue.next(d);
        assertFalse(adapter.play(1));
        assertNull(played);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(adapter.play(1));
        assertSame(d, played);
    }
    @Test public void clearPreservesLiveCurrentAndRemovesNewlyAddedItems() {
        queue.next(d); queue.setCurrent(b);
        adapter.clearAllButCurrent();
        assertEquals(Arrays.asList(b), queue.getAll()); assertSame(b, queue.getCurrent());
    }
    @Test public void disposedSheetCannotPlayOrEditAndDropsItsSnapshot() {
        adapter.dispose();
        assertFalse(adapter.play(1)); assertFalse(adapter.move(1, 2));
        adapter.remove(1); adapter.clearAllButCurrent(); adapter.reload();
        assertNull(played); assertEquals(0, adapter.getItemCount());
        assertEquals(Arrays.asList(a, b, c), queue.getAll());
    }
    @Test public void invalidPositionsDoNotMutateTheQueue() {
        assertFalse(adapter.play(-1)); assertFalse(adapter.play(99));
        assertFalse(adapter.move(-1, 2)); assertFalse(adapter.move(1, 99));
        adapter.remove(-1); adapter.remove(99);
        assertEquals(Arrays.asList(a, b, c), queue.getAll());
    }
    @Test public void movingPlayingVideoKeepsItPlayingAndChangesOnlyQueuePosition() {
        assertTrue(adapter.canMove(0)); assertFalse(adapter.canRemove(0));
        assertTrue(adapter.move(0, 1));
        assertSame(a, queue.getCurrent()); assertEquals(1, queue.getCurrentIndex());
        assertEquals(Arrays.asList(b, a, c), queue.getAll()); assertSame(c, queue.getNext());
        assertNull(played); // no playback-open callback, hence no restart caused by the drag
        assertFalse(adapter.play(1)); adapter.remove(1);
        assertSame(a, queue.getCurrent()); assertEquals(3, queue.getAll().size());
    }
    @Test public void ordinaryReorderKeepsCurrentAndSynchronizesSnapshot() {
        assertTrue(adapter.move(2, 1));
        assertSame(a, queue.getCurrent()); assertSame(c, queue.getNext());
        assertTrue(adapter.matchesLiveQueue());
    }
    @Test public void restoredCurrentIsSelectableWithoutPretendingPlaybackAlreadyStarted() {
        queue.restoreQueue(Arrays.asList(a, b, c), 1); adapter.reload();
        assertTrue(queue.isRestoredQueue()); assertNull(played);
        assertEquals(-1, adapter.currentIndex());
        assertTrue(adapter.matchesLiveQueue()); assertTrue(adapter.canRemove(1));
        assertTrue(adapter.play(1)); assertSame(b, played);
    }

    @Test public void removingRestoredDuplicateDeletesTheChosenOccurrence() {
        Video secondA = video(a.videoId);
        queue.restoreQueue(Arrays.asList(a, b, secondA), -1);
        adapter.reload();
        adapter.remove(2);
        assertEquals(2, queue.getAll().size());
        assertSame("The first occurrence must remain", a, queue.getAll().get(0));
        assertSame(b, queue.getAll().get(1));
        assertTrue(adapter.matchesLiveQueue());
    }

    @Test public void removingEqualDuplicateCannotRemoveThePlayingOccurrence() {
        Video secondA = video(a.videoId);
        queue.restoreQueue(Arrays.asList(a, secondA, b), 0);
        queue.setCurrent(a);
        adapter.reload();
        adapter.remove(1);
        assertSame("Playback must keep its selected object", a, queue.getCurrent());
        assertSame(b, queue.getNext());
        assertEquals(2, queue.getAll().size());
        assertTrue(adapter.matchesLiveQueue());
    }
}
