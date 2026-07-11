package com.newtube.mobile.player;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
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

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.cronet.CronetManager;
import com.liskovsoft.sharedutils.mylogger.Log;

import org.chromium.net.CronetEngine;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

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
     * off, so patience converts those stalls into progress. 6 tries before surfacing the error to
     * the app-level reload - the legacy engine's custom Dash policy was similarly forgiving.
     */
    private static final int LOAD_RETRY_COUNT = 6;

    /**
     * Process-wide executor for Cronet's async callbacks, reused across engine restarts (a
     * per-instance executor would leak: nothing ever shuts it down - the legacy engine's
     * {@code ExoMediaSourceFactory} had the same rule). 2 threads so audio+video segment
     * callbacks make progress together.
     */
    private static final Executor CRONET_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "Media3Cronet");
        thread.setDaemon(true);
        return thread;
    });

    private final Context mContext;
    private final DefaultBandwidthMeter mBandwidthMeter;
    private final DataSource.Factory mHttpDataSourceFactory;
    private final DataSource.Factory mCachedDataSourceFactory;

    Media3SourceFactory(Context context) {
        mContext = context.getApplicationContext();
        mBandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(mContext);

        DefaultHttpDataSource.Factory defaultHttp = new DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS)
                .setReadTimeoutMs(DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS)
                .setTransferListener(mBandwidthMeter);

        // Prefer Cronet (H2/QUIC/Brotli) whenever the embedded engine loads; it is only the leaf
        // HTTP transport - every cache tier above stays byte-identical. Cronet follows
        // http<->https redirects natively (no setAllowCrossProtocolRedirects equivalent needed).
        CronetEngine cronetEngine = CronetManager.getEngine(mContext);
        if (cronetEngine != null) {
            Log.d(TAG, "media transport: cronet");
            mHttpDataSourceFactory = new CronetDataSource.Factory(cronetEngine, CRONET_EXECUTOR)
                    .setUserAgent(USER_AGENT)
                    .setTransferListener(mBandwidthMeter)
                    .setConnectionTimeoutMs(DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS)
                    .setReadTimeoutMs(DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS)
                    .setKeepPostFor302Redirects(true)
                    .setFallbackFactory(defaultHttp);
        } else {
            Log.d(TAG, "media transport: http (cronet unavailable)");
            mHttpDataSourceFactory = defaultHttp;
        }

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
        return fromDashManifest(formatInfo.createMpdStream());
    }

    /** DASH VOD from an MPD stream (side-loaded, static). */
    @Nullable
    MediaSource fromDashManifest(InputStream dashManifest) {
        if (dashManifest == null) {
            return null;
        }

        DashManifest manifest;
        try {
            manifest = new StaticDashManifestParser().parse(GENERATED_MANIFEST_URI, dashManifest);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "fromDashManifest: can't parse generated mpd: " + e);
            return null;
        }

        return new DashMediaSource.Factory(
                        new DefaultDashChunkSource.Factory(mCachedDataSourceFactory),
                        /* manifestDataSourceFactory= */ null)
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(LOAD_RETRY_COUNT))
                .createMediaSource(manifest, new MediaItem.Builder()
                        .setUri(GENERATED_MANIFEST_URI)
                        .setMimeType(MimeTypes.APPLICATION_MPD)
                        .build());
    }

    /** Live (or post-live DVR) DASH from the manifest URL; media3 handles the dynamic manifest. */
    MediaSource fromDashManifestUrl(String dashManifestUrl) {
        return new DashMediaSource.Factory(
                        new DefaultDashChunkSource.Factory(mHttpDataSourceFactory),
                        mHttpDataSourceFactory)
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(LOAD_RETRY_COUNT))
                .createMediaSource(new MediaItem.Builder()
                        .setUri(dashManifestUrl)
                        .setMimeType(MimeTypes.APPLICATION_MPD)
                        .build());
    }

    /** Live HLS from the master playlist URL. */
    MediaSource fromHlsPlaylist(String hlsPlaylistUrl) {
        return new HlsMediaSource.Factory(mHttpDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(LOAD_RETRY_COUNT))
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
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(LOAD_RETRY_COUNT))
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
     * The generated MPD describes finished VOD but has no explicit {@code static} marker media3
     * trusts; force non-dynamic so the timeline gets a fixed duration (same trick as the legacy
     * {@code StaticDashManifestParser}).
     */
    private static class StaticDashManifestParser extends DashManifestParser {
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
