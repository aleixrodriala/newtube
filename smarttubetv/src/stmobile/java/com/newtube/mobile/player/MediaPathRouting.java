package com.newtube.mobile.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.liskovsoft.sharedutils.cronet.CronetManager;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.youtubeapi.common.helpers.MediaHostPreconnect;

import org.chromium.net.CronetEngine;
import org.chromium.net.CronetException;
import org.chromium.net.NetworkException;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * NEWTUBE(media-path): Android wiring of {@link MediaPathVerdicts} - the persisted store, the two
 * background re-probes ({@link MediaPathProber}), and the media-host warm, which on a network with
 * a {@code cronet-stall} verdict goes over the OkHttp media client instead of Cronet: the path the
 * media will use, whose pooled connection the first media request then reuses (every media client
 * derives from one base, see {@link MediaHttpClient#create}).
 */
final class MediaPathRouting {

    private static final String KEY_VERDICTS = "media_path_verdicts";
    private static final String KEY_LAST_OFF = "media_path_last_off";
    /** Same freshness as a successful Cronet warm (PreconnectGate): one warm per host per open. */
    private static final long WARM_FRESH_MS = 25_000;
    private static final int MAX_WARM_HOSTS = 8;

    private static final AndroidEnv ENV =
            new AndroidEnv(AndroidEnv::readScope, AndroidEnv::isDirect);
    private static final MediaPathVerdicts VERDICTS = new MediaPathVerdicts(ENV, /* persistent= */ true);

    private static volatile boolean sAttached;
    @Nullable private static String sLastUseLogKey;

    private MediaPathRouting() {
    }

    static MediaPathVerdicts verdicts() {
        return VERDICTS;
    }

    /** Idempotent; the application context is kept. The store loads on the probe thread. */
    static synchronized void attach(Context context) {
        if (sAttached) {
            return;
        }
        sAttached = true;
        Context app = context.getApplicationContext();
        ENV.mContext = app;
        MediaAddressPreference.attach(app);
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "MediaPathProbe");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        Map<MediaPathVerdicts.Kind, MediaPathProber.Probe> probes =
                new EnumMap<>(MediaPathVerdicts.Kind.class);
        probes.put(MediaPathVerdicts.Kind.CRONET_STALL, new CronetProbe(app));
        probes.put(MediaPathVerdicts.Kind.V6_STALL, new Ipv6Probe());
        VERDICTS.setUseListener(new MediaPathProber(VERDICTS, () -> NetPath.networkId(app),
                executor, probes, MediaPathProber.PROBE_DELAY_MS));
        MediaHostPreconnect.setRouteAdvisor(new OkHttpWarmer(app));
        // Restore on this background thread, while the first /player is still in flight: no
        // caller thread ever reads the preferences or the boot count (a settings-provider call).
        // Until it completes, only what this process observed itself counts.
        executor.execute(VERDICTS::load);
    }

    /** Default-network capability updates (see {@link AndroidEnv#onDefaultCapabilities}). */
    static void onDefaultCapabilities(android.net.Network network,
            android.net.NetworkCapabilities capabilities) {
        if (sAttached && network != null && capabilities != null) {
            ENV.onDefaultCapabilities(network, capabilities);
        }
    }

    /** Whether media on the current network must start on OkHttp (a proven Cronet stall). */
    static boolean isCronetStalled(@Nullable String network) {
        return VERDICTS.isActive(MediaPathVerdicts.Kind.CRONET_STALL, network);
    }

    /** The warm of {@code host} goes over OkHttp instead of Cronet on {@code network}. */
    static boolean warmsOverOkHttp(MediaPathVerdicts book, @Nullable String host,
            @Nullable String network) {
        return MediaAddressPreference.isGoogleVideoHost(host)
                && book.isActive(MediaPathVerdicts.Kind.CRONET_STALL, network);
    }

    /** A book over the real store, as the next process would open it (tests). */
    @VisibleForTesting
    static MediaPathVerdicts newBookForTest(Context context, ScopeReader scopes, boolean direct) {
        AndroidEnv env = new AndroidEnv(scopes, () -> direct);
        env.mContext = context.getApplicationContext();
        return new MediaPathVerdicts(env, /* persistent= */ true);
    }

    /**
     * One line per open that a verdict steers: which verdicts hold on this network, how old, and
     * whether this process restored them - the line to grep on the Pixel.
     */
    static void logUse(String episode, @Nullable String network) {
        String active = VERDICTS.describe(network);
        if (active.isEmpty()) {
            return;
        }
        synchronized (MediaPathRouting.class) {
            String key = episode + '|' + network;
            if (key.equals(sLastUseLogKey)) {
                return;
            }
            sLastUseLogKey = key;
        }
        NetPath.log(episode + " media-path use network=" + network + " " + active);
    }

    // ------------------------------------------------------------------------------------------

    /** Reads the verdict scope of a network id (see {@link AndroidEnv#readScope}). */
    interface ScopeReader {
        @Nullable
        String read(Context context, String network);
    }

    static final class AndroidEnv implements MediaPathVerdicts.Env {
        /** A scope read is reused this long: roaming or the serving network can change later. */
        static final long SCOPE_CACHE_MS = 60_000;

        @Nullable volatile Context mContext;
        private volatile long mBootCount = Long.MIN_VALUE;
        private final ScopeReader mScopeReader;
        private final java.util.function.BooleanSupplier mDirect;
        /** network id -> {scope, read at}: binder calls at most once a minute per network. */
        private final Map<String, Object[]> mScopes = new java.util.concurrent.ConcurrentHashMap<>();

        AndroidEnv(ScopeReader scopeReader, java.util.function.BooleanSupplier direct) {
            mScopeReader = scopeReader;
            mDirect = direct;
        }

        @Override
        public long nowMs() {
            return SystemClock.elapsedRealtime();
        }

        @Nullable
        @Override
        public String networkKey() {
            Context context = mContext;
            return context != null ? NetPath.networkId(context) : null;
        }

        @Nullable
        @Override
        public String scope(String network) {
            long now = SystemClock.elapsedRealtime();
            Object[] cached = mScopes.get(network);
            if (cached != null && now - (Long) cached[1] < SCOPE_CACHE_MS) {
                return (String) cached[0];
            }
            Context context = mContext;
            String scope = context != null ? mScopeReader.read(context, network) : null;
            if (scope != null && MediaPathVerdicts.isScopeKey(scope)) {
                mScopes.put(network, new Object[] {scope, now});
                if (mScopes.size() > 16) {
                    mScopes.keySet().removeIf(key -> !key.equals(network));
                }
                return scope;
            }
            return null; // not current / unidentifiable: never cached, asked again next time
        }

        /**
         * The verdict scope of {@code network}, only while it IS the default network:
         * <ul>
         *   <li>cellular, not roaming, carrier readable: {@code carrier:<subId>:<servingPlmn>:<simPlmn>}
         *       - the subscription from the network's own TelephonyNetworkSpecifier (API 30+) or
         *       the default data subscription, the PLMNs (MCC+MNC) from TelephonyManager, which
         *       needs no permission. A new data session on the same SIM and carrier maps here.</li>
         *   <li>otherwise {@code <network>/<interface>}: that attachment only.</li>
         * </ul>
         * No SSID, BSSID, carrier name, phone number or address is read.
         */
        @Nullable
        static String readScope(Context context, String network) {
            try {
                android.net.ConnectivityManager manager = (android.net.ConnectivityManager)
                        context.getSystemService(Context.CONNECTIVITY_SERVICE);
                android.net.Network active = manager != null ? manager.getActiveNetwork() : null;
                // The id must name this exact Network object (NetPath: transport + ':' + hashCode).
                if (active == null || !network.equals(NetPath.networkId(context))
                        || !network.endsWith(":" + active.hashCode())) {
                    return null;
                }
                android.net.NetworkCapabilities caps = manager.getNetworkCapabilities(active);
                if (caps != null
                        && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                        && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) {
                    String carrier = carrierScope(context, caps);
                    if (carrier != null) {
                        return carrier;
                    }
                }
                android.net.LinkProperties link = manager.getLinkProperties(active);
                String iface = link != null ? link.getInterfaceName() : null;
                return iface != null && !iface.isEmpty() ? network + "/" + iface : null;
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Nullable
        private static String carrierScope(Context context, android.net.NetworkCapabilities caps) {
            int subId = -1;
            if (android.os.Build.VERSION.SDK_INT >= 30
                    && caps.getNetworkSpecifier() instanceof android.net.TelephonyNetworkSpecifier) {
                subId = ((android.net.TelephonyNetworkSpecifier) caps.getNetworkSpecifier())
                        .getSubscriptionId();
            }
            if (subId < 0) {
                subId = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId();
            }
            if (subId < 0) {
                return null;
            }
            android.telephony.TelephonyManager telephony = (android.telephony.TelephonyManager)
                    context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephony == null) {
                return null;
            }
            telephony = telephony.createForSubscriptionId(subId);
            if (telephony == null) {
                return null;
            }
            String serving = telephony.getNetworkOperator();
            String sim = telephony.getSimOperator();
            if (!MediaPathVerdicts.isPlmn(serving) || !MediaPathVerdicts.isPlmn(sim)) {
                return null;
            }
            return MediaPathVerdicts.CARRIER_PREFIX + subId + ":" + serving + ":" + sim;
        }

        /**
         * Whether media goes out without a proxy: the app's configured proxy (OkHttp media client
         * inherits it) or the system proxy its selector reports for googlevideo.
         */
        static boolean isDirect() {
            try {
                OkHttpClient api = OkHttpManager.instance().getClient();
                Proxy proxy = api.proxy();
                if (proxy != null) {
                    return proxy.type() == Proxy.Type.DIRECT;
                }
                java.net.ProxySelector selector = api.proxySelector();
                for (Proxy candidate : selector.select(
                        java.net.URI.create("https://rr1---sn-probe.googlevideo.com/"))) {
                    if (candidate.type() != Proxy.Type.DIRECT) {
                        return false;
                    }
                }
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override
        public boolean direct() {
            return mDirect.getAsBoolean();
        }

        @Nullable
        @Override
        public String episode() {
            return NetPath.context();
        }

        @Override
        public long bootCount() {
            long cached = mBootCount;
            if (cached != Long.MIN_VALUE) {
                return cached;
            }
            Context context = mContext;
            long count = -1;
            if (context != null) {
                try {
                    count = Settings.Global.getInt(context.getContentResolver(),
                            Settings.Global.BOOT_COUNT, -1);
                } catch (RuntimeException e) {
                    count = -1;
                }
            }
            mBootCount = count; // constant for the life of this process
            return count;
        }

        @Override
        public long bootWallMs() {
            return System.currentTimeMillis() - SystemClock.elapsedRealtime();
        }

        @Nullable
        @Override
        public String load() {
            SharedPreferences prefs = prefs();
            return prefs != null ? prefs.getString(KEY_VERDICTS, null) : null;
        }

        @Override
        public void save(@Nullable String snapshot, @Nullable String lastOff) {
            SharedPreferences prefs = prefs();
            if (prefs == null) {
                return;
            }
            // One editor: the verdicts and the removal breadcrumb land on disk together.
            SharedPreferences.Editor editor = prefs.edit();
            if (snapshot == null) {
                editor.remove(KEY_VERDICTS);
            } else {
                editor.putString(KEY_VERDICTS, snapshot);
            }
            if (lastOff != null) {
                editor.putString(KEY_LAST_OFF, lastOff);
            }
            editor.apply();
        }

        @Nullable
        @Override
        public String loadLastOff() {
            SharedPreferences prefs = prefs();
            return prefs != null ? prefs.getString(KEY_LAST_OFF, null) : null;
        }

        /**
         * The default network's capabilities changed (MeteredNetworkMonitor's callback). A roaming
         * flip on the SAME attachment changes its scope (carrier <-> attachment): the cached
         * scope for that network must not outlive it - not even for the cache's 60 s.
         */
        void onDefaultCapabilities(android.net.Network network,
                android.net.NetworkCapabilities capabilities) {
            boolean cellular = capabilities.hasTransport(
                    android.net.NetworkCapabilities.TRANSPORT_CELLULAR);
            boolean home = capabilities.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
            String suffix = ":" + network.hashCode();
            for (Map.Entry<String, Object[]> entry : mScopes.entrySet()) {
                if (!entry.getKey().endsWith(suffix)) {
                    continue;
                }
                boolean carrierCached = ((String) entry.getValue()[0])
                        .startsWith(MediaPathVerdicts.CARRIER_PREFIX);
                if (carrierCached != (cellular && home)) {
                    mScopes.remove(entry.getKey());
                }
            }
        }

        @Nullable
        private SharedPreferences prefs() {
            Context context = mContext;
            // The bandwidth-seed file: already loaded by the time the first source is built.
            return context != null ? context.getSharedPreferences(
                    Media3SourceFactory.NETWORK_PREFS_NAME, Context.MODE_PRIVATE) : null;
        }

        @Override
        public void log(String line) {
            NetPath.log(line);
        }
    }

    // ------------------------------------------------------------------------------------------

    /**
     * The media-host warm on a {@code cronet-stall} network: a {@code generate_204} over the OkHttp
     * media client (IPv4-only when the v6 verdict also holds). Anywhere else the Cronet warm runs
     * unchanged.
     */
    static final class OkHttpWarmer implements MediaHostPreconnect.RouteAdvisor {
        private final Context mContext;
        private final Map<String, Long> mWarmedAt = new LinkedHashMap<>();
        @Nullable private String mWarmNetwork;
        @Nullable private OkHttpClient mClientFor;
        @Nullable private OkHttpClient mClient;

        OkHttpWarmer(Context context) {
            mContext = context;
        }

        @Override
        public boolean warmThroughCronet(String host) {
            if (!MediaAddressPreference.isGoogleVideoHost(host) || VERDICTS.isEmpty()) {
                return true; // the common case costs no network lookup
            }
            String network = NetPath.networkId(mContext);
            if (!warmsOverOkHttp(VERDICTS, host, network)) {
                return true;
            }
            OkHttpClient client = claim(host, network);
            if (client != null) {
                warm(client, host);
            }
            return false; // the Cronet warm would time out after 8 s here, warming nothing
        }

        @Nullable
        private synchronized OkHttpClient claim(String host, String network) {
            long now = SystemClock.elapsedRealtime();
            if (!network.equals(mWarmNetwork)) {
                mWarmedAt.clear();
                mWarmNetwork = network;
            }
            Long last = mWarmedAt.get(host);
            if (last != null && now - last < WARM_FRESH_MS) {
                return null;
            }
            mWarmedAt.remove(host);
            while (mWarmedAt.size() >= MAX_WARM_HOSTS) {
                mWarmedAt.remove(mWarmedAt.keySet().iterator().next());
            }
            mWarmedAt.put(host, now);
            OkHttpClient api = OkHttpManager.instance().getClient();
            if (mClient == null || mClientFor != api) {
                mClient = MediaHttpClient.create(api);
                mClientFor = api;
            }
            return mClient;
        }

        private static void warm(OkHttpClient client, String host) {
            long startMs = SystemClock.elapsedRealtime();
            Request request = new Request.Builder()
                    .url("https://" + host + "/generate_204")
                    .header("User-Agent", Media3SourceFactory.MEDIA_USER_AGENT)
                    .build();
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onResponse(Call call, Response response) {
                    int code = response.code();
                    response.close();
                    NetPath.log("media-path warm via=okhttp host=" + host + " +"
                            + (SystemClock.elapsedRealtime() - startMs) + "ms status=" + code
                            + " reason=cronet-stall");
                }

                @Override
                public void onFailure(Call call, IOException e) {
                    NetPath.log("media-path warm-failed via=okhttp host=" + host + " +"
                            + (SystemClock.elapsedRealtime() - startMs) + "ms reason="
                            + e.getClass().getSimpleName());
                }
            });
        }
    }

    // ------------------------------------------------------------------------------------------

    /** cronet-stall re-probe: {@code generate_204} to the host that stalled, through Cronet. */
    static final class CronetProbe implements MediaPathProber.Probe {
        private final Context mContext;
        private final ExecutorService mCallbacks = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "MediaPathProbeCronet");
            thread.setDaemon(true);
            return thread;
        });

        CronetProbe(Context context) {
            mContext = context;
        }

        @Override
        public MediaPathProber.Result run(String host) {
            CronetEngine engine = CronetManager.getEngine(mContext);
            if (engine == null) {
                return MediaPathProber.Result.inconclusive(-1, "no-cronet");
            }
            String authority = BuildConfig.DEBUG || BuildConfig.BENCHMARK
                    ? DebugHostBlackhole.probeAuthority(host, /* cronetLeg= */ true) : null;
            String url = "https://" + (authority != null ? authority : host) + "/generate_204";
            CountDownLatch done = new CountDownLatch(1);
            AtomicInteger status = new AtomicInteger(-1);
            AtomicLong answeredAtMs = new AtomicLong();
            AtomicInteger errorCode = new AtomicInteger(Integer.MIN_VALUE);
            UrlRequest.Callback callback = new UrlRequest.Callback() {
                @Override
                public void onRedirectReceived(UrlRequest request, UrlResponseInfo info,
                        String newLocationUrl) {
                    answer(request, info);
                }

                @Override
                public void onResponseStarted(UrlRequest request, UrlResponseInfo info) {
                    answer(request, info);
                }

                private void answer(UrlRequest request, UrlResponseInfo info) {
                    answeredAtMs.compareAndSet(0, SystemClock.elapsedRealtime());
                    status.compareAndSet(-1, info.getHttpStatusCode());
                    done.countDown();
                    request.cancel(); // headers are the whole answer; never read the body
                }

                @Override
                public void onReadCompleted(UrlRequest request, UrlResponseInfo info,
                        java.nio.ByteBuffer byteBuffer) {
                    request.cancel();
                }

                @Override
                public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
                    done.countDown();
                }

                @Override
                public void onFailed(UrlRequest request, @Nullable UrlResponseInfo info,
                        CronetException error) {
                    if (error instanceof NetworkException) {
                        errorCode.set(((NetworkException) error).getErrorCode());
                    } else {
                        errorCode.set(0);
                    }
                    done.countDown();
                }

                @Override
                public void onCanceled(UrlRequest request, @Nullable UrlResponseInfo info) {
                    done.countDown();
                }
            };
            long startMs = SystemClock.elapsedRealtime();
            UrlRequest request;
            try {
                request = engine.newUrlRequestBuilder(url, callback, mCallbacks)
                        .setPriority(UrlRequest.Builder.REQUEST_PRIORITY_IDLE)
                        .disableCache()
                        .build();
                request.start();
            } catch (RuntimeException | LinkageError e) {
                return MediaPathProber.Result.inconclusive(-1, "start-" + e.getClass().getSimpleName());
            }
            boolean finished;
            try {
                finished = done.await(MediaPathProber.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                request.cancel();
                return MediaPathProber.Result.inconclusive(-1, "interrupted");
            }
            long elapsed = SystemClock.elapsedRealtime() - startMs;
            if (!finished) {
                request.cancel();
                return MediaPathProber.Result.stalled(elapsed, "timeout");
            }
            if (answeredAtMs.get() > 0) {
                return MediaPathProber.Result.answered(answeredAtMs.get() - startMs,
                        "http=" + status.get());
            }
            int code = errorCode.get();
            if (code == NetworkException.ERROR_TIMED_OUT
                    || code == NetworkException.ERROR_CONNECTION_TIMED_OUT) {
                return MediaPathProber.Result.stalled(elapsed, "cronet-error=" + code);
            }
            return MediaPathProber.Result.inconclusive(elapsed, "cronet-error=" + code);
        }
    }

    /**
     * v6-stall re-probe: a TLS {@code generate_204} to the host that stalled, over its IPv6
     * addresses only, on a private client (own pool, no proxy, none of the media client's
     * listeners, so it can neither reuse nor feed the media path).
     */
    static final class Ipv6Probe implements MediaPathProber.Probe {
        private static final String NO_V6 = "no IPv6 address";
        @Nullable private OkHttpClient mClient;

        private synchronized OkHttpClient client() {
            if (mClient == null) {
                long timeout = MediaPathProber.PROBE_TIMEOUT_MS;
                mClient = new OkHttpClient.Builder()
                        .proxy(Proxy.NO_PROXY)
                        .dns(hostname -> {
                            List<InetAddress> v6 = ipv6Only(Dns.SYSTEM.lookup(hostname));
                            if (v6.isEmpty()) {
                                throw new UnknownHostException(NO_V6);
                            }
                            return v6;
                        })
                        .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
                        .fastFallback(false)
                        .retryOnConnectionFailure(false)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                        .readTimeout(timeout, TimeUnit.MILLISECONDS)
                        .callTimeout(timeout + 1_000, TimeUnit.MILLISECONDS)
                        .build();
            }
            return mClient;
        }

        @Override
        public MediaPathProber.Result run(String host) {
            OkHttpClient client = client();
            long startMs = SystemClock.elapsedRealtime();
            Request request = new Request.Builder()
                    .url("https://" + host + "/generate_204")
                    .header("User-Agent", Media3SourceFactory.MEDIA_USER_AGENT)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                return MediaPathProber.Result.answered(SystemClock.elapsedRealtime() - startMs,
                        "http=" + response.code());
            } catch (UnknownHostException e) {
                return MediaPathProber.Result.inconclusive(SystemClock.elapsedRealtime() - startMs,
                        NO_V6.equals(e.getMessage()) ? "no-v6-address" : "dns-failed");
            } catch (IOException e) {
                long elapsed = SystemClock.elapsedRealtime() - startMs;
                return MediaAddressPreference.isTimeout(e)
                        ? MediaPathProber.Result.stalled(elapsed, "timeout")
                        : MediaPathProber.Result.inconclusive(elapsed, e.getClass().getSimpleName());
            } finally {
                client.connectionPool().evictAll();
            }
        }

        static List<InetAddress> ipv6Only(List<InetAddress> addresses) {
            List<InetAddress> v6 = new ArrayList<>(addresses.size());
            for (InetAddress address : addresses) {
                if (address instanceof Inet6Address) {
                    v6.add(address);
                }
            }
            return v6;
        }
    }
}
