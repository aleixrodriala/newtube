package com.newtube.mobile.player;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

/**
 * Minimal media3 "stats for nerds" for the touch player, replacing the exoplayer2-bound
 * {@code DebugInfoManager}: one monospace overlay text block, refreshed every second while shown.
 */
public class Media3DebugInfoManager {
    private static final long UPDATE_INTERVAL_MS = 1_000;

    private final ViewGroup mDebugViewGroup;
    private final ExoPlayer mPlayer;
    private final DefaultBandwidthMeter mBandwidthMeter;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mUpdateRunnable = this::update;
    private TextView mTextView;

    public Media3DebugInfoManager(ViewGroup debugViewGroup, ExoPlayer player, DefaultBandwidthMeter bandwidthMeter) {
        mDebugViewGroup = debugViewGroup;
        mPlayer = player;
        mBandwidthMeter = bandwidthMeter;
    }

    public void show(boolean show) {
        mHandler.removeCallbacks(mUpdateRunnable);

        if (show) {
            if (mTextView == null) {
                mTextView = new TextView(mDebugViewGroup.getContext());
                mTextView.setTypeface(Typeface.MONOSPACE);
                mTextView.setTextSize(12);
                mTextView.setTextColor(Color.WHITE);
                mTextView.setBackgroundColor(0x88000000);
                int pad = (int) (8 * mDebugViewGroup.getResources().getDisplayMetrics().density);
                mTextView.setPadding(pad, pad, pad, pad);
                mTextView.setGravity(Gravity.START);
                mDebugViewGroup.addView(mTextView,
                        new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            mDebugViewGroup.setVisibility(View.VISIBLE);
            mTextView.setVisibility(View.VISIBLE);
            update();
        } else {
            if (mTextView != null) {
                mTextView.setVisibility(View.GONE);
            }
            mDebugViewGroup.setVisibility(View.GONE);
        }
    }

    private void update() {
        if (mTextView == null || mTextView.getVisibility() != View.VISIBLE) {
            return;
        }

        StringBuilder info = new StringBuilder("engine: media3\n");

        appendFormat(info, "video", mPlayer.getVideoFormat());
        appendFormat(info, "audio", mPlayer.getAudioFormat());

        DecoderCounters counters = mPlayer.getVideoDecoderCounters();
        if (counters != null) {
            counters.ensureUpdated();
            info.append("dropped/rendered: ")
                    .append(counters.droppedBufferCount).append('/')
                    .append(counters.renderedOutputBufferCount).append('\n');
        }

        info.append("bandwidth est: ")
                .append(mBandwidthMeter.getBitrateEstimate() / 1000).append(" kbps\n");
        info.append("buffered ahead: ")
                .append(Math.max(0, mPlayer.getBufferedPosition() - mPlayer.getCurrentPosition()) / 1000)
                .append(" s");

        mTextView.setText(info);

        mHandler.postDelayed(mUpdateRunnable, UPDATE_INTERVAL_MS);
    }

    private static void appendFormat(StringBuilder info, String label, @Nullable Format format) {
        info.append(label).append(": ");
        if (format == null) {
            info.append("none\n");
            return;
        }
        info.append(format.id != null ? format.id : "?")
                .append(' ').append(format.codecs != null ? format.codecs : format.sampleMimeType);
        if (format.width > 0) {
            info.append(' ').append(format.width).append('x').append(format.height)
                    .append('@').append(Math.round(format.frameRate));
        }
        if (format.bitrate > 0) {
            info.append(' ').append(format.bitrate / 1000).append(" kbps");
        }
        info.append('\n');
    }
}
