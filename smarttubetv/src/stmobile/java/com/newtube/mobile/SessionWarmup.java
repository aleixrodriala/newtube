package com.newtube.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

/**
 * FIRST-RUN SESSION WARMUP (mobile-only; nothing on TV references this class).
 *
 * The first format fetch of a fresh install pays ~15-20s of one-time YouTube session setup
 * (visitor identity, app info, downloading + parsing the multi-MB player JS for the url
 * de-scrambling functions, winning-client probing) before the actual /player request - measured on
 * a wiped emulator: ~17.5s of pre-work, then ~1.2s for the video itself. None of it is shippable
 * in the APK (identity is per-install, the JS goes stale within days), but ALL of it is
 * video-independent, so it can run in the background while the user is still browsing Home.
 *
 * This fires a throwaway blocking format fetch for a stable public video on a low-priority
 * thread shortly after app start. Every stage it warms is cached (and partly persisted) by
 * MediaServiceCore, so the user's first real video open drops to the normal ~1-2s. If the user
 * taps a video WHILE the warmup is mid-flight, the two requests serialize on MediaServiceCore's
 * internal locks (mAppInfoSync/mPlayerSync/mClientDataSync) and share the caches - the tap waits
 * only for the remainder, never duplicates the JS work. A same-id collision with a real playback
 * is additionally collapsed by YouTubeMediaItemService's single-flight.
 *
 * {@link #isWarm()} feeds the player's first-run loading hint ("one-time setup" under the
 * spinner): it is persisted once ANY format fetch has succeeded on this install - the warmup's or
 * a real playback's - so the hint can never reappear on later launches.
 */
public final class SessionWarmup {
    private static final String TAG = SessionWarmup.class.getSimpleName();
    private static final String PREFS_NAME = "newtube_session";
    private static final String KEY_SETUP_DONE = "first_setup_done";
    /** Big Buck Bunny (Blender Foundation) - public, stable for a decade, region-free. */
    private static final String WARMUP_VIDEO_ID = "aqz-KE-bpKQ";
    /** Let Home's own feed request out of the gate first; the warmup is heavy (JS parse). */
    private static final long START_DELAY_MS = 1_200;

    private static volatile boolean sStarted;
    private static volatile boolean sWarm;

    private SessionWarmup() {
    }

    /** Kick the one-shot background warmup. Safe to call more than once; only the first acts. */
    public static void start(Context context) {
        if (sStarted) {
            return;
        }
        sStarted = true;

        Context appContext = context.getApplicationContext();
        sWarm = prefs(appContext).getBoolean(KEY_SETUP_DONE, false);

        Thread thread = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                Thread.sleep(START_DELAY_MS);
                Log.d(TAG, "session warmup: start");
                // Blocking on purpose: this thread IS the background executor. The result is
                // discarded - every expensive stage behind it stays cached for the real playback.
                MediaItemFormatInfo formatInfo = YouTubeServiceManager.instance()
                        .getMediaItemService()
                        .getFormatInfo(WARMUP_VIDEO_ID);
                if (formatInfo != null) {
                    Log.d(TAG, "session warmup: done");
                    markWarm(appContext);
                } else {
                    Log.d(TAG, "session warmup: empty result (will warm on first real playback)");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                // Offline first launch etc. - the first real playback warms the session instead.
                Log.d(TAG, "session warmup failed: %s", e.getMessage());
            }
        }, "session-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    /** True once any format fetch has succeeded on this install (persisted). */
    public static boolean isWarm() {
        return sWarm;
    }

    /** Record that the session is set up - called by the warmup or by the first real playback. */
    public static void markWarm(Context context) {
        if (sWarm) {
            return;
        }
        sWarm = true;
        try {
            prefs(context.getApplicationContext()).edit().putBoolean(KEY_SETUP_DONE, true).apply();
        } catch (Exception e) {
            // Worst case the hint shows once more next launch.
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
