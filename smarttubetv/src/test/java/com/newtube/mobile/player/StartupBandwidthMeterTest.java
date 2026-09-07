package com.newtube.mobile.player;

import static org.junit.Assert.*;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.Clock;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.Shadows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class StartupBandwidthMeterTest {
    private final FakeMeter delegate = new FakeMeter();
    private final List<Long> saved = new ArrayList<>();

    @Test public void legacyUndatedEstimateDoesNotStartAtTwentySevenMegabits() {
        assertEquals(1_000_000, StartupBandwidthMeter.seedFor(27_000_000, 0, 1_000_000));
    }

    @Test public void freshLowEstimateIsRetainedAndHighEstimateIsBounded() {
        assertEquals(600_000, StartupBandwidthMeter.seedFor(600_000, 999_000, 1_000_000));
        assertEquals(4_000_000, StartupBandwidthMeter.seedFor(27_000_000, 999_000, 1_000_000));
    }

    @Test public void staleFutureAndCorruptSamplesFallBackWithoutTrustingTheirTimestamp() {
        assertEquals(1_000_000, StartupBandwidthMeter.seedFor(27_000_000, 1, 400_000));
        assertEquals(1_000_000, StartupBandwidthMeter.seedFor(27_000_000, 1_000_001, 1_000_000));
        assertEquals(1_000_000, StartupBandwidthMeter.seedFor(-1, 999_000, 1_000_000));
        assertEquals(1_000_000, StartupBandwidthMeter.seedFor(Long.MAX_VALUE, 999_000, 1_000_000));
    }

    @Test public void freshSampleAgeBoundaryIsInclusive() {
        assertEquals(600_000, StartupBandwidthMeter.seedFor(600_000, 1, 300_001));
        assertEquals(1_000_000, StartupBandwidthMeter.seedFor(600_000, 1, 300_002));
    }

    @Test public void startsConservativelyUntilSubstantialBytesHaveArrived() {
        StartupBandwidthMeter meter = meter(true);
        assertEquals(1_000_000, meter.getBitrateEstimate());
        meter.onSample(100, 1000, 27_000_000); // initialization/header-sized data is not proof
        assertEquals(1_000_000, meter.getBitrateEstimate());
        assertTrue(saved.isEmpty());
    }

    @Test public void completedEarlySampleCanRaiseQualityBeforeStockEstimatorWarms() {
        StartupBandwidthMeter meter = meter(true);
        meter.onSample(100, 100_000, delegate.estimate);
        assertEquals(8_000_000, meter.getBitrateEstimate());
        assertTrue("early unfiltered estimate is not persisted", saved.isEmpty());
    }

    @Test public void slowEarlySampleCanLowerQualityToo() {
        StartupBandwidthMeter meter = meter(true);
        meter.onSample(1000, 40_000, delegate.estimate);
        assertEquals(320_000, meter.getBitrateEstimate());
    }

    @Test public void matureByteThresholdReturnsToStockEstimateAndSavesRealMeasurement() {
        StartupBandwidthMeter meter = meter(true);
        delegate.estimate = 6_000_000;
        meter.onSample(300, 512 * 1024, delegate.estimate);
        assertEquals(delegate.estimate, meter.getBitrateEstimate());
        assertEquals(Collections.singletonList(6_000_000L), saved);
        delegate.estimate = 2_000_000;
        assertEquals(2_000_000, meter.getBitrateEstimate());
    }

    @Test public void matureTimeThresholdAlsoReturnsToStockEstimate() {
        StartupBandwidthMeter meter = meter(true);
        meter.onSample(1000, 40_000, delegate.estimate);
        delegate.estimate = 300_000;
        meter.onSample(1000, 40_000, delegate.estimate);
        assertEquals(300_000, meter.getBitrateEstimate());
        assertEquals(Collections.singletonList(300_000L), saved);
    }

    @Test public void tinySlowFailuresNeverPublishASeedAsMeasuredThroughput() {
        StartupBandwidthMeter meter = meter(true);
        meter.onSample(3000, 500, 27_000_000);
        assertTrue(saved.isEmpty());
        assertEquals(1_000_000, meter.getBitrateEstimate());
    }

    @Test public void networkChangeAndLongIdleDiscardCurrentConfidence() {
        StartupBandwidthMeter meter = meter(true);
        meter.onSample(100, 100_000, delegate.estimate);
        meter.onNetworkTypeChanged(C.NETWORK_TYPE_4G);
        assertEquals(1_000_000, meter.getBitrateEstimate());
        meter.onSample(100, 100_000, delegate.estimate);
        SystemClock.sleep(30_001);
        assertEquals(1_000_000, meter.getBitrateEstimate());
    }

    @Test public void stockResetEventAlsoClearsConfidence() {
        StartupBandwidthMeter meter = meter(true);
        meter.onSample(100, 100_000, delegate.estimate);
        meter.onSample(0, 0, 27_000_000);
        assertEquals(1_000_000, meter.getBitrateEstimate());
    }

    @Test public void comparisonOffIsExactDelegateAndDoesNotPublishMeasurements() {
        StartupBandwidthMeter meter = meter(false);
        meter.onSample(3000, 600_000, delegate.estimate);
        assertEquals(delegate.estimate, meter.getBitrateEstimate());
        assertTrue(saved.isEmpty());
    }

    @Test public void realAdaptiveSelectionStartsLowerThenRaisesQualityWithFreshTransfer() {
        StartupBandwidthMeter meter = meter(true);
        ExoTrackSelection selection = selection(meter, new int[]{0, 1, 2});
        select(selection, 0);
        assertEquals(640, selection.getSelectedFormat().width);
        meter.onSample(100, 100_000, delegate.estimate);
        select(selection, 6_000_000);
        assertEquals(1920, selection.getSelectedFormat().width);
    }

    @Test public void actualFixedManualTrackIsNeverDowngradedByStartupEstimate() {
        ExoTrackSelection selection = selection(meter(true), new int[]{2});
        assertTrue(selection instanceof FixedTrackSelection);
        select(selection, 0);
        assertEquals(1920, selection.getSelectedFormat().width);
    }

    @Test public void transportCallbacksAndTimingStillReachTheOriginalMeter() {
        StartupBandwidthMeter meter = meter(true);
        DataSpec spec = new DataSpec.Builder().setUri("https://media.invalid/fixture").build();
        assertSame(meter, meter.getTransferListener());
        meter.onTransferInitializing(null, spec, true);
        meter.onTransferStart(null, spec, true);
        meter.onBytesTransferred(null, spec, true, 100);
        meter.onTransferEnd(null, spec, true);
        assertEquals(4, delegate.callbacks);
        assertEquals(1234, meter.getTimeToFirstByteEstimateUs());
    }

    @Test public void actualStockTransferEventsDriveTheEarlyEstimate() {
        StartupBandwidthMeter meter = realMeter();
        DataSpec spec = spec(0);
        meter.onTransferStart(null, spec, true);
        SystemClock.sleep(100);
        meter.onBytesTransferred(null, spec, true, 100_000);
        meter.onTransferEnd(null, spec, true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(8_000_000, meter.getBitrateEstimate());
    }

    @Test public void cachedAndDeliberatelyPacedReadsDoNotPretendToMeasureTheNetwork() {
        StartupBandwidthMeter meter = realMeter();
        DataSpec cached = spec(0);
        meter.onTransferStart(null, cached, false);
        SystemClock.sleep(100);
        meter.onBytesTransferred(null, cached, false, 1_000_000);
        meter.onTransferEnd(null, cached, false);
        DataSpec paced = spec(DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED);
        meter.onTransferStart(null, paced, true);
        SystemClock.sleep(100);
        meter.onBytesTransferred(null, paced, true, 1_000_000);
        meter.onTransferEnd(null, paced, true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1_000_000, meter.getBitrateEstimate());
        assertTrue(saved.isEmpty());
    }

    @Test public void overlappingAudioAndVideoUseStockAggregateTransferAccounting() {
        StartupBandwidthMeter meter = realMeter();
        DataSpec spec = spec(0);
        meter.onTransferStart(null, spec, true);
        meter.onBytesTransferred(null, spec, true, 50_000);
        SystemClock.sleep(50);
        meter.onTransferStart(null, spec, true);
        meter.onBytesTransferred(null, spec, true, 50_000);
        SystemClock.sleep(50);
        meter.onTransferEnd(null, spec, true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(8_000_000, meter.getBitrateEstimate());
        meter.onBytesTransferred(null, spec, true, 50_000);
        SystemClock.sleep(50);
        meter.onTransferEnd(null, spec, true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(8_000_000, meter.getBitrateEstimate());
    }

    private StartupBandwidthMeter realMeter() {
        // Null context is supported by Media3's legacy-compatible builder and avoids a real
        // ConnectivityManager observer in this offline transfer-accounting test.
        DefaultBandwidthMeter stock = new DefaultBandwidthMeter.Builder(null)
                .setInitialBitrateEstimate(27_000_000).build();
        return new StartupBandwidthMeter(stock, type -> 1_000_000,
                (type, rate) -> saved.add(rate), C.NETWORK_TYPE_UNKNOWN, true, Clock.DEFAULT);
    }

    private static DataSpec spec(int flags) {
        return new DataSpec.Builder().setUri("https://media.invalid/fixture").setFlags(flags).build();
    }

    private StartupBandwidthMeter meter(boolean enabled) {
        return new StartupBandwidthMeter(delegate, type -> 1_000_000,
                (type, rate) -> saved.add(rate), C.NETWORK_TYPE_WIFI, enabled, Clock.DEFAULT);
    }

    private ExoTrackSelection selection(BandwidthMeter meter, int[] tracks) {
        TrackGroup group = new TrackGroup(format("360", 640, 360, 400_000),
                format("720", 1280, 720, 1_000_000), format("1080", 1920, 1080, 2_300_000));
        return new AdaptiveTrackSelection.Factory(5000, 50_000, 25_000, 0.7f)
                .createTrackSelections(new ExoTrackSelection.Definition[]{
                        new ExoTrackSelection.Definition(group, tracks)}, meter,
                        new MediaPeriodId(new Object()), Timeline.EMPTY)[0];
    }

    private static Format format(String id, int width, int height, int rate) {
        return new Format.Builder().setId(id).setSampleMimeType(MimeTypes.VIDEO_VP9)
                .setWidth(width).setHeight(height).setAverageBitrate(rate).build();
    }

    private static void select(ExoTrackSelection selection, long bufferUs) {
        selection.updateSelectedTrack(0, bufferUs, C.TIME_UNSET, Collections.emptyList(),
                new MediaChunkIterator[]{MediaChunkIterator.EMPTY, MediaChunkIterator.EMPTY,
                        MediaChunkIterator.EMPTY});
    }

    private static class FakeMeter implements BandwidthMeter, TransferListener {
        long estimate = 27_000_000;
        int callbacks;
        @Override public long getBitrateEstimate() { return estimate; }
        @Override public long getTimeToFirstByteEstimateUs() { return 1234; }
        @Override public TransferListener getTransferListener() { return this; }
        @Override public void addEventListener(Handler handler, EventListener listener) {}
        @Override public void removeEventListener(EventListener listener) {}
        @Override public void onTransferInitializing(DataSource source, DataSpec spec, boolean network) { callbacks++; }
        @Override public void onTransferStart(DataSource source, DataSpec spec, boolean network) { callbacks++; }
        @Override public void onBytesTransferred(DataSource source, DataSpec spec, boolean network, int bytes) { callbacks++; }
        @Override public void onTransferEnd(DataSource source, DataSpec spec, boolean network) { callbacks++; }
    }
}
