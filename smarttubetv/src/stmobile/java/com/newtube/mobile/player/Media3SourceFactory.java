package com.newtube.mobile.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DataSchemeDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cronet.CronetDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.DefaultDashChunkSource;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.common.util.NetworkTypeObserver;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.cronet.CronetManager;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaStartupTimeoutException;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;

import org.chromium.net.CronetEngine;
import org.chromium.net.UrlRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.OkHttpClient;

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
 * (matching what the legacy engine had), with media3 {@link OkHttpDataSource} as the
 * fallback when Cronet is unavailable or a zero-progress startup timeout triggers recovery.
 * The first request of every source is bounded by {@link StartupDeadlinePolicy} (short on a link
 * with evidence of being fast, the old 8 s otherwise) and fails over to OkHttp inside the same
 * open - see {@link StartupFailoverDataSource}.
 * The shared singleton
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
    /** The UA every googlevideo fetch in the app identifies with - the downloader must match it. */
    public static final String MEDIA_USER_AGENT = USER_AGENT;

    /** Fake base for side-loaded manifests; segment URLs inside the MPD are absolute. */
    private static final Uri GENERATED_MANIFEST_URI = Uri.parse("https://youtube.com/generated.mpd");
    private static final AtomicLong GENERATED_SOURCE_IDS = new AtomicLong();

    /**
     * media3's default gives a segment 3 tries before erroring the whole source; googlevideo
     * routinely stalls/kills connections mid-segment when the client reads slowly (throttled
     * network, huge in-flight segment), and every retry re-opens with a Range from where it left
     * off, so patience converts those stalls into progress. 6 tries are kept, but
     * {@link FailFastLoadErrorPolicy} caps the retry backoff at 1s (stock policy stretches to 5s
     * per try = up to ~60s of silent in-player retrying). Fatal HTTP codes (403 expired/invalid
     * signed URL, 416 unsatisfiable range) and zero-progress initialization timeouts surface after
     * their first failure to the app-level reload, which re-fetches fresh signed URLs within
     * seconds. Replaying an identical signed URL + init/range only adds load and delays recovery.
     */
    private static final int LOAD_RETRY_COUNT = 6;

    /**
     * 4s read timeout (half the 8s default): a silently-stalled googlevideo read should fail fast
     * into the (1s-backoff) retry, which re-opens ranged from the stall point. Connect timeouts
     * stay at the 8s default.
     */
    private static final int READ_TIMEOUT_MS = 4_000;

    /**
     * A zero-progress timeout while reading DASH initialization bytes is different from a
     * mid-stream segment stall: there is no playable buffer yet, so replaying the same QUIC stream
     * up to six times only extends the spinner. Surface that first timeout to the app-level source
     * recovery and temporarily build the replacement source on the OkHttp transport.
     * NEWTUBE(media-path): this short bypass is only for that weak evidence (Cronet timed out, the
     * other transport was never tried). A proven stall - OkHttp answered the request Cronet could
     * not - is a persisted per-network verdict instead (MediaPathVerdicts): a flat 120 s made
     * every open after it lapsed pay the 3.5 s stall again (4 of 16 LTE hops, Pixel 9).
     */
    private static final long CRONET_STARTUP_TIMEOUT_BYPASS_MS = 2 * 60_000L;

    /**
     * NEWTUBE(startup-failover): process-wide, so first-byte evidence and the one-early-failover-
     * per-open bound survive player/factory re-creation. See {@link StartupDeadlinePolicy}.
     */
    private static final StartupDeadlinePolicy STARTUP_POLICY = new StartupDeadlinePolicy();
    /** Debug/benchmark override: {@code off} (always the long wait) or a budget in ms. */
    private static final String PROP_STARTUP_BUDGET = "debug.arc.startup_budget_ms";
    /** Fires the fallback leg's deadline (an OkHttp call cancel); never runs media I/O. */
    private static final ScheduledExecutorService STARTUP_DEADLINES = newStartupDeadlineScheduler();
    @Nullable
    private static String sLastStartupDecisionLog;

    private static ScheduledExecutorService newStartupDeadlineScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "Media3StartupDeadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

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

    // NEWTUBE(abr-seed): persist the bandwidth meter's EWMA estimate PER Android network type.
    // A single global value made a fast home-Wi-Fi session seed the first 5G/roaming video at tens
    // of Mbps (or a slow cellular session unnecessarily hold Wi-Fi down). Media3 resets the meter
    // on network replacement and now finds a seed learned on the same class of link. Values remain
    // clamped; corrupt/pathological prefs fall back to media3's country x network-type table.
    /** Package-private: MediaPathRouting keeps its verdicts in the same (already loaded) file. */
    static final String NETWORK_PREFS_NAME = "newtube_network";
    /** Read once as a migration source for installs that predate per-network seeds. */
    private static final String KEY_BITRATE_ESTIMATE = "bw_estimate_bps";
    private static final String KEY_BITRATE_ESTIMATE_PREFIX = "bw_estimate_bps_net_";
    private static final String KEY_BITRATE_TIME_PREFIX = "bw_estimate_time_net_";
    private static final long MIN_PERSISTED_BITRATE = 100_000;      // 100 kbps
    private static final long MAX_PERSISTED_BITRATE = 50_000_000;   // 50 Mbps
    private static final int[] SEEDED_NETWORK_TYPES = {
            C.NETWORK_TYPE_WIFI,
            C.NETWORK_TYPE_2G,
            C.NETWORK_TYPE_3G,
            C.NETWORK_TYPE_4G,
            C.NETWORK_TYPE_5G_SA,
            C.NETWORK_TYPE_5G_NSA,
            C.NETWORK_TYPE_CELLULAR_UNKNOWN,
            C.NETWORK_TYPE_ETHERNET,
            C.NETWORK_TYPE_OTHER
    };

    /** Process-wide meter (replaces {@code DefaultBandwidthMeter.getSingletonInstance}), seeded once. */
    private static StartupBandwidthMeter sBandwidthMeter;
    private static long sLastEstimateSaveMs;
    private static int sLastEstimateNetwork = C.NETWORK_TYPE_UNKNOWN;

    private static synchronized StartupBandwidthMeter getOrCreateBandwidthMeter(Context context) {
        if (sBandwidthMeter == null) {
            DefaultBandwidthMeter.Builder builder = new DefaultBandwidthMeter.Builder(context)
                    .setResetOnNetworkTypeChange(true);
            SharedPreferences preferences =
                    context.getSharedPreferences(NETWORK_PREFS_NAME, Context.MODE_PRIVATE);
            int currentType = currentNetworkType(context);

            // One-time, conservative migration: assign the old global seed only to the network
            // class active during migration. Never copy a Wi-Fi estimate into every mobile class.
            String currentKey = bitrateKey(currentType);
            long legacy = preferences.getLong(KEY_BITRATE_ESTIMATE, 0);
            if (isPersistableNetworkType(currentType)
                    && !preferences.contains(currentKey)
                    && isValidBitrate(legacy)) {
                preferences.edit().putLong(currentKey, legacy).remove(KEY_BITRATE_ESTIMATE).apply();
            }

            int seededTypes = 0;
            long currentSeed = 0;
            for (int networkType : SEEDED_NETWORK_TYPES) {
                long saved = preferences.getLong(bitrateKey(networkType), 0);
                if (isValidBitrate(saved)) {
                    builder.setInitialBitrateEstimate(networkType, saved);
                    seededTypes++;
                    if (networkType == currentType) {
                        currentSeed = saved;
                    }
                }
            }
            // Existing installs have no measurement timestamp: an old fast-network hint must
            // not select a large first chunk on today's weak link. Fresh transfers can raise
            // quality immediately; this does not constrain the user's explicit track choice.
            boolean startupPolicy = !(BuildConfig.DEBUG || BuildConfig.BENCHMARK)
                    || !"off".equals(DebugMediaShaper.prop("debug.arc.startup_abr"));
            sBandwidthMeter = new StartupBandwidthMeter(builder.build(),
                    type -> StartupBandwidthMeter.seedFor(
                            preferences.getLong(bitrateKey(type), 0),
                            preferences.getLong(KEY_BITRATE_TIME_PREFIX + type, 0),
                            System.currentTimeMillis()),
                    (type, bitrate) -> saveMeasuredEstimate(context, type, bitrate), currentType,
                    startupPolicy, androidx.media3.common.util.Clock.DEFAULT);
            android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            NetworkTypeObserver.getInstance(context).register(
                    sBandwidthMeter::onNetworkTypeChanged, mainHandler::post);
            NetPath.log(NetPath.context() + " abr-init network=" + networkTypeName(currentType)
                    + " seed=" + sBandwidthMeter.getBitrateEstimate()
                    + " storedSeed=" + (currentSeed > 0 ? currentSeed : "default")
                    + " bootstrap=" + (startupPolicy ? "fresh" : "legacy")
                    + " storedTypes=" + seededTypes);
        }
        return sBandwidthMeter;
    }

    // Persist real samples during playback: process death/force-stop need not run teardown.
    private static synchronized void saveMeasuredEstimate(Context context, int networkType, long estimate) {
        if (!isPersistableNetworkType(networkType) || !isValidBitrate(estimate)) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (networkType == sLastEstimateNetwork && now - sLastEstimateSaveMs < 5_000) return;
        sLastEstimateSaveMs = now;
        sLastEstimateNetwork = networkType;
        context.getSharedPreferences(NETWORK_PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(bitrateKey(networkType), estimate)
                .putLong(KEY_BITRATE_TIME_PREFIX + networkType, System.currentTimeMillis()).apply();
        NetPath.log(NetPath.context() + " abr-persist network=" + networkTypeName(networkType)
                + " estimate=" + estimate + " measured=y");
    }

    private static boolean isValidBitrate(long bitrate) {
        return bitrate >= MIN_PERSISTED_BITRATE && bitrate <= MAX_PERSISTED_BITRATE;
    }

    private static boolean isPersistableNetworkType(int networkType) {
        return networkType != C.NETWORK_TYPE_UNKNOWN && networkType != C.NETWORK_TYPE_OFFLINE;
    }

    private static String bitrateKey(int networkType) {
        return KEY_BITRATE_ESTIMATE_PREFIX + networkType;
    }

    /**
     * NetworkTypeObserver is callback-driven and commonly returns UNKNOWN during the first few
     * hundred milliseconds of a cold process even though ConnectivityManager already has the
     * default network. Fill that startup gap for seed selection/migration. Cellular generation is
     * intentionally left as CELLULAR_UNKNOWN rather than requiring phone-state permissions.
     */
    private static int currentNetworkType(Context context) {
        int observed = NetworkTypeObserver.getInstance(context).getNetworkType();
        if (observed != C.NETWORK_TYPE_UNKNOWN) {
            return observed;
        }
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager != null ? manager.getActiveNetwork() : null;
            NetworkCapabilities caps = network != null
                    ? manager.getNetworkCapabilities(network) : null;
            if (network == null) {
                return C.NETWORK_TYPE_OFFLINE;
            }
            if (caps == null) {
                return C.NETWORK_TYPE_UNKNOWN;
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return C.NETWORK_TYPE_WIFI;
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return C.NETWORK_TYPE_ETHERNET;
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return C.NETWORK_TYPE_CELLULAR_UNKNOWN;
            }
            return C.NETWORK_TYPE_OTHER;
        } catch (RuntimeException e) {
            return C.NETWORK_TYPE_UNKNOWN;
        }
    }

    private static String networkTypeName(int networkType) {
        switch (networkType) {
            case C.NETWORK_TYPE_OFFLINE:
                return "offline";
            case C.NETWORK_TYPE_WIFI:
                return "wifi";
            case C.NETWORK_TYPE_2G:
                return "2g";
            case C.NETWORK_TYPE_3G:
                return "3g";
            case C.NETWORK_TYPE_4G:
                return "4g";
            case C.NETWORK_TYPE_5G_SA:
                return "5g-sa";
            case C.NETWORK_TYPE_5G_NSA:
                return "5g-nsa";
            case C.NETWORK_TYPE_CELLULAR_UNKNOWN:
                return "cell";
            case C.NETWORK_TYPE_ETHERNET:
                return "ethernet";
            case C.NETWORK_TYPE_OTHER:
                return "other";
            default:
                return "unknown(" + networkType + ')';
        }
    }

    private final Context mContext;
    private final StartupBandwidthMeter mBandwidthMeter;
    private final DataSource.Factory mHttpDataSourceFactory;
    private final DataSource.Factory mCachedDataSourceFactory;
    @Nullable private DataSource.Factory mSabrDataSourceFactory;
    private final boolean mCronetAvailable;
    private final OkHttpClient mMediaOkHttpClient;
    private final StartupFailoverDataSource.Callbacks mStartupCallbacks = new StartupCallbacks();
    // The Cronet bypass is PROCESS-wide (keyed by network), not per factory: a factory lives
    // with one player instance, and a new open can get a new player - measured on the Pixel, a
    // bypass set by one open was simply gone 20 s later on the next one.
    private static long sCronetBypassUntilMs;
    @Nullable
    private static String sCronetBypassNetwork;
    @Nullable
    private static String sCronetBypassLoggedEpisode;

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
        mCronetAvailable = cronetEngine != null;
        // Network identity, the persisted media-path verdicts (Cronet / IPv6 stalls per network
        // attachment), their background re-probes and the preconnect route. No I/O here.
        MediaPathRouting.attach(mContext);
        mMediaOkHttpClient = MediaHttpClient.create(OkHttpManager.instance().getClient());
        OkHttpDataSource.Factory okHttp = new OkHttpDataSource.Factory(mMediaOkHttpClient)
                .setUserAgent(USER_AGENT)
                .setTransferListener(mBandwidthMeter);

        // Without Cronet (or while it is bypassed) OkHttp is the only path: no early failover,
        // the startup request keeps OkHttp's own timeouts, only the no-response verdict is marked.
        StartupFailoverDataSource.Transports okHttpOnly = new StartupFailoverDataSource.Transports() {
            @Override
            public DataSource primary(int headersBudgetMs) {
                return debugLeg(okHttp.createDataSource(), /* cronetLeg= */ false);
            }

            @Override
            public boolean canFailOver() {
                return false;
            }

            @Nullable
            @Override
            public StartupFailoverDataSource.AbortableLeg fallback() {
                return null;
            }
        };

        DataSource.Factory leafFactory;
        if (mCronetAvailable) {
            Log.d(TAG, "media transport: cronet");
            DataSource.Factory cronetHttp = newCronetFactory(cronetEngine, okHttp,
                    DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS);
            StartupFailoverDataSource.Transports cronetFirst = new StartupFailoverDataSource.Transports() {
                @Override
                public DataSource primary(int headersBudgetMs) {
                    // CronetDataSource's connection timeout is exactly "no response headers
                    // within N ms" (DNS+connect+TLS/QUIC+request+headers). A shorter budget needs
                    // its own factory; the factory is a few fields, the engine is shared.
                    DataSource source = headersBudgetMs >= StartupDeadlinePolicy.LONG_BUDGET_MS
                            ? cronetHttp.createDataSource()
                            : newCronetFactory(cronetEngine, okHttp, headersBudgetMs).createDataSource();
                    return debugLeg(source, /* cronetLeg= */ true);
                }

                @Override
                public boolean canFailOver() {
                    return true;
                }

                @Override
                public StartupFailoverDataSource.AbortableLeg fallback() {
                    return new OkHttpLeg(mMediaOkHttpClient, mBandwidthMeter);
                }
            };
            // Transport selection happens for each newly-created chunk source (one per track per
            // source build). Inside it, the startup request fails over from Cronet to OkHttp on
            // its own budget (StartupFailoverDataSource); a startup timeout also marks Cronet
            // unhealthy on this network, so the next sources start on OkHttp. Cronet's factory
            // fallback alone only handles a missing engine.
            leafFactory = () -> createTransportDataSource(cronetFirst, okHttpOnly, okHttp, defaultHttp);
        } else {
            Log.d(TAG, "media transport: okhttp (cronet unavailable)");
            leafFactory = () -> createTransportDataSource(null, okHttpOnly, okHttp, defaultHttp);
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

        boolean bypassCache = (BuildConfig.DEBUG || BuildConfig.BENCHMARK)
                && "off".equals(DebugMediaShaper.prop("debug.arc.media_cache"));
        if (bypassCache) {
            NetPath.log("media-cache bypass=debug");
        }
        Cache mediaCache = bypassCache ? null : Media3PlayerCache.get(mContext);
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

    private synchronized DataSource createTransportDataSource(
            @Nullable StartupFailoverDataSource.Transports cronetFirst,
            StartupFailoverDataSource.Transports okHttpOnly,
            DataSource.Factory fallbackFactory, DataSource.Factory legacyHttpFactory) {
        // Compare the same requests, ranges and player response through the existing transports.
        // This override is inert in release builds and does not touch persisted preferences.
        if (BuildConfig.DEBUG && "http".equals(DebugMediaShaper.prop("debug.arc.media_transport"))) {
            NetPath.log(NetPath.context() + " media-transport http reason=debug-comparison");
            return legacyHttpFactory.createDataSource();
        }
        if (BuildConfig.DEBUG && "okhttp".equals(DebugMediaShaper.prop("debug.arc.media_transport"))) {
            NetPath.log(NetPath.context() + " media-transport okhttp reason=debug-comparison");
            return fallbackFactory.createDataSource();
        }
        StartupFailoverDataSource.Transports transports = okHttpOnly;
        // NEWTUBE(media-path): the network id costs binder calls, so only once a verdict exists.
        String network = MediaPathRouting.verdicts().isEmpty() ? null : NetPath.networkId(mContext);
        if (cronetFirst != null) {
            // A Cronet stall proven on THIS network attachment (persisted across processes,
            // re-probed in the background) starts media on OkHttp; so does the short load-error
            // bypass. Anywhere else Cronet leads, exactly as before.
            boolean verdict = network != null && MediaPathRouting.isCronetStalled(network);
            if (!verdict && !shouldBypassCronet(mContext)) {
                transports = cronetFirst;
            } else {
                logBypassedSource(verdict, network);
                if (verdict) {
                    MediaPathRouting.verdicts().noteUse(MediaPathVerdicts.Kind.CRONET_STALL, network);
                }
            }
        }
        if (network != null) {
            MediaPathRouting.logUse(NetPath.context(), network);
        }
        // Link evidence comes only from the device's normal transport: Cronet, or OkHttp when
        // there is no Cronet at all - never from OkHttp standing in for a bypassed Cronet.
        boolean normalTransport = transports == cronetFirst || cronetFirst == null;
        return new StartupFailoverDataSource(transports, mStartupCallbacks, STARTUP_DEADLINES,
                android.os.SystemClock::elapsedRealtime, StartupDeadlinePolicy.LONG_BUDGET_MS,
                normalTransport);
    }

    /** Once per open: its sources start on OkHttp because Cronet is bypassed on this network. */
    private static synchronized void logBypassedSource(boolean verdict, @Nullable String network) {
        String episode = NetPath.context();
        if (episode.equals(sCronetBypassLoggedEpisode)) {
            return;
        }
        sCronetBypassLoggedEpisode = episode;
        NetPath.log(episode + " media-transport okhttp-fallback active reason="
                + (verdict ? "cronet-stall-verdict net=" + network
                        : "startup-init-timeout net=" + sCronetBypassNetwork + " remainingMs="
                                + Math.max(0, sCronetBypassUntilMs
                                        - android.os.SystemClock.elapsedRealtime())));
    }

    private DataSource.Factory newCronetFactory(CronetEngine cronetEngine,
            OkHttpDataSource.Factory okHttp, int connectionTimeoutMs) {
        return new CronetDataSource.Factory(cronetEngine, CRONET_EXECUTOR)
                .setUserAgent(USER_AGENT)
                // NEWTUBE(net): media segments are the ONLY request whose lateness the user
                // sees as a stall, so they must outrank everything else queued on the shared
                // Cronet engine (the InnerTube preconnect, /player warmups, image fetches).
                // media3 1.10.1's CronetDataSource.Factory defaults requestPriority to
                // REQUEST_PRIORITY_MEDIUM (=3, verified in its ctor bytecode); Cronet applies
                // it as UrlRequest.Builder#setPriority, which drives both socket-pool
                // ordering and H2/H3 stream priority on a congested link.
                .setRequestPriority(UrlRequest.Builder.REQUEST_PRIORITY_HIGHEST)
                .setTransferListener(mBandwidthMeter)
                .setConnectionTimeoutMs(connectionTimeoutMs)
                .setReadTimeoutMs(READ_TIMEOUT_MS)
                .setKeepPostFor302Redirects(true)
                .setFallbackFactory(okHttp);
    }

    /** DEBUG/BENCHMARK builds: route the leg through the dead-host simulation (inert unset). */
    private static DataSource debugLeg(DataSource source, boolean cronetLeg) {
        return BuildConfig.DEBUG || BuildConfig.BENCHMARK
                ? DebugHostBlackhole.wrap(source, cronetLeg) : source;
    }

    private void markCronetStartupTimeout(String trigger, String evidence) {
        if (mCronetAvailable) {
            markCronetBypass(NetPath.networkId(mContext), trigger, evidence);
        }
    }

    /** Bypasses Cronet on {@code network} - the network the evidence was gathered on. */
    static synchronized void markCronetBypass(String network, String trigger, String evidence) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now < sCronetBypassUntilMs && network.equals(sCronetBypassNetwork)) {
            return;
        }
        sCronetBypassUntilMs = now + CRONET_STARTUP_TIMEOUT_BYPASS_MS;
        sCronetBypassNetwork = network;
        sCronetBypassLoggedEpisode = null;
        NetPath.log(NetPath.context() + " media-transport cronet-bypass reason="
                + "startup-init-timeout trigger=" + trigger + " evidence=" + evidence
                + " net=" + network
                + " cooldownMs=" + CRONET_STARTUP_TIMEOUT_BYPASS_MS);
    }

    static synchronized boolean shouldBypassCronet(Context context) {
        if (sCronetBypassUntilMs == 0) {
            return false;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now >= sCronetBypassUntilMs) {
            NetPath.log(NetPath.context() + " media-transport cronet-bypass cleared reason=expired"
                    + " net=" + sCronetBypassNetwork);
            clearCronetBypass();
            return false;
        }
        String currentNetwork = NetPath.networkId(context);
        if (!currentNetwork.equals(sCronetBypassNetwork)) {
            NetPath.log(NetPath.context() + " media-transport cronet-bypass cleared reason="
                    + "network-change old=" + sCronetBypassNetwork + " new=" + currentNetwork);
            clearCronetBypass();
            return false;
        }
        return true;
    }

    static synchronized void clearCronetBypass() {
        sCronetBypassUntilMs = 0;
        sCronetBypassNetwork = null;
        sCronetBypassLoggedEpisode = null;
    }

    private LoadErrorHandlingPolicy newLoadErrorPolicy() {
        return new FailFastLoadErrorPolicy(
                // Pre-existing path (long budget, or no failover leg): only Cronet was tried, and
                // the replacement source's one different path IS the bypass, so it stays. A
                // verdict that already tried OkHttp too never reaches it (see the policy).
                mCronetAvailable
                        ? () -> markCronetStartupTimeout("load-error", "cronet-timeout-only")
                        : null);
    }

    // ------------------------------------------------------------------------------------------
    // NEWTUBE(startup-failover): evidence, bookkeeping and NetPath lines for
    // StartupFailoverDataSource. Signed URLs never reach the log: host and itag only.
    // ------------------------------------------------------------------------------------------

    private final class StartupCallbacks implements StartupFailoverDataSource.Callbacks {
        @Override
        public StartupDeadlinePolicy.Decision decide(DataSpec dataSpec) {
            String episode = NetPath.context();
            StartupDeadlinePolicy.Evidence evidence = readStartupEvidence();
            String forced = BuildConfig.DEBUG || BuildConfig.BENCHMARK
                    ? DebugMediaShaper.prop(PROP_STARTUP_BUDGET) : "";
            StartupDeadlinePolicy.Decision decision;
            if ("off".equalsIgnoreCase(forced)) {
                decision = StartupDeadlinePolicy.longBudget("debug-off", -1, 0, episode,
                        evidence.networkKey);
            } else if (parsePositiveInt(forced) > 0) {
                decision = STARTUP_POLICY.isSpent(episode)
                        ? StartupDeadlinePolicy.longBudget("spent", -1, 0, episode,
                                evidence.networkKey)
                        : new StartupDeadlinePolicy.Decision(parsePositiveInt(forced), true,
                                "debug-forced", -1, 0, episode, evidence.networkKey);
            } else {
                decision = STARTUP_POLICY.decide(evidence, episode,
                        android.os.SystemClock.elapsedRealtime());
            }
            logStartupDecision(episode, decision, evidence);
            return decision;
        }

        @Override
        public void onFirstByte(long firstByteMs) {
            STARTUP_POLICY.recordFirstByte(NetPath.networkId(mContext), firstByteMs,
                    android.os.SystemClock.elapsedRealtime());
        }

        @Override
        public void onEarlyTimeout(DataSpec dataSpec, StartupDeadlinePolicy.Decision decision,
                long waitedMs, IOException cause) {
            STARTUP_POLICY.markEarlyFailover(decision.episodeKey);
            // No transport-wide bypass yet: one silent URL says nothing about Cronet until the
            // OkHttp leg shows the same URL answers there (see onFallbackResult).
            NetPath.log(NetPath.context() + " startup-init-timeout adaptive action=transport-failover"
                    + " from=cronet to=okhttp budgetMs=" + decision.budgetMs
                    + " reason=" + decision.reason + " waitedMs=" + waitedMs + " bytes=0"
                    + " cronetStatus=" + cronetStatus(cause) + mediaTag(dataSpec));
        }

        @Override
        public void onFallbackResult(DataSpec dataSpec, StartupDeadlinePolicy.Decision decision,
                boolean answered, boolean deadlineHit, long legMs, long totalMs,
                @Nullable IOException cause) {
            boolean noResponse = !answered && (deadlineHit || cause == null
                    || StartupFailoverDataSource.isNoResponseTimeout(cause));
            String outcome = answered ? "answered" : noResponse ? "no-response" : "failed";
            // Only an answer on the other transport for the SAME request, on the SAME network the
            // primary stalled on, is evidence against Cronet/QUIC. Silence on both is a dead edge
            // host; an answer after a handover says nothing about the network that stalled.
            // NEWTUBE(media-path): an HTTP status (a 403 of a dead signed URL) is an answer too.
            String answeredOn = NetPath.networkId(mContext);
            int httpStatus = answered ? -1 : StartupFailoverDataSource.responseCode(cause);
            boolean reached = answered || (!noResponse && httpStatus > 0);
            String bypass = !reached
                    ? (noResponse ? "bypass=n reason=host-dead" : "bypass=n reason=okhttp-error")
                    : StartupDeadlinePolicy.sameKnownNetwork(decision.networkKey, answeredOn)
                    ? (answered ? "bypass=y reason=okhttp-answered"
                            : "bypass=y reason=okhttp-http-" + httpStatus)
                    : "bypass=n reason=network-changed stalledOn=" + decision.networkKey
                            + " answeredOn=" + answeredOn;
            NetPath.log(NetPath.context() + " startup-failover okhttp " + outcome
                    + " legMs=" + legMs + " totalMs=" + totalMs
                    + (answered ? "" : " deadline=" + (deadlineHit ? "y" : "n")
                            + " causes=" + (cause != null ? NetPath.throwableSummary(cause) : "none"))
                    + ' ' + bypass + mediaTag(dataSpec));
        }

        @Override
        public void onPrimaryTransportFault(DataSpec dataSpec, StartupDeadlinePolicy.Decision decision,
                IOException primaryFailure) {
            if (!mCronetAvailable) {
                return;
            }
            String answeredOn = NetPath.networkId(mContext);
            if (StartupDeadlinePolicy.sameKnownNetwork(decision.networkKey, answeredOn)) {
                int status = cronetStatusCode(primaryFailure);
                if (isPathPhase(status)) {
                    // NEWTUBE(media-path): Cronet never got past connect/TLS while OkHttp reached
                    // the same edge - a fault of this network's path for Cronet (Movistar LTE:
                    // cronetStatus=11 on every stall). A persisted per-network verdict, re-probed
                    // in the background, instead of a flat 120 s bypass.
                    MediaPathRouting.verdicts().observe(MediaPathVerdicts.Kind.CRONET_STALL,
                            decision.networkKey, dataSpec.uri.getHost(),
                            "okhttp-answered cronetStatus=" + cronetStatus(primaryFailure));
                } else {
                    // Stuck after the request was sent (13 "waiting": the edge was slow for that
                    // request), or in DNS / Cronet's own socket pool: not a path verdict. The
                    // short in-memory bypass still keeps this open's retries off Cronet.
                    markCronetBypass(decision.networkKey, "adaptive",
                            "okhttp-answered cronetStatus=" + cronetStatus(primaryFailure));
                }
            } else {
                NetPath.log(NetPath.context() + " media-transport cronet-bypass mark=n"
                        + " reason=network-changed stalledOn=" + decision.networkKey
                        + " answeredOn=" + answeredOn);
            }
        }
    }

    private StartupDeadlinePolicy.Evidence readStartupEvidence() {
        String networkKey = NetPath.networkId(mContext);
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager != null ? manager.getActiveNetwork() : null;
            NetworkCapabilities caps = network != null
                    ? manager.getNetworkCapabilities(network) : null;
            if (caps == null) {
                return new StartupDeadlinePolicy.Evidence(networkKey, false, false, 0, 0);
            }
            int downKbps = Math.max(0, caps.getLinkDownstreamBandwidthKbps());
            boolean cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
            // NEWTUBE(startup-budget): the radio generation tells Android's just-connected 14 kbps
            // placeholder from a real 2G reading; only looked up when the reading is that low.
            int rat = cellular && downKbps > 0
                    && downKbps <= StartupDeadlinePolicy.PLACEHOLDER_MAX_DOWN_KBPS
                    ? radioGeneration(manager, network) : StartupDeadlinePolicy.RAT_UNKNOWN;
            return new StartupDeadlinePolicy.Evidence(networkKey,
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                    downKbps, mBandwidthMeter.measuredBitrate(), cellular, rat);
        } catch (RuntimeException e) {
            return new StartupDeadlinePolicy.Evidence(networkKey, false, false, 0, 0);
        }
    }

    /**
     * The network's own NetworkInfo subtype (no phone-state permission needed; media3's
     * NetworkTypeObserver reads the same field, but only for the default network and with lag).
     */
    @SuppressWarnings("deprecation")
    private static int radioGeneration(ConnectivityManager manager, Network network) {
        try {
            android.net.NetworkInfo info = manager.getNetworkInfo(network);
            return info != null ? StartupDeadlinePolicy.ratOf(info.getSubtype())
                    : StartupDeadlinePolicy.RAT_UNKNOWN;
        } catch (RuntimeException e) {
            return StartupDeadlinePolicy.RAT_UNKNOWN;
        }
    }

    /** One line per open and outcome: the audio and video init requests decide identically. */
    private static synchronized void logStartupDecision(String episode,
            StartupDeadlinePolicy.Decision decision, StartupDeadlinePolicy.Evidence evidence) {
        String key = episode + '|' + decision.budgetMs + '|' + decision.reason;
        if (key.equals(sLastStartupDecisionLog)) {
            return;
        }
        sLastStartupDecisionLog = key;
        NetPath.log(episode + " startup-init-timeout adaptive budgetMs=" + decision.budgetMs
                + " early=" + (decision.early ? "y" : "n") + " reason=" + decision.reason
                + " samples=" + decision.samples + " worstFirstByteMs=" + decision.worstFirstByteMs
                + " downKbps=" + evidence.downKbps
                + (evidence.rat != StartupDeadlinePolicy.RAT_UNKNOWN
                        ? " rat=" + StartupDeadlinePolicy.ratName(evidence.rat) : "")
                + " measuredKbps=" + evidence.measuredBps / 1000
                + " validated=" + (evidence.validated ? "y" : "n")
                + " captive=" + (evidence.captive ? "y" : "n")
                + " net=" + evidence.networkKey);
    }

    private static int parsePositiveInt(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String mediaTag(DataSpec dataSpec) {
        String itag = null;
        try {
            itag = dataSpec.uri.getQueryParameter("itag");
        } catch (UnsupportedOperationException ignored) {
            // opaque uri
        }
        return " host=" + dataSpec.uri.getHost() + " itag=" + itag;
    }

    /** Cronet's connection status when it gave up, or -1 when the failure did not carry one. */
    static int cronetStatusCode(@Nullable Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (e instanceof CronetDataSource.OpenException) {
                return ((CronetDataSource.OpenException) e).cronetConnectionStatus;
            }
            if (e.getCause() == e) {
                break;
            }
        }
        return -1;
    }

    /** Stuck establishing the path itself (TCP connect or TLS), before any request was sent. */
    static boolean isPathPhase(int cronetStatus) {
        return cronetStatus == UrlRequest.Status.CONNECTING
                || cronetStatus == UrlRequest.Status.SSL_HANDSHAKE;
    }

    /** Where Cronet was stuck when the budget expired (resolving/connecting/tls/waiting...). */
    private static String cronetStatus(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (e instanceof CronetDataSource.OpenException) {
                int status = ((CronetDataSource.OpenException) e).cronetConnectionStatus;
                switch (status) {
                    case UrlRequest.Status.RESOLVING_HOST:
                        return status + "(resolving)";
                    case UrlRequest.Status.CONNECTING:
                        return status + "(connecting)";
                    case UrlRequest.Status.SSL_HANDSHAKE:
                        return status + "(tls)";
                    case UrlRequest.Status.SENDING_REQUEST:
                        return status + "(sending)";
                    case UrlRequest.Status.WAITING_FOR_RESPONSE:
                        return status + "(waiting)";
                    case UrlRequest.Status.WAITING_FOR_AVAILABLE_SOCKET:
                    case UrlRequest.Status.WAITING_FOR_STALLED_SOCKET_POOL:
                        return status + "(socket-pool)";
                    default:
                        return String.valueOf(status);
                }
            }
        }
        return "n/a";
    }

    /**
     * The failover leg: OkHttp over TCP (a different path from Cronet's QUIC/H2 session and
     * resolver) for the same DataSpec. Its pending open is aborted by cancelling the OkHttp call,
     * which OkHttpDataSource turns into an open IOException; an aborted leg is never reused.
     */
    static final class OkHttpLeg implements StartupFailoverDataSource.AbortableLeg {
        private final DataSource mSource;
        private volatile Call mCall;
        private volatile boolean mAborted;

        OkHttpLeg(OkHttpClient client, @Nullable TransferListener meter) {
            Call.Factory calls = request -> {
                Call call = client.newCall(request);
                mCall = call;
                if (mAborted) {
                    call.cancel();
                }
                return call;
            };
            mSource = debugLeg(new OkHttpDataSource.Factory(calls)
                    .setUserAgent(USER_AGENT)
                    .setTransferListener(meter)
                    .createDataSource(), /* cronetLeg= */ false);
        }

        @Override
        public DataSource source() {
            return mSource;
        }

        @Override
        public void abort() {
            mAborted = true;
            Call call = mCall;
            if (call != null) {
                call.cancel();
            }
        }
    }

    public BandwidthMeter getBandwidthMeter() {
        return mBandwidthMeter;
    }

    /** DASH VOD from InnerTube formats via the generated MPD. Null if the manifest can't be built. */
    @Nullable
    MediaSource fromDashFormatInfo(MediaItemFormatInfo formatInfo) {
        return fromDashFormatInfo(formatInfo, null);
    }

    /** Same build; {@code timing} (open path only) receives the XML generation / parse split. */
    @Nullable
    MediaSource fromDashFormatInfo(MediaItemFormatInfo formatInfo, @Nullable SourceBuildTiming timing) {
        long startMs = android.os.SystemClock.elapsedRealtime();
        // NEWTUBE(open-cpu): the same manifest without printing and re-lexing its XML (see
        // DirectMpd). Anything it declines or fails on takes the text route below, unchanged.
        DirectMpd.Recorder recorder = DirectMpd.record(formatInfo);
        if (recorder != null) {
            long recordedMs = android.os.SystemClock.elapsedRealtime();
            MediaSource direct = null;
            try {
                StaticDashManifestParser parser = new StaticDashManifestParser();
                direct = fromStaticManifest(
                        parser.parse(recorder.replay(), GENERATED_MANIFEST_URI), parser);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "fromDashFormatInfo: direct manifest failed, using xml: " + e);
            }
            if (direct != null) {
                if (timing != null) {
                    timing.genMs = recordedMs - startMs;
                    timing.parseMs = android.os.SystemClock.elapsedRealtime() - recordedMs;
                    timing.mpdRoute = "direct";
                }
                return direct;
            }
        }
        InputStream mpd = formatInfo.createMpdStream();
        long generatedMs = android.os.SystemClock.elapsedRealtime();
        MediaSource source = fromDashManifest(mpd, formatInfo.isLive());
        if (timing != null) {
            timing.genMs = generatedMs - startMs;
            timing.parseMs = android.os.SystemClock.elapsedRealtime() - generatedMs;
            timing.mpdRoute = "xml";
        }
        return source;
    }

    MediaSource fromSabrFormatInfo(MediaItemFormatInfo formatInfo) {
        com.newtube.sabr.SabrStreamInfo info = SabrFormatAdapter.adapt(formatInfo);
        MediaSource source = new com.newtube.sabr.SabrMediaSource(info, getSabrDataSourceFactory(), mBandwidthMeter);
        NetPath.log("sabr source=vod transport=okhttp cache=off tracks=" + info.tracks.size());
        if (formatInfo.getSubtitles() == null || formatInfo.getSubtitles().isEmpty()) return source;
        java.util.ArrayList<MediaSource> sources = new java.util.ArrayList<>();
        sources.add(source);
        androidx.media3.extractor.text.DefaultSubtitleParserFactory parsers =
                new androidx.media3.extractor.text.DefaultSubtitleParserFactory();
        for (com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle subtitle : formatInfo.getSubtitles()) {
            if (subtitle == null || subtitle.getBaseUrl() == null) continue;
            androidx.media3.common.Format format = new androidx.media3.common.Format.Builder()
                    .setId(subtitle.getVssId()).setSampleMimeType(subtitle.getMimeType())
                    .setLanguage(subtitle.getLanguageCode()).setLabel(subtitle.getName()).build();
            if (!parsers.supportsFormat(format)) continue;
            // Stock Media3 sidecar extraction, lazy until selected. Text is NOT handed to a
            // container chunk extractor (the null-wrapper failure in the legacy SABR module).
            sources.add(androidx.media3.exoplayer.source.SabrSubtitleSourceFactory.create(
                    mHttpDataSourceFactory, format, Uri.parse(subtitle.getBaseUrl())));
        }
        return sources.size() == 1 ? source : new MergingMediaSource(sources.toArray(new MediaSource[0]));
    }

    private synchronized DataSource.Factory getSabrDataSourceFactory() {
        // Pay no optional transport setup cost on the default DASH startup path. Stateful POSTs
        // share the existing media pool/proxy/TLS, without account headers, retries or redirects.
        if (mSabrDataSourceFactory == null) {
            mSabrDataSourceFactory = new OkHttpDataSource.Factory(
                    MediaHttpClient.create(OkHttpManager.instance().getClient()).newBuilder()
                            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build())
                    .setUserAgent(USER_AGENT).setTransferListener(mBandwidthMeter);
        }
        return mSabrDataSourceFactory;
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

        return fromStaticManifest(manifest, parser);
    }

    /** The side-loaded source for a generated manifest {@code parser} produced (either route). */
    private MediaSource fromStaticManifest(DashManifest manifest, StaticDashManifestParser parser) {
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
                .setLoadErrorHandlingPolicy(newLoadErrorPolicy())
                .createMediaSource(manifest, new MediaItem.Builder()
                        // Every generated MPD shares a fake base URI. Preload callbacks/ownership
                        // need a unique in-process source identity, including A -> B -> A opens.
                        // This ID is local metadata only; it never changes an HTTP request.
                        .setMediaId("generated-source-" + GENERATED_SOURCE_IDS.incrementAndGet())
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
                .setLoadErrorHandlingPolicy(newLoadErrorPolicy())
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
            // Manifest URLs are signed credentials. Keep only fields needed to distinguish an
            // expired/tokenized route; never put the complete URL in logcat.
            android.net.Uri uri = android.net.Uri.parse(dashManifestUrl);
            String expire = uri.getQueryParameter("expire");
            long expireInSec = -1;
            if (expire != null) {
                try {
                    expireInSec = Long.parseLong(expire) - System.currentTimeMillis() / 1_000L;
                } catch (NumberFormatException ignored) {
                    // leave unknown
                }
            }
            NetPath.log(NetPath.context() + " dash-url host=" + uri.getHost()
                    + " expireInSec=" + expireInSec
                    + " pot=" + (uri.getQueryParameter("pot") != null ? "y" : "n"));
        }

        return new DashMediaSource.Factory(
                        new DefaultDashChunkSource.Factory(mHttpDataSourceFactory),
                        mHttpDataSourceFactory)
                .setManifestParser(new LiveDashManifestParser())
                .setLoadErrorHandlingPolicy(newLoadErrorPolicy())
                .createMediaSource(new MediaItem.Builder()
                        .setUri(dashManifestUrl)
                        .setMimeType(MimeTypes.APPLICATION_MPD)
                        .build());
    }

    /** Live HLS from the master playlist URL. */
    MediaSource fromHlsPlaylist(String hlsPlaylistUrl) {
        return new HlsMediaSource.Factory(mHttpDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(newLoadErrorPolicy())
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

        Uri uri = Uri.parse(urlList.get(0));
        if (isLocalUri(uri)) {
            // NEWTUBE(downloads): a downloaded file. The HTTP/cache tiers cannot open content://
            // or file:// - DefaultDataSource routes those to the content and file readers.
            return new ProgressiveMediaSource.Factory(new DefaultDataSource.Factory(mContext))
                    .createMediaSource(MediaItem.fromUri(uri));
        }

        return new ProgressiveMediaSource.Factory(mCachedDataSourceFactory)
                .setLoadErrorHandlingPolicy(newLoadErrorPolicy())
                .createMediaSource(MediaItem.fromUri(uri));
    }

    private static boolean isLocalUri(Uri uri) {
        String scheme = uri.getScheme();
        return "content".equals(scheme) || "file".equals(scheme);
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
     * connection into ~60s of silent retrying), and a fatal transport error (403/416 or a known
     * offline/DNS failure, see {@link FailFastLoadErrorPolicy#isFatalTransportError}) stops after
     * its first rejection. A zero-progress initialization timeout also surfaces immediately:
     * retrying an unreadable init range cannot produce a first frame, and the replacement source
     * can bypass a stalled Cronet/QUIC route. This lets
     * {@code onPlayerError} surface and
     * {@code ErrorFixerController.applyNoPlaybackFix()}+{@code reloadVideo()} re-fetches fresh
     * signed URLs within seconds.
     */
    static final class FailFastLoadErrorPolicy extends DefaultLoadErrorHandlingPolicy {
        @Nullable
        private final Runnable mStartupTimeoutCallback;

        FailFastLoadErrorPolicy() {
            this(null);
        }

        FailFastLoadErrorPolicy(@Nullable Runnable startupTimeoutCallback) {
            super(LOAD_RETRY_COUNT);
            mStartupTimeoutCallback = startupTimeoutCallback;
        }

        @Override
        public long getRetryDelayMsFor(LoadErrorHandlingPolicy.LoadErrorInfo loadErrorInfo) {
            if (isFatalTransportError(loadErrorInfo.exception)) {
                return C.TIME_UNSET; // don't retry: surface the error to the app-level reload
            }
            if (isStartupNoProgressTimeout(
                    loadErrorInfo.mediaLoadData.dataType,
                    loadErrorInfo.loadEventInfo.bytesLoaded,
                    loadErrorInfo.exception)) {
                // A verdict that already failed over to OkHttp and got silence there too is a
                // dead host, not a Cronet fault: no transport-wide bypass for it.
                boolean hostDead = MediaStartupTimeoutException.isHostDeadInChain(
                        loadErrorInfo.exception);
                if (mStartupTimeoutCallback != null && !hostDead) {
                    mStartupTimeoutCallback.run();
                }
                NetPath.log(NetPath.context() + " startup-init-timeout action=source-failover"
                        + " track=" + loadErrorInfo.mediaLoadData.trackType
                        + " retry=" + loadErrorInfo.errorCount
                        + " bytes=" + loadErrorInfo.loadEventInfo.bytesLoaded
                        + " loadMs=" + loadErrorInfo.loadEventInfo.loadDurationMs
                        + (hostDead ? " bypass=n reason=host-dead" : ""));
                return C.TIME_UNSET;
            }
            return Math.min(super.getRetryDelayMsFor(loadErrorInfo), 1000);
        }

        static boolean isStartupNoProgressTimeout(
                int dataType, long bytesLoaded, Throwable exception) {
            if (dataType != C.DATA_TYPE_MEDIA_INITIALIZATION || bytesLoaded != 0) {
                return false;
            }
            for (Throwable e = exception; e != null; e = e.getCause()) {
                if (e instanceof SocketTimeoutException) {
                    return true;
                }
                String message = e.getMessage();
                if (message != null && message.contains("ERR_TIMED_OUT")) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 403: expired/invalid signed URL - no retry can heal it. 416: the server refuses the
         * requested byte range - retrying the SAME DataSpec can never heal it either; failing
         * fast surfaces {@code onPlayerError} so the app-level reload fetches a fresh manifest.
         */
        static boolean isFatalTransportError(Throwable exception) {
            for (Throwable e = exception; e != null; e = e.getCause()) {
                if (e instanceof HttpDataSource.InvalidResponseCodeException) {
                    int code = ((HttpDataSource.InvalidResponseCodeException) e).responseCode;
                    if (code == 403 || code == 416) {
                        return true;
                    }
                }
                // These cannot be healed by replaying the same media DataSpec six times. Surface
                // immediately so the app-level connectivity recovery owns the retry lifecycle.
                if (e instanceof UnknownHostException || e instanceof NoRouteToHostException) {
                    return true;
                }
                String message = e.getMessage();
                if (message != null && (message.contains("ERR_NAME_NOT_RESOLVED")
                        || message.contains("ERR_INTERNET_DISCONNECTED"))) {
                    return true;
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
    // Package-private for DirectMpdEquivalenceTest.
    static class StaticDashManifestParser extends DashManifestParser {
        private boolean mWasDynamic;

        /** Whether the source manifest declared {@code type="dynamic"} (live), pre-forcing. */
        boolean wasDynamic() {
            return mWasDynamic;
        }

        /**
         * NEWTUBE(open-cpu): {@link #parse(Uri, InputStream)}'s own steps on events that are
         * already in hand ({@link DirectMpd.Replay}) instead of text.
         */
        DashManifest parse(org.xmlpull.v1.XmlPullParser xpp, Uri uri) throws IOException {
            try {
                int eventType = xpp.next();
                if (eventType != org.xmlpull.v1.XmlPullParser.START_TAG || !"MPD".equals(xpp.getName())) {
                    throw androidx.media3.common.ParserException.createForMalformedManifest(
                            "inputStream does not contain a valid media presentation description",
                            /* cause= */ null);
                }
                return parseMediaPresentationDescription(xpp, uri);
            } catch (org.xmlpull.v1.XmlPullParserException e) {
                throw androidx.media3.common.ParserException.createForMalformedManifest(
                        /* message= */ null, /* cause= */ e);
            }
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
