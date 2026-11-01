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

    @Test public void listenerMayUnregisterDuringNotification() {
        Runnable[] once = new Runnable[1];
        once[0] = () -> queue.removeListener(once[0]);
        queue.addListener(once[0]);
        queue.clear(); queue.clear();
        assertEquals(2, changes.get());
    }
}
