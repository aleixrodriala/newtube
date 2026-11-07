package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Handler;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.chunk.MediaChunk;
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator;
import androidx.media3.exoplayer.source.chunk.SingleSampleMediaChunk;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.media3.exoplayer.upstream.BandwidthMeter;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The PiP / mini-card rung cap: media3's viewport retention rule,
 * applied inside adaptive selection only (so the track set - and therefore the sample stream and
 * its buffer - never changes), with explicit picks, audio and media3's own switching rules
 * untouched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ViewportCappedTrackSelectionTest {
    private static final Format P1080 = video("137", 1920, 1080, 4_000_000);
    private static final Format P720 = video("136", 1280, 720, 2_000_000);
    private static final Format P480 = video("135", 854, 480, 1_000_000);
    private static final Format P360 = video("134", 640, 360, 600_000);
    private static final Format P240 = video("133", 426, 240, 300_000);
    private static final Format P144 = video("160", 256, 144, 100_000);
    private static final List<Format> LADDER = Arrays.asList(P1080, P720, P480, P360, P240, P144);
    private static final Format P1440 = video("271", 2560, 1440, 9_000_000);
    private static final Format V1080 = video("v1080", 1080, 1920, 3_000_000);
    private static final Format V720 = video("v720", 720, 1280, 1_500_000);
    private static final Format V480 = video("v480", 480, 854, 800_000);
    private static final Format V360 = video("v360", 360, 640, 500_000);
    private static final List<Format> VERTICAL = Arrays.asList(V1080, V720, V480, V360);
    private static final long DOWN_WINDOW_US = 62_500_000L; // HIGH preset midpoint
    /** A 16:9 window as wide as a Pixel 9 (1080x608). */
    private static final int BOX_W = 1080;
    private static final int BOX_H = 608;

    private final VideoViewportCap cap = new VideoViewportCap();

    @After
    public void tearDown() {
        VideoViewportCap.shared().clear("test");
    }

    // ---------------------------------------------------------------------------------
    // Pure rule (media3's getMaxVideoPixelsToRetainForViewport, orientation fixed)
    // ---------------------------------------------------------------------------------

    @Test
    public void defaultPipWindowKeepsThreeSixtyP() {
        assertEquals(640 * 360, VideoViewportCap.maxPixelsToRetain(LADDER, 604, 340));
    }

    @Test
    public void miniCardKeepsThreeSixtyP() {
        // 180x102 dp at the old TV-derived density (2.525 on a Pixel 9); the system's 2.625
        // (473x268) keeps the same rung.
        assertEquals(640 * 360, VideoViewportCap.maxPixelsToRetain(LADDER, 473, 268));
        assertEquals(640 * 360, VideoViewportCap.maxPixelsToRetain(LADDER, 455, 258));
    }

    @Test
    public void enlargedPipWindowKeepsSevenTwentyP() {
        assertEquals(1280 * 720, VideoViewportCap.maxPixelsToRetain(LADDER, 1000, 563));
    }

    @Test
    public void windowLargerThanEveryRungCapsNothing() {
        assertEquals(Integer.MAX_VALUE, VideoViewportCap.maxPixelsToRetain(LADDER, 2424, 1364));
    }

    @Test
    public void verticalVideoInAVerticalPipWindow() {
        List<Format> shorts = Arrays.asList(video("a", 1080, 1920, 3_000_000),
                video("b", 720, 1280, 1_500_000), video("c", 360, 640, 500_000));
        assertEquals(360 * 640, VideoViewportCap.maxPixelsToRetain(shorts, 340, 604));
    }

    @Test
    public void audioAndUnknownSizesAreAlwaysAllowed() {
        Format opus = new Format.Builder().setId("251").setSampleMimeType(MimeTypes.AUDIO_OPUS)
                .setAverageBitrate(160_000).build();
        assertTrue(VideoViewportCap.allows(opus, 1));
        assertTrue(VideoViewportCap.allows(P1080, Integer.MAX_VALUE));
        assertFalse(VideoViewportCap.allows(P720, 640 * 360));
        assertTrue(VideoViewportCap.allows(P360, 640 * 360));
    }

    @Test
    public void windowPixelsUseTheSystemDensityNotTheAppsOverride() {
        Configuration pip = new Configuration();
        pip.screenWidthDp = 230;
        pip.screenHeightDp = 130;
        pip.densityDpi = 504; // UI scale 1.2x over a Pixel 9's 420: the activity's override
        int[] pixels = VideoViewportCap.windowPixels(pip, 420); // Pixel 9: 2.625
        assertEquals(604, pixels[0]);
        assertEquals(341, pixels[1]);
        assertNull(VideoViewportCap.windowPixels(new Configuration(), 420));
        assertNull(VideoViewportCap.windowPixels(pip, 0));
        assertNull(VideoViewportCap.windowPixels(null, 420));
    }

    @Test
    public void setIsIdempotentAndClearRemovesTheCap() {
        cap.set("pip", 604, 340);
        VideoViewportCap.Viewport first = cap.current();
        cap.set("pip", 604, 340); // the config change that follows PiP entry
        assertSame(first, cap.current());
        cap.set("pip", 1000, 563); // user resize
        assertNotSame(first, cap.current());
        cap.set("pip", 0, 340); // unusable size is ignored, the cap stays
        assertEquals(1000, cap.current().width);
        cap.clear("pip-exit");
        assertNull(cap.current());
    }

    // ---------------------------------------------------------------------------------
    // The rule in other window shapes
    // ---------------------------------------------------------------------------------

    @Test
    public void aScreenWideSixteenByNineWindowKeepsSevenTwentyP() {
        // A 1080x608 window (an enlarged PiP on a tablet): 720p (1280x720) already covers it.
        assertEquals(1280 * 720, VideoViewportCap.maxPixelsToRetain(LADDER, BOX_W, BOX_H));
    }

    @Test
    public void verticalVideoFullscreenPortraitKeepsTheTopRung() {
        // A vertical video on the whole 1080x2424 portrait screen fills the width: 1080x1920.
        int maxPixels = VideoViewportCap.maxPixelsToRetain(VERTICAL, 1080, 2424);
        assertEquals(1080 * 1920, maxPixels);
        assertTrue(VideoViewportCap.allows(V1080, maxPixels));
    }

    @Test
    public void landscapeFullscreenCapsNothingBelowTenEightyP() {
        List<Format> ladder = new ArrayList<>(LADDER);
        ladder.add(0, P1440);
        int maxPixels = VideoViewportCap.maxPixelsToRetain(ladder, 2424, 1080);
        assertTrue(VideoViewportCap.allows(P1080, maxPixels));
        assertEquals(1920 * 1080, maxPixels);
    }

    @Test
    public void verticalVideoInAWideWindowIsLetterboxed() {
        // 1080x1920 shown in a 1080x608 window is 342x608 on screen -> 360x640 covers it.
        assertEquals(360 * 640, VideoViewportCap.maxPixelsToRetain(VERTICAL, BOX_W, BOX_H));
    }

    // ---------------------------------------------------------------------------------
    // Inside the real AdaptiveTrackSelection
    // ---------------------------------------------------------------------------------

    @Test
    public void withoutACapTheSelectionIsStockAbr() {
        ExoTrackSelection selection = adaptive(LADDER);
        select(selection, 0);
        assertSame(P1080, selection.getSelectedFormat());
    }

    @Test
    public void pipCapPicksTheWindowRungOnAFreshSelection() {
        cap.set("pip", 604, 340);
        ExoTrackSelection selection = adaptive(LADDER);
        select(selection, 0);
        assertSame(P360, selection.getSelectedFormat());
    }

    @Test
    public void enteringPipWithAFullBufferKeepsFullResolutionUntilMedia3WouldDownSwitch() {
        ExoTrackSelection selection = adaptive(LADDER);
        select(selection, 0);
        assertSame(P1080, selection.getSelectedFormat());

        cap.set("pip", 604, 340);
        // media3 refuses a down-switch while the buffer is above the preset's down window: the
        // PiP window keeps receiving full-resolution chunks - no quality drop on entry.
        select(selection, DOWN_WINDOW_US);
        assertSame(P1080, selection.getSelectedFormat());
        // Once the buffer is below the window, NEW chunks come at the window's rung.
        select(selection, DOWN_WINDOW_US - 1);
        assertSame(P360, selection.getSelectedFormat());
    }

    @Test
    public void leavingPipIsAnOrdinaryUpSwitch() {
        cap.set("pip", 604, 340);
        ExoTrackSelection selection = adaptive(LADDER);
        select(selection, 0);
        assertSame(P360, selection.getSelectedFormat());

        cap.clear("pip-exit");
        select(selection, 4_999_999); // media3's 5 s up-switch guard still applies
        assertSame(P360, selection.getSelectedFormat());
        select(selection, 5_000_000);
        assertSame(P1080, selection.getSelectedFormat());
    }

    @Test
    public void explicitQualityPickIsAFixedSelectionTheCapNeverSees() {
        cap.set("pip", 604, 340);
        TrackGroup group = new TrackGroup(LADDER.toArray(new Format[0]));
        ExoTrackSelection selection = factory().createTrackSelections(
                new ExoTrackSelection.Definition[] {new ExoTrackSelection.Definition(group, 0)},
                new FakeMeter(), new MediaPeriodId(new Object()), Timeline.EMPTY)[0];
        assertTrue(selection instanceof FixedTrackSelection);
        assertSame(P1080, selection.getSelectedFormat());
    }

    @Test
    public void audioSelectionIgnoresTheCap() {
        cap.set("pip", 604, 340);
        Format high = new Format.Builder().setId("251").setSampleMimeType(MimeTypes.AUDIO_OPUS)
                .setAverageBitrate(160_000).build();
        Format low = new Format.Builder().setId("250").setSampleMimeType(MimeTypes.AUDIO_OPUS)
                .setAverageBitrate(70_000).build();
        ExoTrackSelection selection = adaptive(Arrays.asList(high, low));
        select(selection, 0);
        assertSame(high, selection.getSelectedFormat());
    }

    @Test
    public void factoryKeepsEveryStockAbrKnob() throws Exception {
        TrackGroup group = new TrackGroup(LADDER.toArray(new Format[0]));
        ExoTrackSelection.Definition[] definitions = {
                new ExoTrackSelection.Definition(group, 0, 1, 2, 3, 4, 5)};
        ExoTrackSelection stock = new AdaptiveTrackSelection.Factory(5_000, 62_500, 25_000, 0.7f)
                .createTrackSelections(definitions, new FakeMeter(), new MediaPeriodId(new Object()),
                        Timeline.EMPTY)[0];
        ExoTrackSelection capped = new ViewportCappedTrackSelection.Factory(5_000, 62_500, 25_000,
                0.7f, cap).createTrackSelections(definitions, new FakeMeter(),
                new MediaPeriodId(new Object()), Timeline.EMPTY)[0];
        assertTrue(capped instanceof ViewportCappedTrackSelection);
        for (String name : new String[] {"minDurationForQualityIncreaseUs",
                "maxDurationForQualityDecreaseUs", "minDurationToRetainAfterDiscardUs",
                "maxWidthToDiscard", "maxHeightToDiscard", "bandwidthFraction",
                "bufferedFractionToLiveEdgeForQualityIncrease", "clock"}) {
            Field field = AdaptiveTrackSelection.class.getDeclaredField(name);
            field.setAccessible(true);
            assertEquals(name, field.get(stock), field.get(capped));
        }
    }

    @Test
    public void initializerSelectorUsesTheSharedCapAndLeavesTheViewportParametersAlone()
            throws Exception {
        Media3PlayerInitializer initializer =
                new Media3PlayerInitializer(org.robolectric.RuntimeEnvironment.getApplication());
        DefaultTrackSelector selector = initializer.createTrackSelector();
        Field factoryField = DefaultTrackSelector.class.getDeclaredField("trackSelectionFactory");
        factoryField.setAccessible(true);
        Object factory = factoryField.get(selector);
        assertTrue(factory instanceof ViewportCappedTrackSelection.Factory);
        Field capField = ViewportCappedTrackSelection.Factory.class.getDeclaredField("mCap");
        capField.setAccessible(true);
        assertSame(VideoViewportCap.shared(), capField.get(factory));
        // The cap never becomes a selector parameter (that would recreate the video stream):
        // the viewport stays media3's physical-display default.
        DefaultTrackSelector.Parameters parameters = selector.getParameters();
        assertTrue(parameters.isViewportSizeLimitedByPhysicalDisplaySize);
        assertEquals(Integer.MAX_VALUE, parameters.viewportWidth);
    }

    // ---- NEWTUBE(quality-start): startup chunks replaced once the quality goes up ----

    @Test
    public void startupChunksStayWhileTheSelectionHasNotSwitched() {
        List<MediaChunk> queue = startupQueue(P480, 0, 5_000_000L, 10_000_000L);
        assertEquals(queue.size(), ViewportCappedTrackSelection.startupDiscardIndex(
                500_000L, queue, C.SELECTION_REASON_INITIAL, P1080, 1f));
    }

    @Test
    public void startupChunksBeyondTheRetainWindowAreReplacedAfterAnUpSwitch() {
        // 480p chunks at 0, 5 and 10 s, then the first 1080p one at 15 s; playing at 0.5 s.
        List<MediaChunk> queue = startupQueue(P480, 0, 5_000_000L, 10_000_000L);
        queue.add(chunk(P1080, C.SELECTION_REASON_ADAPTIVE, 15_000_000L));
        // The chunk at 5 s starts 4.5 s ahead: it plays out; everything from 10 s is fetched again.
        assertEquals(2, ViewportCappedTrackSelection.startupDiscardIndex(
                500_000L, queue, C.SELECTION_REASON_ADAPTIVE, P1080, 1f));
    }

    @Test
    public void verticalStartupChunksAreReplacedToo() {
        // Stock media3 never drops these: 480x854 is taller than its 719 px discard bound.
        List<MediaChunk> queue = startupQueue(V480, 0, 5_000_000L, 10_000_000L);
        assertEquals(2, ViewportCappedTrackSelection.startupDiscardIndex(
                500_000L, queue, C.SELECTION_REASON_ADAPTIVE, V1080, 1f));
    }

    @Test
    public void chunksFromALaterDownSwitchAreNeverDropped() {
        // A dip mid-video: those low chunks were chosen ADAPTIVE, and a long buffer is kept.
        List<MediaChunk> queue = new ArrayList<>();
        for (long start = 100_000_000L; start < 160_000_000L; start += 5_000_000L) {
            queue.add(chunk(P480, C.SELECTION_REASON_ADAPTIVE, start));
        }
        assertEquals(queue.size(), ViewportCappedTrackSelection.startupDiscardIndex(
                100_500_000L, queue, C.SELECTION_REASON_ADAPTIVE, P1080, 1f));
    }

    @Test
    public void aSelectionThatHasNotDecidedYetDiscardsNothing() {
        // Re-created by an audio/captions change: UNKNOWN, its top rung nominally "selected".
        List<MediaChunk> queue = startupQueue(P480, 0, 5_000_000L, 10_000_000L);
        assertEquals(queue.size(), ViewportCappedTrackSelection.startupDiscardIndex(
                500_000L, queue, C.SELECTION_REASON_UNKNOWN, P1080, 1f));
    }

    @Test
    public void evaluateQueueSizeReplacesStartupChunksAfterARealUpSwitch() {
        MutableMeter meter = new MutableMeter(500_000L);
        ExoTrackSelection selection = adaptive(LADDER, meter);
        select(selection, 0, Collections.emptyList()); // first decision: INITIAL at the seed's rung
        assertEquals(C.SELECTION_REASON_INITIAL, selection.getSelectionReason());
        Format startup = selection.getSelectedFormat();
        List<MediaChunk> queue = startupQueue(startup, 0, 5_000_000L, 10_000_000L, 15_000_000L);

        meter.bitrate = 100_000_000L; // the first real transfers
        select(selection, 20_000_000L, queue);
        assertEquals(C.SELECTION_REASON_ADAPTIVE, selection.getSelectionReason());
        assertEquals(P1080, selection.getSelectedFormat());
        assertEquals(2, selection.evaluateQueueSize(500_000L, queue));
    }

    @Test
    public void evaluateQueueSizeKeepsStartupChunksWhenTheUpSwitchNoLongerFits() {
        MutableMeter meter = new MutableMeter(500_000L);
        ExoTrackSelection selection = adaptive(LADDER, meter);
        select(selection, 0, Collections.emptyList());
        List<MediaChunk> queue = startupQueue(selection.getSelectedFormat(),
                0, 5_000_000L, 10_000_000L, 15_000_000L);
        meter.bitrate = 100_000_000L;
        select(selection, 20_000_000L, queue);
        assertEquals(P1080, selection.getSelectedFormat());

        meter.bitrate = 500_000L; // collapsed before the next chunk request refreshed the choice
        assertEquals(queue.size(), selection.evaluateQueueSize(500_000L, queue));
    }

    @Test
    public void startupChunksAtTheSelectedRungOrAboveStay() {
        List<MediaChunk> queue = startupQueue(P720, 0, 5_000_000L, 10_000_000L);
        assertEquals(queue.size(), ViewportCappedTrackSelection.startupDiscardIndex(
                500_000L, queue, C.SELECTION_REASON_ADAPTIVE, P720, 1f));
        assertEquals(queue.size(), ViewportCappedTrackSelection.startupDiscardIndex(
                500_000L, queue, C.SELECTION_REASON_ADAPTIVE, P480, 1f));
    }

    @Test
    public void theRetainWindowIsPlayoutTimeAtHigherSpeeds() {
        // At 2x, 5 s of playout is 10 s of media: the chunk at 10 s (9.5 s ahead) stays.
        List<MediaChunk> queue = startupQueue(P480, 0, 5_000_000L, 10_000_000L, 15_000_000L);
        assertEquals(3, ViewportCappedTrackSelection.startupDiscardIndex(
                500_000L, queue, C.SELECTION_REASON_ADAPTIVE, P1080, 2f));
    }

    private static List<MediaChunk> startupQueue(Format format, long... startsUs) {
        List<MediaChunk> queue = new ArrayList<>();
        for (long start : startsUs) {
            queue.add(chunk(format, C.SELECTION_REASON_INITIAL, start));
        }
        return queue;
    }

    private static MediaChunk chunk(Format format, int reason, long startUs) {
        return new SingleSampleMediaChunk(new ByteArrayDataSource(new byte[1]),
                new DataSpec(Uri.parse("https://media.invalid/" + startUs)), format,
                reason, null, startUs, startUs + 5_000_000L, startUs / 5_000_000L,
                C.TRACK_TYPE_VIDEO, format);
    }

    // ---------------------------------------------------------------------------------

    private ViewportCappedTrackSelection.Factory factory() {
        return new ViewportCappedTrackSelection.Factory(5_000, (int) (DOWN_WINDOW_US / 1000), 25_000,
                0.7f, cap);
    }

    private ExoTrackSelection adaptive(List<Format> formats) {
        TrackGroup group = new TrackGroup(formats.toArray(new Format[0]));
        int[] tracks = new int[formats.size()];
        for (int i = 0; i < tracks.length; i++) tracks[i] = i;
        return factory().createTrackSelections(
                new ExoTrackSelection.Definition[] {new ExoTrackSelection.Definition(group, tracks)},
                new FakeMeter(), new MediaPeriodId(new Object()), Timeline.EMPTY)[0];
    }

    private ExoTrackSelection adaptive(List<Format> formats, BandwidthMeter meter) {
        TrackGroup group = new TrackGroup(formats.toArray(new Format[0]));
        int[] tracks = new int[formats.size()];
        for (int i = 0; i < tracks.length; i++) tracks[i] = i;
        return factory().createTrackSelections(
                new ExoTrackSelection.Definition[] {new ExoTrackSelection.Definition(group, tracks)},
                meter, new MediaPeriodId(new Object()), Timeline.EMPTY)[0];
    }

    private static void select(ExoTrackSelection selection, long bufferedUs, List<MediaChunk> queue) {
        MediaChunkIterator[] iterators = new MediaChunkIterator[selection.length()];
        Arrays.fill(iterators, MediaChunkIterator.EMPTY);
        selection.updateSelectedTrack(0, bufferedUs, C.TIME_UNSET, queue, iterators);
    }

    private static void select(ExoTrackSelection selection, long bufferedUs) {
        MediaChunkIterator[] iterators = new MediaChunkIterator[selection.length()];
        Arrays.fill(iterators, MediaChunkIterator.EMPTY);
        selection.updateSelectedTrack(0, bufferedUs, C.TIME_UNSET, Collections.emptyList(), iterators);
    }

    /** {@code count} consecutive 5 s chunks of {@code format} starting at position 0. */
    private static List<MediaChunk> chunks(Format format, int count) {
        List<MediaChunk> queue = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long startUs = i * 5_000_000L;
            queue.add(new SingleSampleMediaChunk(new ByteArrayDataSource(new byte[1]),
                    new DataSpec(Uri.parse("https://media.invalid/" + i)), format,
                    C.SELECTION_REASON_ADAPTIVE, null, startUs, startUs + 5_000_000L, i,
                    C.TRACK_TYPE_VIDEO, format));
        }
        return queue;
    }

    private static Format video(String id, int width, int height, int bitrate) {
        return new Format.Builder().setId(id).setSampleMimeType(MimeTypes.VIDEO_VP9)
                .setWidth(width).setHeight(height).setAverageBitrate(bitrate).build();
    }

    /** A link whose estimate the test moves. */
    private static final class MutableMeter implements BandwidthMeter {
        long bitrate;

        MutableMeter(long bitrate) {
            this.bitrate = bitrate;
        }

        @Override public long getBitrateEstimate() { return bitrate; }
        @Override public TransferListener getTransferListener() { return null; }
        @Override public void addEventListener(Handler handler, EventListener listener) {}
        @Override public void removeEventListener(EventListener listener) {}
    }

    /** Plenty of bandwidth: every choice below is the cap's or media3's rule, never the link's. */
    private static final class FakeMeter implements BandwidthMeter {
        @Override public long getBitrateEstimate() { return 100_000_000L; }
        @Override public TransferListener getTransferListener() { return null; }
        @Override public void addEventListener(Handler handler, EventListener listener) {}
        @Override public void removeEventListener(EventListener listener) {}
    }
}
