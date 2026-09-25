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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The PiP / mini-card rung cap and the data-saving inline-box cap: media3's viewport retention rule,
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
    /** Pixel 9 portrait: the measured inline surface (`pip surface-available size=1080x608`). */
    private static final int BOX_W = 1080;
    private static final int BOX_H = 608;

    /** The monitor's gate: metered AND Data Saver on (see sharedCapFollowsTheDataSavingGate). */
    private final AtomicBoolean saving = new AtomicBoolean(false);
    private final VideoViewportCap cap = new VideoViewportCap(saving::get);

    @After
    public void tearDown() {
        VideoViewportCap.shared().clear("test");
        VideoViewportCap.shared().clearInline("test");
        MeteredNetworkMonitor.setMeteredForTest(null);
        MeteredNetworkMonitor.setDataSaverForTest(null);
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
        // 180x102 dp at the app's TV-derived density (2.525 on a Pixel 9).
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
    public void windowPixelsUseTheSystemDensityNotTheAppsSwappedMetrics() {
        Configuration pip = new Configuration();
        pip.screenWidthDp = 230;
        pip.screenHeightDp = 130;
        pip.densityDpi = 420; // Pixel 9: 2.625
        int[] pixels = VideoViewportCap.windowPixels(pip);
        assertEquals(604, pixels[0]);
        assertEquals(341, pixels[1]);
        assertNull(VideoViewportCap.windowPixels(new Configuration()));
        assertNull(VideoViewportCap.windowPixels(null));
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
    // Inline (portrait) box: rung choice
    // ---------------------------------------------------------------------------------

    @Test
    public void inlinePortraitBoxKeepsSevenTwentyP() {
        // 1080p is 1920 wide for a 1080 px box; 720p (1280x720) already covers 1080x608.
        assertEquals(1280 * 720, VideoViewportCap.maxPixelsToRetain(LADDER, BOX_W, BOX_H));
        assertEquals(1280 * 720, VideoViewportCap.maxPixelsToRetain(LADDER, BOX_W, BOX_H, true));
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
    public void verticalVideoInTheInlineBoxIsLetterboxedUnlessTheModeCrops() {
        // Fit: 1080x1920 shown in a 1080x608 box is 342x608 on screen -> 360x640 covers it.
        assertEquals(360 * 640, VideoViewportCap.maxPixelsToRetain(VERTICAL, BOX_W, BOX_H));
        // Zoom/fill: the video covers the box (1080 wide, cropped) -> no rung below 1080x1920.
        assertEquals(1080 * 1920, VideoViewportCap.maxPixelsToRetain(VERTICAL, BOX_W, BOX_H, true));
    }

    // ---------------------------------------------------------------------------------
    // Inline (portrait) box: saving / not saving / fullscreen / PiP transitions
    // ---------------------------------------------------------------------------------

    @Test
    public void inlineBoxCapsOnlyWhileSavingData() {
        cap.setInline(BOX_W, BOX_H, false);
        assertNull(cap.current()); // Wi-Fi, or cellular without Data Saver: never capped
        saving.set(true);
        VideoViewportCap.Viewport inline = cap.current();
        assertEquals(VideoViewportCap.MODE_INLINE, inline.mode);
        assertEquals(BOX_W, inline.width);
        assertEquals(BOX_H, inline.height);
        cap.onSavingChanged(true, "data-saver"); // logging only
        saving.set(false);
        assertNull(cap.current());
        cap.onSavingChanged(false, "data-saver");
    }

    @Test
    public void setInlineIsIdempotentAndFullscreenRemovesIt() {
        saving.set(true);
        cap.setInline(BOX_W, BOX_H, false);
        VideoViewportCap.Viewport first = cap.current();
        cap.setInline(BOX_W, BOX_H, false); // every layout pass repeats the box
        assertSame(first, cap.current());
        cap.setInline(BOX_W, BOX_H, true); // resize mode switched to zoom
        assertNotSame(first, cap.current());
        assertTrue(cap.current().cover);
        cap.setInline(0, BOX_H, false); // unusable size is ignored, the box stays
        assertEquals(BOX_W, cap.current().width);
        cap.clearInline("fullscreen");
        assertNull(cap.current());
    }

    @Test
    public void smallWindowWinsOverTheInlineBoxAndHandsBackOnExit() {
        saving.set(true);
        cap.setInline(BOX_W, BOX_H, false);
        cap.set("pip", 604, 340);
        assertEquals("pip", cap.current().mode);
        saving.set(false);
        assertEquals("pip", cap.current().mode); // PiP caps whatever the network / Data Saver
        cap.clear("pip-exit");
        assertNull(cap.current()); // back inline, not saving data: no cap
        saving.set(true);
        assertEquals(VideoViewportCap.MODE_INLINE, cap.current().mode);
    }

    @Test
    public void sharedCapFollowsTheDataSavingGate() {
        VideoViewportCap shared = VideoViewportCap.shared();
        shared.setInline(BOX_W, BOX_H, false);
        MeteredNetworkMonitor.setMeteredForTest(true);
        MeteredNetworkMonitor.setDataSaverForTest(false);
        assertNull(shared.current()); // unlimited LTE reports metered: quality wins
        MeteredNetworkMonitor.setDataSaverForTest(true);
        assertEquals(VideoViewportCap.MODE_INLINE, shared.current().mode);
        MeteredNetworkMonitor.setMeteredForTest(false); // Wi-Fi with Data Saver still on
        assertNull(shared.current());
    }

    @Test
    public void savingInlineCapPicksSevenTwentyPOnAFreshSelection() {
        saving.set(true);
        cap.setInline(BOX_W, BOX_H, false);
        ExoTrackSelection selection = adaptive(LADDER);
        select(selection, 0);
        assertSame(P720, selection.getSelectedFormat());
    }

    @Test
    public void notSavingInlineBoxIsStockAbr() {
        cap.setInline(BOX_W, BOX_H, false);
        ExoTrackSelection selection = adaptive(LADDER);
        select(selection, 0);
        assertSame(P1080, selection.getSelectedFormat());
    }

    @Test
    public void stoppingSavingOrFullscreenLiftsTheInlineCapForNewChunks() {
        saving.set(true);
        cap.setInline(BOX_W, BOX_H, false);
        ExoTrackSelection selection = adaptive(LADDER);
        select(selection, 0);
        assertSame(P720, selection.getSelectedFormat());

        saving.set(false); // Wi-Fi or Data Saver off: an ordinary up-switch, 5 s guard included
        select(selection, 4_999_999);
        assertSame(P720, selection.getSelectedFormat());
        select(selection, 5_000_000);
        assertSame(P1080, selection.getSelectedFormat());

        saving.set(true); // Data Saver back on: the next chunk is capped again
        select(selection, 30_000_000);
        assertSame(P720, selection.getSelectedFormat());

        cap.clearInline("fullscreen");
        select(selection, 30_000_000);
        assertSame(P1080, selection.getSelectedFormat());
    }

    @Test
    public void inlineCapDownSwitchLetsBufferedFullResolutionChunksPlayOut() {
        ExoTrackSelection selection = adaptive(LADDER);
        select(selection, 0);
        assertSame(P1080, selection.getSelectedFormat());

        saving.set(true); // Data Saver on mid-playback with ~30 s of 1080p already buffered
        cap.setInline(BOX_W, BOX_H, false);
        select(selection, 30_000_000);
        assertSame(P720, selection.getSelectedFormat()); // NEW chunks at the box's rung
        // ...and not one buffered 1080p chunk is dropped: media3 only discards chunks BELOW ideal.
        List<MediaChunk> queue = chunks(P1080, 12);
        assertEquals(queue.size(), selection.evaluateQueueSize(0, queue));
    }

    @Test
    public void liftingTheInlineCapKeepsBuffered720pChunks() {
        saving.set(true);
        cap.setInline(BOX_W, BOX_H, false);
        ExoTrackSelection selection = adaptive(LADDER);
        select(selection, 0);
        cap.clearInline("fullscreen");
        // media3 refetches chunks > 25 s ahead only if they are at most 1279x719: 720p plays out.
        List<MediaChunk> queue = chunks(P720, 12);
        assertEquals(queue.size(), selection.evaluateQueueSize(0, queue));
    }

    @Test
    public void explicitQualityPickIsNeverCappedInline() {
        saving.set(true);
        cap.setInline(BOX_W, BOX_H, false);
        TrackGroup group = new TrackGroup(LADDER.toArray(new Format[0]));
        ExoTrackSelection selection = factory().createTrackSelections(
                new ExoTrackSelection.Definition[] {new ExoTrackSelection.Definition(group, 0)},
                new FakeMeter(), new MediaPeriodId(new Object()), Timeline.EMPTY)[0];
        assertTrue(selection instanceof FixedTrackSelection);
        assertSame(P1080, selection.getSelectedFormat());
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

    /** Plenty of bandwidth: every choice below is the cap's or media3's rule, never the link's. */
    private static final class FakeMeter implements BandwidthMeter {
        @Override public long getBitrateEstimate() { return 100_000_000L; }
        @Override public TransferListener getTransferListener() { return null; }
        @Override public void addEventListener(Handler handler, EventListener listener) {}
        @Override public void removeEventListener(EventListener listener) {}
    }
}
