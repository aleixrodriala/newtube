package com.newtube.mobile.player;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DataSchemeDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cronet.CronetDataSource;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.DefaultDashChunkSource;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.cronet.CronetManager;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;

import org.chromium.net.CronetEngine;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Media3 counterpart of {@code ExoMediaSourceFactory}, reduced to the branches the touch player
 * actually uses:
 *
 * <ul>
 *   <li><b>DASH VOD</b> (the everyday path): the InnerTube formats are turned into an MPD by
 *       {@code MediaItemFormatInfo.createMpdStream()} (the same XML generator SmartTube shipped
 *       for years) and side-loaded through media3's stock parser - no network manifest. The
 *       legacy path's in-memory {@code DashManifestParser2} object graph is not needed.</li>
 *   <li><b>Live DASH / HLS</b> from the manifest URL - media3's own dynamic-manifest handling
 *       (refreshes, live window, behind-live-window recovery) replaces the legacy custom live
 *       parser.</li>
 *   <li><b>Progressive URL list</b> - legacy LQ fallback, first (=best) URL only, like before.</li>
 * </ul>
 *
 * <p>Networking: the media path rides H2/QUIC via the embedded Cronet engine when available
 * (matching what the legacy engine had), with media3 {@link DefaultHttpDataSource} as the
 * build-time fallback and per-request fallback factory. The shared singleton
 * {@link DefaultBandwidthMeter} is attached as transfer listener to the chosen leaf transport -
 * same "one meter feeds both the estimator and the track selector" wiring the legacy round added -
 * plus the on-disk {@link CacheDataSource} tier (stable YouTube cache keys, see
 * {@link Media3PlayerCache}) for immutable media. Live manifests bypass the cache, mirroring the
 * legacy rule.</p>
 */
public class Media3SourceFactory {

    private static final String TAG = Media3SourceFactory.class.getSimpleName();

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36";

    /** Fake base for side-loaded manifests; segment URLs inside the MPD are absolute. */
    private static final Uri GENERATED_MANIFEST_URI = Uri.parse("https://youtube.com/generated.mpd");

    /**
     * media3's default gives a segment 3 tries before erroring the whole source; googlevideo
     * routinely stalls/kills connections mid-segment when the client reads slowly (throttled
     * network, huge in-flight segment), and every retry re-opens with a Range from where it left
     * off, so patience converts those stalls into progress. 6 tries are kept, but
     * {@link FailFastLoadErrorPolicy} caps the retry backoff at 1s (stock policy stretches to 5s
     * per try = up to ~60s of silent in-player retrying) and gives a fatal HTTP code (403
     * expired/invalid signed URL, 416 unsatisfiable range - retrying the same DataSpec can't heal
     * either) only 2 tries before surfacing the error to the app-level reload, which re-fetches
     * fresh signed URLs within seconds. A deterministic 403/416 is surfaced after its first
     * response; replaying an identical signed URL + range only adds load and delays recovery.
     */
    private static final int LOAD_RETRY_COUNT = 6;

    /**
     * 4s read timeout (half the 8s default): a silently-stalled googlevideo read should fail fast
     * into the (1s-backoff) retry, which re-opens ranged from the stall point. Connect timeouts
     * stay at the 8s default.
     */
    private static final int READ_TIMEOUT_MS = 4_000;

    /**
     * DISABLED after on-device verification (v1.2.1 round): googlevideo PRIORITIZES the
     * {@code range=} query param over the {@code Range:} header and answers misaligned
     * (416, or 200-style bodies already offset from the requested position); CronetDataSource
     * then applies its own {@code position}-skip on top of the already-offset body, so the
     * extractor reads garbage AND TeeDataSource writes the misaligned bytes into the shared
     * SimpleCache (poisoned entries). A correct implementation must zero
     * {@code DataSpec.position} and drop the {@code Range:} header when using {@code range=}
     * (NewPipe's YoutubeHttpDataSource approach) - parked as a future experiment behind this
     * gate. Do NOT flip to {@code true} without on-device verification. With {@code false} the
     * leaf transport is used unwrapped - zero behavior change.
     */
    private static final boolean GOOGLEVIDEO_RANGE_QUERY = false;

    /** Request counter for the {@code rn=} param; shared across all rewritten requests. */
    private static final AtomicLong RANGE_QUERY_RN = new AtomicLong();

    /**
     * Process-wide executor for Cronet's async callbacks, reused across engine restarts (a
     * per-instance executor would leak: nothing ever shuts it down - the legacy engine's
     * {@code ExoMediaSourceFactory} had the same rule). 4 threads: DASH audio + DASH video alone
     * fill 2, and merged playback (dash-mpd+hls) runs 3+ concurrent loaders whose response
     * callbacks would serialize on a 2-thread pool. Cronet does its network I/O on its own
     * internal threads - this pool only runs the app-side callbacks, so the headroom is cheap.
     */
    private static final Executor CRONET_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread thread = new Thread(r, "Media3Cronet");
        thread.setDaemon(true);
        return thread;
    });

    // NEWTUBE(abr-seed): persist the bandwidth meter's EWMA estimate across process restarts so
    // the FIRST video of a cold session starts ABR from the last known real throughput instead of
    // media3's coarse country x network-type table (which under-picks on fast connections and
    // over-picks into an early rebuffer on slow ones). Clamped to sane bounds - a corrupt or
    // pathological persisted value falls back to the default table.
    private static final String NETWORK_PREFS_NAME = "newtube_network";
    private static final String KEY_BITRATE_ESTIMATE = "bw_estimate_bps";
    private static final long MIN_PERSISTED_BITRATE = 100_000;      // 100 kbps
    private static final long MAX_PERSISTED_BITRATE = 50_000_000;   // 50 Mbps

    /** Process-wide meter (replaces {@code DefaultBandwidthMeter.getSingletonInstance}), seeded once. */
    private static DefaultBandwidthMeter sBandwidthMeter;

    private static synchronized DefaultBandwidthMeter getOrCreateBandwidthMeter(Context context) {
        if (sBandwidthMeter == null) {
            DefaultBandwidthMeter.Builder builder = new DefaultBandwidthMeter.Builder(context);
            long saved = context.getSharedPreferences(NETWORK_PREFS_NAME, Context.MODE_PRIVATE)
                    .getLong(KEY_BITRATE_ESTIMATE, 0);
            if (saved >= MIN_PERSISTED_BITRATE && saved <= MAX_PERSISTED_BITRATE) {
                builder.setInitialBitrateEstimate(saved);
                Log.d(TAG, "bandwidth meter seeded with persisted estimate: " + saved + " bps");
            }
            sBandwidthMeter = builder.build();
        }
        return sBandwidthMeter;
    }

    /** Persist the current estimate (cheap, async apply); called on player teardown. */
    void persistBandwidthEstimate() {
        DefaultBandwidthMeter meter = mBandwidthMeter;
        if (meter == null) {
            return;
        }
        long estimate = meter.getBitrateEstimate();
        if (estimate >= MIN_PERSISTED_BITRATE && estimate <= MAX_PERSISTED_BITRATE) {
            mContext.getSharedPreferences(NETWORK_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_BITRATE_ESTIMATE, estimate).apply();
        }
    }

    private final Context mContext;
    private final DefaultBandwidthMeter mBandwidthMeter;
    private final DataSource.Factory mHttpDataSourceFactory;
    private final DataSource.Factory mCachedDataSourceFactory;

    Media3SourceFactory(Context context) {
        mContext = context.getApplicationContext();
        mBandwidthMeter = getOrCreateBandwidthMeter(mContext);

        DefaultHttpDataSource.Factory defaultHttp = new DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS)
                .setReadTimeoutMs(READ_TIMEOUT_MS)
                .setTransferListener(mBandwidthMeter);

        // Prefer Cronet (H2/QUIC/Brotli) whenever the embedded engine loads; it is only the leaf
        // HTTP transport - every cache tier above stays byte-identical. Cronet follows
        // http<->https redirects natively (no setAllowCrossProtocolRedirects equivalent needed).
        CronetEngine cronetEngine = CronetManager.getEngine(mContext);
        DataSource.Factory leafFactory;
        if (cronetEngine != null) {
            Log.d(TAG, "media transport: cronet");
            leafFactory = new CronetDataSource.Factory(cronetEngine, CRONET_EXECUTOR)
                    .setUserAgent(USER_AGENT)
                    .setTransferListener(mBandwidthMeter)
                    .setConnectionTimeoutMs(DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS)
                    .setReadTimeoutMs(READ_TIMEOUT_MS)
                    .setKeepPostFor302Redirects(true)
                    .setFallbackFactory(defaultHttp);
        } else {
            Log.d(TAG, "media transport: http (cronet unavailable)");
            leafFactory = defaultHttp;
        }

        // NEWTUBE(debug-shaper): runtime bandwidth/fault shaping for on-device experiments
        // (see DebugMediaShaper - the Pixel 9 is the dev Mac's uplink, so radio-level
        // throttling is off-limits, and the emulator's throttle stalls instead of shaping).
        // Debug builds only; inert while the debug.arc.* props are unset.
        if (BuildConfig.DEBUG) {
            leafFactory = new DebugMediaShaper.Factory(leafFactory);
        }

        // googlevideo range-query mirroring sits directly on the leaf transport, BELOW the cache
        // tier (CacheDataSource upstream = resolving(leaf)): the cache key factory reads only
        // id/itag/lmt/xtags/sq from the ORIGINAL uri, so cache keys never see rn=/range=.
        mHttpDataSourceFactory = GOOGLEVIDEO_RANGE_QUERY
                ? new ResolvingDataSource.Factory(leafFactory, Media3SourceFactory::mirrorRangeIntoQuery)
                : leafFactory;

        Cache mediaCache = Media3PlayerCache.get(mContext);
        if (mediaCache != null) {
            mCachedDataSourceFactory = new CacheDataSource.Factory()
                    .setCache(mediaCache)
                    .setCacheKeyFactory(Media3PlayerCache.getCacheKeyFactory())
                    .setUpstreamDataSourceFactory(mHttpDataSourceFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        } else {
            mCachedDataSourceFactory = mHttpDataSourceFactory;
        }
    }

    public DefaultBandwidthMeter getBandwidthMeter() {
        return mBandwidthMeter;
    }

    /** DASH VOD from InnerTube formats via the generated MPD. Null if the manifest can't be built. */
    @Nullable
    MediaSource fromDashFormatInfo(MediaItemFormatInfo formatInfo) {
        return fromDashManifest(formatInfo.createMpdStream(), formatInfo.isLive());
    }

    /** Legacy entry point (PlayerEngine.openDash(InputStream)); treated as VOD normalization. */
    @Nullable
    MediaSource fromDashManifest(InputStream dashManifest) {
        return fromDashManifest(dashManifest, /* isLive= */ false);
    }

    /**
     * DASH from a generated MPD stream. VOD is side-loaded static and rides the cache. A LIVE
     * video only lands here as a last resort (no dash/hls manifest url at all - the dispatch in
     * {@code VideoLoaderController} routes live to the URL paths first): its dynamic manifest must
     * NOT be forced static (that produced a fake ~48h static window that ended playback
     * instantly), and media3 rejects side-loaded dynamic manifests outright
     * ({@code DashMediaSource.Factory.createMediaSource(manifest, item)} checkArguments
     * {@code !manifest.dynamic}) - so the dynamic manifest is handed over as a {@code data:} URI,
     * which media3 treats as a URL-loaded dynamic manifest (real live window). It cannot refresh
     * beyond its snapshot (the bytes are fixed), so it stays a last resort.
     */
    @Nullable
    MediaSource fromDashManifest(InputStream dashManifest, boolean isLive) {
        if (dashManifest == null) {
            return null;
        }

        if (isLive) {
            return fromLiveDashManifest(dashManifest);
        }

        StaticDashManifestParser parser = new StaticDashManifestParser();
        DashManifest manifest;
        try {
            manifest = parser.parse(GENERATED_MANIFEST_URI, dashManifest);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "fromDashManifest: can't parse generated mpd: " + e);
            return null;
        }

        // "Live media bypasses the cache" applies to SIDE-LOADED manifests too: the generated MPD
        // (YouTubeMPDBuilder) declares type="dynamic" whenever the FORMATS are live media
        // (yt_live_broadcast / live=1 urls) - which includes PAST live streams whose formatInfo is
        // no longer live. For those the parser's static forcing IS the VOD normalization, and the
        // recorded original flag routes their sq-addressed segments off the cache. The sq-aware
        // cache key (Media3PlayerCache) already keeps such segments apart - this routing makes
        // that belt-and-braces, and spares the LRU cache segments that would never be re-watched.
        // Static VOD keeps the cached tier.
        DataSource.Factory chunkDataSourceFactory =
                parser.wasDynamic() ? mHttpDataSourceFactory : mCachedDataSourceFactory;

        return new DashMediaSource.Factory(
                        new DefaultDashChunkSource.Factory(chunkDataSourceFactory),
                        /* manifestDataSourceFactory= */ null)
                .setLoadErrorHandlingPolicy(new FailFastLoadErrorPolicy())
                .createMediaSource(manifest, new MediaItem.Builder()
                        .setUri(GENERATED_MANIFEST_URI)
                        .setMimeType(MimeTypes.APPLICATION_MPD)
                        .build());
    }

    /**
     * LAST-RESORT live path (see {@link #fromDashManifest(InputStream, boolean)}): serve the
     * generated dynamic MPD through a {@code data:} URI so media3 owns it as a URL-loaded dynamic
     * manifest (live window, live-edge positioning) instead of a forbidden dynamic side-load or a
     * broken forced-static one. Uncached transport: live segments are a moving edge. Segment URLs
     * inside the generated MPD are absolute, so the fake base URI never matters.
     */
    @Nullable
    private MediaSource fromLiveDashManifest(InputStream dashManifest) {
        byte[] manifestBytes;
        try {
            manifestBytes = readAllBytes(dashManifest);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "fromLiveDashManifest: can't read generated mpd: " + e);
            return null;
        }

        Log.d(TAG, "fromLiveDashManifest: last-resort live mpd (" + manifestBytes.length + " bytes)");

        Uri dataUri = Uri.parse("data:" + MimeTypes.APPLICATION_MPD + ";base64,"
                + android.util.Base64.encodeToString(manifestBytes, android.util.Base64.NO_WRAP));

        return new DashMediaSource.Factory(
                        new DefaultDashChunkSource.Factory(mHttpDataSourceFactory),
                        /* manifestDataSourceFactory= */ DataSchemeDataSource::new)
                .setLoadErrorHandlingPolicy(new FailFastLoadErrorPolicy())
                .createMediaSource(new MediaItem.Builder()
                        .setUri(dataUri)
                        .setMimeType(MimeTypes.APPLICATION_MPD)
                        .build());
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(64 * 1024);
        byte[] chunk = new byte[8 * 1024];
        int read;
        while ((read = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /**
     * Live (or post-live DVR) DASH from the manifest URL. media3 handles the dynamic-manifest
     * mechanics (refreshes, live window), but YouTube live MPDs need the app-level
     * {@link LiveDashManifestParser} (ported from the legacy TV engine) for a sane, monotonically
     * growing DVR window - the stock parser yields a negative window duration (dead timebar, no
     * DVR scrubbing). Don't make the parser static/shared: it retains per-stream state and needs a
     * reset for each live source (same rule as the legacy wiring).
     */
    MediaSource fromDashManifestUrl(String dashManifestUrl) {
        if (BuildConfig.DEBUG) {
            // Full (un-truncated) manifest url so the exact MPD the app got can be curl'ed.
            NetPath.log("dash-url-full " + dashManifestUrl);
        }

        return new DashMediaSource.Factory(
                        new DefaultDashChunkSource.Factory(mHttpDataSourceFactory),
                        mHttpDataSourceFactory)
                .setManifestParser(new LiveDashManifestParser())
                .setLoadErrorHandlingPolicy(new FailFastLoadErrorPolicy())
                .createMediaSource(new MediaItem.Builder()
                        .setUri(dashManifestUrl)
                        .setMimeType(MimeTypes.APPLICATION_MPD)
                        .build());
    }

    /** Live HLS from the master playlist URL. */
    MediaSource fromHlsPlaylist(String hlsPlaylistUrl) {
        return new HlsMediaSource.Factory(mHttpDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(new FailFastLoadErrorPolicy())
                .createMediaSource(new MediaItem.Builder()
                        .setUri(hlsPlaylistUrl)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build());
    }

    /** Legacy LQ fallback: progressive playback of the first (best) muxed URL. */
    @Nullable
    MediaSource fromUrlList(List<String> urlList) {
        if (urlList == null || urlList.isEmpty()) {
            return null;
        }

        return new ProgressiveMediaSource.Factory(mCachedDataSourceFactory)
                .setLoadErrorHandlingPolicy(new FailFastLoadErrorPolicy())
                .createMediaSource(MediaItem.fromUri(urlList.get(0)));
    }

    /** DASH (generated) + extended-quality HLS in one source. */
    @Nullable
    MediaSource fromMerged(MediaItemFormatInfo formatInfo, String hlsPlaylistUrl) {
        MediaSource dash = fromDashFormatInfo(formatInfo);
        if (dash == null) {
            return fromHlsPlaylist(hlsPlaylistUrl);
        }
        return new MergingMediaSource(dash, fromHlsPlaylist(hlsPlaylistUrl));
    }

    @Nullable
    MediaSource fromMerged(InputStream dashManifest, String hlsPlaylistUrl) {
        MediaSource dash = fromDashManifest(dashManifest);
        if (dash == null) {
            return fromHlsPlaylist(hlsPlaylistUrl);
        }
        return new MergingMediaSource(dash, fromHlsPlaylist(hlsPlaylistUrl));
    }

    /**
     * {@link ResolvingDataSource.Resolver} for {@link #GOOGLEVIDEO_RANGE_QUERY} - PARKED, gate is
     * off. As written this keeps {@code DataSpec.position}/{@code length} (the {@code Range:}
     * header still goes out alongside {@code range=}), and on-device verification showed
     * googlevideo prioritizes the {@code range=} query over the header and replies misaligned
     * (416 / 200-with-offset-body) while CronetDataSource still re-skips {@code position} bytes -
     * garbage into the extractor and poisoned cache writes. Before this gate is ever re-enabled,
     * the resolver must zero the position and suppress the Range header (NewPipe's
     * YoutubeHttpDataSource approach). Live/OTF sq-addressed segments are whole-resource
     * per-segment fetches and are left alone.
     */
    private static DataSpec mirrorRangeIntoQuery(DataSpec dataSpec) {
        if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET) {
            return dataSpec;
        }

        Uri uri = dataSpec.uri;
        String host = uri.getHost();
        String path = uri.getPath();
        if (host == null || !host.endsWith(".googlevideo.com")
                || path == null || !path.startsWith("/videoplayback")) {
            return dataSpec;
        }

        // Already range-addressed, or sq-addressed (live/OTF): leave untouched.
        if (uri.getQueryParameter("range") != null) {
            return dataSpec;
        }
        if (uri.getQueryParameter("sq") != null || path.contains("/sq/")) {
            return dataSpec;
        }

        // Whole-file position-0 unbounded requests stay untouched.
        if (dataSpec.position <= 0 && dataSpec.length == C.LENGTH_UNSET) {
            return dataSpec;
        }

        String range = dataSpec.length != C.LENGTH_UNSET
                ? dataSpec.position + "-" + (dataSpec.position + dataSpec.length - 1)
                : dataSpec.position + "-";
        long rn = RANGE_QUERY_RN.incrementAndGet();
        Uri rewritten = uri.buildUpon()
                .appendQueryParameter("rn", String.valueOf(rn))
                .appendQueryParameter("range", range)
                .build();

        if (rn == 1) {
            Log.d(TAG, "range-query rewrite active: " + rewritten);
        }

        return dataSpec.withUri(rewritten);
    }

    /**
     * {@link DefaultLoadErrorHandlingPolicy} ({@link #LOAD_RETRY_COUNT} tries) that fails fast:
     * retry backoff capped at 1s (the stock (errorCount-1)*1000-capped-5000 stretches a dead
     * connection into ~60s of silent retrying), and a fatal HTTP code (403/416, see
     * {@link #isFatalHttpCode}) stops after its first rejection so {@code onPlayerError} surfaces and
     * {@code ErrorFixerController.applyNoPlaybackFix()}+{@code reloadVideo()} re-fetches fresh
     * signed URLs within seconds.
     */
    private static final class FailFastLoadErrorPolicy extends DefaultLoadErrorHandlingPolicy {
        FailFastLoadErrorPolicy() {
            super(LOAD_RETRY_COUNT);
        }

        @Override
        public long getRetryDelayMsFor(LoadErrorHandlingPolicy.LoadErrorInfo loadErrorInfo) {
            if (isFatalHttpCode(loadErrorInfo.exception)) {
                return C.TIME_UNSET; // don't retry: surface the error to the app-level reload
            }
            return Math.min(super.getRetryDelayMsFor(loadErrorInfo), 1000);
        }

        /**
         * 403: expired/invalid signed URL - no retry can heal it. 416: the server refuses the
         * requested byte range - retrying the SAME DataSpec can never heal it either; failing
         * fast surfaces {@code onPlayerError} so the app-level reload fetches a fresh manifest.
         */
        private static boolean isFatalHttpCode(Throwable exception) {
            for (Throwable e = exception; e != null; e = e.getCause()) {
                if (e instanceof HttpDataSource.InvalidResponseCodeException) {
                    int code = ((HttpDataSource.InvalidResponseCodeException) e).responseCode;
                    if (code == 403 || code == 416) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * The generated MPD describes finished VOD but has no explicit {@code static} marker media3
     * trusts; force non-dynamic so the timeline gets a fixed duration (same trick as the legacy
     * {@code StaticDashManifestParser}). The original {@code dynamic} flag is recorded first -
     * it's how {@link #fromDashManifest} tells a side-loaded LIVE manifest from VOD.
     */
    private static class StaticDashManifestParser extends DashManifestParser {
        private boolean mWasDynamic;

        /** Whether the source manifest declared {@code type="dynamic"} (live), pre-forcing. */
        boolean wasDynamic() {
            return mWasDynamic;
        }

        @Override
        protected DashManifest buildMediaPresentationDescription(
                long availabilityStartTime,
                long durationMs,
                long minBufferTimeMs,
                boolean dynamic,
                long minUpdateTimeMs,
                long timeShiftBufferDepthMs,
                long suggestedPresentationDelayMs,
                long publishTimeMs,
                @Nullable androidx.media3.exoplayer.dash.manifest.ProgramInformation programInformation,
                @Nullable androidx.media3.exoplayer.dash.manifest.UtcTimingElement utcTiming,
                @Nullable androidx.media3.exoplayer.dash.manifest.ServiceDescriptionElement serviceDescription,
                @Nullable Uri location,
                List<androidx.media3.exoplayer.dash.manifest.Period> periods) {
            mWasDynamic |= dynamic;
            return super.buildMediaPresentationDescription(
                    availabilityStartTime,
                    durationMs,
                    minBufferTimeMs,
                    /* dynamic= */ false,
                    minUpdateTimeMs,
                    timeShiftBufferDepthMs,
                    suggestedPresentationDelayMs,
                    publishTimeMs,
                    programInformation,
                    utcTiming,
                    serviceDescription,
                    location,
                    periods);
        }
    }
}
