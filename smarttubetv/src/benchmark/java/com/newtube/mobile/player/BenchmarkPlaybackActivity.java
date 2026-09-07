package com.newtube.mobile.player;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Trace;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

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

/** Shell-only, benchmark-variant fixture; every media byte is read from the bundled asset. */
public final class BenchmarkPlaybackActivity extends Activity implements SurfaceHolder.Callback {
    private static final String FRAME_TRACE = "NewTubeFixture.firstFrame";
    private static final String READY_TRACE = "NewTubeFixture.ready";
    private static final int TRACE_COOKIE = 1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private TextView status;
    private long startedMs;
    private long firstFrameMs = -1;
    private long readyMs = -1;
    private boolean frameTraceOpen;
    private boolean readyTraceOpen;
    private boolean fullyDrawn;
    private boolean failed;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startedMs = SystemClock.elapsedRealtime();
        Trace.beginAsyncSection(FRAME_TRACE, TRACE_COOKIE);
        Trace.beginAsyncSection(READY_TRACE, TRACE_COOKIE);
        frameTraceOpen = readyTraceOpen = true;
        SessionWarmup.onPlaybackRequested();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout container = new FrameLayout(this);
        SurfaceView surface = new SurfaceView(this);
        container.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(Color.BLACK);
        container.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(container);
        if (!"1".equals(DebugMediaShaper.prop("debug.arc.benchmark_fixture"))) {
            mark("error", "Offline fixture mode is required before starting the process");
            failed = true;
            return;
        }
        mark("loading", "Offline decoder fixture");
        surface.getHolder().addCallback(this);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (player != null || failed) return;
        Media3PlayerInitializer initializer = new Media3PlayerInitializer(this);
        player = initializer.createPlayer(initializer.createTrackSelector(),
                new DefaultBandwidthMeter.Builder(this).build());
        player.setVideoSurfaceHolder(holder);
        player.setVolume(0f);
        player.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                if (firstFrameMs < 0) {
                    firstFrameMs = elapsedMs();
                    Trace.endAsyncSection(FRAME_TRACE, TRACE_COOKIE);
                    frameTraceOpen = false;
                    log("first-frame");
                }
                maybeReady();
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && readyMs < 0) {
                    readyMs = elapsedMs();
                    Trace.endAsyncSection(READY_TRACE, TRACE_COOKIE);
                    readyTraceOpen = false;
                    log("ready");
                } else if (fullyDrawn && state == Player.STATE_BUFFERING) {
                    failed = true;
                    mark("error", "Unexpected buffering in the local fixture");
                }
                maybeReady();
            }

            @Override public void onPlayerError(PlaybackException error) {
                failed = true;
                mark("error", "Playback failed: " + error.errorCode);
                log("error");
            }
        });

        // Keep the logical item remote to exercise the production network LoadControl policy,
        // while resolving every read to the local asset before any data source is opened.
        DataSource.Factory local = new ResolvingDataSource.Factory(
                new DefaultDataSource.Factory(this), spec -> spec.withUri(Uri.parse("asset:///ttff-fixture.mp4")));
        MediaItem item = new MediaItem.Builder().setMediaId("benchmark-fixture")
                .setUri("https://fixture.invalid/ttff-fixture.mp4")
                .setMimeType(MimeTypes.VIDEO_MP4).build();
        player.setMediaSource(new ProgressiveMediaSource.Factory(local).createMediaSource(item));
        player.prepare();
        player.play();
    }

    private void maybeReady() {
        if (failed || fullyDrawn || firstFrameMs < 0 || readyMs < 0
                || player == null || player.getPlaybackState() != Player.STATE_READY) return;
        fullyDrawn = true;
        mark("ready", "Frame decoded and playback ready");
        // StartupTimingMetric can now distinguish the initial Activity draw from usable playback.
        reportFullyDrawn();
        handler.postDelayed(this::checkProgress, 1_000);
    }

    private void checkProgress() {
        if (player == null || failed) return;
        DecoderCounters counters = player.getVideoDecoderCounters();
        if (counters != null) counters.ensureUpdated();
        if (player.isPlaying() && player.getCurrentPosition() >= 800
                && counters != null && counters.renderedOutputBufferCount >= 20) {
            mark("progress", "Local fixture is decoding and advancing");
            log("progress");
        } else if (elapsedMs() > 20_000) {
            failed = true;
            mark("error", "Local fixture did not continue decoding");
        } else {
            handler.postDelayed(this::checkProgress, 100);
        }
    }

    private void mark(String phase, String message) {
        status.setText(message);
        status.setContentDescription("fixture:" + phase);
    }

    private long elapsedMs() { return SystemClock.elapsedRealtime() - startedMs; }

    private void log(String event) {
        android.util.Log.i("BenchmarkFixture", "event=" + event + " elapsedMs=" + elapsedMs()
                + " firstFrameMs=" + firstFrameMs + " readyMs=" + readyMs);
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if (player != null) player.clearVideoSurfaceHolder(holder);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        if (frameTraceOpen) Trace.endAsyncSection(FRAME_TRACE, TRACE_COOKIE);
        if (readyTraceOpen) Trace.endAsyncSection(READY_TRACE, TRACE_COOKIE);
        super.onDestroy();
    }
}
