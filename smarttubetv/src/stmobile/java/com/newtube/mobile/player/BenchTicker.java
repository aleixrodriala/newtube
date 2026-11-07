package com.newtube.mobile.player;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

/**
 * NEWTUBE(bench): playback evidence for the in-app source benchmark (netbench phase 1). The
 * benchmark opens a video with a forced source and needs to know it was really *played* - that
 * the position kept advancing past the first minute and across a seek - not just that /player
 * answered or the first bytes arrived.
 *
 * <p>Debug and benchmark builds only, and only with {@code adb shell setprop debug.arc.bench 1}
 * (read when the player is created). Every {@link #TICK_MS} ({@code debug.arc.bench_tick_ms}, 100 ms
 * or more, to time a stall) it logs one line:
 * {@code bench-tick video=<id> pos=<ms> dur=<ms> buf=<ms> state=<READY|BUFFERING|ENDED|IDLE>
 * playing=<y|n> t=<elapsedRealtime>}. With {@code debug.arc.bench_seek <at_s>:<fraction>}
 * (e.g. {@code 90:0.7}) it seeks once per video to that fraction of the duration after
 * {@code at_s} seconds of playback position, logging {@code bench-seek}.
 */
public final class BenchTicker {
    private static final long TICK_MS = 10_000;
    private long mTickMs = TICK_MS;

    /** What video the player is on; the activity knows, the player does not. */
    public interface VideoIdSource {
        @Nullable String currentVideoId();
    }

    private final ExoPlayer mPlayer;
    private final VideoIdSource mVideoIds;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final long mSeekAtMs;
    private final double mSeekFraction;
    @Nullable private String mSeekedVideoId;
    private boolean mStopped;

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            if (mStopped) return;
            tick();
            mHandler.postDelayed(this, mTickMs);
        }
    };

    /** Returns a running ticker, or null when the benchmark is off or the build is a release. */
    @Nullable
    public static BenchTicker startIfEnabled(boolean benchBuild, ExoPlayer player, VideoIdSource videoIds) {
        if (!benchBuild || !"1".equals(systemProperty("debug.arc.bench"))) {
            return null;
        }
        long seekAtMs = -1;
        double seekFraction = 0;
        String seek = systemProperty("debug.arc.bench_seek");
        int colon = seek.indexOf(':');
        if (colon > 0) {
            try {
                seekAtMs = (long) (Double.parseDouble(seek.substring(0, colon)) * 1000);
                seekFraction = Double.parseDouble(seek.substring(colon + 1));
            } catch (NumberFormatException e) {
                seekAtMs = -1;
            }
        }
        BenchTicker ticker = new BenchTicker(player, videoIds, seekAtMs, seekFraction);
        String tick = systemProperty("debug.arc.bench_tick_ms");
        if (tick != null && tick.matches("[0-9]{3,5}")) {
            ticker.mTickMs = Long.parseLong(tick);
        }
        NetPath.log("bench-start tickMs=" + ticker.mTickMs + " seek=" + (seekAtMs >= 0 ? seek : "none"));
        ticker.mHandler.postDelayed(ticker.mTick, ticker.mTickMs);
        return ticker;
    }

    private BenchTicker(ExoPlayer player, VideoIdSource videoIds, long seekAtMs, double seekFraction) {
        mPlayer = player;
        mVideoIds = videoIds;
        mSeekAtMs = seekAtMs;
        mSeekFraction = seekFraction;
    }

    public void stop() {
        mStopped = true;
        mHandler.removeCallbacks(mTick);
    }

    private void tick() {
        String videoId = mVideoIds.currentVideoId();
        long pos = mPlayer.getCurrentPosition();
        long dur = mPlayer.getDuration();
        NetPath.log("bench-tick video=" + (videoId != null ? videoId : "?")
                + " pos=" + pos
                + " dur=" + (dur == C.TIME_UNSET ? -1 : dur)
                + " buf=" + mPlayer.getTotalBufferedDuration()
                + " state=" + stateName(mPlayer.getPlaybackState())
                + " playing=" + (mPlayer.isPlaying() ? "y" : "n")
                + " t=" + SystemClock.elapsedRealtime());

        if (mSeekAtMs >= 0 && videoId != null && !videoId.equals(mSeekedVideoId)
                && pos >= mSeekAtMs && dur != C.TIME_UNSET && dur > 0 && !mPlayer.isCurrentMediaItemLive()) {
            long target = (long) (dur * mSeekFraction);
            mSeekedVideoId = videoId;
            NetPath.log("bench-seek video=" + videoId + " from=" + pos + " to=" + target);
            mPlayer.seekTo(target);
        }
    }

    private static String stateName(int state) {
        switch (state) {
            case Player.STATE_READY: return "READY";
            case Player.STATE_BUFFERING: return "BUFFERING";
            case Player.STATE_ENDED: return "ENDED";
            default: return "IDLE";
        }
    }

    private static String systemProperty(String key) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            String value = (String) properties.getMethod("get", String.class, String.class)
                    .invoke(null, key, "");
            return value != null ? value.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
