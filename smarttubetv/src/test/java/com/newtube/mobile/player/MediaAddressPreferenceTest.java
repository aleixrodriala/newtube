package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.annotation.Nullable;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** "Prefer IPv4 for googlevideo on this network" after an IPv6 handshake stall (Movistar LTE). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MediaAddressPreferenceTest {
    private static final String EDGE = "rr6---sn-uxax4vopj5xn-cjoe.googlevideo.com";
    private static final long MIN = 60_000L;

    private final FakeEnv mEnv = new FakeEnv();
    private final MediaAddressPreference mPreference = new MediaAddressPreference(mEnv);

    @Test
    public void ipv6HandshakeTimeoutThenIpv4ConnectPrefersIpv4OnThisNetwork() throws Exception {
        MediaAddressPreference.CallWatch call = mPreference.newCallWatch(EDGE);
        call.connectStart(EDGE, v6(1));
        call.connectFailed(EDGE, v6(1), new SocketTimeoutException("Read timed out"));
        call.connectStart(EDGE, v4(1));
        call.connectEnd(EDGE, v4(1));

        assertTrue(mPreference.isPreferV4Active());
        assertEquals(Collections.singletonList("media-dns prefer-v4 on reason=v6-handshake-stall"
                + " host=" + EDGE + " net=cell:104 proofWindowMs=" + MediaAddressPreference.PREFER_V4_MS
                + " maxMs=" + MediaAddressPreference.MAX_PREFER_V4_MS), mEnv.lines);
        // IPv4 only, original order (OkHttp's fast fallback would re-interleave IPv6 first).
        assertEquals(Arrays.asList(v4(1), v4(2)),
                mPreference.order(EDGE, Arrays.asList(v6(1), v4(1), v6(2), v4(2))));
    }

    @Test
    public void aHandoverBetweenTheStallAndTheIpv4SuccessMarksNothing() throws Exception {
        // Wi-Fi -> cellular mid-request: the IPv6 stall was Wi-Fi's, the IPv4 success cellular's.
        mEnv.network = "wifi:7";
        MediaAddressPreference.CallWatch call = mPreference.newCallWatch(EDGE);
        call.connectStart(EDGE, v6(1));
        call.connectFailed(EDGE, v6(1), new SocketTimeoutException("Read timed out"));
        mEnv.network = "cell:104";
        call.connectStart(EDGE, v4(1));
        call.connectEnd(EDGE, v4(1));

        assertFalse(mPreference.isPreferV4Active());
        assertEquals(Collections.singletonList("media-dns prefer-v4 mark=n reason=network-changed"
                + " host=" + EDGE + " stalledOn=wifi:7 connectedOn=cell:104 now=cell:104"), mEnv.lines);
    }

    @Test
    public void aHandoverAfterTheIpv4ConnectStartedMarksNothingEither() throws Exception {
        MediaAddressPreference.CallWatch call = mPreference.newCallWatch(EDGE);
        call.connectStart(EDGE, v6(1));
        call.connectFailed(EDGE, v6(1), new SocketTimeoutException());
        call.connectStart(EDGE, v4(1));
        mEnv.network = "wifi:7"; // the success is reported after the default network moved
        call.connectEnd(EDGE, v4(1));

        mEnv.network = "cell:104";
        assertFalse(mPreference.isPreferV4Active());
    }

    @Test
    public void onlyGoogleVideoHostsAndMixedFamiliesAreFiltered() throws Exception {
        markStall();
        List<InetAddress> mixed = Arrays.asList(v6(1), v4(1));
        assertSame(mixed, mPreference.order("www.youtube.com", mixed));
        List<InetAddress> v6Only = Arrays.asList(v6(1), v6(2));
        assertEquals(v6Only, mPreference.order(EDGE, v6Only)); // never an empty answer
        assertEquals(Collections.singletonList(v6(1)),
                mPreference.order(EDGE, Collections.singletonList(v6(1))));
    }

    @Test
    public void aCancelledIpv6RaceLoserIsNotAStall() throws Exception {
        MediaAddressPreference.CallWatch call = mPreference.newCallWatch(EDGE);
        call.connectFailed(EDGE, v6(1), new SocketException("Socket closed"));
        call.connectEnd(EDGE, v4(1));

        assertFalse(mPreference.isPreferV4Active());
        assertTrue(mEnv.lines.isEmpty());
    }

    @Test
    public void anotherIpv6RouteSucceedingIsNotEvidenceForIpv4() throws Exception {
        MediaAddressPreference.CallWatch call = mPreference.newCallWatch(EDGE);
        call.connectFailed(EDGE, v6(1), new SocketTimeoutException("connect timed out"));
        call.connectEnd(EDGE, v6(2));

        assertFalse(mPreference.isPreferV4Active());
    }

    @Test
    public void nonGoogleVideoStallsAndUnknownNetworksNeverMark() throws Exception {
        MediaAddressPreference.CallWatch call = mPreference.newCallWatch("www.youtube.com");
        call.connectFailed("www.youtube.com", v6(1), new SocketTimeoutException());
        call.connectEnd("www.youtube.com", v4(1));
        assertFalse(mPreference.isPreferV4Active());

        mEnv.network = null;
        markStall();
        mEnv.network = "cell:104";
        assertFalse(mPreference.isPreferV4Active());
    }

    @Test
    public void theMarkLapsesWithoutRecentIpv4Proof() throws Exception {
        markStall();
        mEnv.now += MediaAddressPreference.PREFER_V4_MS;

        assertFalse(mPreference.isPreferV4Active());
        assertEquals("media-dns prefer-v4 off reason=expired net=cell:104 was=cell:104",
                mEnv.lines.get(1));
        List<InetAddress> mixed = Arrays.asList(v6(1), v4(1));
        assertSame(mixed, mPreference.order(EDGE, mixed));
    }

    @Test
    public void workingIpv4KeepsTheMarkAliveUpToTheHardCap() throws Exception {
        markStall();
        for (int i = 0; i < 5; i++) { // an IPv4 media connect every 10 minutes
            mEnv.now += 10 * MIN;
            MediaAddressPreference.CallWatch call = mPreference.newCallWatch(EDGE);
            call.connectStart(EDGE, v4(1));
            call.connectEnd(EDGE, v4(1));
            assertTrue(mPreference.isPreferV4Active());
        }
        // ...but IPv6 is re-tested at least hourly.
        mEnv.now += 10 * MIN;
        assertFalse(mPreference.isPreferV4Active());
    }

    @Test
    public void ipv4ProofOnAnotherNetworkDoesNotCount() throws Exception {
        markStall();
        mEnv.now += 15 * MIN;
        mPreference.onV4Connected("wifi:7");
        mEnv.now += 6 * MIN;

        assertFalse(mPreference.isPreferV4Active());
    }

    @Test
    public void theMarkBelongsToTheNetworkThatShowedTheStall() throws Exception {
        markStall();
        mEnv.network = "wifi:7";

        List<InetAddress> mixed = Arrays.asList(v6(1), v4(1));
        assertSame(mixed, mPreference.order(EDGE, mixed));
        assertEquals("media-dns prefer-v4 off reason=network-change net=wifi:7 was=cell:104",
                mEnv.lines.get(1));
        mEnv.network = "cell:104";
        assertFalse(mPreference.isPreferV4Active()); // cleared, not suspended
    }

    @Test
    public void anIpv4FailureGivesIpv6ItsChanceBack() throws Exception {
        markStall();
        MediaAddressPreference.CallWatch call = mPreference.newCallWatch(EDGE);
        call.connectFailed(EDGE, v4(1), new SocketTimeoutException("connect timed out"));

        assertFalse(mPreference.isPreferV4Active());
        assertTrue(mEnv.lines.get(1).startsWith("media-dns prefer-v4 off reason=v4-failed host="));
    }

    @Test
    public void aRepeatStallExtendsWithoutRelogging() throws Exception {
        markStall();
        mEnv.now += MediaAddressPreference.PREFER_V4_MS - 1;
        markStall();
        mEnv.now += 10;

        assertTrue(mPreference.isPreferV4Active());
        assertEquals(1, mEnv.lines.size());
    }

    @Test
    public void onlyAFilteredCallWhoseIpv4RoutesAllFailedIsRetriedOnce() throws Exception {
        MediaAddressPreference.CallWatch unfiltered = mPreference.newCallWatch(EDGE);
        unfiltered.connectFailed(EDGE, v4(1), new SocketException("Connection refused"));
        assertFalse(unfiltered.claimUnfilteredRetry()); // it already had both families

        markStall();
        MediaAddressPreference.CallWatch connected = mPreference.newCallWatch(EDGE);
        connected.connectEnd(EDGE, v4(1));
        assertFalse(connected.claimUnfilteredRetry()); // no route failed

        MediaAddressPreference.CallWatch failed = mPreference.newCallWatch(EDGE);
        failed.connectFailed(EDGE, v4(1), new SocketException("Connection refused"));
        assertTrue(failed.claimUnfilteredRetry());
        assertFalse(failed.claimUnfilteredRetry()); // once per call
        assertFalse(mPreference.isPreferV4Active()); // and the failure cleared the mark
    }

    @Test
    public void dnsWrapperAppliesThePreferenceToTheUpstreamAnswer() throws Exception {
        List<InetAddress> answer = Arrays.asList(v6(1), v4(1), v6(2));
        okhttp3.Dns dns = mPreference.dns(hostname -> answer);
        assertSame(answer, dns.lookup(EDGE));

        markStall();
        assertEquals(Collections.singletonList(v4(1)), dns.lookup(EDGE));
    }

    @Test
    public void okHttpEventsReachTheWatchWithTheCallHost() throws Exception {
        Call call = new OkHttpClient().newCall(new Request.Builder()
                .url("https://" + EDGE + "/videoplayback?itag=251").build());
        MediaHttpClient.RouteListener listener =
                new MediaHttpClient.RouteListener(mPreference.newCallWatch(call));

        listener.connectStart(call, new InetSocketAddress(v6(1), 443), Proxy.NO_PROXY);
        listener.connectFailed(call, new InetSocketAddress(v6(1), 443), Proxy.NO_PROXY, null,
                new SocketTimeoutException("Read timed out"));
        listener.connectStart(call, new InetSocketAddress(v4(1), 443), Proxy.NO_PROXY);
        listener.connectEnd(call, new InetSocketAddress(v4(1), 443), Proxy.NO_PROXY, null);

        assertTrue(mPreference.isPreferV4Active());
        assertSame(mPreference.watchFor(call), mPreference.watchFor(call));
    }

    /**
     * End to end through a real OkHttp client: the mark is active, but IPv4 is the broken family
     * for this host (nothing listens on its IPv4 address). The same request must still succeed
     * over IPv6 instead of surfacing a source error.
     */
    @Test
    public void aFilteredRequestWhoseIpv4FailsSucceedsOverIpv6InTheSameCall() throws Exception {
        InetAddress v6Loopback = InetAddress.getByName("::1");
        ServerSocket server;
        try {
            server = new ServerSocket(0, 4, v6Loopback);
        } catch (IOException e) {
            Assume.assumeTrue("no IPv6 loopback here", false);
            return;
        }
        int port = server.getLocalPort();
        InetAddress deadV4 = InetAddress.getByAddress(EDGE, new byte[] {127, 0, 0, 1});
        InetAddress liveV6 = InetAddress.getByAddress(EDGE, v6Loopback.getAddress());
        List<List<InetAddress>> answers = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        OkHttpClient client = null;
        try {
            executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    for (String line; (line = reader.readLine()) != null && !line.isEmpty();) {
                        // drain the request headers
                    }
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n"
                            + "Connection: close\r\n\r\nok").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
                return null;
            });
            markStall();
            client = MediaHttpClient.withAddressPreference(new OkHttpClient.Builder()
                            .fastFallback(true)
                            .connectTimeout(2, TimeUnit.SECONDS)
                            .readTimeout(2, TimeUnit.SECONDS),
                    mPreference, hostname -> {
                        List<InetAddress> all = Arrays.asList(liveV6, deadV4);
                        List<InetAddress> seen = mPreference.order(hostname, all);
                        answers.add(seen);
                        return all;
                    })
                    .build();

            try (Response response = client.newCall(new Request.Builder()
                    .url("http://" + EDGE + ":" + port + "/videoplayback?itag=251").build())
                    .execute()) {
                assertEquals(200, response.code());
                assertEquals("ok", response.body().string());
            }
            // The first resolution was IPv4-only, the retry saw both families.
            assertEquals(Collections.singletonList(deadV4), answers.get(0));
            assertEquals(2, answers.get(answers.size() - 1).size());
            assertTrue(mEnv.lines.stream().anyMatch(line -> line.startsWith(
                    "media-dns prefer-v4 off reason=v4-failed")));
            assertTrue(mEnv.lines.stream().anyMatch(line -> line.startsWith(
                    "media-dns prefer-v4 retry=unfiltered reason=v4-routes-failed host=" + EDGE)));
        } finally {
            server.close();
            executor.shutdownNow();
            if (client != null) {
                client.dispatcher().executorService().shutdown();
                client.connectionPool().evictAll();
            }
        }
    }

    private void markStall() throws UnknownHostException {
        MediaAddressPreference.CallWatch call = mPreference.newCallWatch(EDGE);
        call.connectFailed(EDGE, v6(1), new IOException("tls", new SocketTimeoutException()));
        call.connectEnd(EDGE, v4(1));
    }

    private static InetAddress v4(int last) throws UnknownHostException {
        return InetAddress.getByAddress(EDGE, new byte[] {(byte) 173, (byte) 194, 1, (byte) last});
    }

    private static InetAddress v6(int last) throws UnknownHostException {
        byte[] address = new byte[16];
        address[0] = 0x2a;
        address[1] = 0x00;
        address[15] = (byte) last;
        return InetAddress.getByAddress(EDGE, address);
    }

    private static final class FakeEnv implements MediaAddressPreference.Env {
        long now = 1_000;
        @Nullable volatile String network = "cell:104";
        final List<String> lines = new CopyOnWriteArrayList<>();

        @Override
        public long nowMs() {
            return now;
        }

        @Nullable
        @Override
        public String networkKey() {
            return network;
        }

        @Override
        public void log(String line) {
            lines.add(line);
        }
    }
}
