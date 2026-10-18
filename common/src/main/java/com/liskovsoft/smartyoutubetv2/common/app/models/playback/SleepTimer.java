package com.liskovsoft.smartyoutubetv2.common.app.models.playback;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerManager;

/**
 * NEWTUBE(sleep-timer): session-level sleep timer (issues #47, #77).
 *
 * <p>Mirrors the official YouTube mobile app: accessible directly from the player's gear menu.
 * Supports preset durations (10, 15, 20, 30, 45, 60 minutes) and "End of video".
 * When the duration elapses or the current video ends, playback pauses.</p>
 *
 * <p>Deliberately transient like {@link QueuePlaybackMode}: it covers the active listening/watching
 * session and does not persist across app restarts.</p>
 */
public final class SleepTimer {
    public static final int OPTION_OFF = 0;
    public static final int OPTION_END_OF_VIDEO = -1;
    public static final int[] PRESETS_MINUTES = {10, 15, 20, 30, 45, 60};

    public interface Listener {
        void onSleepTimerTriggered();
    }

    private static int sSelectedOption = OPTION_OFF;
    private static long sTargetRealtimeMs = 0;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static PlayerManager sPlayer;
    private static Listener sListener;

    private static final Runnable sRunnable = () -> {
        if (sSelectedOption > 0) {
            trigger();
        }
    };

    private SleepTimer() {}

    public static void setPlayer(@Nullable PlayerManager player) {
        sPlayer = player;
    }

    public static void setListener(@Nullable Listener listener) {
        sListener = listener;
    }

    public static int getSelectedOption() {
        return sSelectedOption;
    }

    public static long getTargetRealtimeMs() {
        return sTargetRealtimeMs;
    }

    public static boolean isArmed() {
        return sSelectedOption != OPTION_OFF;
    }

    public static boolean isEndOfVideoArmed() {
        return sSelectedOption == OPTION_END_OF_VIDEO;
    }

    public static void arm(int minutes) {
        cancel();
        sSelectedOption = minutes;
        long delayMs = minutes * 60 * 1000L;
        sTargetRealtimeMs = SystemClock.elapsedRealtime() + delayMs;
        sHandler.postDelayed(sRunnable, delayMs);
    }

    public static void armEndOfVideo() {
        cancel();
        sSelectedOption = OPTION_END_OF_VIDEO;
        sTargetRealtimeMs = 0;
    }

    public static void cancel() {
        sSelectedOption = OPTION_OFF;
        sTargetRealtimeMs = 0;
        sHandler.removeCallbacks(sRunnable);
    }

    public static void onEndOfVideoReached() {
        if (isEndOfVideoArmed()) {
            trigger();
        }
    }

    private static void trigger() {
        cancel();
        if (sPlayer != null) {
            sPlayer.setPlayWhenReady(false);
        }
        if (sListener != null) {
            sListener.onSleepTimerTriggered();
        }
    }
}
