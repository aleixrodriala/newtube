package com.newtube.mobile.ui.playback;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import android.widget.FrameLayout;
import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.request.Request;
import com.bumptech.glide.request.target.DrawableImageViewTarget;
import com.bumptech.glide.signature.ObjectKey;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class QueueSheetImagesTest {
    private Application app;
    private QueueSheet.Adapter adapter;
    private QueueSheet.RowHolder row;
    private Video a, b, played;
    private final CountDownLatch fetched = new CountDownLatch(1);
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private static Video video(String id) {
        Video v = new Video(); v.videoId = id; v.title = id; return v;
    }
    @Before public void before() {
        app = RuntimeEnvironment.getApplication(); app.setTheme(R.style.Theme_NewTube);
        a = video("aaaaaaaaaaa"); b = video("bbbbbbbbbbb");
        Playlist queue = Playlist.instance(); queue.clear(); queue.addAll(Arrays.asList(a, b)); queue.setCurrent(a);
        adapter = new QueueSheet.Adapter(app, new QueueSheet.Host() {
            public void playFromQueue(Video v) { played = v; }
            public void onQueueChanged() { }
            public void showQueueSheet(BottomSheetDialog dialog) { }
        }, new BottomSheetDialog(app));
        adapter.onCountChanged = () -> { }; adapter.reload();
        row = adapter.onCreateViewHolder(new FrameLayout(app), 0);
    }
    @After public void after() {
        adapter.onViewRecycled(row); adapter.dispose(); Playlist.instance().clear();
        shadowOf(Looper.getMainLooper()).idle();
    }
    private void fakeImage(boolean fail) { fakeImage(fail ? "disconnect" : "hold"); }
    private void fakeImage(String mode) {
        String url = "queue-test://" + UUID.randomUUID(); b.cardImageUrl = url;
        Glide.get(app).getRegistry().prepend(String.class, InputStream.class,
                new ModelLoaderFactory<String, InputStream>() {
                    public ModelLoader<String, InputStream> build(MultiModelLoaderFactory unused) {
                        return new ModelLoader<String, InputStream>() {
                            public boolean handles(String model) { return url.equals(model); }
                            public LoadData<InputStream> buildLoadData(String model, int width, int height, Options options) {
                                return new LoadData<>(new ObjectKey(model), new DataFetcher<InputStream>() {
                                    public void loadData(Priority priority, DataCallback<? super InputStream> callback) {
                                        attempts.incrementAndGet();
                                        if ("disconnect".equals(mode)) {
                                            callback.onLoadFailed(new IOException("simulated lost connection"));
                                        } else if ("corrupt".equals(mode)) {
                                            callback.onDataReady(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}));
                                        }
                                        fetched.countDown();
                                    }
                                    public void cleanup() { }
                                    public void cancel() { cancelled.set(true); }
                                    public Class<InputStream> getDataClass() { return InputStream.class; }
                                    public DataSource getDataSource() { return DataSource.REMOTE; }
                                });
                            }
                        };
                    }
                    public void teardown() { }
                });
    }
    @Test public void connectionFailureLeavesPlaceholderAndQueueUsableWithoutRetryLoop() throws Exception {
        fakeImage(true); adapter.onBindViewHolder(row, 1);
        assertTrue(fetched.await(5, TimeUnit.SECONDS)); shadowOf(Looper.getMainLooper()).idle();
        Request request = new DrawableImageViewTarget(row.thumbnail).getRequest();
        assertNotNull(request); awaitDecode(request);
        assertFalse("request still running", request.isRunning()); assertFalse("corrupt data decoded", request.isComplete());
        assertNotNull(row.thumbnail.getDrawable()); assertEquals(1, attempts.get());
        assertEquals(2, adapter.getItemCount()); assertTrue(adapter.play(1)); assertSame(b, played);
    }
    private void awaitDecode(Request request) throws Exception {
        // Decoder work is asynchronous even after DataFetcher has delivered its bytes. Pump the
        // simulated UI until the request's terminal callback arrives (test-only, bounded to 5 s).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (request.isRunning() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            if (request.isRunning()) Thread.sleep(10);
        }
        shadowOf(Looper.getMainLooper()).idle();
    }
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test public void corruptOrTruncatedImageDoesNotCrashOrRemoveTheVideo() throws Exception {
        fakeImage("corrupt"); adapter.onBindViewHolder(row, 1);
        assertTrue(fetched.await(5, TimeUnit.SECONDS)); shadowOf(Looper.getMainLooper()).idle();
        Request request = new DrawableImageViewTarget(row.thumbnail).getRequest();
        assertNotNull(request); awaitDecode(request);
        assertFalse("request still running", request.isRunning()); assertFalse("corrupt data decoded", request.isComplete());
        assertNotNull(row.thumbnail.getDrawable()); assertEquals(2, adapter.getItemCount());
        assertTrue(adapter.play(1)); assertSame(b, played);
    }
    @Test public void missingTitleAndAuthorStillRenderASelectableVideo() {
        b.title = null;
        adapter.onBindViewHolder(row, 1); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(app.getString(R.string.mobile_queue_untitled_video), row.title.getText().toString());
        assertTrue(adapter.play(1)); assertSame(b, played);
    }
    @Test public void recycledRowCancelsItsOutstandingRequestAndClearsItsImage() throws Exception {
        fakeImage(false); adapter.onBindViewHolder(row, 1);
        assertTrue(fetched.await(5, TimeUnit.SECONDS));
        adapter.onViewRecycled(row); shadowOf(Looper.getMainLooper()).idle();
        assertTrue(cancelled.get());
        assertNull(new DrawableImageViewTarget(row.thumbnail).getRequest());
        assertNull(row.thumbnail.getDrawable());
    }
    @Test public void missingQueueRowCannotCrashOrSelectAnotherVideo() {
        Playlist.instance().addAll(Arrays.asList((Video) null)); adapter.reload();
        adapter.onBindViewHolder(row, 2);
        assertEquals(app.getString(R.string.mobile_queue_untitled_video), row.title.getText().toString());
        assertFalse(adapter.play(2)); assertFalse(adapter.move(2, 1));
        adapter.clearAllButCurrent(); assertEquals(Arrays.asList(a), Playlist.instance().getAll());
    }
    @Test public void missingImageNeedsNoConnectionAndDoesNotHideTheVideo() {
        b.cardImageUrl = null; b.altCardImageUrl = null;
        adapter.onBindViewHolder(row, 1); shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(row.thumbnail.getDrawable()); assertEquals(b.title, row.title.getText().toString());
        assertTrue(adapter.play(1)); assertSame(b, played); assertEquals(0, attempts.get());
    }
    @Test public void reusingHolderCancelsTheOldImageBeforeDisplayingAnotherVideo() throws Exception {
        fakeImage(false); adapter.onBindViewHolder(row, 1);
        assertTrue(fetched.await(5, TimeUnit.SECONDS));
        adapter.onBindViewHolder(row, 0); shadowOf(Looper.getMainLooper()).idle();
        assertTrue(cancelled.get()); assertEquals(a.title, row.title.getText().toString());
    }
}
