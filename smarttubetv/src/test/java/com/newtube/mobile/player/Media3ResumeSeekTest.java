package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowSystemProperties;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The player calls behind a history resume: an EXACT seek (the right segment is requested), then -
 * when that segment starts loading and its index-derived start lies 0.5-8 s before the target -
 * one PREVIOUS_SYNC seek, each framed by a restore of the player-wide seek parameters. Any seek
 * that is not ours, including raw ones that bypass the controller, cancels the snap.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class Media3ResumeSeekTest {
    private static final SeekParameters PLAYER_DEFAULT = new SeekParameters(5_000_000, 1_000_000);
    private final List<String> calls = new ArrayList<>();
    private final Timeline timeline = new SinglePeriodTimeline(600_000_000L, /* isSeekable= */ true,
            /* isDynamic= */ false, /* useLiveConfiguration= */ false, /* manifest= */ null,
            MediaItem.fromUri("https://youtube.com/generated.mpd"));
    private Media3PlayerController controller;
    private ExoPlayer player;
    private SeekParameters current = PLAYER_DEFAULT;
    private long positionMs;
    private boolean live;

    @Before
    public void setUp() {
        PlayerEventListener listener = (PlayerEventListener) Proxy.newProxyInstance(
                PlayerEventListener.class.getClassLoader(), new Class<?>[] {PlayerEventListener.class},
                (proxy, method, args) -> null);
        controller = new Media3PlayerController(RuntimeEnvironment.getApplication(), listener);
        player = (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(),
                new Class<?>[] {ExoPlayer.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "seekTo":
                            positionMs = (Long) args[args.length - 1];
                            calls.add("seek " + positionMs + " " + name(current));
                            // ExoPlayerImpl reports the requested position synchronously.
                            controller.onPositionDiscontinuity(position(0), position(positionMs),
                                    Player.DISCONTINUITY_REASON_SEEK);
                            return null;
                        case "getSeekParameters": return current;
                        case "setSeekParameters":
                            current = (SeekParameters) args[0];
                            return null;
                        case "getCurrentPosition": return positionMs;
                        case "getDuration": return 600_000L;
                        case "isCurrentMediaItemLive": return live;
                        case "getCurrentTimeline": return timeline;
                        case "getCurrentPeriodIndex": return 0;
                        case "getPlaybackState": return Player.STATE_BUFFERING;
                        case "getPlayWhenReady": return true;
                        case "getMediaItemCount": return 0;
                        case "addListener":
                        case "addAnalyticsListener": return null;
                        default: throw new AssertionError("Unexpected player call: " + method.getName());
                    }
                });
        controller.setPlayer(player);
    }

    @Test
    public void resumeSeeksExactlyThenSnapsWhenItsSegmentStartsLoading() {
        controller.seekToResumePosition(260_000);
        assertEquals(Collections.singletonList("seek 260000 exact"), calls);
        assertEquals(PLAYER_DEFAULT, current);

        audioChunk(250_000, 260_000);  // not the video segment: nothing
        videoChunk(256_780, 262_100);
        assertEquals(Arrays.asList("seek 260000 exact", "seek 260000 previous-sync"), calls);
        assertEquals(PLAYER_DEFAULT, current); // restored for every later seek

        videoChunk(262_100, 267_400); // the next segment: no second snap
        assertEquals(2, calls.size());
    }

    @Test
    public void rawMediaSessionSeekCancelsEvenWithinTheOldGuard() {
        controller.seekToResumePosition(260_000);
        player.seekTo(260_500); // lock screen / double-tap overlay: bypasses setPositionMs
        videoChunk(256_780, 262_100);
        assertEquals(Arrays.asList("seek 260000 exact", "seek 260500 player-default"), calls);
    }

    @Test
    public void controllerSeekCancelsTheSnap() {
        controller.seekToResumePosition(260_000);
        controller.setPositionMs(300_000); // SponsorBlock / chapter / scrub: exact wins
        videoChunk(296_000, 301_300);
        assertEquals(Arrays.asList("seek 260000 exact", "seek 300000 player-default"), calls);
    }

    @Test
    public void staleEventOfAnotherSourceIsIgnored() {
        controller.seekToResumePosition(260_000);
        loadStarted(new MediaSource.MediaPeriodId(new Object()), C.TRACK_TYPE_VIDEO, 256_780, 262_100);
        assertEquals(1, calls.size());
        videoChunk(256_780, 262_100); // the real one still snaps
        assertEquals(2, calls.size());
    }

    @Test
    public void longSegmentKeepsTheExactPosition() {
        controller.seekToResumePosition(260_000);
        videoChunk(248_000, 270_000); // 12 s early: rewinding that far is worse than decoding
        assertEquals(Collections.singletonList("seek 260000 exact"), calls);
    }

    @Test
    public void readyExpiresTheSnap() {
        controller.seekToResumePosition(260_000);
        controller.onPlaybackStateChanged(Player.STATE_READY); // e.g. audio-only playback
        videoChunk(256_780, 262_100);
        assertEquals(Collections.singletonList("seek 260000 exact"), calls);
    }

    @Test
    public void historyKeepsTheTargetUntilPlaybackPassesIt() {
        controller.seekToResumePosition(260_000);
        videoChunk(256_780, 262_100);
        positionMs = 256_780; // where media3 put the playhead
        assertEquals(260_000, controller.getHistoryPositionMs());
        positionMs = 262_000;
        assertEquals(262_000, controller.getHistoryPositionMs());
    }

    @Test
    public void liveResumeStaysOnThePlainPath() {
        live = true;
        controller.seekToResumePosition(260_000);
        videoChunk(256_780, 262_100);
        assertEquals(Collections.singletonList("seek 260000 player-default"), calls);
    }

    @Test
    public void debugSwitchRestoresThePreSnapResume() {
        ShadowSystemProperties.override("debug.arc.resume_snap", "0");
        try {
            controller.seekToResumePosition(260_000);
            videoChunk(256_780, 262_100);
            assertEquals(Collections.singletonList("seek 260000 player-default"), calls);
            positionMs = 256_000;
            assertEquals(256_000, controller.getHistoryPositionMs()); // no floor either
        } finally {
            ShadowSystemProperties.override("debug.arc.resume_snap", "");
        }
    }

    @Test
    public void aNewOpenForgetsTheArmedSnap() {
        controller.seekToResumePosition(260_000);
        controller.resetPlayerState(); // the next open (a related tap) before the segment loaded
        videoChunk(256_780, 262_100);
        assertEquals(Collections.singletonList("seek 260000 exact"), calls);
    }

    private void videoChunk(long startMs, long endMs) {
        loadStarted(new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)), C.TRACK_TYPE_VIDEO,
                startMs, endMs);
    }

    private void audioChunk(long startMs, long endMs) {
        loadStarted(new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)), C.TRACK_TYPE_AUDIO,
                startMs, endMs);
    }

    private void loadStarted(MediaSource.MediaPeriodId periodId, int trackType, long startMs, long endMs) {
        AnalyticsListener listener = ReflectionHelpers.getField(controller, "mResumeListener");
        DataSpec spec = new DataSpec(Uri.parse("https://rr1---sn-test.googlevideo.com/videoplayback"));
        listener.onLoadStarted(
                new AnalyticsListener.EventTime(0, timeline, 0, periodId, 0, timeline, 0, periodId, 0, 0),
                new LoadEventInfo(1, spec, 0),
                new MediaLoadData(C.DATA_TYPE_MEDIA, trackType, null, C.SELECTION_REASON_INITIAL, null,
                        startMs, endMs),
                /* retryCount= */ 0);
    }

    private static Player.PositionInfo position(long ms) {
        return new Player.PositionInfo(null, 0, null, null, 0, ms, ms, C.INDEX_UNSET, C.INDEX_UNSET);
    }

    private static String name(SeekParameters parameters) {
        if (parameters == SeekParameters.EXACT) return "exact";
        if (parameters == Media3PlayerController.RESUME_SNAP_PARAMETERS) return "previous-sync";
        if (parameters == PLAYER_DEFAULT) return "player-default";
        return "other";
    }
}
