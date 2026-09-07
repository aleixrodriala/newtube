package com.liskovsoft.smartyoutubetv2.common.misc;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

public class BufferingDetector {
    private static final long BUFFERING_WINDOW_MS = 60_000;
    private static final long BUFFERING_DURATION_MS = 20_000;
    
    private long mBeginTimeMs = -1;
    private long mStartTimeMs = -1;
    private long mTotalDurationMs;
    private final Runnable mOnLongBuffering = this::onLongBuffering;
    private final OnLongBuffering mCallback;

    public interface OnLongBuffering {
        void onLongBuffering();
    }

    public BufferingDetector(@NonNull OnLongBuffering callback) {
        mCallback = callback;
    }

    public void onStartBuffering() {
        // Repeated BUFFERING callbacks describe the same stall; they must not restart its timer.
        if (mStartTimeMs >= 0) {
            return;
        }
        // Use the same monotonic clock as Handler delays, unaffected by wall-clock corrections.
        long currentTimeMs = SystemClock.uptimeMillis();
        if (mBeginTimeMs < 0 || currentTimeMs - mBeginTimeMs > BUFFERING_WINDOW_MS) {
            mBeginTimeMs = currentTimeMs;
            mTotalDurationMs = 0;
        }
        mStartTimeMs = currentTimeMs;
        Utils.postDelayed(mOnLongBuffering, Math.max(0, BUFFERING_DURATION_MS - mTotalDurationMs));
    }

    public void onStopBuffering() {
        Utils.removeCallbacks(mOnLongBuffering);
        // Upstream 4899533: count an active interval once. Both onPlay and onPause call here;
        // counting the intervening healthy playback can exhaust or disable the next watchdog.
        if (mStartTimeMs >= 0) {
            mTotalDurationMs += SystemClock.uptimeMillis() - mStartTimeMs;
            mStartTimeMs = -1;
        }
    }

    /**
     * Reset buffering stats
     */
    public void reset() {
        mBeginTimeMs = mStartTimeMs = -1;
        mTotalDurationMs = 0;
        Utils.removeCallbacks(mOnLongBuffering);
    }

    private void onLongBuffering() {
        reset();
        mCallback.onLongBuffering();
    }
}
