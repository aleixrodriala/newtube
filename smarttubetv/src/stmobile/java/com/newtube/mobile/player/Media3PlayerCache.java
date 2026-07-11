package com.newtube.mobile.player;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheKeyFactory;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.File;
import java.util.List;

/**
 * Process-wide on-disk media cache for the Media3 touch player: one bounded LRU
 * {@link SimpleCache} with a database-backed index, living in its OWN directory - a
 * {@code SimpleCache} folder can only ever be opened by one cache implementation. (The legacy
 * vendored-ExoPlayer cache, {@code MobilePlayerCache}, used a separate directory and was deleted
 * with that engine.)
 *
 * <p>{@link #CACHE_KEY_FACTORY} replaces the legacy stable-key patch that lived in the vendored
 * {@code DashManifestParser2}: googlevideo media URLs are signed and expire, so the default
 * URL-as-key would orphan every cached range on the next InnerTube refresh. Keying on the stable
 * identifying params (video id + itag + last-modified + audio variant) lets cached ranges survive
 * signed-URL refreshes exactly like the legacy fix did. Range-request fragmentation needs no
 * equivalent here: media3 keys are range-free by design.</p>
 */
public final class Media3PlayerCache {

    private static final String TAG = Media3PlayerCache.class.getSimpleName();

    private static final String CACHE_DIR_NAME = "media3-media-cache";

    /** Upper bound for the on-disk cache (LRU-evicted); mirrors the legacy mobile cache budget. */
    private static final long MAX_CACHE_BYTES = 256L * 1024 * 1024; // 256 MB

    private static SimpleCache sCache;

    /**
     * Stable cache keys for googlevideo media: {@code id} (video) + {@code itag} (format) +
     * {@code lmt} (upload revision) + {@code xtags} (audio language/DRC variant) uniquely identify
     * the immutable media bytes; everything else in the URL (host shard, {@code expire},
     * {@code sig}, client ip...) is volatile. Non-googlevideo URLs keep the default URI key.
     */
    private static final CacheKeyFactory CACHE_KEY_FACTORY = (DataSpec dataSpec) -> {
        Uri uri = dataSpec.uri;
        String id = uri.getQueryParameter("id");
        String itag = uri.getQueryParameter("itag");

        if (id == null || itag == null) {
            return dataSpec.key != null ? dataSpec.key : uri.toString();
        }

        String lmt = uri.getQueryParameter("lmt");
        String xtags = uri.getQueryParameter("xtags");
        String key = "yt." + id + "." + itag + "." + (lmt != null ? lmt : "") + "." + (xtags != null ? xtags : "");

        // Segment addressing distinction: range-addressed VOD (SegmentBase) reuses ONE URL for the
        // whole stream - byte ranges tell the pieces apart, so the stable key alone is correct (and
        // must stay byte-identical: warm caches out there key on it). Live/OTF DASH instead gives
        // every segment its OWN URL via `sq` (query param `?sq=N` or path form `/sq/N/`), each
        // starting at position 0 - without an sq suffix they'd all collapse into one cache resource
        // and segment N's cached bytes would be served for N+1.
        String sq = uri.getQueryParameter("sq");
        if (sq == null) {
            List<String> pathSegments = uri.getPathSegments();
            int sqIndex = pathSegments.indexOf("sq");
            if (sqIndex >= 0 && sqIndex + 1 < pathSegments.size()
                    && TextUtils.isDigitsOnly(pathSegments.get(sqIndex + 1))) {
                sq = pathSegments.get(sqIndex + 1);
            }
        }

        return sq != null ? key + ".sq" + sq : key;
    };

    private Media3PlayerCache() {
    }

    public static CacheKeyFactory getCacheKeyFactory() {
        return CACHE_KEY_FACTORY;
    }

    /**
     * Lazily creates (once) and returns the shared media cache, or {@code null} if it could not be
     * created (the player then simply runs without a disk cache).
     */
    @Nullable
    public static synchronized Cache get(Context context) {
        if (sCache == null) {
            try {
                File cacheDir = new File(context.getApplicationContext().getCacheDir(), CACHE_DIR_NAME);
                sCache = new SimpleCache(
                        cacheDir,
                        new LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                        new StandaloneDatabaseProvider(context.getApplicationContext()));
            } catch (Throwable e) {
                Log.e(TAG, "Failed to create media cache: " + e.getMessage());
            }
        }

        return sCache;
    }
}
