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
 * then connect - both attempts started on the same network, still the current one - the
 * {@code v6-stall} verdict is recorded for that network in {@link MediaPathVerdicts} (persisted;
 * scoped to the SIM + carrier on cellular, to the attachment on Wi-Fi; re-probed in the background
 * - see there). With fast fallback on, OkHttp
 * 5 re-interleaves resolved addresses IPv6-first ({@code RouteSelector} ->
 * {@code reorderForHappyEyeballs}, verified in 5.4.0), so a mere reordering would be undone: while
 * the verdict holds, the lookup returns the IPv4 addresses only (all addresses when there are
 * none). Cronet is untouched.</p>
 *
 * <p>IPv6 is never stranded: a call whose filtered IPv4 routes all failed is retried once, in the
 * same request, with the unfiltered answer ({@link RetryInterceptor}), and when that retry then
 * connects over IPv6 the verdict is dropped (IPv4 is the broken family there). The verdict's own
 * re-probe drops it as soon as IPv6 answers the host that stalled.</p>
 */
final class MediaAddressPreference {

    /** Clock, network identity and logging; Android-backed in production, fakes in tests. */
    interface Env {
        long nowMs();

        /** Credential-free identity of the default network, or null when unknown. */
        @Nullable
        String networkKey();

        void log(String line);
    }

    private static final MediaAddressPreference SHARED =
            new MediaAddressPreference(new AndroidEnv(), MediaPathRouting.verdicts());

    /**
     * Set by {@link RetryInterceptor} around its one unfiltered re-send. OkHttp resolves routes on
     * the thread running the interceptor chain (verified with 5.4.0 fast fallback by
     * MediaAddressPreferenceTest's end-to-end retry), so the flag reaches exactly that call's
     * lookup while the verdict itself stays in place for every other call.
     */
    private static final ThreadLocal<Boolean> UNFILTERED_RETRY = new ThreadLocal<>();

    private final Env mEnv;
    private final MediaPathVerdicts mVerdicts;
    /** Per-call watches, so the retry interceptor can read what its call's routes did. */
    private final Map<Call, CallWatch> mWatches = Collections.synchronizedMap(new WeakHashMap<>());

    /** A preference whose verdicts live only in memory (tests). */
    MediaAddressPreference(Env env) {
        this(env, inMemoryVerdicts(env));
    }

    MediaAddressPreference(Env env, MediaPathVerdicts verdicts) {
        mEnv = env;
        mVerdicts = verdicts;
    }

    static MediaPathVerdicts inMemoryVerdicts(Env env) {
        return new MediaPathVerdicts(new MediaPathVerdicts.Env() {
            @Override public long nowMs() {
                return env.nowMs();
            }

            @Nullable @Override public String networkKey() {
                return env.networkKey();
            }

            @Nullable @Override public String scope(String network) {
                return network.equals(env.networkKey()) ? network + "/test0" : null;
            }

            @Override public boolean direct() {
                return true;
            }

            @Nullable @Override public String episode() {
                return null;
            }

            @Override public long bootCount() {
                return -1;
            }

            @Override public long bootWallMs() {
                return 0;
            }

            @Nullable @Override public String loadLastOff() {
                return null;
            }

            @Nullable @Override public String load() {
                return null;
            }

            @Override public void save(@Nullable String snapshot, @Nullable String lastOff) {
            }

            @Override public void log(String line) {
                env.log(line);
            }
        }, /* persistent= */ false);
    }

    static MediaAddressPreference shared() {
        return SHARED;
    }

    MediaPathVerdicts verdicts() {
        return mVerdicts;
    }

    /** Gives the shared instance a context for the network identity (application context). */
    static void attach(Context context) {
        AndroidEnv.sContext = context.getApplicationContext();
    }

    static boolean isGoogleVideoHost(@Nullable String host) {
        return host != null && host.endsWith(".googlevideo.com");
    }

    /** Whether IPv4 is currently preferred on the active network. */
    boolean isPreferV4Active() {
        // No verdict anywhere (every Wi-Fi-only device): no network lookup per media call.
        return !mVerdicts.isEmpty()
                && mVerdicts.isActive(MediaPathVerdicts.Kind.V6_STALL, mEnv.networkKey());
    }

    /** An IPv6 attempt to {@code host} stalled and an IPv4 route then connected on {@code network}. */
    void markV6HandshakeStall(String host, String network) {
        mVerdicts.observe(MediaPathVerdicts.Kind.V6_STALL, network, host, "v6-handshake-stall");
    }

    /**
     * A filtered call's IPv4 routes all failed and its unfiltered retry then connected over IPv6 on
     * {@code network}: IPv4 is the broken family there, so IPv6 gets its chance back.
     */
    void onV6ConnectedAfterV4Failed(String host, @Nullable String network) {
        mVerdicts.clear(MediaPathVerdicts.Kind.V6_STALL, network,
                "v4-failed-v6-connected host=" + host);
    }

    /** The lookup result OkHttp should see for {@code host}. */
    List<InetAddress> order(String host, List<InetAddress> addresses) {
        if (!isGoogleVideoHost(host) || addresses.size() < 2
                || Boolean.TRUE.equals(UNFILTERED_RETRY.get()) || mVerdicts.isEmpty()) {
            return addresses;
        }
        String network = mEnv.networkKey();
        // A host this network's media reaches: the verdicts' EXPLORE probe candidate.
        mVerdicts.noteHost(network, host);
        if (!mVerdicts.isActive(MediaPathVerdicts.Kind.V6_STALL, network)) {
            return addresses;
        }
        List<InetAddress> ipv4 = ipv4Only(addresses);
        if (ipv4.size() < addresses.size()) {
            // The verdict kept this connection off IPv6: its re-probe may be due.
            mVerdicts.noteUse(MediaPathVerdicts.Kind.V6_STALL, network);
        }
        return ipv4;
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
     * which says nothing about IPv6 on this network. OkHttp reports connectEnd after TLS, so an
     * IPv6 connectEnd means the whole handshake worked. Attempts of one call report from several
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
                // Counted for this call's unfiltered retry only. One host's IPv4 failure (a dead
                // edge fails on both families) is no evidence that IPv6 works on this network;
                // the retry connecting over IPv6 is (see connectEnd).
                mV4Failures++;
            }
        }

        synchronized void connectEnd(@Nullable String host, @Nullable InetAddress address) {
            mConnected = true;
            if (!isGoogleVideoHost(host)) {
                return;
            }
            if (address instanceof Inet6Address) {
                String network = startNetwork(address);
                if (mRetried && mV4Failures > 0
                        && StartupDeadlinePolicy.sameKnownNetwork(network, mEnv.networkKey())) {
                    onV6ConnectedAfterV4Failed(host, network);
                }
                return;
            }
            if (!(address instanceof Inet4Address)) {
                return;
            }
            String network = startNetwork(address);
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
     * failed, the request is sent once more, resolving afresh with both families (this call only;
     * the verdict is dropped if that retry then connects over IPv6). Application interceptors may
     * call proceed() again after an exception.
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
                UNFILTERED_RETRY.set(Boolean.TRUE);
                try {
                    return chain.proceed(chain.request());
                } finally {
                    UNFILTERED_RETRY.remove();
                }
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
