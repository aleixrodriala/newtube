package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.datasource.TransferListener;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.BaseMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SampleQueue;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.source.preload.PreloadException;
import androidx.media3.exoplayer.source.preload.PreloadMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;

/** Media3's real preload period, sample queue and allocator, fed only synthetic in-memory samples. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class Media3PreloadSampleHandoffTest {
    private static final PlayerId FOREGROUND = new PlayerId("preload-handoff-test");
    private final MemorySource source = new MemorySource();
    private final Control control = new Control();
    private DefaultTrackSelector selector;
    private DefaultLoadControl loadControl;
    private DefaultAllocator allocations;
    private PreloadMediaSource preload;
    private MediaSource.MediaSourceCaller foregroundCaller;
    private MediaPeriod foregroundPeriod;

    @Before
    public void setUp() {
        Application context = RuntimeEnvironment.getApplication();
        selector = new DefaultTrackSelector(context);
        DefaultBandwidthMeter bandwidth = new DefaultBandwidthMeter.Builder(context).build();
        selector.init(() -> { }, bandwidth);
        allocations = new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
        loadControl = new DefaultLoadControl.Builder().setAllocator(allocations)
                .setPlayerTargetBufferBytes(PlayerId.PRELOAD.name, 4 * 1024 * 1024)
                .build();
        // A controlled common looper makes both preload and foreground operations deterministic.
        // Production uses the shared builder's non-main playback looper, not this test looper.
        preload = new PreloadMediaSource.Factory(new DefaultMediaSourceFactory(context), control,
                selector, bandwidth, new RendererCapabilities[] {VIDEO_CAPABILITIES}, loadControl,
                Looper.getMainLooper()).createMediaSource(source);
    }

    @After
    public void tearDown() {
        if (foregroundPeriod != null) {
            preload.releasePeriod(foregroundPeriod);
        }
        if (foregroundCaller != null) {
            preload.releaseSource(foregroundCaller);
            loadControl.onReleased(FOREGROUND);
        }
        preload.releasePreloadMediaSource();
        shadowOf(Looper.getMainLooper()).idle();
        selector.release();
    }

    @Test
    public void loadsRealQueuedSamplesAndStopsAtTheShortTarget() {
        preload.preload(0);
        shadowOf(Looper.getMainLooper()).idle();

        assertNull(control.error);
        assertEquals(1, control.completions);
        assertEquals(2, source.period.loads);
        assertEquals(2, source.period.queue.getWriteIndex());
        assertEquals(2_000_000, source.period.getBufferedPositionUs());
        assertFalse(source.period.released);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, source.period.loads);
    }

    @Test
    public void sameStartAndTrackReuseTheLoadedPeriodAndReadableSamples() {
        preload.preload(0);
        shadowOf(Looper.getMainLooper()).idle();
        MemoryPeriod loaded = source.period;
        adopt(0);

        assertTrue(control.usedByPlayer);
        assertEquals(1, source.periodCreations);
        assertSame(loaded, source.period);
        SampleStream[] streams = new SampleStream[1];
        foregroundPeriod.selectTracks(new ExoTrackSelection[] {
                new FixedTrackSelection(MemoryPeriod.GROUP, 0)}, new boolean[1], streams,
                new boolean[1], 0);
        FormatHolder holder = new FormatHolder();
        DecoderInputBuffer sample = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
        assertEquals(C.RESULT_FORMAT_READ, streams[0].readData(holder, sample, 0));
        assertEquals(C.RESULT_BUFFER_READ, streams[0].readData(holder, sample, 0));
        assertEquals(0, sample.timeUs);
        assertEquals(4, sample.data.position());

        // Equivalent to removing manager ownership after foreground tracks are known.
        preload.releasePreloadMediaSource();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(loaded.released);
        sample.clear();
        assertEquals(C.RESULT_BUFFER_READ, streams[0].readData(holder, sample, 0));
        assertEquals(1_000_000, sample.timeUs);
        assertEquals(2, loaded.loads); // no foreground re-fetch was needed for either sample
    }

    @Test
    public void resumeAtDifferentPositionDiscardsTheIncompatiblePreloadedPeriod() {
        preload.preload(0);
        shadowOf(Looper.getMainLooper()).idle();
        MemoryPeriod loaded = source.period;
        adopt(5_000_000);
        assertTrue(loaded.released);
        assertEquals(2, source.periodCreations);
    }

    @Test
    public void cancelReleasesSampleAllocationsAndTheUnplayedSource() {
        preload.preload(0);
        shadowOf(Looper.getMainLooper()).idle();
        MemoryPeriod loaded = source.period;
        preload.releasePreloadMediaSource();
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(loaded.released);
        assertEquals(1, source.sourceReleases);
        // PlayerId.PRELOAD's accounting entry is deliberately gone after onReleased; query the
        // real backing allocator, not an invalid released-player filtering view.
        assertEquals(0, allocations.getTotalBytesAllocated());
    }

    private void adopt(long positionUs) {
        loadControl.onPrepared(FOREGROUND);
        foregroundCaller = (mediaSource, timeline) -> { };
        preload.prepareSource(foregroundCaller, null, FOREGROUND);
        foregroundPeriod = preload.createPeriod(new MediaSource.MediaPeriodId(source.timeline.getUidOfPeriod(0)),
                loadControl.getAllocator(FOREGROUND), positionUs);
        foregroundPeriod.prepare(new MediaPeriod.Callback() {
            @Override public void onPrepared(MediaPeriod mediaPeriod) { }
            @Override public void onContinueLoadingRequested(MediaPeriod mediaPeriod) { }
        }, positionUs);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static final RendererCapabilities VIDEO_CAPABILITIES = new RendererCapabilities() {
        @Override public String getName() { return "MemoryVideo"; }
        @Override public int getTrackType() { return C.TRACK_TYPE_VIDEO; }
        @Override public int supportsFormat(Format format) {
            return RendererCapabilities.create(C.FORMAT_HANDLED);
        }
        @Override public int supportsMixedMimeTypeAdaptation() { return ADAPTIVE_NOT_SUPPORTED; }
    };

    private static final class Control implements PreloadMediaSource.PreloadControl {
        int completions;
        boolean usedByPlayer;
        PreloadException error;

        @Override public boolean onSourcePrepared(PreloadMediaSource source) { return true; }
        @Override public boolean onTracksSelected(PreloadMediaSource source) { return true; }
        @Override public boolean onContinueLoadingRequested(PreloadMediaSource source, long bufferedUs) {
            if (bufferedUs < Media3NextPreloader.TARGET_DURATION_MS * 1000) {
                return true;
            }
            completions++;
            return false;
        }
        @Override public void onUsedByPlayer(PreloadMediaSource source) { usedByPlayer = true; }
        @Override public void onPreloadError(PreloadException failure, PreloadMediaSource source) {
            error = failure;
        }
    }

    private static final class MemorySource extends BaseMediaSource {
        final MediaItem item = MediaItem.fromUri("https://media.invalid/memory-samples.mpd");
        final Timeline timeline = new SinglePeriodTimeline(60_000_000, true, false, false, null, item);
        MemoryPeriod period;
        int periodCreations;
        int sourceReleases;

        @Override public MediaItem getMediaItem() { return item; }
        @Override protected void prepareSourceInternal(TransferListener listener) { refreshSourceInfo(timeline); }
        @Override public void maybeThrowSourceInfoRefreshError() { }
        @Override public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long positionUs) {
            periodCreations++;
            period = new MemoryPeriod(allocator);
            return period;
        }
        @Override public void releasePeriod(MediaPeriod period) { ((MemoryPeriod) period).release(); }
        @Override protected void releaseSourceInternal() { sourceReleases++; }
    }

    private static final class MemoryPeriod implements MediaPeriod {
        static final Format FORMAT = new Format.Builder().setId("test-video")
                .setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(320).setHeight(180)
                .setAverageBitrate(100_000).build();
        static final TrackGroup GROUP = new TrackGroup(FORMAT);
        final SampleQueue queue;
        Callback callback;
        int loads;
        boolean released;

        MemoryPeriod(Allocator allocator) {
            queue = SampleQueue.createWithoutDrm(allocator);
            queue.format(FORMAT);
        }

        void release() { released = true; queue.release(); }
        @Override public void prepare(Callback callback, long positionUs) {
            this.callback = callback;
            callback.onPrepared(this);
        }
        @Override public void maybeThrowPrepareError() { }
        @Override public TrackGroupArray getTrackGroups() { return new TrackGroupArray(GROUP); }
        @Override public long selectTracks(ExoTrackSelection[] selections, boolean[] mayRetain,
                SampleStream[] streams, boolean[] reset, long positionUs) {
            for (int index = 0; index < streams.length; index++) {
                if (selections[index] == null) {
                    streams[index] = null;
                } else if (streams[index] == null || !mayRetain[index]) {
                    streams[index] = new SampleStream() {
                        @Override public boolean isReady() { return queue.isReady(false); }
                        @Override public void maybeThrowError() { }
                        @Override public int readData(FormatHolder holder, DecoderInputBuffer sample, int flags) {
                            return queue.read(holder, sample, flags, false);
                        }
                        @Override public int skipData(long positionUs) { return 0; }
                    };
                    reset[index] = true;
                }
            }
            return positionUs;
        }
        @Override public boolean continueLoading(LoadingInfo info) {
            // Opaque test payloads exercise storage/handoff, not codec validity or decoding.
            byte[] bytes = new byte[] {0, 0, 1, 0};
            queue.sampleData(new ParsableByteArray(bytes), bytes.length, 0);
            queue.sampleMetadata(loads * 1_000_000L, C.BUFFER_FLAG_KEY_FRAME, bytes.length, 0, null);
            loads++;
            callback.onContinueLoadingRequested(this);
            return true;
        }
        @Override public long getBufferedPositionUs() { return loads * 1_000_000L; }
        @Override public long getNextLoadPositionUs() { return getBufferedPositionUs(); }
        @Override public boolean isLoading() { return false; }
        @Override public long readDiscontinuity() { return C.TIME_UNSET; }
        @Override public long seekToUs(long positionUs) { return positionUs; }
        @Override public long getAdjustedSeekPositionUs(long positionUs, SeekParameters parameters) { return positionUs; }
        @Override public void discardBuffer(long positionUs, boolean keyframe) { }
        @Override public void reevaluateBuffer(long positionUs) { }
    }
}
