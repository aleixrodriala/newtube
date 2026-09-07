package com.newtube.mobile.player;

import android.content.Context;
import android.os.SystemClock;

import com.liskovsoft.sharedutils.cronet.CronetManager;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Starts local, process-wide player infrastructure before a selected video needs it. */
public final class PlayerInfrastructureWarmup {
    private static PlayerInfrastructureWarmup sInstance;

    private final Executor mExecutor;
    private final Runnable mTransport;
    private final Runnable mCache;
    private final AtomicBoolean mStarted = new AtomicBoolean();

    PlayerInfrastructureWarmup(Executor executor, Runnable transport, Runnable cache) {
        mExecutor = executor;
        mTransport = transport;
        mCache = cache;
    }

    /** Call after Application has configured the network stack. No media or API request is made. */
    public static synchronized void start(Context context) {
        if (BuildConfig.DEBUG && "off".equals(DebugMediaShaper.prop("debug.arc.player_warmup"))) {
            return;
        }
        if (sInstance == null) {
            Context app = context.getApplicationContext();
            sInstance = new PlayerInfrastructureWarmup(task -> {
                Thread worker = new Thread(task, "PlayerInfrastructureWarmup");
                worker.setDaemon(true);
                worker.start();
            }, () -> CronetManager.getEngine(app), () -> {
                if (!BuildConfig.DEBUG || !"off".equals(DebugMediaShaper.prop("debug.arc.media_cache"))) {
                    androidx.media3.datasource.cache.Cache cache = Media3PlayerCache.get(app);
                    if (cache != null) {
                        // SimpleCache initializes its index asynchronously. Wait on this worker,
                        // not the Activity's first source construction or first media read.
                        cache.getCacheSpace();
                    }
                }
            });
        }
        sInstance.schedule();
    }

    void schedule() {
        if (!mStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            mExecutor.execute(() -> {
                initialize("transport", mTransport);
                initialize("cache", mCache);
            });
        } catch (RuntimeException error) {
            mStarted.set(false); // an executor rejection must not break launch or block a later try
            NetPath.log("player-warmup schedule-failed error=" + error.getClass().getSimpleName());
        }
    }

    private static void initialize(String name, Runnable task) {
        long start = SystemClock.elapsedRealtime();
        try {
            task.run();
            NetPath.log("player-warmup " + name + " elapsedMs="
                    + (SystemClock.elapsedRealtime() - start));
        } catch (RuntimeException | LinkageError error) {
            // The selected video's ordinary initialization/fallback remains authoritative.
            // A missing native transport must not prevent independent disk-cache warming.
            NetPath.log("player-warmup " + name + " failed=" + error.getClass().getSimpleName());
        }
    }
}
