package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.res.Configuration;
import android.os.Handler;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The PiP / mini-card rung cap: media3's viewport retention rule, applied inside adaptive
 * selection only (so the track set - and therefore the sample stream and its buffer - never
 * changes), with explicit picks, audio and media3's own switching rules untouched.
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
    private static final long DOWN_WINDOW_US = 62_500_000L; // HIGH preset midpoint

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
