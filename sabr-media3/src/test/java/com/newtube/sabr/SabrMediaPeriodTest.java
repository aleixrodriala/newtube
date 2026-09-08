package com.newtube.sabr;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import com.newtube.sabr.proto.videostreaming.VideoPlaybackAbrRequest;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class SabrMediaPeriodTest {
    private SabrMediaSource.VodPeriod period;
    private SampleStream[] streams;
    private long position;
    private boolean autoContinue = true;

    @After public void release() {
        if (period != null && !period.released) period.release();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void fragmentedMp4AudioAndVideoReachActualSampleQueuesAndEos() throws Exception {
        SabrFixtures fixture = fixture(false);
        start(fixture);
        await(() -> period.fatal != null || period.getNextLoadPositionUs() == C.TIME_END_OF_SOURCE);
        assertNull(failure(), period.fatal);
        assertTrue(fixture.mediaRequests() >= 12);
        assertSamples(0, 250);
        assertSamples(1, 500);
        assertEquals(C.TIME_END_OF_SOURCE, period.getBufferedPositionUs());
    }

    @Test public void fragmentedWebmAudioAndVideoUseStockExtractors() throws Exception {
        SabrFixtures fixture = fixture(true);
        start(fixture);
        await(() -> period.fatal != null || period.getNextLoadPositionUs() == C.TIME_END_OF_SOURCE);
        assertNull(failure(), period.fatal);
        assertTrue(fixture.mediaRequests() >= 8);
        assertSamples(0, 250);
        assertSamples(1, 500);
    }

    @Test public void backwardsSeekClearsRangesAndRestartsTheActualContainer() throws Exception {
        SabrFixtures fixture = fixture(false);
        start(fixture);
        await(() -> period.fatal != null || period.getNextLoadPositionUs() == C.TIME_END_OF_SOURCE);
        assertNull(failure(), period.fatal);
        int before = fixture.openCount.get();
        position = 4_000_000;
        period.seekToUs(position);
        period.continueLoading(loading());
        await(() -> period.fatal != null || period.getNextLoadPositionUs() == C.TIME_END_OF_SOURCE);
        assertNull(failure(), period.fatal);
        assertTrue(fixture.openCount.get() > before);
        VideoPlaybackAbrRequest first = VideoPlaybackAbrRequest.parseFrom(fixture.requests.get(before).httpBody);
        assertEquals(4000, first.getClientAbrState().getPlayerTimeMs());
        assertEquals(0, first.getBufferedRangesCount());
        assertEquals(0, first.getSelectedFormatIdsCount());
        assertSamples(0, 160);
    }

    @Test public void seekWhileLoadingCancelsTheOldGenerationBeforePublishingNewSamples() throws Exception {
        SabrFixtures fixture = fixture(false);
        fixture.blockReads = true;
        start(fixture);
        await(() -> fixture.openCount.get() == 2);
        position = 8_000_000;
        period.seekToUs(position);
        fixture.blockReads = false;
        period.continueLoading(loading());
        await(() -> period.fatal != null || period.getNextLoadPositionUs() == C.TIME_END_OF_SOURCE);
        assertNull(failure(), period.fatal);
        assertTrue(fixture.closedCount.get() >= 2);
        assertSamples(0, 80);
    }

    @Test public void releaseClosesBlockedRequestsAndDoesNotStartAnotherLoad() throws Exception {
        SabrFixtures fixture = fixture(false);
        fixture.blockReads = true;
        start(fixture);
        await(() -> fixture.openCount.get() == 2);
        period.release();
        await(() -> fixture.closedCount.get() == 2);
        pump(100);
        assertEquals(2, fixture.openCount.get());
        assertEquals(0, fixture.mediaRequests());
    }

    @Test public void httpDenialIsTerminalAndCancelsTheOtherTrackWithoutRetry() throws Exception {
        SabrFixtures fixture = fixture(false);
        fixture.status = 403;
        start(fixture);
        await(() -> period.fatal != null);
        assertEquals(403, period.fatal.httpStatus);
        int count = fixture.openCount.get();
        pump(250);
        assertEquals(count, fixture.openCount.get());
        assertTrue(count <= 2);
        assertFalse(period.continueLoading(loading()));
        assertEquals(0, fixture.mediaRequests());
        assertThrows(SabrException.class, () -> streams[0].maybeThrowError());
    }

    @Test public void truncatedContainerIsAnErrorNotEos() throws Exception {
        SabrFixtures fixture = fixture(true);
        fixture.truncate = true;
        start(fixture);
        await(() -> period.fatal != null);
        assertNotEquals(C.TIME_END_OF_SOURCE, period.getBufferedPositionUs());
        assertFalse(streams[0].isReady());
    }

    @Test public void identicalPublicFormatsRetainTheirDistinctFullProtocolIdentities() throws Exception {
        SabrFixtures fixture = fixture(false);
        SabrStreamInfo.Track first = fixture.info.tracks.get(0);
        SabrStreamInfo.Track second = new SabrStreamInfo.Track(first.format, 123457, "variant=2", null, false);
        SabrStreamInfo info = new SabrStreamInfo(SabrFixtures.VIDEO, fixture.info.durationUs,
                SabrFixtures.ENDPOINT, "AA", "TVHTML5", "fixture", null, null, List.of(first, second));
        period = new SabrMediaSource.VodPeriod(info, fixture,
                new DefaultBandwidthMeter.Builder(RuntimeEnvironment.getApplication()).build(),
                new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                new MediaSourceEventListener.EventDispatcher(), PlayerId.UNSET);
        assertEquals(1, period.groups.length);
        assertSame(first.format, second.format);
        assertSame(second, period.selectedTrack(new FixedTrackSelection(period.groups.get(0), 1)));
        assertSame(first, period.selectedTrack(new FixedTrackSelection(period.groups.get(0), 0)));
    }

    /**
     * The video-format buffered ranges of a request. A video request also carries a full-duration
     * claim for its companion audio - the only way to ask for video without the server adding an
     * audio track of its own - and that claim is not this stream's history.
     */
    private static List<com.newtube.sabr.proto.videostreaming.BufferedRange> videoRanges(
            VideoPlaybackAbrRequest request) {
        List<com.newtube.sabr.proto.videostreaming.BufferedRange> result = new java.util.ArrayList<>();
        for (com.newtube.sabr.proto.videostreaming.BufferedRange range : request.getBufferedRangesList()) {
            if (range.getFormatId().equals(request.getPreferredVideoFormatIds(0))
                    || range.getFormatId().getItag() == 136 || range.getFormatId().getItag() == 133) {
                result.add(range);
            }
        }
        return result;
    }

    /**
     * The server stops delivering once the client is far enough ahead of the playhead and answers
     * with control parts and no media. Measured against real YouTube on 2026-09-08 at roughly
     * thirty seconds of audio. Treating that as terminal stopped playback one second after the
     * first frame on the Pixel; it must be a wait.
     */
    @Test public void aPacedMediaFreeResponseWaitsInsteadOfFailing() throws Exception {
        SabrFixtures fixture = new SabrFixtures(name -> getClass().getResourceAsStream("/" + name), false);
        fixture.pacingLimitMs = 4000;
        start(fixture);
        await(() -> period.fatal != null || fixture.openCount.get() >= 8);
        assertNull(failure(), period.fatal);
        long paced = period.getBufferedPositionUs();
        assertTrue("stopped before the pacing limit: " + paced, paced >= 4_000_000);
        assertTrue("delivered past the pacing limit: " + paced, paced < 9_000_000);
        // Advancing the playhead lets the same streams continue without a new source.
        position = 8_000_000;
        period.continueLoading(loading());
        await(() -> period.fatal != null || period.getBufferedPositionUs() > paced);
        assertNull(failure(), period.fatal);
        assertTrue(period.getBufferedPositionUs() > paced);
    }

    /** A stream with nothing left to play is starving, not paced, and may not wait forever. */
    @Test public void aStarvingStreamStillFailsAfterRepeatedMediaFreeResponses() throws Exception {
        SabrFixtures fixture = new SabrFixtures(name -> getClass().getResourceAsStream("/" + name), false);
        fixture.mediaFree = true;
        start(fixture);
        await(() -> period.fatal != null);
        assertEquals("SABR stopped: no_media_progress", period.fatal.getMessage());
    }

    @Test public void manualQualityReplacementReinitializesTheSelectedContainerAtThePlayhead() throws Exception {
        SabrFixtures fixture = new SabrFixtures(name -> getClass().getResourceAsStream("/" + name), false, true);
        start(fixture);
        await(() -> period.fatal != null || period.getNextLoadPositionUs() == C.TIME_END_OF_SOURCE);
        assertNull(failure(), period.fatal);
        position = 4_000_000;
        period.seekToUs(position);
        ExoTrackSelection[] selected = {new FixedTrackSelection(period.groups.get(0), 1),
                new FixedTrackSelection(period.groups.get(1), 0)};
        int before = fixture.openCount.get();
        period.selectTracks(selected, new boolean[]{false, true}, streams, new boolean[2], position);
        period.continueLoading(loading());
        await(() -> period.fatal != null || period.getNextLoadPositionUs() == C.TIME_END_OF_SOURCE);
        assertNull(failure(), period.fatal);
        assertSamples(0, 160);
        boolean lowInit = false;
        for (int i = before; i < fixture.requests.size(); i++) {
            VideoPlaybackAbrRequest request = VideoPlaybackAbrRequest.parseFrom(fixture.requests.get(i).httpBody);
            if (request.getPreferredVideoFormatIdsCount() == 0) continue;
            assertEquals(133, request.getPreferredVideoFormatIds(0).getItag());
            if (request.getSelectedFormatIdsCount() == 0) {
                lowInit = true;
                assertEquals(4000, request.getClientAbrState().getPlayerTimeMs());
                // The replacement retains nothing of its own. The one range a video request always
                // carries is the companion-audio suppression claim, never video history.
                assertEquals(List.of(), videoRanges(request));
            }
        }
        assertTrue(lowInit);
    }

    @Test public void adaptiveQualitySwitchPreservesActualOldQualityBufferWithoutInventingPlayhead() throws Exception {
        autoContinue = false;
        SabrFixtures fixture = new SabrFixtures(name -> getClass().getResourceAsStream("/" + name), false, true);
        start(fixture);
        await(() -> !period.isLoading()); // Initialization.
        period.continueLoading(loading());
        await(() -> !period.isLoading()); // One real segment, about two seconds ahead of position=0.
        assertNull(failure(), period.fatal);
        assertTrue(period.streams.get(0).nextLoadUs >= 2_000_000);
        assertTrue(period.streams.get(0).nextLoadUs < fixture.info.durationUs);
        ExoTrackSelection[] selected = {new FixedTrackSelection(period.groups.get(0), 1),
                new FixedTrackSelection(period.groups.get(1), 0)};
        period.selectTracks(selected, new boolean[]{true, true}, streams, new boolean[2], position);
        int before = fixture.openCount.get();
        period.continueLoading(loading());
        await(() -> !period.isLoading());
        assertNull(failure(), period.fatal);
        boolean requested = false;
        for (int i = before; i < fixture.requests.size(); i++) {
            VideoPlaybackAbrRequest request = VideoPlaybackAbrRequest.parseFrom(fixture.requests.get(i).httpBody);
            if (request.getPreferredVideoFormatIdsCount() == 0) continue;
            requested = true;
            assertEquals(133, request.getPreferredVideoFormatIds(0).getItag());
            assertEquals(0, request.getClientAbrState().getPlayerTimeMs());
            assertEquals(0, request.getSelectedFormatIdsCount());
            List<com.newtube.sabr.proto.videostreaming.BufferedRange> retained = videoRanges(request);
            assertEquals(1, retained.size());
            assertEquals(136, retained.get(0).getFormatId().getItag());
            assertEquals(2000, retained.get(0).getDurationMs());
        }
        assertTrue(requested);
        autoContinue = true;
        period.continueLoading(loading());
        await(() -> period.fatal != null || period.getNextLoadPositionUs() == C.TIME_END_OF_SOURCE);
        assertNull(failure(), period.fatal);
        assertSamples(0, 250);
    }

    private SabrFixtures fixture(boolean webm) throws Exception {
        return new SabrFixtures(name -> getClass().getResourceAsStream("/" + name), webm);
    }
    private void start(SabrFixtures fixture) {
        period = new SabrMediaSource.VodPeriod(fixture.info, fixture,
                new DefaultBandwidthMeter.Builder(RuntimeEnvironment.getApplication()).build(),
                new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                new MediaSourceEventListener.EventDispatcher(), PlayerId.UNSET);
        period.prepare(new MediaPeriod.Callback() {
            @Override public void onPrepared(MediaPeriod ignored) {}
            @Override public void onContinueLoadingRequested(MediaPeriod ignored) {
                if (autoContinue) period.continueLoading(loading());
            }
        }, 0);
        ExoTrackSelection[] selections = new ExoTrackSelection[period.groups.length];
        streams = new SampleStream[selections.length];
        for (int i = 0; i < selections.length; i++) selections[i] = new FixedTrackSelection(period.groups.get(i), 0);
        period.selectTracks(selections, new boolean[selections.length], streams, new boolean[selections.length], 0);
        period.continueLoading(loading());
    }
    private LoadingInfo loading() { return new LoadingInfo.Builder().setPlaybackPositionUs(position).setPlaybackSpeed(1).build(); }
    private void assertSamples(int index, int minimum) throws Exception {
        FormatHolder format = new FormatHolder();
        DecoderInputBuffer buffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
        int samples = 0;
        long lastTime = -1;
        for (int i = 0; i < 10000; i++) {
            buffer.clear();
            int result = streams[index].readData(format, buffer, 0);
            if (result == C.RESULT_FORMAT_READ) continue;
            assertEquals(C.RESULT_BUFFER_READ, result);
            if (buffer.isEndOfStream()) break;
            samples++;
            lastTime = Math.max(lastTime, buffer.timeUs);
            assertTrue("old-generation timestamp", buffer.timeUs >= position - 100_000);
        }
        assertTrue("sample count " + samples, samples >= minimum);
        assertTrue("last timestamp " + lastTime, lastTime > 11_000_000);
    }
    private String failure() { return period.fatal == null ? "no failure" : period.fatal.getMessage(); }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) pump(5);
        assertTrue("Timed out: " + failure(), condition.getAsBoolean());
    }
    private static void pump(long ms) throws Exception {
        shadowOf(Looper.getMainLooper()).idle();
        Thread.sleep(ms);
        // idleFor, not idle: a paced wait is posted with a delay, so the shadow clock has to move
        // for it to become due. Real sleeping is still needed because loads run on a real thread.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms));
    }
}
