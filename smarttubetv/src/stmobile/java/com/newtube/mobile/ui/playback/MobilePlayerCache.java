package com.newtube.mobile.ui.playback;

import android.content.Context;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.database.ExoDatabaseProvider;
import com.google.android.exoplayer2.upstream.cache.Cache;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.File;

/**
 * Process-wide on-disk media cache for the touch player (CACHING/SEEK FIX).
 *
 * <p>The reused SmartTube player has no media cache at all: {@code ExoMediaSourceFactory} builds a
 * plain {@code DefaultDataSourceFactory} over HTTP, and the only thing retained behind the playhead
 * was the in-memory back-buffer configured by {@code ExoPlayerInitializer}. The mobile flavor now
 * widens that buffer to two minutes, but a farther backward seek would still re-buffer and
 * re-download the same ranges without this disk tier.</p>
 *
 * <p>This holds a single {@link SimpleCache} (bounded LRU under the app's internal cache dir) that
 * {@code MobileMainApplication} registers with {@link
 * com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory#setMediaCache(Cache)}. Once
 * installed, progressive and DASH media plus SmoothStreaming segments are served through a
 * {@code CacheDataSource}, so any backward seek to already-downloaded bytes is read from disk
 * instead of the network. HLS and live manifests deliberately bypass it because this ExoPlayer
 * version uses one data-source factory for both mutable playlists and segments. Memory stays
 * bounded: the cache lives on disk and the LRU evictor caps it at {@link #MAX_CACHE_BYTES}.</p>
 *
 * <p>Must be a singleton per directory: {@link SimpleCache} locks its folder and throws if a second
 * instance is created for the same directory in the same process, hence the static holder here.</p>
 */
public final class MobilePlayerCache {

    private static final String TAG = MobilePlayerCache.class.getSimpleName();

    /** Sub-directory of the internal cache dir dedicated to the ExoPlayer media cache. */
    private static final String CACHE_DIR_NAME = "exo-media-cache";

    /** Upper bound for the on-disk cache (LRU-evicted). A few hundred MB is plenty for seek-back. */
    private static final long MAX_CACHE_BYTES = 256L * 1024 * 1024; // 256 MB

    private static SimpleCache sCache;
    private static ExoDatabaseProvider sDatabaseProvider;

    private MobilePlayerCache() {
    }

    /**
     * Lazily creates (once) and returns the shared media cache, or {@code null} if it could not be
     * created (in which case the player simply falls back to the previous no-cache behavior).
     */
    @Nullable
    public static synchronized Cache get(Context context) {
        if (sCache == null) {
            try {
                File cacheDir = new File(context.getApplicationContext().getCacheDir(), CACHE_DIR_NAME);
                // The database-backed index avoids renaming/touching cache files on every read and
                // makes startup scale much better once the cache contains hundreds of spans.
                sDatabaseProvider = new ExoDatabaseProvider(context.getApplicationContext());
                sCache = new SimpleCache(
                        cacheDir,
                        new LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                        sDatabaseProvider);
            } catch (Throwable e) {
                // e.g. folder already locked by another SimpleCache, or no free space. Non-fatal:
                // playback continues without a disk cache (original behavior).
                Log.e(TAG, "Failed to create media cache: " + e.getMessage());
            }
        }

        return sCache;
    }
}
