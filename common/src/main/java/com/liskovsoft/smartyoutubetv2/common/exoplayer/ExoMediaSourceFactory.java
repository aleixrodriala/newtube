package com.liskovsoft.smartyoutubetv2.common.exoplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ext.cronet.CronetDataSourceFactory;
import com.google.android.exoplayer2.ext.cronet.CronetEngineWrapper;
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSourceFactory;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.dash.DashChunkSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.dash.manifest.DashManifest;
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser;
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser2;
import com.google.android.exoplayer2.source.dash.manifest.Period;
import com.google.android.exoplayer2.source.dash.manifest.ProgramInformation;
import com.google.android.exoplayer2.source.dash.manifest.UtcTimingElement;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.sabr.DefaultSabrChunkSource;
import com.google.android.exoplayer2.source.sabr.SabrChunkSource;
import com.google.android.exoplayer2.source.sabr.SabrMediaSource;
import com.google.android.exoplayer2.source.sabr.manifest.SabrManifest;
import com.google.android.exoplayer2.source.sabr.manifest.SabrManifestParser;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSource.Factory;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource.BaseFactory;
import com.google.android.exoplayer2.upstream.cache.Cache;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.CacheDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.cronet.CronetManager;
import com.liskovsoft.sharedutils.helpers.FileHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.errors.DashDefaultLoadErrorHandlingPolicy;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.errors.SabrDefaultLoadErrorHandlingPolicy;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.errors.TrackErrorFixer;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ExoMediaSourceFactory {
    private static final String TAG = ExoMediaSourceFactory.class.getSimpleName();
    @SuppressLint("StaticFieldLeak")
    //private static ExoMediaSourceFactory sInstance;
    private static final int MAX_SEGMENTS_PER_LOAD = 1; // default - 1 (1-5)
    private static final String USER_AGENT = DefaultHeaders.APP_USER_AGENT;
    @SuppressLint("StaticFieldLeak")
    private static volatile DefaultBandwidthMeter sBandwidthMeter;
    private final Context mContext;
    private static final Uri DASH_MANIFEST_URI = Uri.parse("https://example.com/test.mpd");
    private static final String DASH_MANIFEST_EXTENSION = "mpd";
    private static final String HLS_PLAYLIST_EXTENSION = "m3u8";
    // Feed the same meter into both the network data sources and ExoPlayer's track selector. Using
    // two meters (or not attaching a listener here) leaves AdaptiveTrackSelection at its initial
    // estimate forever, so "Auto" cannot down-switch when throughput drops.
    private static final boolean USE_BANDWIDTH_METER = true;
    private static final AtomicInteger CRONET_THREAD_ID = new AtomicInteger();
    // A process-wide callback pool avoids leaking one never-shutdown executor each time the player
    // engine is recreated. Two threads let audio and video Cronet callbacks make progress together.
    private static final Executor CRONET_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "ExoPlayer-Cronet-" + CRONET_THREAD_ID.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private TrackErrorFixer mTrackErrorFixer;
    private Factory mMediaDataSourceFactory;
    private Factory mUncachedMediaDataSourceFactory;

    // NEWTUBE(mobile-cache): optional on-disk media cache, enabled only by the touch (stmobile)
    // flavor via setMediaCache() from MobileMainApplication. When non-null, GET-based media
    // sources (progressive / static DASH / SmoothStreaming segments) are served through a CacheDataSource
    // so already-downloaded bytes are read back from disk on a backward seek instead of being
    // re-downloaded from the network. It stays null on TV builds, which retain the plain,
    // non-caching media data-source path.
    // NOTE: SABR is deliberately NOT cached (see getSabrChunkSourceFactory) because it streams via
    // HTTP POST request bodies that CacheDataSource can neither key nor replay.
    private static volatile Cache sMediaCache;

    public ExoMediaSourceFactory(Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * Returns the process-wide bandwidth meter shared by ExoPlayer and every media data source.
     * The context-aware builder provides a useful network-specific initial estimate and resets the
     * estimate when Android reports a network-type change.
     */
    public static synchronized DefaultBandwidthMeter getBandwidthMeter(Context context) {
        if (sBandwidthMeter == null) {
            sBandwidthMeter = new DefaultBandwidthMeter.Builder(context.getApplicationContext()).build();
        }

        return sBandwidthMeter;
    }

    /**
     * NEWTUBE(mobile-cache): install a process-wide {@link Cache} used to persist already-downloaded
     * media segments to disk. Must be a singleton per cache directory (SimpleCache locks the folder).
     * Call once (e.g. from the Application) before any player is created. Pass {@code null} to disable.
     */
    public static void setMediaCache(Cache cache) {
        sMediaCache = cache;
    }

    public MediaSource fromSabrFormatInfo(MediaItemFormatInfo formatInfo) {
        return buildSabrMediaSource(formatInfo);
    }

    public MediaSource fromDashFormatInfo(MediaItemFormatInfo formatInfo) {
        return buildDashMediaSource(formatInfo);
    }

    public MediaSource fromDashManifest(InputStream dashManifest) {
        return buildMPDMediaSource(DASH_MANIFEST_URI, dashManifest);
    }

    public MediaSource fromDashManifestUrl(String dashManifestUrl) {
        return buildMediaSource(Uri.parse(dashManifestUrl), DASH_MANIFEST_EXTENSION);
    }

    public MediaSource fromHlsPlaylist(String hlsPlaylist) {
        return buildMediaSource(Uri.parse(hlsPlaylist), HLS_PLAYLIST_EXTENSION);
    }

    public MediaSource fromUrlList(List<String> urlList) {
        MediaSource[] mediaSources = new MediaSource[urlList.size()];

        for (int i = 0; i < urlList.size(); i++) {
            mediaSources[i] = buildMediaSource(Uri.parse(urlList.get(i)), null);
        }

        //return mediaSources.length == 1 ? mediaSources[0] : new ConcatenatingMediaSource(mediaSources); // or playlist
        return mediaSources[0]; // item with max resolution
    }

    /**
     * Returns a new DataSource factory.
     *
     * @param useBandwidthMeter Whether to set {@link #getBandwidthMeter(Context)} as a listener to
     *                          the new DataSource factory.
     * @return A new DataSource factory.
     */
    private DataSource.Factory buildDataSourceFactory(boolean useBandwidthMeter) {
        DefaultBandwidthMeter bandwidthMeter = useBandwidthMeter ? getBandwidthMeter(mContext) : null;
        return new DefaultDataSourceFactory(mContext, bandwidthMeter, buildHttpDataSourceFactory(useBandwidthMeter));
    }

    /**
     * Returns a new HttpDataSource factory.
     *
     * @param useBandwidthMeter Whether to set {@link #getBandwidthMeter(Context)} as a listener to
     *                          the new DataSource factory.
     * @return A new HttpDataSource factory.
     */
    private HttpDataSource.Factory buildHttpDataSourceFactory(boolean useBandwidthMeter) {
        PlayerTweaksData tweaksData = PlayerTweaksData.instance(mContext);
        int source = tweaksData.getPlayerDataSource();
        DefaultBandwidthMeter bandwidthMeter = useBandwidthMeter ? getBandwidthMeter(mContext) : null;
        return source == PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP ? buildOkHttpDataSourceFactory(bandwidthMeter) :
                        source == PlayerTweaksData.PLAYER_DATA_SOURCE_CRONET && CronetManager.getEngine(mContext) != null ? buildCronetDataSourceFactory(bandwidthMeter) :
                                buildDefaultHttpDataSourceFactory(bandwidthMeter);
    }

    @SuppressWarnings("deprecation")
    private MediaSource buildMediaSource(Uri uri, String overrideExtension) {
        int type = TextUtils.isEmpty(overrideExtension) ? Util.inferContentType(uri) : Util.inferContentType("." + overrideExtension);
        switch (type) {
            case C.TYPE_SS:
                SsMediaSource ssSource =
                        new SsMediaSource.Factory(
                                getSsChunkSourceFactory(),
                                getNonCachedMediaDataSourceFactory() // NEWTUBE(mobile-cache): manifest must not be cached
                        )
                                .createMediaSource(uri);
                if (mTrackErrorFixer != null) {
                    ssSource.addEventListener(Utils.sHandler, mTrackErrorFixer);
                }
                return ssSource;
            case C.TYPE_DASH:
                DashMediaSource dashSource =
                        new DashMediaSource.Factory(
                                getDashChunkSourceFactory(),
                                getNonCachedMediaDataSourceFactory() // NEWTUBE(mobile-cache): live/dynamic manifest must not be cached
                        )
                                .setManifestParser(new LiveDashManifestParser()) // Don't make static! Need state reset for each live source.
                                .setLoadErrorHandlingPolicy(new DashDefaultLoadErrorHandlingPolicy())
                                .createMediaSource(uri);
                if (mTrackErrorFixer != null) {
                    dashSource.addEventListener(Utils.sHandler, mTrackErrorFixer);
                }
                return dashSource;
            case C.TYPE_HLS:
                // NEWTUBE(mobile-cache): HlsMediaSource uses one factory for BOTH the (live-refreshed)
                // playlist and the segments, so it must bypass the cache to keep live HLS correct.
                HlsMediaSource hlsSource = new HlsMediaSource.Factory(getNonCachedMediaDataSourceFactory()).createMediaSource(uri);
                if (mTrackErrorFixer != null) {
                    hlsSource.addEventListener(Utils.sHandler, mTrackErrorFixer);
                }
                return hlsSource;
            case C.TYPE_OTHER:
                ExtractorMediaSource extractorSource = new ExtractorMediaSource.Factory(getMediaDataSourceFactory())
                        .setExtractorsFactory(new DefaultExtractorsFactory())
                        .createMediaSource(uri);
                if (mTrackErrorFixer != null) {
                    extractorSource.addEventListener(Utils.sHandler, mTrackErrorFixer);
                }
                return extractorSource;
            default: {
                throw new IllegalStateException("Unsupported type: " + type);
            }
        }
    }

    private MediaSource buildSabrMediaSource(MediaItemFormatInfo formatInfo) {
        // Are you using FrameworkSampleSource or ExtractorSampleSource when you build your player?
        SabrMediaSource sabrSource = new SabrMediaSource.Factory(
                getSabrChunkSourceFactory(),
                null
        )
                .setLoadErrorHandlingPolicy(new SabrDefaultLoadErrorHandlingPolicy())
                .createMediaSource(getSabrManifest(formatInfo));
        if (mTrackErrorFixer != null) {
            sabrSource.addEventListener(Utils.sHandler, mTrackErrorFixer);
        }
        return sabrSource;
    }

    private MediaSource buildDashMediaSource(MediaItemFormatInfo formatInfo) {
        // Are you using FrameworkSampleSource or ExtractorSampleSource when you build your player?
        DashMediaSource dashSource = new DashMediaSource.Factory(
                getDashChunkSourceFactory(),
                null
        )
                .setLoadErrorHandlingPolicy(new DashDefaultLoadErrorHandlingPolicy())
                .createMediaSource(getManifest(formatInfo));
        if (mTrackErrorFixer != null) {
            dashSource.addEventListener(Utils.sHandler, mTrackErrorFixer);
        }
        return dashSource;
    }

    private MediaSource buildMPDMediaSource(Uri uri, InputStream mpdContent) {
        // Are you using FrameworkSampleSource or ExtractorSampleSource when you build your player?
        DashMediaSource dashSource = new DashMediaSource.Factory(
                getDashChunkSourceFactory(),
                null
        )
                .setLoadErrorHandlingPolicy(new DashDefaultLoadErrorHandlingPolicy())
                .createMediaSource(getManifest(uri, mpdContent));
        if (mTrackErrorFixer != null) {
            dashSource.addEventListener(Utils.sHandler, mTrackErrorFixer);
        }
        return dashSource;
    }

    private MediaSource buildMPDMediaSource(Uri uri, String mpdContent) {
        if (mpdContent == null || mpdContent.isEmpty()) {
            Log.e(TAG, "Can't build media source. MpdContent is null or empty. " + mpdContent);
            return null;
        }

        // Are you using FrameworkSampleSource or ExtractorSampleSource when you build your player?
        DashMediaSource dashSource = new DashMediaSource.Factory(
                new DefaultDashChunkSource.Factory(getMediaDataSourceFactory()),
                null
        )
                .createMediaSource(getManifest(uri, mpdContent));
        if (mTrackErrorFixer != null) {
            dashSource.addEventListener(Utils.sHandler, mTrackErrorFixer);
        }
        return dashSource;
    }

    private SabrManifest getSabrManifest(MediaItemFormatInfo formatInfo) {
        SabrManifestParser parser = new SabrManifestParser();
        return parser.parse(formatInfo);
    }

    private DashManifest getManifest(MediaItemFormatInfo formatInfo) {
        DashManifestParser2 parser = new DashManifestParser2();
        return parser.parse(formatInfo);
    }

    private DashManifest getManifest(Uri uri, InputStream mpdContent) {
        DashManifestParser parser = new StaticDashManifestParser();
        DashManifest result;
        try {
            result = parser.parse(uri, mpdContent);
        } catch (IOException e) {
            throw new IllegalStateException("Malformed mpd file:\n" + mpdContent, e);
        }
        return result;
    }

    private DashManifest getManifest(Uri uri, String mpdContent) {
        DashManifestParser parser = new StaticDashManifestParser();
        DashManifest result;
        try {
            result = parser.parse(uri, FileHelpers.toStream(mpdContent));
        } catch (IOException e) {
            throw new IllegalStateException("Malformed mpd file:\n" + mpdContent, e);
        }
        return result;
    }

    /**
     * Use OkHttp for networking
     */
    private HttpDataSource.Factory buildOkHttpDataSourceFactory(DefaultBandwidthMeter bandwidthMeter) {
        OkHttpDataSourceFactory dataSourceFactory = new OkHttpDataSourceFactory(OkHttpManager.instance().getClient(), USER_AGENT,
                bandwidthMeter);
        addCommonHeaders(dataSourceFactory);
        return dataSourceFactory;
    }

    private HttpDataSource.Factory buildCronetDataSourceFactory(DefaultBandwidthMeter bandwidthMeter) {
        CronetDataSourceFactory dataSourceFactory =
                new CronetDataSourceFactory(
                        new CronetEngineWrapper(CronetManager.getEngine(mContext)),
                        CRONET_EXECUTOR,
                        null,
                        bandwidthMeter,
                        (int) OkHttpManager.getConnectTimeoutMs(),
                        (int) OkHttpManager.getReadTimeoutMs(),
                        true,
                        USER_AGENT);
        addCommonHeaders(dataSourceFactory);
        return dataSourceFactory;
    }

    /**
     * Use built-in component for networking
     */
    private HttpDataSource.Factory buildDefaultHttpDataSourceFactory(DefaultBandwidthMeter bandwidthMeter) {
        DefaultHttpDataSourceFactory dataSourceFactory = new DefaultHttpDataSourceFactory(
                USER_AGENT, bandwidthMeter, (int) OkHttpManager.getConnectTimeoutMs(),
                (int) OkHttpManager.getReadTimeoutMs(), true); // allowCrossProtocolRedirects = true

        addCommonHeaders(dataSourceFactory); // cause troubles for some users
        return dataSourceFactory;
    }

    private static void addCommonHeaders(BaseFactory dataSourceFactory) {
        // Doesn't work
        // Trying to fix 429 error (too many requests)
        //String authorization = RetrofitOkHttpHelper.getAuthHeaders().get("Authorization");
        //
        //if (authorization != null) {
        //    dataSourceFactory.getDefaultRequestProperties().set("Authorization", authorization);
        //}

        //HeaderManager headerManager = new HeaderManager(context);
        //HashMap<String, String> headers = headerManager.getHeaders();

        // NOTE: "Accept-Encoding" should not be set manually (gzip is added by default).

        //for (String header : headers.keySet()) {
        //    if (EXO_HEADERS.contains(header)) {
        //        dataSourceFactory.getDefaultRequestProperties().set(header, headers.get(header));
        //    }
        //}

        // Emulate browser request
        //dataSourceFactory.getDefaultRequestProperties().set("accept", "*/*");
        //dataSourceFactory.getDefaultRequestProperties().set("accept-encoding", "identity"); // Next won't work: gzip, deflate, br
        //dataSourceFactory.getDefaultRequestProperties().set("accept-language", "en-US,en;q=0.9");
        //dataSourceFactory.getDefaultRequestProperties().set("dnt", "1");
        //dataSourceFactory.getDefaultRequestProperties().set("origin", "https://www.youtube.com");
        //dataSourceFactory.getDefaultRequestProperties().set("referer", "https://www.youtube.com/");
        //dataSourceFactory.getDefaultRequestProperties().set("sec-fetch-dest", "empty");
        //dataSourceFactory.getDefaultRequestProperties().set("sec-fetch-mode", "cors");
        //dataSourceFactory.getDefaultRequestProperties().set("sec-fetch-site", "cross-site");

        // WARN: Compression won't work with legacy streams.
        // "Accept-Encoding" should not be set manually (gzip is added by default).
        // Otherwise you should do decompression yourself.
        // Source: https://stackoverflow.com/questions/18898959/httpurlconnection-not-decompressing-gzip/42346308#42346308
        //dataSourceFactory.getDefaultRequestProperties().set("Accept-Encoding", AppConstants.ACCEPT_ENCODING_DEFAULT);
    }

    public void setTrackErrorFixer(TrackErrorFixer trackErrorFixer) {
        mTrackErrorFixer = trackErrorFixer;
    }

    public void release() {
        mMediaDataSourceFactory = null;
        mUncachedMediaDataSourceFactory = null;
    }

    @NonNull
    private DefaultSsChunkSource.Factory getSsChunkSourceFactory() {
        return new DefaultSsChunkSource.Factory(getMediaDataSourceFactory());
    }

    @NonNull
    private SabrChunkSource.Factory getSabrChunkSourceFactory() {
        // NEWTUBE(mobile-cache): SABR must never go through the disk cache. It uses HTTP POST with a
        // per-request body; CacheDataSource keys purely by URI and drops the body when fetching
        // upstream, which would corrupt SABR playback.
        return new DefaultSabrChunkSource.Factory(getNonCachedMediaDataSourceFactory(), MAX_SEGMENTS_PER_LOAD);
    }

    @NonNull
    private DashChunkSource.Factory getDashChunkSourceFactory() {
        return new DefaultDashChunkSource.Factory(getMediaDataSourceFactory(), MAX_SEGMENTS_PER_LOAD);
    }

    private Factory getMediaDataSourceFactory() {
        if (mMediaDataSourceFactory == null) {
            Factory factory = buildDataSourceFactory(USE_BANDWIDTH_METER);

            // NEWTUBE(mobile-cache): when the mobile flavor installed a media cache, serve GET-based
            // sources through it so backward seeks read already-downloaded bytes from disk. Cache
            // read/write errors fall back to the network for that request (FLAG_IGNORE_CACHE_ON_ERROR).
            Cache cache = sMediaCache;
            if (cache != null) {
                factory = new CacheDataSourceFactory(cache, factory, CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
            }

            mMediaDataSourceFactory = factory;
        }

        return mMediaDataSourceFactory;
    }

    /**
     * NEWTUBE(mobile-cache): a plain, never-cached data source factory (only instantiated on the
     * mobile build). See {@link #getNonCachedMediaDataSourceFactory()}.
     */
    private Factory getUncachedMediaDataSourceFactory() {
        if (mUncachedMediaDataSourceFactory == null) {
            mUncachedMediaDataSourceFactory = buildDataSourceFactory(USE_BANDWIDTH_METER);
        }

        return mUncachedMediaDataSourceFactory;
    }

    /**
     * NEWTUBE(mobile-cache): the data source that must bypass the disk cache. Used for things that are
     * NOT immutable media segments: live/dynamic manifests + playlists (DASH-URL, HLS, SmoothStreaming
     * — caching a dynamic manifest would serve a stale copy on refresh and stall live playback) and
     * SABR (HTTP POST). On TV (cache off) this returns the shared non-caching factory; only the
     * mobile build (cache on) needs a separate uncached factory.
     */
    private Factory getNonCachedMediaDataSourceFactory() {
        return sMediaCache != null ? getUncachedMediaDataSourceFactory() : getMediaDataSourceFactory();
    }

    // EXO: 2.10 - 2.12
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
                ProgramInformation programInformation,
                UtcTimingElement utcTiming,
                Uri location,
                List<Period> periods) {
            return new DashManifest(
                    availabilityStartTime,
                    durationMs,
                    minBufferTimeMs,
                    false,
                    minUpdateTimeMs,
                    timeShiftBufferDepthMs,
                    suggestedPresentationDelayMs,
                    publishTimeMs,
                    programInformation,
                    utcTiming,
                    location,
                    periods);
        }
    }

    // EXO: 2.13
    //private static class StaticDashManifestParser extends DashManifestParser {
    //    @Override
    //    protected DashManifest buildMediaPresentationDescription(
    //            long availabilityStartTime,
    //            long durationMs,
    //            long minBufferTimeMs,
    //            boolean dynamic,
    //            long minUpdateTimeMs,
    //            long timeShiftBufferDepthMs,
    //            long suggestedPresentationDelayMs,
    //            long publishTimeMs,
    //            @Nullable ProgramInformation programInformation,
    //            @Nullable UtcTimingElement utcTiming,
    //            @Nullable ServiceDescriptionElement serviceDescription,
    //            @Nullable Uri location,
    //            List<Period> periods) {
    //        return new DashManifest(
    //                availabilityStartTime,
    //                durationMs,
    //                minBufferTimeMs,
    //                false,
    //                minUpdateTimeMs,
    //                timeShiftBufferDepthMs,
    //                suggestedPresentationDelayMs,
    //                publishTimeMs,
    //                programInformation,
    //                utcTiming,
    //                serviceDescription,
    //                location,
    //                periods);
    //    }
    //}
}
