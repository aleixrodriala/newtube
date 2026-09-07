package com.newtube.mobile.player;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import com.newtube.mobile.SessionWarmup;

/** Debug/instrumentation-only real-decoder fixture. Every media byte comes from an APK asset. */
public final class TtffFixtureActivity extends Activity implements SurfaceHolder.Callback {
    public static final String TAG = "TTFFFixture";
    private static final Uri ASSET = Uri.parse("asset:///ttff-fixture.mp4");
    private static final Uri LOGICAL_URI = Uri.parse("https://fixture.invalid/ttff-fixture.mp4");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable sample = new Runnable() {
        @Override public void run() {
            if (player != null) {
                log("sample");
                handler.postDelayed(this, 5_000);
            }
        }
    };
    private ExoPlayer player;
    private SurfaceView surface;
    private DataSource.Factory fixtureSource;
    private int episode;
    private long openTimeMs;
    private long firstFrameMs = -1;
    private long firstReadyMs = -1;
    private int errors;
    private int unexpectedBuffering;
    private String error;
    private String phase = "created";
    private boolean expectedBuffering;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SessionWarmup.onPlaybackRequested();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        setContentView(surface);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (player == null) {
            Media3PlayerInitializer initializer = new Media3PlayerInitializer(this);
            player = initializer.createPlayer(initializer.createTrackSelector(),
                    new DefaultBandwidthMeter.Builder(this).build());
            player.setVolume(0f); // Decode the fixture's audio without sounding a test tone.
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_BUFFERING && !expectedBuffering) {
                        unexpectedBuffering++;
                    }
                    if (state == Player.STATE_READY) {
                        if (firstReadyMs < 0) firstReadyMs = elapsedMs();
                        expectedBuffering = false;
                    }
                    log("state-" + state);
                }

                @Override public void onRenderedFirstFrame() {
                    if (firstFrameMs < 0) firstFrameMs = elapsedMs();
                    log("first-frame");
                }

                @Override public void onPlayerError(PlaybackException failure) {
                    errors++;
                    error = failure.errorCode + ":" + failure.getClass().getSimpleName();
                    log("error");
                }
            });

            // Media3 has DIFFERENT local-playback buffer defaults. Keep the logical item HTTPS
            // to exercise the actual app startup/rebuffer gates, but unconditionally resolve all
            // reads to the APK asset. No request can reach fixture.invalid or any media server.
            DataSource.Factory local = new ResolvingDataSource.Factory(
                    new DefaultDataSource.Factory(this), spec -> spec.withUri(ASSET));
            fixtureSource = new DebugMediaShaper.Factory(local);
            handler.post(sample);
        }
        player.setVideoSurfaceHolder(holder);
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if (player != null) player.clearVideoSurfaceHolder(holder);
    }

    /** Called on main by instrumentation; each open replaces the source on the SAME engine. */
    public void openFixture(String name) {
        if (player == null) throw new IllegalStateException("Fixture surface is not ready");
        SessionWarmup.onPlaybackRequested();
        phase = name;
        episode++;
        openTimeMs = SystemClock.elapsedRealtime();
        firstFrameMs = -1;
        firstReadyMs = -1;
        expectedBuffering = true;
        MediaItem item = new MediaItem.Builder().setMediaId("fixture-" + episode)
                .setUri(LOGICAL_URI).setMimeType(MimeTypes.VIDEO_MP4).build();
        player.setMediaSource(new ProgressiveMediaSource.Factory(fixtureSource).createMediaSource(item));
        player.prepare();
        player.play();
        log("open");
    }

    public void pauseFixture() {
        phase = "pause";
        player.pause();
        log("pause");
    }

    public void resumeFixture() {
        phase = "resume";
        player.play();
        log("resume");
    }

    public void seekFixture(long positionMs) {
        phase = "seek";
        expectedBuffering = true;
        player.seekTo(positionMs);
        log("seek");
    }

    /** Cached seeks can remain READY without emitting a new READY callback. */
    public void finishExpectedTransition() {
        expectedBuffering = false;
    }

    public Snapshot snapshot() {
        DecoderCounters counters = player != null ? player.getVideoDecoderCounters() : null;
        if (counters != null) counters.ensureUpdated();
        return new Snapshot(player != null, episode,
                player != null ? player.getPlaybackState() : Player.STATE_IDLE,
                player != null && player.isPlaying(),
                player != null ? player.getCurrentPosition() : 0,
                player != null ? player.getBufferedPosition() : 0,
                firstFrameMs, firstReadyMs,
                counters != null ? counters.renderedOutputBufferCount : 0,
                counters != null ? counters.droppedBufferCount : 0,
                errors, unexpectedBuffering, error);
    }

    private long elapsedMs() {
        return openTimeMs == 0 ? 0 : SystemClock.elapsedRealtime() - openTimeMs;
    }

    private void log(String event) {
        Snapshot s = snapshot();
        Log.i(TAG, "event=" + event + " phase=" + phase + " episode=" + episode
                + " elapsedMs=" + elapsedMs() + " firstFrameMs=" + firstFrameMs
                + " readyMs=" + firstReadyMs + " state=" + s.state
                + " positionMs=" + s.positionMs + " bufferedMs=" + s.bufferedPositionMs
                + " frames=" + s.frames + " dropped=" + s.dropped
                + " errors=" + errors + " unexpectedBuffering=" + unexpectedBuffering);
    }

    @Override protected void onStop() {
        if (player != null) player.pause();
        super.onStop();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        surface.getHolder().removeCallback(this);
        if (player != null) {
            player.clearVideoSurface();
            player.release();
            player = null;
        }
        Log.i(TAG, "event=released episodes=" + episode + " errors=" + errors
                + " unexpectedBuffering=" + unexpectedBuffering);
        super.onDestroy();
    }

    public static final class Snapshot {
        public final boolean initialized;
        public final int episode;
        public final int state;
        public final boolean playing;
        public final long positionMs;
        public final long bufferedPositionMs;
        public final long firstFrameMs;
        public final long readyMs;
        public final int frames;
        public final int dropped;
        public final int errors;
        public final int unexpectedBuffering;
        public final String error;

        Snapshot(boolean initialized, int episode, int state, boolean playing, long positionMs,
                long bufferedPositionMs, long firstFrameMs, long readyMs, int frames, int dropped,
                int errors, int unexpectedBuffering, String error) {
            this.initialized = initialized;
            this.episode = episode;
            this.state = state;
            this.playing = playing;
            this.positionMs = positionMs;
            this.bufferedPositionMs = bufferedPositionMs;
            this.firstFrameMs = firstFrameMs;
            this.readyMs = readyMs;
            this.frames = frames;
            this.dropped = dropped;
            this.errors = errors;
            this.unexpectedBuffering = unexpectedBuffering;
            this.error = error;
        }
    }
}
