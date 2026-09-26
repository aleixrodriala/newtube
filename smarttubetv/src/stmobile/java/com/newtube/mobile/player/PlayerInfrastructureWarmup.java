package com.newtube.mobile.player;

import android.content.Context;
import android.os.SystemClock;

import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;

import com.liskovsoft.sharedutils.cronet.CronetManager;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Starts local, process-wide player infrastructure before a selected video needs it. */
public final class PlayerInfrastructureWarmup {
    /**
     * NEWTUBE(codec-list): every MIME a YouTube DASH/HLS answer can offer the renderers. Media3's
     * track selection asks {@link MediaCodecUtil#getDecoderInfos} for each of them (renderer
     * capabilities, on the playback thread, between {@code prepare} and the first media request).
     * The first query of a process builds the platform MediaCodecList and parses every matching
     * codec's capabilities; the result is cached per (MIME, secure, tunneling) for the process.
     * On a cold share-link open that first query sat on the critical path: prepare -&gt; first frame
     * was 342-404 ms cold vs 198-379 ms for warm-process card taps (Pixel 9, 2026-09-25).
     * Non-secure, non-tunneling: the keys the stock renderers use for this content. Ordered by how
     * likely a YouTube DASH answer is to carry the type (VP9 + Opus nearly always), because the
     * scan stops once a source is being prepared - see {@link #onPlaybackPreparing()}.
     */
    static final String[] DECODER_MIME_TYPES = {
            MimeTypes.VIDEO_VP9,
            MimeTypes.AUDIO_OPUS,
            MimeTypes.VIDEO_AV1,
            MimeTypes.VIDEO_H264,
            MimeTypes.AUDIO_AAC,
    };

    /** Set once the first source is handed to a player: see {@link #onPlaybackPreparing()}. */
    private static volatile boolean sPlaybackPreparing;

    private static PlayerInfrastructureWarmup sInstance;

    private final Executor mExecutor;
    private final Runnable mTransport;
    private final Runnable mCache;
    private final Runnable mCodecs;
    private final AtomicBoolean mStarted = new AtomicBoolean();

    PlayerInfrastructureWarmup(Executor executor, Runnable transport, Runnable cache) {
        this(executor, transport, cache, () -> { });
    }

    PlayerInfrastructureWarmup(Executor executor, Runnable transport, Runnable cache, Runnable codecs) {
        mExecutor = executor;
        mTransport = transport;
        mCache = cache;
        mCodecs = codecs;
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
            }, PlayerInfrastructureWarmup::warmDecoderInfos);
        }
        sInstance.schedule();
    }

    /**
     * Fills media3's process-wide decoder list cache (see {@link #DECODER_MIME_TYPES}). Read-only
     * queries: no codec instance is created, so no decoder resource is held. Best effort - a
     * failing query is swallowed by media3 and simply happens again, uncached, at first use.
     * Debug/benchmark A/B switch: {@code debug.arc.codec_warmup=off}.
     */
    static void warmDecoderInfos() {
        if ((BuildConfig.DEBUG || BuildConfig.BENCHMARK)
                && "off".equals(DebugMediaShaper.prop("debug.arc.codec_warmup"))) {
            return;
        }
        int warmed = warmDecoderInfos(DECODER_MIME_TYPES, () -> sPlaybackPreparing,
                mimeType -> MediaCodecUtil.warmDecoderInfoCache(mimeType, /* secure= */ false,
                        /* tunneling= */ false));
        if (warmed < DECODER_MIME_TYPES.length) {
            NetPath.log("player-warmup codecs stopped warmed=" + warmed + "/"
                    + DECODER_MIME_TYPES.length + " reason=playback-preparing");
        }
    }

    /** The scan loop, split out for tests: returns how many types were warmed before a stop. */
    static int warmDecoderInfos(String[] mimeTypes, java.util.function.BooleanSupplier stop,
            java.util.function.Consumer<String> warm) {
        int warmed = 0;
        for (String mimeType : mimeTypes) {
            if (stop.getAsBoolean()) {
                break;
            }
            warm.accept(mimeType);
            warmed++;
        }
        return warmed;
    }

    /**
     * NEWTUBE(codec-list): a player is about to prepare its first source. From here on the playback
     * thread queries exactly the types its manifest carries, and media3's
     * {@link MediaCodecUtil#getDecoderInfos} is one process-wide synchronized method: a warm-up
     * scan still running would make the playback thread wait for a type it may not even need (the
     * reviewed contention). So the scan stops before its next type; at most the one type already
     * in progress finishes, and its result is cached for whoever needs it.
     */
    public static void onPlaybackPreparing() {
        sPlaybackPreparing = true;
    }

    void schedule() {
        if (!mStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            mExecutor.execute(() -> {
                initialize("transport", mTransport);
                initialize("cache", mCache);
                // Last: the transport serves the very first /player of a cold share link, the
                // decoder list is needed only once that answer has been turned into a source.
                initialize("codecs", mCodecs);
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
