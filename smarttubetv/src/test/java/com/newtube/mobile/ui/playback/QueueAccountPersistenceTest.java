package com.newtube.mobile.ui.playback;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 35}, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class QueueAccountPersistenceTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final String OUT = repeat('0'), A = repeat('a'), B = repeat('b');
    private static String repeat(char c) { char[] chars = new char[64]; Arrays.fill(chars, c); return new String(chars); }
    private final List<Runnable> tasks = new ArrayList<>();
    private final AtomicBoolean privateMode = new AtomicBoolean();
    private Playlist queue;
    private QueueAccountStorage storage;
    private QueuePersistence manager;
    private int closed;
    @Before public void setup() throws Exception {
        queue = Playlist.instance(); queue.clear();
        storage = new QueueAccountStorage(folder.getRoot(), A);
        start(A);
    }
    private void start(String scope) {
        manager = new QueuePersistence(queue, storage, scope, new Handler(Looper.getMainLooper()), tasks::add,
                privateMode::get, () -> "sssssssssss");
        manager.start();
    }
    private void switchTo(String scope) { manager.switchScope(scope, () -> { closed++; privateMode.set(false); }); }
    private void add(String id) { Video video = Video.from(id); video.fromQueue = true; queue.add(video); }
    private List<String> ids() { List<String> result = new ArrayList<>(); for (Video v : queue.getAll()) result.add(v.videoId); return result; }
    private void capture() { shadowOf(Looper.getMainLooper()).idle(); }
    private void settle() { capture(); while (!tasks.isEmpty()) tasks.remove(0).run(); }
    @After public void teardown() { manager.stop(); tasks.clear(); queue.clear(); capture(); }

    @Test public void signedOutAndTwoAccountsKeepTheirOwnQueuesAcrossSwitchesAndRestart() {
        add("aaaaaaaaaaa"); queue.setCurrent(queue.getAll().get(0));
        switchTo(OUT); assertTrue(ids().isEmpty()); add("ooooooooooo");
        switchTo(B); assertTrue(ids().isEmpty()); add("bbbbbbbbbbb");
        switchTo(A); assertEquals(Arrays.asList("aaaaaaaaaaa"), ids()); assertTrue(queue.isRestoredQueue());
        assertEquals(0, queue.getCurrentIndex());
        switchTo(OUT); assertEquals(Arrays.asList("ooooooooooo"), ids());
        switchTo(B); assertEquals(Arrays.asList("bbbbbbbbbbb"), ids()); settle();
        manager.stop(); queue.clear(); start(A);
        assertEquals(Arrays.asList("aaaaaaaaaaa"), ids()); assertTrue(queue.isRestoredQueue());
        assertEquals(5, closed);
    }

    @Test public void rapidSwitchesCoalescePerAccountWithoutDroppingOutgoingWrites() {
        for (int i = 0; i < 100; i++) {
            switchTo(A); queue.clear(); add("aaaaaaaaaaa");
            switchTo(B); queue.clear(); add("bbbbbbbbbbb");
            switchTo(OUT); queue.clear(); add("ooooooooooo");
        }
        assertEquals(1, tasks.size());
        switchTo(A); assertEquals(Arrays.asList("aaaaaaaaaaa"), ids()); settle();
        switchTo(B); assertEquals(Arrays.asList("bbbbbbbbbbb"), ids());
        switchTo(OUT); assertEquals(Arrays.asList("ooooooooooo"), ids());
    }

    @Test public void clearingOneAccountDoesNotClearAnotherOrResurrectOlderWrites() {
        add("aaaaaaaaaaa"); switchTo(B); add("bbbbbbbbbbb");
        switchTo(A); queue.clear(); switchTo(B); settle();
        switchTo(A); assertTrue(ids().isEmpty());
        switchTo(B); assertEquals(Arrays.asList("bbbbbbbbbbb"), ids());
    }

    @Test public void privatePlaybackCannotTravelToAnotherAccountOrOverwriteItsNormalQueue() {
        add("aaaaaaaaaaa"); settle(); privateMode.set(true); add("sssssssssss"); capture();
        switchTo(B); assertTrue(ids().isEmpty()); add("bbbbbbbbbbb");
        switchTo(A); assertEquals(Arrays.asList("aaaaaaaaaaa"), ids());
        switchTo(B); assertEquals(Arrays.asList("bbbbbbbbbbb"), ids());
    }

    @Test public void closingOldPlaybackCannotWriteIntoIncomingAccount() {
        add("aaaaaaaaaaa"); switchTo(B); add("bbbbbbbbbbb"); switchTo(A);
        manager.switchScope(B, () -> { queue.clear(); add("ccccccccccc"); });
        assertEquals(Arrays.asList("bbbbbbbbbbb"), ids());
        switchTo(A); assertEquals(Arrays.asList("aaaaaaaaaaa"), ids());
    }

    @Test public void failedWritesStillPreserveSeparateQueuesDuringThisProcess() {
        manager.stop();
        QueueStorage unavailable = new QueueStorage() {
            public QueueSnapshot read(String scope) { return null; }
            public void write(String scope, byte[] bytes) throws java.io.IOException { throw new java.io.IOException("fixture full disk"); }
        };
        manager = new QueuePersistence(queue, unavailable, A, new Handler(Looper.getMainLooper()), tasks::add,
                privateMode::get, () -> null);
        manager.start();
        add("aaaaaaaaaaa"); settle(); switchTo(B); add("bbbbbbbbbbb"); settle();
        switchTo(A); assertEquals(Arrays.asList("aaaaaaaaaaa"), ids());
        switchTo(B); assertEquals(Arrays.asList("bbbbbbbbbbb"), ids());
    }

    @Test public void duplicateAccountNotificationDoesNotClosePlaybackOrReplaceLiveQueue() {
        add("aaaaaaaaaaa"); Video current = queue.getAll().get(0); queue.setCurrent(current);
        switchTo(A); assertEquals(0, closed); assertSame(current, queue.getCurrent()); assertFalse(queue.isRestoredQueue());
    }

    @Test public void legacyQueueIsClaimedOnceByInitialAccountEvenIfItHasNotYetBeenWritten() throws Exception {
        add("lllllllllll"); settle();
        byte[] bytes = QueueSnapshot.capture(queue, Collections.emptySet()).encode();
        manager.stop(); queue.clear();
        File fresh = folder.newFolder();
        new QueueSnapshotStore(new File(fresh, "mobile-queue-v1.json")).write(bytes);
        QueueAccountStorage first = new QueueAccountStorage(fresh, A);
        assertEquals("lllllllllll", first.read(A).videos.get(0).videoId);
        QueueAccountStorage second = new QueueAccountStorage(fresh, B);
        assertNull(second.read(B)); assertNotNull(second.read(A));
        queue.clear(); first.write(A, QueueSnapshot.capture(queue, Collections.emptySet()).encode());
        assertTrue(second.read(A).videos.isEmpty());
        assertFalse(new File(fresh, "mobile-queue-v1.json").exists());
    }
}
