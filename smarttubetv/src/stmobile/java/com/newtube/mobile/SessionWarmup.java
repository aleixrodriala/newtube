package com.newtube.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

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
 * thread once the first feed has painted (launch fallback: 15s). It yields to real playback
 * before starting a pending fetch. Every stage it warms is cached (and partly persisted) by
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
    /** Let the feed's own image loads out of the gate first; the warmup is heavy (JS parse). */
    private static final long START_DELAY_MS = 1_200;
    /**
     * The warmup is normally kicked by the first feed paint (MobileBrowseActivity) so it never
     * competes with the launch-critical /browse chain. This fallback covers the paths where no
     * feed ever paints: offline first launch, or a deep link straight into playback.
     */
    private static final long LAUNCH_FALLBACK_DELAY_MS = 15_000;

    private static final SessionWarmupGate sGate = new SessionWarmupGate();
    // This handler owns only our timers, so cancelling all its callbacks cannot affect UI work.
    // Sleeping worker threads used to retain two native stacks until their deadlines, even when
    // real playback had already made the speculative fetch unnecessary.
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private SessionWarmup() {
    }

    /**
     * Call once from Application.onCreate: restores the persisted warm flag (isWarm feeds the
     * player's first-run hint, which may be consulted before any feed paints) and arms the
     * launch fallback. Does NOT fire the warmup fetch — that waits for the first feed paint.
     */
    public static synchronized void init(Context context) {
        Context appContext = context.getApplicationContext();
        sGate.restore(prefs(appContext).getBoolean(KEY_SETUP_DONE, false));
        if (!sGate.tryScheduleFallback()) {
            return;
        }

        sHandler.postDelayed(() -> start(appContext), LAUNCH_FALLBACK_DELAY_MS);
        trace("fallback-scheduled delayMs=" + LAUNCH_FALLBACK_DELAY_MS);
    }

    /** Kick the one-shot background warmup. Safe to call more than once; only the first acts. */
    public static synchronized void start(Context context) {
        if (!sGate.trySchedule()) {
            return;
        }

        Context appContext = context.getApplicationContext();
        sHandler.removeCallbacksAndMessages(null); // the feed won; the launch fallback is obsolete
        sHandler.postDelayed(() -> beginFetch(appContext), START_DELAY_MS);
        trace("fetch-scheduled delayMs=" + START_DELAY_MS);
    }

    private static synchronized void beginFetch(Context appContext) {
        // Real playback or memory pressure may arrive during the feed delay. Claim the fetch
        // before allocating a worker; after this point an in-flight fetch is allowed to finish.
        if (!sGate.tryBeginFetch()) {
            return;
        }

        Thread thread = new Thread(() -> {
            long startedMs = SystemClock.elapsedRealtime();
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                Log.d(TAG, "session warmup: start");
                trace("fetch-start");
                // Blocking on purpose: this thread IS the background executor. The result is
                // discarded - every expensive stage behind it stays cached for the real playback.
                MediaItemFormatInfo formatInfo = YouTubeServiceManager.instance()
                        .getMediaItemService()
                        .getFormatInfo(WARMUP_VIDEO_ID);
                if (formatInfo != null) {
                    Log.d(TAG, "session warmup: done");
                    trace("fetch-done elapsedMs=" + (SystemClock.elapsedRealtime() - startedMs));
                    markWarm(appContext);
                } else {
                    Log.d(TAG, "session warmup: empty result (will warm on first real playback)");
                    trace("fetch-empty elapsedMs=" + (SystemClock.elapsedRealtime() - startedMs));
                }
            } catch (Throwable e) {
                // Offline first launch etc. - the first real playback warms the session instead.
                Log.d(TAG, "session warmup failed: %s", e.getMessage());
                trace("fetch-failed elapsedMs=" + (SystemClock.elapsedRealtime() - startedMs)
                        + " error=" + e.getClass().getSimpleName());
            }
        }, "session-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    /** True once any format fetch has succeeded on this install (persisted). */
    public static boolean isWarm() {
        return sGate.isWarm();
    }

    /** The selected video owns setup now; pending speculative work must yield to it. */
    public static synchronized void onPlaybackRequested() {
        sGate.onPlaybackRequested();
        sHandler.removeCallbacksAndMessages(null);
        trace("pending-cancelled reason=playback");
    }

    /** Do not allocate a speculative extractor/JS heap after the OS asks us to free memory. */
    public static synchronized void onMemoryPressure() {
        sGate.onMemoryPressure();
        sHandler.removeCallbacksAndMessages(null);
        trace("pending-cancelled reason=memory-pressure");
    }

    /** Record that the session is set up - called by the warmup or by the first real playback. */
    public static synchronized void markWarm(Context context) {
        boolean needsPersist = sGate.markWarm();
        sHandler.removeCallbacksAndMessages(null);
        if (!needsPersist) {
            return;
        }
        try {
            prefs(context.getApplicationContext()).edit().putBoolean(KEY_SETUP_DONE, true).apply();
        } catch (Exception e) {
            // Worst case the hint shows once more next launch.
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void trace(String event) {
        if (com.liskovsoft.smartyoutubetv2.tv.BuildConfig.DEBUG) {
            // The shared logger may be suppressed; correlate allocations with the same direct
            // credential-free diagnostic stream used by playback and transport measurements.
            android.util.Log.d("NetPath", "session-warmup " + event);
        }
    }
}
