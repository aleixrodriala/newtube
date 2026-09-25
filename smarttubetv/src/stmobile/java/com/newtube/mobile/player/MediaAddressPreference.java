package com.newtube.mobile.player;

import android.content.Context;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.Interceptor;
import okhttp3.Response;

/**
 * NEWTUBE(media-dns): "prefer IPv4 for googlevideo on this network" for the OkHttp media client.
 *
 * <p>Measured on Movistar LTE (Pixel 9): some googlevideo edges accept IPv6 TCP but the TLS
 * handshake then stalls (ICMPv6 filtered, so the server's large certificate flight falls into a
 * PMTU black hole) while IPv4 answers in ~150 ms. OkHttp's fast fallback races only the TCP
 * connect, so the IPv6 socket wins, the handshake read times out after the 4 s read timeout, and
 * only then does IPv4 connect: every OkHttp media open to that edge paid ~4.2 s.</p>
 *
 * <p>When a call to a googlevideo host sees an IPv6 connect/TLS attempt TIME OUT and an IPv4 route
 * then connect - both attempts started on the same network, still the current one - IPv4 is
 * preferred for googlevideo hosts on that network. With fast fallback on, OkHttp 5 re-interleaves
 * resolved addresses IPv6-first ({@code RouteSelector} -> {@code reorderForHappyEyeballs},
 * verified in 5.4.0), so a mere reordering would be undone: while the mark is active the lookup
 * returns the IPv4 addresses only (all addresses when there are none). Cronet is untouched.</p>
 *
 * <p>IPv6 is never stranded: the mark stays valid only while IPv4 keeps proving itself (an IPv4
 * connect on that network within {@link #PREFER_V4_MS}, and at most {@link #MAX_PREFER_V4_MS}
 * after the stall), any IPv4 connect failure clears it, and a call whose filtered IPv4 routes all
 * failed is retried once, in the same request, with the unfiltered answer
 * ({@link RetryInterceptor}).</p>
 */
final class MediaAddressPreference {

    /** The mark lapses when no IPv4 connect on its network proved it for this long. */
    static final long PREFER_V4_MS = 20 * 60_000L;
    /** Hard cap after the stall that set it: IPv6 gets re-tested at least this often. */
    static final long MAX_PREFER_V4_MS = 60 * 60_000L;

    /** Clock, network identity and logging; Android-backed in production, fakes in tests. */
    interface Env {
        long nowMs();

        /** Credential-free identity of the default network, or null when unknown. */
        @Nullable
        String networkKey();

        void log(String line);
    }

    private static final MediaAddressPreference SHARED = new MediaAddressPreference(new AndroidEnv());

    private final Env mEnv;
    /** Per-call watches, so the retry interceptor can read what its call's routes did. */
    private final Map<Call, CallWatch> mWatches = Collections.synchronizedMap(new WeakHashMap<>());
    @Nullable private String mNetwork;
    private long mMarkedAtMs;
    private long mLastV4ProofMs;

    MediaAddressPreference(Env env) {
        mEnv = env;
    }

    static MediaAddressPreference shared() {
        return SHARED;
    }

    /** Gives the shared instance a context for the network identity (application context). */
    static void attach(Context context) {
        AndroidEnv.sContext = context.getApplicationContext();
    }

    static boolean isGoogleVideoHost(@Nullable String host) {
        return host != null && host.endsWith(".googlevideo.com");
    }

    /** Whether IPv4 is currently preferred on the active network; retires a stale mark. */
    synchronized boolean isPreferV4Active() {
        if (mNetwork == null) {
            return false;
        }
        String network = mEnv.networkKey();
        if (!mNetwork.equals(network)) {
            clear("network-change", network);
            return false;
        }
        long now = mEnv.nowMs();
        if (now >= mLastV4ProofMs + PREFER_V4_MS || now >= mMarkedAtMs + MAX_PREFER_V4_MS) {
            clear("expired", network);
            return false;
        }
        return true;
    }

    /** An IPv6 attempt to {@code host} stalled and an IPv4 route then connected on {@code network}. */
    synchronized void markV6HandshakeStall(String host, String network) {
        if (!StartupDeadlinePolicy.isKnownNetwork(network)) {
            return;
        }
        long now = mEnv.nowMs();
        boolean wasActive = network.equals(mNetwork);
        if (!wasActive) {
            mNetwork = network;
            mMarkedAtMs = now;
            mEnv.log("media-dns prefer-v4 on reason=v6-handshake-stall host=" + host
                    + " net=" + network + " proofWindowMs=" + PREFER_V4_MS
                    + " maxMs=" + MAX_PREFER_V4_MS);
        }
        mLastV4ProofMs = now;
    }

    /** IPv4 connected to googlevideo on {@code network}: keeps a mark on that network valid. */
    synchronized void onV4Connected(@Nullable String network) {
        if (mNetwork != null && mNetwork.equals(network)) {
            mLastV4ProofMs = mEnv.nowMs();
        }
    }

    /** An IPv4 connect failed on {@code network}: give IPv6 its chance back there. */
    synchronized void onV4ConnectFailed(String host, @Nullable String network) {
        if (mNetwork != null && mNetwork.equals(network)) {
            clear("v4-failed host=" + host, network);
        }
    }

    private void clear(String reason, @Nullable String network) {
        mEnv.log("media-dns prefer-v4 off reason=" + reason + " net=" + network
                + " was=" + mNetwork);
        mNetwork = null;
        mMarkedAtMs = 0;
        mLastV4ProofMs = 0;
    }

    /** The lookup result OkHttp should see for {@code host}. */
    List<InetAddress> order(String host, List<InetAddress> addresses) {
        if (!isGoogleVideoHost(host) || addresses.size() < 2 || !isPreferV4Active()) {
            return addresses;
        }
        return ipv4Only(addresses);
    }

    /** IPv4 addresses in their original order, or the input when it has none. */
    static List<InetAddress> ipv4Only(List<InetAddress> addresses) {
        List<InetAddress> ipv4 = new ArrayList<>(addresses.size());
        for (InetAddress address : addresses) {
            if (address instanceof Inet4Address) {
                ipv4.add(address);
            }
        }
        return ipv4.isEmpty() ? addresses : ipv4;
    }

    /** Dns for the media OkHttp client: {@code upstream} filtered through this preference. */
    Dns dns(Dns upstream) {
        return hostname -> {
            List<InetAddress> addresses = upstream.lookup(hostname);
            return order(hostname, addresses);
        };
    }

    /** A watch for {@code call} (registered for the retry interceptor). */
    CallWatch newCallWatch(Call call) {
        CallWatch watch = newCallWatch(call.request().url().host());
        mWatches.put(call, watch);
        return watch;
    }

    CallWatch newCallWatch(@Nullable String host) {
        return new CallWatch(isGoogleVideoHost(host) && isPreferV4Active());
    }

    @Nullable
    CallWatch watchFor(Call call) {
        return mWatches.get(call);
    }

    /**
     * Per-call observer fed from OkHttp's EventListener. Every attempt remembers the network it
     * STARTED on, so a handover between the IPv6 stall and the IPv4 success marks nothing. Only a
     * TIMEOUT on an IPv6 route counts: fast-fallback race losers are cancelled (socket closed),
     * which says nothing about IPv6 on this network. Attempts of one call report from several
     * threads, hence the lock.
     */
    final class CallWatch {
        /** The Dns answered this call IPv4-only (the mark was active when it began). */
        private final boolean mFiltered;
        private final Map<InetAddress, String> mStartNetworks = new HashMap<>();
        @Nullable private String mV6StallNetwork;
        private int mV4Failures;
        private boolean mConnected;
        private boolean mRetried;

        CallWatch(boolean filtered) {
            mFiltered = filtered;
        }

        synchronized void connectStart(@Nullable String host, @Nullable InetAddress address) {
            if (isGoogleVideoHost(host) && address != null) {
                mStartNetworks.put(address, mEnv.networkKey());
            }
        }

        synchronized void connectFailed(@Nullable String host, @Nullable InetAddress address,
                IOException failure) {
            if (!isGoogleVideoHost(host) || address == null) {
                return;
            }
            String network = startNetwork(address);
            if (address instanceof Inet6Address && isTimeout(failure)) {
                mV6StallNetwork = network;
            } else if (address instanceof Inet4Address) {
                mV4Failures++;
                onV4ConnectFailed(host, network);
            }
        }

        synchronized void connectEnd(@Nullable String host, @Nullable InetAddress address) {
            mConnected = true;
            if (!isGoogleVideoHost(host) || !(address instanceof Inet4Address)) {
                return;
            }
            String network = startNetwork(address);
            onV4Connected(network);
            if (mV6StallNetwork == null) {
                return;
            }
            String current = mEnv.networkKey();
            if (StartupDeadlinePolicy.sameKnownNetwork(mV6StallNetwork, network)
                    && StartupDeadlinePolicy.sameKnownNetwork(network, current)) {
                markV6HandshakeStall(host, network);
            } else {
                mEnv.log("media-dns prefer-v4 mark=n reason=network-changed host=" + host
                        + " stalledOn=" + mV6StallNetwork + " connectedOn=" + network
                        + " now=" + current);
            }
            mV6StallNetwork = null;
        }

        /**
         * True once, when this call was answered IPv4-only and every IPv4 route it tried failed
         * without any connection: the caller retries with the (now unfiltered) full answer.
         */
        synchronized boolean claimUnfilteredRetry() {
            if (!mFiltered || mV4Failures == 0 || mConnected || mRetried) {
                return false;
            }
            mRetried = true;
            return true;
        }

        private String startNetwork(InetAddress address) {
            String network = mStartNetworks.get(address);
            return network != null ? network : mEnv.networkKey();
        }
    }

    /**
     * Keeps a filtered call from failing on the filter itself: when its IPv4-only routes all
     * failed (which also cleared the mark), the request is sent once more, resolving afresh with
     * both families. Application interceptors may call proceed() again after an exception.
     */
    static final class RetryInterceptor implements Interceptor {
        private final MediaAddressPreference mPreference;

        RetryInterceptor(MediaAddressPreference preference) {
            mPreference = preference;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            try {
                return chain.proceed(chain.request());
            } catch (IOException e) {
                CallWatch watch = mPreference.watchFor(chain.call());
                if (chain.call().isCanceled() || watch == null || !watch.claimUnfilteredRetry()) {
                    throw e;
                }
                mPreference.mEnv.log("media-dns prefer-v4 retry=unfiltered reason=v4-routes-failed"
                        + " host=" + chain.request().url().host()
                        + " cause=" + e.getClass().getSimpleName());
                return chain.proceed(chain.request());
            }
        }
    }

    static boolean isTimeout(@Nullable Throwable failure) {
        for (Throwable e = failure; e != null; e = e.getCause()) {
            if (e instanceof SocketTimeoutException) {
                return true;
            }
            String message = e.getMessage();
            if (message != null && (message.contains("timed out") || message.equals("timeout"))) {
                return true;
            }
            if (e.getCause() == e) {
                break;
            }
        }
        return false;
    }

    private static final class AndroidEnv implements Env {
        @Nullable static volatile Context sContext;

        @Override
        public long nowMs() {
            return android.os.SystemClock.elapsedRealtime();
        }

        @Nullable
        @Override
        public String networkKey() {
            Context context = sContext;
            return context != null ? NetPath.networkId(context) : null;
        }

        @Override
        public void log(String line) {
            NetPath.log(line);
        }
    }
}
