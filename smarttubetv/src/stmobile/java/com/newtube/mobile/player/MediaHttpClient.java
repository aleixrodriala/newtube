package com.newtube.mobile.player;

import android.os.SystemClock;

import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.Dns;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;

/** Standard OkHttp transport for media, with bounded inactivity but no whole-stream deadline. */
public final class MediaHttpClient {
    private MediaHttpClient() {}

    public static OkHttpClient create(OkHttpClient sharedClient) {
        // Start with stock TLS and headers. Share the pool so the existing default-network
        // handover eviction also retires media sockets, and retain the configured proxy route.
        // API interceptors, cookies and origin authentication do not belong on media requests.
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectionPool(sharedClient.connectionPool())
                .proxy(sharedClient.proxy())
                .proxySelector(sharedClient.proxySelector())
                .proxyAuthenticator(sharedClient.proxyAuthenticator())
                .fastFallback(true)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .writeTimeout(4, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS);
        // NEWTUBE(media-dns): an IPv6 handshake stall on a googlevideo edge followed by a fast
        // IPv4 connect marks "prefer IPv4 for googlevideo on this network" (MediaAddressPreference).
        return withAddressPreference(builder, MediaAddressPreference.shared(), Dns.SYSTEM).build();
    }

    /** Package-private seam: the preference and upstream Dns are injectable in tests. */
    static OkHttpClient.Builder withAddressPreference(OkHttpClient.Builder builder,
            MediaAddressPreference preference, Dns upstream) {
        // The only interceptor on the media client: it re-sends a call once when the IPv4-only
        // answer it got failed on every route (see MediaAddressPreference.RetryInterceptor).
        return builder.dns(preference.dns(upstream))
                .addInterceptor(new MediaAddressPreference.RetryInterceptor(preference))
                .eventListenerFactory(call -> BuildConfig.DEBUG
                        ? new TimingListener(preference.newCallWatch(call))
                        : new RouteListener(preference.newCallWatch(call)));
    }

    /** Feeds per-route connect outcomes (address family only) to the IPv4 preference. */
    static class RouteListener extends EventListener {
        private final MediaAddressPreference.CallWatch mWatch;

        RouteListener(MediaAddressPreference.CallWatch watch) {
            mWatch = watch;
        }

        @Override public void connectStart(Call call, InetSocketAddress address, Proxy proxy) {
            mWatch.connectStart(call.request().url().host(), address.getAddress());
        }

        @Override public void connectEnd(Call call, InetSocketAddress address, Proxy proxy, Protocol protocol) {
            mWatch.connectEnd(call.request().url().host(), address.getAddress());
        }

        @Override public void connectFailed(Call call, InetSocketAddress address, Proxy proxy,
                Protocol protocol, IOException failure) {
            mWatch.connectFailed(call.request().url().host(), address.getAddress(), failure);
        }
    }

    /** Diagnostic events never include addresses, headers, signed queries or exception messages. */
    private static final class TimingListener extends RouteListener {
        private final Map<InetSocketAddress, Long> connects = new HashMap<>();
        private long startMs;
        private long dnsStartMs;
        private long dnsMs = -1;
        private long tlsStartMs;
        private long tlsMs = -1;
        private long headersMs = -1;
        private int status = -1;
        private int attempts;
        private String protocol = "?";

        TimingListener(MediaAddressPreference.CallWatch watch) {
            super(watch);
        }

        @Override public void callStart(Call call) {
            startMs = SystemClock.elapsedRealtime();
        }

        @Override public void dnsStart(Call call, String domainName) {
            dnsStartMs = SystemClock.elapsedRealtime();
        }

        @Override public void dnsEnd(Call call, String domainName, List<InetAddress> addresses) {
            dnsMs = SystemClock.elapsedRealtime() - dnsStartMs;
        }

        @Override public synchronized void connectStart(Call call, InetSocketAddress address, Proxy proxy) {
            super.connectStart(call, address, proxy);
            connects.put(address, SystemClock.elapsedRealtime());
            attempts++;
        }

        @Override public void connectEnd(Call call, InetSocketAddress address, Proxy proxy, Protocol protocol) {
            super.connectEnd(call, address, proxy, protocol);
            finishConnect(call, address, "ok");
        }

        @Override public void connectFailed(Call call, InetSocketAddress address, Proxy proxy,
                Protocol protocol, IOException failure) {
            super.connectFailed(call, address, proxy, protocol, failure);
            finishConnect(call, address, failure.getClass().getSimpleName());
        }

        private synchronized void finishConnect(Call call, InetSocketAddress address, String result) {
            Long started = connects.remove(address);
            String family = address.getAddress() == null ? "unknown"
                    : address.getAddress() instanceof Inet6Address ? "v6" : "v4";
            NetPath.log("okhttp-connect family=" + family
                    + " elapsed=" + (started == null ? -1 : SystemClock.elapsedRealtime() - started)
                    + "ms result=" + result + " host=" + call.request().url().host());
        }

        @Override public void connectionAcquired(Call call, Connection connection) {
            protocol = connection.protocol().toString();
        }

        @Override public void secureConnectStart(Call call) {
            tlsStartMs = SystemClock.elapsedRealtime();
        }

        @Override public void secureConnectEnd(Call call, Handshake handshake) {
            tlsMs = SystemClock.elapsedRealtime() - tlsStartMs;
        }

        @Override public void responseHeadersEnd(Call call, Response response) {
            headersMs = SystemClock.elapsedRealtime() - startMs;
            status = response.code();
        }

        @Override public void callEnd(Call call) {
            finish(call, "ok");
        }

        @Override public void callFailed(Call call, IOException failure) {
            finish(call, failure.getClass().getSimpleName());
        }

        private synchronized void finish(Call call, String result) {
            NetPath.log("okhttp-media protocol=" + protocol + " status=" + status + " headers=" + headersMs
                    + "ms total=" + (SystemClock.elapsedRealtime() - startMs) + "ms dns=" + dnsMs
                    + " tls=" + tlsMs + " attempts=" + attempts + " result=" + result
                    + " host=" + call.request().url().host());
        }
    }
}
