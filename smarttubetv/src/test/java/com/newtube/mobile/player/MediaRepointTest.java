package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NEWTUBE(wall-swap): a refused request of an armed open waits for the recovery's answer and goes
 * on at the new answer's URL for the same file; everything else behaves as before.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MediaRepointTest {
    private static final String VIDEO = "pRpeEdMmmQ0";
    private static final int LENGTH = 512;
    /** The wall, as a byte offset: requests from here on are refused for a walled answer. */
    private static final long WALL_BYTE = 10_000;

    @Before
    public void setUp() {
        MediaRepoint.setEnabled(true);
        MediaRepoint.sSwapWaitMs = 5_000;
        MediaRepoint.arm(null, null, (session, refused) -> { });
    }

    @After
    public void tearDown() {
        MediaRepoint.sSwapWaitMs = MediaRepoint.SWAP_WAIT_MS;
        MediaRepoint.arm(null, null, (session, refused) -> { }); // disarms whatever a test armed
    }

    private static String url(String answerId, String itag, String lmt) {
        return "https://rr1---sn-x.googlevideo.com/videoplayback?id=" + answerId + "&itag=" + itag
                + "&lmt=" + lmt + "&clen=99000&ei=" + answerId + "&mime=" + (itag.equals("251")
                ? "audio%2Fwebm" : "video%2Fwebm") + "&sig=x";
    }

    private static DataSpec chunk(String url, long position) {
        return new DataSpec.Builder().setUri(url).setPosition(position).setLength(LENGTH).build();
    }

    /** googlevideo: answers whose ei is in {@code walled} are refused from WALL_BYTE on. */
    private static final class Googlevideo implements DataSource {
        final List<String> walled = new CopyOnWriteArrayList<>();
        final List<DataSpec> opened = new CopyOnWriteArrayList<>();
        @Nullable private DataSpec mOpen;

        @Override public void addTransferListener(TransferListener transferListener) {
        }

        @Override public long open(DataSpec dataSpec) throws IOException {
            opened.add(dataSpec);
            String ei = dataSpec.uri.getQueryParameter("ei");
            if (walled.contains(ei) && dataSpec.position >= WALL_BYTE) {
                throw new HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null,
                        Collections.emptyMap(), dataSpec, new byte[0]);
            }
            mOpen = dataSpec;
            return LENGTH;
        }

        @Override public int read(byte[] buffer, int offset, int length) {
            return -1;
        }

        @Nullable @Override public Uri getUri() {
            return mOpen != null ? mOpen.uri : null;
        }

        @Override public Map<String, List<String>> getResponseHeaders() {
            return Collections.emptyMap();
        }

        @Override public void close() {
            mOpen = null;
        }
    }

    private static MediaFormat format(String url) {
        return (MediaFormat) Proxy.newProxyInstance(MediaFormat.class.getClassLoader(),
                new Class<?>[] {MediaFormat.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUrl": return url;
                        case "isOtf": return false;
                        default: return defaultValue(method.getReturnType());
                    }
                });
    }

    private static MediaItemFormatInfo answer(String answerId, String... itags) {
        List<MediaFormat> formats = new ArrayList<>();
        for (String itag : itags) {
            formats.add(format(url(answerId, itag, itag.equals("251") ? "1791131996733842" : "1791352668006159")));
        }
        return (MediaItemFormatInfo) Proxy.newProxyInstance(MediaItemFormatInfo.class.getClassLoader(),
                new Class<?>[] {MediaItemFormatInfo.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAdaptiveFormats": return formats;
                        case "getVideoId": return VIDEO;
                        case "isLive": return false;
                        default: return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    /** The recovery, on another thread: lands {@code next} (or fails) once asked. */
    private static final class Recovery implements MediaRepoint.Requester {
        final AtomicInteger asked = new AtomicInteger();
        final CountDownLatch askedLatch = new CountDownLatch(1);
        @Nullable volatile MediaItemFormatInfo next;
        volatile long delayMs = 50;

        @Override public void onSwapNeeded(MediaRepoint.Session session, Uri refused) {
            asked.incrementAndGet();
            askedLatch.countDown();
            MediaItemFormatInfo answer = next;
            new Thread(() -> {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                    return;
                }
                if (answer == null || !session.land(MediaRepoint.keyedUrls(answer), null)) {
                    session.fail();
                }
            }).start();
        }
    }

    /** The refused request must go out after the first served one (ms clock). */
    private static void pause() {
        try {
            Thread.sleep(3);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    private static int status(IOException e) {
        return ReadinessGate.responseCode(e);
    }

    @Test
    public void keyIgnoresTheAnswerIdButNotTheFile() {
        assertEquals(MediaRepoint.keyOf(Uri.parse(url("A", "248", "1"))),
                MediaRepoint.keyOf(Uri.parse(url("B", "248", "1"))));
        assertFalse(MediaRepoint.keyOf(Uri.parse(url("A", "248", "1")))
                .equals(MediaRepoint.keyOf(Uri.parse(url("A", "248", "2")))));
        assertNull(MediaRepoint.keyOf(Uri.parse(url("A", "248", "1") + "&sq=4")));
        assertNull(MediaRepoint.keyOf(Uri.parse("https://manifest.googlevideo.com/api/manifest/hls_playlist/x")));
    }

    @Test
    public void unarmedPassesThe403Through() {
        Googlevideo gv = new Googlevideo();
        gv.walled.add("A");
        DataSource source = MediaRepoint.wrap(() -> gv).createDataSource();
        IOException e = assertThrows(IOException.class, () -> source.open(chunk(url("A", "248", "1791352668006159"), WALL_BYTE)));
        assertEquals(403, status(e));
    }

    @Test
    public void aRefusalBeforeAnythingWasServedFailsAtOnce() {
        Googlevideo gv = new Googlevideo();
        gv.walled.add("A");
        Recovery recovery = new Recovery();
        recovery.next = answer("B", "248", "251");
        assertNotNull(MediaRepoint.arm(VIDEO, answer("A", "248", "251"), recovery));
        DataSource source = MediaRepoint.wrap(() -> gv).createDataSource();
        IOException e = assertThrows(IOException.class, () -> source.open(chunk(url("A", "248", "1791352668006159"), WALL_BYTE)));
        assertEquals(403, status(e));
        assertEquals(0, recovery.asked.get());
    }

    @Test
    public void theWallWaitsAndContinuesOnTheNewAnswerForTheSameBytes() throws IOException {
        Googlevideo gv = new Googlevideo();
        gv.walled.add("A");
        Recovery recovery = new Recovery();
        recovery.next = answer("B", "248", "251");
        MediaRepoint.Session session = MediaRepoint.arm(VIDEO, answer("A", "248", "251"), recovery);
        DataSource source = MediaRepoint.wrap(() -> gv).createDataSource();
        String video = url("A", "248", "1791352668006159");

        assertEquals(LENGTH, source.open(chunk(video, 0)));
        source.close();
        pause();
        assertEquals(LENGTH, source.open(chunk(video, WALL_BYTE + 7)));
        source.close();

        assertEquals(1, recovery.asked.get());
        DataSpec retried = gv.opened.get(gv.opened.size() - 1);
        assertEquals("B", retried.uri.getQueryParameter("ei"));
        assertEquals(WALL_BYTE + 7, retried.position);
        assertEquals(LENGTH, retried.length);

        // Later requests of the open, the other track included, go straight to the new answer.
        assertEquals(LENGTH, source.open(chunk(url("A", "251", "1791131996733842"), WALL_BYTE * 3)));
        assertEquals("B", gv.opened.get(gv.opened.size() - 1).uri.getQueryParameter("ei"));
        assertEquals(1, recovery.asked.get());
        assertFalse(session.isPending());
    }

    @Test
    public void concurrentRefusalsShareOneRecovery() throws Exception {
        Googlevideo gv = new Googlevideo();
        gv.walled.add("A");
        Recovery recovery = new Recovery();
        recovery.next = answer("B", "248", "251");
        recovery.delayMs = 300;
        MediaRepoint.arm(VIDEO, answer("A", "248", "251"), recovery);
        DataSource video = MediaRepoint.wrap(() -> gv).createDataSource();
        DataSource audio = MediaRepoint.wrap(() -> gv).createDataSource();
        video.open(chunk(url("A", "248", "1791352668006159"), 0));
        video.close();
        pause();

        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Thread audioLoader = new Thread(() -> {
            try {
                audio.open(chunk(url("A", "251", "1791131996733842"), WALL_BYTE));
            } catch (IOException e) {
                failures.add(e);
            }
        });
        audioLoader.start();
        assertTrue(recovery.askedLatch.await(2, TimeUnit.SECONDS));
        video.open(chunk(url("A", "248", "1791352668006159"), WALL_BYTE));
        audioLoader.join(3_000);

        assertTrue(failures.toString(), failures.isEmpty());
        assertEquals(1, recovery.asked.get());
    }

    @Test
    public void anAnswerWithoutTheRefusedFileFailsAsBefore() {
        Googlevideo gv = new Googlevideo();
        gv.walled.add("A");
        Recovery recovery = new Recovery();
        recovery.next = answer("B", "251"); // no 248
        MediaRepoint.arm(VIDEO, answer("A", "248", "251"), recovery);
        DataSource source = MediaRepoint.wrap(() -> gv).createDataSource();
        String video = url("A", "248", "1791352668006159");
        assertThrows(IOException.class, () -> {
            source.open(chunk(video, 0));
            source.close();
            pause();
            source.open(chunk(video, WALL_BYTE));
        });
        assertEquals(1, recovery.asked.get());
        // Given up: a later refusal of the open fails at once, with no new recovery.
        IOException e = assertThrows(IOException.class, () -> source.open(chunk(video, WALL_BYTE * 2)));
        assertEquals(403, status(e));
        assertEquals(1, recovery.asked.get());
    }

    @Test
    public void noAnswerInTimeFailsAsBefore() {
        MediaRepoint.sSwapWaitMs = 200;
        Googlevideo gv = new Googlevideo();
        gv.walled.add("A");
        Recovery recovery = new Recovery();
        recovery.next = answer("B", "248", "251");
        recovery.delayMs = 2_000; // too late
        MediaRepoint.arm(VIDEO, answer("A", "248", "251"), recovery);
        DataSource source = MediaRepoint.wrap(() -> gv).createDataSource();
        String video = url("A", "248", "1791352668006159");
        IOException e = assertThrows(IOException.class, () -> {
            source.open(chunk(video, 0));
            source.close();
            pause();
            source.open(chunk(video, WALL_BYTE));
        });
        assertEquals(403, status(e));
    }

    @Test
    public void aWalledReplacementGetsOneMoreSwapThenFails() {
        Googlevideo gv = new Googlevideo();
        gv.walled.add("A");
        gv.walled.add("B");
        gv.walled.add("C");
        Recovery recovery = new Recovery();
        recovery.next = answer("B", "248", "251");
        MediaRepoint.arm(VIDEO, answer("A", "248", "251"), recovery);
        DataSource source = MediaRepoint.wrap(() -> gv).createDataSource();
        String video = url("A", "248", "1791352668006159");
        IOException e = assertThrows(IOException.class, () -> {
            source.open(chunk(video, 0));
            source.close();
            pause();
            recovery.next = answer("C", "248", "251");
            // B is refused too: the second swap goes to C, also refused, and no third is asked.
            source.open(chunk(video, WALL_BYTE));
        });
        assertEquals(403, status(e));
        assertEquals(MediaRepoint.MAX_SWAPS, recovery.asked.get());
    }

    @Test
    public void aRequestSentTogetherWithTheFirstServedOneIsTheGatesNotAWall() throws IOException {
        Googlevideo gv = new Googlevideo();
        gv.walled.add("A");
        Recovery recovery = new Recovery();
        recovery.next = answer("B", "248", "251");
        MediaRepoint.Session session = MediaRepoint.arm(VIDEO, answer("A", "248", "251"), recovery);
        DataSource source = MediaRepoint.wrap(() -> gv).createDataSource();
        // Served after this request went out: its refusal belongs to the answer's readiness gate.
        long sentAt = MediaRepoint.nowMs();
        pause();
        session.onServed();
        assertFalse(session.servedBefore(sentAt));
        pause();
        assertTrue(session.servedBefore(MediaRepoint.nowMs()));
        assertEquals(0, recovery.asked.get());
    }

    @Test
    public void aFileTheNewAnswerLacksFailsAloneWithoutANewSwap() throws IOException {
        Googlevideo gv = new Googlevideo();
        gv.walled.add("A");
        Recovery recovery = new Recovery();
        recovery.next = answer("B", "248", "251"); // no 247
        MediaRepoint.arm(VIDEO, answer("A", "248", "251", "247"), recovery);
        DataSource source = MediaRepoint.wrap(() -> gv).createDataSource();
        source.open(chunk(url("A", "248", "1791352668006159"), 0));
        source.close();
        pause();
        source.open(chunk(url("A", "248", "1791352668006159"), WALL_BYTE));
        source.close();
        assertEquals(1, recovery.asked.get());
        IOException e = assertThrows(IOException.class,
                () -> source.open(chunk(url("A", "247", "1791352668006159"), WALL_BYTE)));
        assertEquals(403, status(e));
        assertEquals(1, recovery.asked.get());
        // The swapped files still play.
        assertEquals(LENGTH, source.open(chunk(url("A", "251", "1791131996733842"), WALL_BYTE)));
    }

    @Test
    public void disarmingReleasesAWaitingRequest() throws Exception {
        Googlevideo gv = new Googlevideo();
        gv.walled.add("A");
        Recovery recovery = new Recovery();
        recovery.next = null;
        recovery.delayMs = 10_000;
        MediaRepoint.Session session = MediaRepoint.arm(VIDEO, answer("A", "248", "251"), recovery);
        DataSource source = MediaRepoint.wrap(() -> gv).createDataSource();
        String video = url("A", "248", "1791352668006159");
        source.open(chunk(video, 0));
        source.close();
        pause();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Thread loader = new Thread(() -> {
            try {
                source.open(chunk(video, WALL_BYTE));
            } catch (IOException e) {
                failures.add(e);
            }
        });
        loader.start();
        assertTrue(recovery.askedLatch.await(2, TimeUnit.SECONDS));
        MediaRepoint.disarm(session); // a new open, a release
        loader.join(2_000);
        assertFalse(loader.isAlive());
        assertEquals(1, failures.size());
    }
}
