package com.newtube.mobile.ui.playback;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
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
public class QueuePersistenceTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private Playlist queue;
    private File path;
    private QueueSnapshotStore store;
    private QueuePersistence manager;
    private final AtomicBoolean privateMode = new AtomicBoolean();
    private final List<Runnable> writes = new ArrayList<>();
    private final Executor writer = writes::add;
    private Video a, b, c;
    private static Video video(String id) { Video v = Video.from(id); v.title = id; return v; }
    private void start() {
        manager = new QueuePersistence(queue, store, new Handler(Looper.getMainLooper()), writer, privateMode::get);
        manager.start();
    }
    private void settle() {
        shadowOf(Looper.getMainLooper()).idle();
        while (!writes.isEmpty()) writes.remove(0).run();
    }
    private void seed() {
        queue.add(a); queue.setCurrent(a);
        b.fromQueue = true; queue.add(b); c.fromQueue = true; queue.add(c);
        queue.setCurrent(b); settle();
    }
    private List<String> ids(List<Video> videos) {
        List<String> result = new ArrayList<>(); for (Video v : videos) result.add(v.videoId); return result;
    }
    @Before public void before() throws Exception {
        queue = Playlist.instance(); queue.clear(); path = new File(folder.getRoot(), "queue.json");
        store = new QueueSnapshotStore(path);
        a = video("aaaaaaaaaaa"); b = video("bbbbbbbbbbb"); c = video("ccccccccccc");
        a.title = "Coffee ☕ & Unicode"; a.author = "Channel";
        start();
    }
    @After public void after() { manager.stop(); writes.clear(); queue.clear(); shadowOf(Looper.getMainLooper()).idle(); }

    @Test public void coldRestartOrUpdateRestoresOrderCursorAndTextWithoutPlayback() {
        seed(); manager.stop(); queue.clear(); start();
        assertEquals(Arrays.asList(a.videoId, b.videoId, c.videoId), ids(queue.getAll()));
        assertEquals(1, queue.getCurrentIndex()); assertEquals(b.videoId, queue.getCurrent().videoId);
        assertEquals(a.title, queue.getAll().get(0).title); assertEquals("Channel", queue.getAll().get(0).author);
        assertTrue(queue.isRestoredQueue()); assertTrue(queue.hasExplicitQueue());
        assertTrue(writes.isEmpty()); // loading is not a playback or a write event
        Video picked = queue.getCurrent(); queue.setCurrent(picked);
        assertFalse(queue.isRestoredQueue()); assertSame(picked, queue.getCurrent());
    }

    @Test public void editsAdvanceAndClearReplaceTheSavedStateInsteadOfResurrectingOldRows() {
        seed(); queue.move(2, 1); queue.setCurrent(c); queue.remove(b); settle();
        QueueSnapshot saved = store.read(); assertNotNull(saved);
        assertEquals(Arrays.asList(a.videoId, c.videoId), ids(saved.videos)); assertEquals(1, saved.current);
        queue.clearExceptCurrent(); settle(); assertTrue(store.read().videos.isEmpty());
        manager.stop(); queue.clear(); start(); assertTrue(queue.getAll().isEmpty());
    }

    @Test public void slowStorageHasOnlyOneWorkerAndLatestClearWins() {
        queue.add(a); queue.setCurrent(a);
        for (int i = 0; i < 100; i++) {
            Video v = video(String.format(java.util.Locale.ROOT, "%011d", i)); v.fromQueue = true; queue.add(v);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, writes.size());
        }
        queue.clear(); settle(); assertNotNull(store.read()); assertTrue(store.read().videos.isEmpty());
        manager.stop(); start(); assertTrue(queue.getAll().isEmpty());
    }

    @Test public void incognitoDoesNotOverwriteNormalQueueOrLeakAfterPrivateModeEnds() throws Exception {
        seed(); byte[] normal = Files.readAllBytes(path.toPath());
        privateMode.set(true);
        Video secret = video("sssssssssss"); secret.fromQueue = true; queue.add(secret);
        secret.incognito = false; queue.setCurrent(secret); settle();
        assertArrayEquals(normal, Files.readAllBytes(path.toPath()));
        privateMode.set(false);
        Video publicVideo = video("ppppppppppp"); publicVideo.fromQueue = true; queue.add(publicVideo); settle();
        assertEquals(Arrays.asList(publicVideo.videoId), ids(store.read().videos));
        assertEquals(-1, store.read().current);
        queue.clear(); settle(); assertTrue(store.read().videos.isEmpty());
    }

    @Test public void ordinaryPlaybackHistoryDoesNotBecomeASavedQueue() {
        queue.add(a); queue.setCurrent(a); queue.add(b); queue.setCurrent(b); settle();
        assertNotNull(store.read()); assertTrue(store.read().videos.isEmpty());
    }

    private Video privateEntryAfterSessionEnds() {
        seed();
        privateMode.set(true);
        Video secret = video("sssssssssss");
        secret.fromQueue = true;
        secret.incognito = true;
        queue.add(secret);
        queue.setCurrent(secret);
        secret.incognito = false; // VideoStateController consumes this routing flag.
        settle();
        privateMode.set(false);
        queue.setCurrent(a);
        settle();
        assertFalse(ids(store.read().videos).contains(secret.videoId));
        return secret;
    }

    @Test public void playNextCannotForgetPrivateEntryDuringItsTemporaryRemoval() {
        Video secret = privateEntryAfterSessionEnds();
        queue.next(secret);
        settle();
        assertFalse("Play next must retain the private exclusion", ids(store.read().videos).contains(secret.videoId));
    }

    @Test public void appendCannotForgetPrivateEntryDuringItsTemporaryRemoval() {
        Video secret = privateEntryAfterSessionEnds();
        Video publicVideo = video("ppppppppppp");
        publicVideo.fromQueue = true;
        queue.add(publicVideo);
        settle();
        queue.add(secret);
        settle();
        assertFalse("Append must retain the private exclusion", ids(store.read().videos).contains(secret.videoId));
        assertTrue(ids(store.read().videos).contains(publicVideo.videoId));
    }

    @Test public void interruptedAtomicWriteKeepsTheLastCompleteSnapshot() throws Exception {
        seed();
        FileOutputStream partial = new AtomicFile(path).startWrite();
        partial.write("{broken".getBytes(StandardCharsets.UTF_8)); partial.close(); // process died before finishWrite
        QueueSnapshot recovered = store.read(); assertNotNull(recovered);
        assertEquals(Arrays.asList(a.videoId, b.videoId, c.videoId), ids(recovered.videos));
    }

    @Test public void corruptOrFutureFilesDoNotCrashOrGetOverwrittenOnStartup() throws Exception {
        manager.stop(); queue.clear();
        byte[] future = "{\"version\":2,\"items\":[]}".getBytes(StandardCharsets.UTF_8);
        Files.write(path.toPath(), future); start(); settle();
        assertTrue(queue.getAll().isEmpty()); assertArrayEquals(future, Files.readAllBytes(path.toPath()));
        manager.stop(); Files.write(path.toPath(), "{truncated".getBytes(StandardCharsets.UTF_8)); start();
        assertTrue(queue.getAll().isEmpty());
    }

    @Test public void oversizedFileIsIgnoredAndOversizedWriteCannotDamagePreviousQueue() throws Exception {
        seed();
        try { store.write(new byte[QueueSnapshot.MAX_BYTES + 1]); fail(); } catch (java.io.IOException expected) { }
        assertNotNull(store.read()); assertEquals(3, store.read().videos.size());
        manager.stop(); queue.clear(); Files.write(path.toPath(), new byte[QueueSnapshot.MAX_BYTES + 1]); start();
        assertTrue(queue.getAll().isEmpty());
    }

    @Test public void anExistingInMemorySelectionTakesPrecedenceOverSavedQueue() {
        seed(); manager.stop(); queue.clear(); queue.add(c); queue.setCurrent(c); start();
        assertEquals(Arrays.asList(c.videoId), ids(queue.getAll())); assertSame(c, queue.getCurrent());
        assertFalse(queue.isRestoredQueue());
    }
}
