package com.liskovsoft.smartyoutubetv2.common.app.models.data;

import static org.junit.Assert.*;
import android.app.Application;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlaylistListenerTest {
    private Playlist queue;
    private final AtomicInteger changes = new AtomicInteger();
    private final Runnable listener = changes::incrementAndGet;
    private Video a, b, c;

    private static Video video(String id) {
        Video v = new Video(); v.videoId = id; v.title = id; return v;
    }

    @Before public void before() {
        queue = Playlist.instance(); queue.clear();
        a = video("aaaaaaaaaaa"); b = video("bbbbbbbbbbb"); c = video("ccccccccccc");
        queue.addAll(Arrays.asList(a, b)); queue.setCurrent(a);
        queue.addListener(listener);
    }
    @After public void after() { queue.removeListener(listener); queue.clear(); }

    private void changed(Runnable operation) {
        int before = changes.get(); operation.run(); assertTrue(changes.get() > before);
    }

    @Test public void queueEditsAndPlaybackAdvanceNotify() {
        changed(() -> queue.next(c));
        assertSame(c, queue.getNext());
        changed(() -> queue.move(2, 1));
        assertSame(b, queue.getNext());
        changed(() -> queue.setCurrent(b));
        changed(() -> queue.remove(c));
        changed(() -> queue.clearPosition());
        changed(() -> queue.addAll(Arrays.asList(c)));
        changed(() -> queue.add(a));
        changed(() -> queue.clear());
    }

    @Test public void stoppedUiReceivesNoEventsAndRegistrationIsIdempotent() {
        queue.addListener(listener);
        queue.clear(); assertEquals(1, changes.get());
        queue.removeListener(listener);
        queue.addAll(Arrays.asList(a, b)); assertEquals(1, changes.get());
    }

    @Test public void clearExceptCurrentEmitsOneStateAndHandlesMissingRows() {
        a.fromQueue = true; b.fromQueue = true; c.fromQueue = true;
        queue.addAll(Arrays.asList(null, c)); changes.set(0);
        queue.clearExceptCurrent();
        assertEquals(Arrays.asList(a), queue.getAll()); assertSame(a, queue.getCurrent());
        assertTrue(a.fromQueue); assertFalse(b.fromQueue); assertFalse(c.fromQueue);
        assertEquals(1, changes.get());
        queue.clearExceptCurrent(); assertEquals(1, changes.get());
    }
    @Test public void appendingIsFifoAndDoesNotStartOrReplaceCurrentPlayback() {
        queue.clear(); queue.add(a); queue.add(b); queue.add(c);
        assertEquals(Arrays.asList(a, b, c), queue.getAll()); assertNull(queue.getCurrent());
        queue.setCurrent(a);
        Video d = video("ddddddddddd"); queue.add(d);
        assertEquals(Arrays.asList(a, b, c, d), queue.getAll());
        assertSame(a, queue.getCurrent()); assertSame(b, queue.getNext());
    }
    @Test public void gettersDoNotScheduleUiWork() {
        for (int i = 0; i < 1000; i++) {
            queue.getCurrent(); queue.getNext(); queue.getAll(); queue.getCurrentIndex();
        }
        assertEquals(0, changes.get());
    }
    @Test public void restoredSnapshotIsOneStateAndSelectedDuplicateUsesItsActualPosition() {
        Video otherA = video(a.videoId);
        changes.set(0); queue.restoreQueue(Arrays.asList(a, b, otherA), 2);
        assertEquals(1, changes.get()); assertTrue(queue.isRestoredQueue());
        assertSame(otherA, queue.getCurrent());
        queue.setCurrent(otherA);
        assertEquals(2, queue.getCurrentIndex()); assertFalse(queue.isRestoredQueue());
    }

    @Test public void explicitQueueSurvivesConsumedRoutingFlagsAndClearResetsIt() {
        assertFalse(queue.hasExplicitQueue());
        c.fromQueue = true; queue.next(c);
        c.fromQueue = false; queue.setCurrent(c);
        assertTrue(queue.hasExplicitQueue());
        queue.clearExceptCurrent();
        assertFalse(queue.hasExplicitQueue());
        assertSame(c, queue.getCurrent());
        b.fromQueue = true; queue.add(b);
        b.fromQueue = false; queue.setCurrent(b);
        assertTrue(queue.hasExplicitQueue());
        queue.clear(); assertFalse(queue.hasExplicitQueue());
    }

    @Test public void removingAllPendingItemsDoesNotTurnLaterHistoryIntoAQueue() {
        queue.clear();
        c.fromQueue = true; queue.add(c);
        assertTrue(queue.hasExplicitQueue());
        queue.remove(c);
        queue.add(a); queue.setCurrent(a);
        queue.add(b); queue.setCurrent(b);
        assertFalse(queue.hasExplicitQueue());
    }

    @Test public void listenerMayUnregisterDuringNotification() {
        Runnable[] once = new Runnable[1];
        once[0] = () -> queue.removeListener(once[0]);
        queue.addListener(once[0]);
        queue.clear(); queue.clear();
        assertEquals(2, changes.get());
    }

    @Test public void requeuingPublishesOnlyOneCompleteState() {
        queue.add(c);
        changes.set(0);
        java.util.List<java.util.List<Video>> states = new java.util.ArrayList<>();
        Runnable snapshot = () -> states.add(new java.util.ArrayList<>(queue.getAll()));
        queue.addListener(snapshot);
        try {
            queue.add(b);
            assertEquals(1, changes.get());
            assertEquals(1, states.size());
            assertEquals(Arrays.asList(a, c, b), states.get(0));
            assertSame(a, queue.getCurrent());
            changes.set(0);
            states.clear();
            queue.next(b);
            assertEquals(1, changes.get());
            assertEquals(1, states.size());
            assertEquals(Arrays.asList(a, b, c), states.get(0));
            assertSame(a, queue.getCurrent());
        } finally {
            queue.removeListener(snapshot);
        }
    }

    @Test public void userRemovalProtectsOnlyThePlayingOccurrence() {
        Video otherA = video(a.videoId);
        queue.restoreQueue(Arrays.asList(a, otherA, b), 0);
        queue.setCurrent(a);
        assertFalse(queue.canRemoveQueuedItem(a));
        assertFalse(queue.removeQueuedItem(a));
        assertTrue(queue.canRemoveQueuedItem(otherA));
        assertTrue(queue.removeQueuedItem(otherA));
        assertSame(a, queue.getCurrent());
        assertSame(b, queue.getNext());
        assertEquals(2, queue.getAll().size());
    }

    @Test public void internalRemovalStillAcceptsAnEquivalentClone() {
        queue.remove(video(b.videoId));
        assertEquals(1, queue.getAll().size());
        assertSame(a, queue.getCurrent());
    }
}
