package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One line per phase and open, on the same +X clock as the other NetPath milestones. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class OpenPhaseLogTest {
    private final List<String> lines = new ArrayList<>();
    private long elapsed;
    private OpenPhaseLog log;

    @Before
    public void setUp() {
        log = new OpenPhaseLog(lines::add, () -> "ep=7 video=abc", () -> elapsed);
    }

    @Test
    public void nothingIsLoggedBeforeTheFirstPrepare() {
        loadStarted(C.DATA_TYPE_MEDIA_INITIALIZATION, C.TRACK_TYPE_VIDEO);
        log.onPlaybackStateChanged(time(), Player.STATE_READY);
        assertEquals(Collections.emptyList(), lines);
    }

    @Test
    public void dashOpenLogsEachPhaseOnce() {
        log.onPrepare();
        elapsed = 310;
        loadStarted(C.DATA_TYPE_MEDIA_INITIALIZATION, C.TRACK_TYPE_VIDEO);
        loadStarted(C.DATA_TYPE_MEDIA_INITIALIZATION, C.TRACK_TYPE_AUDIO);
        elapsed = 360;
        loadCompleted(C.DATA_TYPE_MEDIA_INITIALIZATION, C.TRACK_TYPE_AUDIO, 40, 13_282);
        loadCompleted(C.DATA_TYPE_MEDIA_INITIALIZATION, C.TRACK_TYPE_VIDEO, 50, 23_924);
        elapsed = 362;
        loadStarted(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_AUDIO);
        loadStarted(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO);
        loadStarted(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO); // the second chunk is not a milestone
        elapsed = 380;
        log.onVideoDecoderInitialized(time(), "c2.google.vp9.decoder", 0, 31);
        log.onAudioDecoderInitialized(time(), "c2.android.opus.decoder", 0, 4);
        log.onVideoDecoderInitialized(time(), "c2.google.vp9.decoder", 0, 29); // ABR re-init
        elapsed = 470;
        log.onPlaybackStateChanged(time(), Player.STATE_READY);
        log.onPlaybackStateChanged(time(), Player.STATE_BUFFERING);
        log.onPlaybackStateChanged(time(), Player.STATE_READY); // a rebuffer's READY
        elapsed = 900;
        loadCompleted(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 538, 1_312_116);
        loadCompleted(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 600, 1_331_182);

        assertEquals(java.util.Arrays.asList(
                "ep=7 video=abc media-load init +310 track=video id=248 h=1080",
                "ep=7 video=abc media-init done +360 track=audio loadMs=40 bytes=13282",
                "ep=7 video=abc media-init done +360 track=video loadMs=50 bytes=23924",
                "ep=7 video=abc media-load chunk +362 track=audio id=251 start=0",
                "ep=7 video=abc media-load chunk +362 track=video id=248 h=1080 start=0",
                "ep=7 video=abc decoder init +380 type=video name=c2.google.vp9.decoder initMs=31",
                "ep=7 video=abc decoder init +380 type=audio name=c2.android.opus.decoder initMs=4",
                "ep=7 video=abc ready +470 bufferedMs=0",
                "ep=7 video=abc media-chunk done +900 track=video loadMs=538 bytes=1312116"
                        + " start=0 src=cache"), lines);
    }

    @Test
    public void everyPrepareStartsANewSetOfFirsts() {
        log.onPrepare();
        log.onPlaybackStateChanged(time(), Player.STATE_READY);
        log.onPrepare();
        elapsed = 12;
        log.onPlaybackStateChanged(time(), Player.STATE_READY);
        assertEquals(2, lines.size());
        assertEquals("ep=7 video=abc ready +12 bufferedMs=0", lines.get(1));
    }

    @Test
    public void keptCodecReportsReuseInsteadOfInit() {
        log.onPrepare();
        elapsed = 400;
        Format oldFormat = videoFormat("248", 1080);
        Format newFormat = videoFormat("247", 720);
        log.onVideoInputFormatChanged(time(), newFormat, new DecoderReuseEvaluation(
                "c2.google.vp9.decoder", oldFormat, newFormat,
                DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_RECONFIGURATION, 0));
        log.onAudioInputFormatChanged(time(), audioFormat(), new DecoderReuseEvaluation(
                "c2.android.opus.decoder", audioFormat(), audioFormat(),
                DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION, 0));
        // A later init for the same open (an ABR switch that could not reuse) is not a milestone.
        log.onVideoDecoderInitialized(time(), "c2.google.vp9.decoder", 0, 30);

        assertEquals(java.util.Arrays.asList(
                "ep=7 video=abc decoder reuse +400 type=video name=c2.google.vp9.decoder"
                        + " result=reconfigure id=247 h=720",
                "ep=7 video=abc decoder reuse +400 type=audio name=c2.android.opus.decoder"
                        + " result=as-is"), lines);
    }

    @Test
    public void refusedReuseDefersToTheInitLine() {
        log.onPrepare();
        Format vp9 = videoFormat("248", 1080);
        Format av1 = new Format.Builder().setId("399").setSampleMimeType(MimeTypes.VIDEO_AV1)
                .setHeight(1080).build();
        log.onVideoInputFormatChanged(time(), av1, new DecoderReuseEvaluation(
                "c2.google.vp9.decoder", vp9, av1, DecoderReuseEvaluation.REUSE_RESULT_NO,
                DecoderReuseEvaluation.DISCARD_REASON_MIME_TYPE_CHANGED));
        elapsed = 5;
        log.onVideoDecoderInitialized(time(), "c2.google.av1.decoder", 0, 44);
        assertEquals(Collections.singletonList(
                "ep=7 video=abc decoder init +5 type=video name=c2.google.av1.decoder initMs=44"), lines);
    }

    @Test
    public void firstCodecOfTheSessionHasNoReuseEvaluation() {
        log.onPrepare();
        log.onVideoInputFormatChanged(time(), videoFormat("248", 1080), null);
        assertTrue(lines.isEmpty());
    }

    @Test
    public void sourceWithoutInitSegmentsLogsOneFirstChunkLine() {
        log.onPrepare();
        loadStarted(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO);
        loadStarted(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO);
        assertEquals(Collections.singletonList(
                "ep=7 video=abc media-load chunk +0 track=video id=248 h=1080 start=0"), lines);
    }

    @Test
    public void audioChunksAfterAResumeSnapAreTraced() {
        log.onPrepare();
        elapsed = 75;
        loadStarted(C.DATA_TYPE_MEDIA_INITIALIZATION, C.TRACK_TYPE_VIDEO);
        lines.clear();
        // The audio request for the exact target, canceled by the snap, then the snapped segment.
        log.onLoadStarted(time(), loadInfo(0, 0, false), chunk(C.TRACK_TYPE_AUDIO, 60_000), 0);
        elapsed = 80;
        log.onLoadCanceled(time(), loadInfo(4, 0, false), chunk(C.TRACK_TYPE_AUDIO, 60_000));
        log.onLoadStarted(time(), loadInfo(0, 0, false), chunk(C.TRACK_TYPE_AUDIO, 50_000), 0);
        log.onLoadStarted(time(), loadInfo(0, 0, false), chunk(C.TRACK_TYPE_AUDIO, 60_000), 0); // 3rd: silent
        elapsed = 900;
        log.onLoadCompleted(time(), loadInfo(690, 171_000, true), chunk(C.TRACK_TYPE_AUDIO, 50_000));
        assertEquals(java.util.Arrays.asList(
                "ep=7 video=abc media-load chunk +75 track=audio id=251 start=60000",
                "ep=7 video=abc media-load canceled +80 track=audio start=60000 loadMs=4 bytes=0",
                "ep=7 video=abc media-load chunk +80 track=audio id=251 start=50000",
                "ep=7 video=abc media-chunk done +900 track=audio loadMs=690 bytes=171000"
                        + " start=50000 src=net"), lines);
    }

    @Test
    public void cancelsAfterReadyAreNotStartupEvents() {
        log.onPrepare();
        log.onPlaybackStateChanged(time(), Player.STATE_READY);
        lines.clear();
        log.onLoadCanceled(time(), loadInfo(4, 0, false), chunk(C.TRACK_TYPE_VIDEO, 5_000));
        assertTrue(lines.isEmpty());
    }

    @Test
    public void rendererReadinessShowsWhatReadyWaitedFor() {
        log.onPrepare();
        elapsed = 139;
        log.onRendererReadyChanged(time(), 0, C.TRACK_TYPE_VIDEO, true);
        log.onRendererReadyChanged(time(), 0, C.TRACK_TYPE_VIDEO, false);
        log.onRendererReadyChanged(time(), 0, C.TRACK_TYPE_VIDEO, true); // not a first
        elapsed = 910;
        log.onRendererReadyChanged(time(), 1, C.TRACK_TYPE_AUDIO, true);
        log.onRendererReadyChanged(time(), 2, C.TRACK_TYPE_TEXT, true); // not tracked
        assertEquals(java.util.Arrays.asList(
                "ep=7 video=abc renderer-ready +139 type=video",
                "ep=7 video=abc renderer-ready +910 type=audio"), lines);
    }

    @Test
    public void manifestAndTextLoadsAreIgnored() {
        log.onPrepare();
        loadStarted(C.DATA_TYPE_MANIFEST, C.TRACK_TYPE_UNKNOWN);
        loadStarted(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_TEXT);
        assertTrue(lines.isEmpty());
    }

    private void loadStarted(int dataType, int trackType) {
        log.onLoadStarted(time(), loadInfo(0, 0), loadData(dataType, trackType), 0);
    }

    private void loadCompleted(int dataType, int trackType, long loadMs, long bytes) {
        log.onLoadCompleted(time(), loadInfo(loadMs, bytes), loadData(dataType, trackType));
    }

    private static LoadEventInfo loadInfo(long loadMs, long bytes, boolean network) {
        DataSpec spec = new DataSpec(Uri.parse("https://rr1---sn-test.googlevideo.com/videoplayback"));
        java.util.Map<String, java.util.List<String>> headers = network
                ? Collections.singletonMap("content-length", Collections.singletonList(String.valueOf(bytes)))
                : Collections.emptyMap();
        return new LoadEventInfo(1, spec, spec.uri, headers, 0, loadMs, bytes);
    }

    private static MediaLoadData chunk(int trackType, long startMs) {
        Format format = trackType == C.TRACK_TYPE_VIDEO ? videoFormat("248", 1080) : audioFormat();
        return new MediaLoadData(C.DATA_TYPE_MEDIA, trackType, format, C.SELECTION_REASON_INITIAL, null,
                startMs, startMs + 10_000);
    }

    private static LoadEventInfo loadInfo(long loadMs, long bytes) {
        DataSpec spec = new DataSpec(Uri.parse("https://rr1---sn-test.googlevideo.com/videoplayback"));
        return new LoadEventInfo(1, spec, spec.uri, Collections.emptyMap(), 0, loadMs, bytes);
    }

    private static MediaLoadData loadData(int dataType, int trackType) {
        Format format = trackType == C.TRACK_TYPE_VIDEO ? videoFormat("248", 1080)
                : trackType == C.TRACK_TYPE_AUDIO ? audioFormat() : null;
        return new MediaLoadData(dataType, trackType, format, C.SELECTION_REASON_INITIAL, null,
                0, C.TIME_UNSET);
    }

    private static Format videoFormat(String id, int height) {
        return new Format.Builder().setId(id).setSampleMimeType(MimeTypes.VIDEO_VP9)
                .setHeight(height).build();
    }

    private static Format audioFormat() {
        return new Format.Builder().setId("251").setSampleMimeType(MimeTypes.AUDIO_OPUS).build();
    }

    private static AnalyticsListener.EventTime time() {
        return new AnalyticsListener.EventTime(0, Timeline.EMPTY, 0, null, 0,
                Timeline.EMPTY, 0, null, 0, 0);
    }
}
