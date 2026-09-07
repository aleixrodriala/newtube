package com.newtube.mobile.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.Authenticator;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/** Verifies transport isolation and real ranged bytes using only synthetic localhost traffic. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MediaHttpClientTest {
    @Test
    public void mediaUsesStockTlsAndHostnameVerificationInsteadOfSharedOverrides() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        X509TrustManager trustManager = null;
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) {
                trustManager = (X509TrustManager) manager;
                break;
            }
        }
        SSLContext customContext = SSLContext.getInstance("TLS");
        customContext.init(null, new TrustManager[] {trustManager}, null);
        HostnameVerifier sharedVerifier = (hostname, session) -> false;
        OkHttpClient shared = new OkHttpClient.Builder()
                .sslSocketFactory(customContext.getSocketFactory(), trustManager)
                .hostnameVerifier(sharedVerifier)
                .build();
        OkHttpClient stock = new OkHttpClient();
        OkHttpClient media = MediaHttpClient.create(shared);

        assertNotSame(shared.sslSocketFactory(), media.sslSocketFactory());
        assertNotSame(sharedVerifier, media.hostnameVerifier());
        assertSame(stock.hostnameVerifier(), media.hostnameVerifier());
        assertArrayEquals(stock.x509TrustManager().getAcceptedIssuers(),
                media.x509TrustManager().getAcceptedIssuers());
        assertEquals(stock.connectionSpecs(), media.connectionSpecs());
        assertEquals(stock.certificatePinner(), media.certificatePinner());
    }

    @Test
    public void mediaRetainsSharedPoolExplicitProxyAndProxyAuthentication() {
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 9));
        Authenticator proxyAuthenticator = (route, response) -> null;
        OkHttpClient shared = new OkHttpClient.Builder()
                .proxy(proxy).proxyAuthenticator(proxyAuthenticator).build();
        OkHttpClient media = MediaHttpClient.create(shared);

        assertSame(shared.connectionPool(), media.connectionPool());
        assertSame(proxy, media.proxy());
        assertSame(proxyAuthenticator, media.proxyAuthenticator());
    }

    @Test
    public void mediaRetainsProxySelectorWhenThereIsNoExplicitProxy() {
        ProxySelector selector = new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                return Collections.singletonList(Proxy.NO_PROXY);
            }

            @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
        };
        OkHttpClient shared = new OkHttpClient.Builder().proxySelector(selector).build();
        OkHttpClient media = MediaHttpClient.create(shared);

        assertNull(media.proxy());
        assertSame(selector, media.proxySelector());
    }

    @Test
    public void inactivityLimitsAndFastFallbackDoNotInheritAWholeStreamDeadline() {
        OkHttpClient shared = new OkHttpClient.Builder()
                .callTimeout(1, TimeUnit.MILLISECONDS)
                .connectTimeout(1, TimeUnit.MILLISECONDS)
                .readTimeout(1, TimeUnit.MILLISECONDS)
                .writeTimeout(1, TimeUnit.MILLISECONDS)
                .fastFallback(false)
                .build();
        OkHttpClient media = MediaHttpClient.create(shared);

        assertEquals(0, media.callTimeoutMillis());
        assertEquals(8_000, media.connectTimeoutMillis());
        assertEquals(4_000, media.readTimeoutMillis());
        assertEquals(4_000, media.writeTimeoutMillis());
        assertTrue(media.fastFallback());
    }

    @Test
    public void adapterReturnsExact206RangeWithoutApiHeadersCookiesOrInterceptors() throws Exception {
        byte[] original = fixtureBytes();
        byte[] expected = Arrays.copyOfRange(original, 17, 23);
        AtomicInteger apiActivity = new AtomicInteger();
        OkHttpClient media = MediaHttpClient.create(apiClient(apiActivity));
        assertTrue(media.interceptors().isEmpty());
        assertTrue(media.networkInterceptors().isEmpty());
        assertSame(CookieJar.NO_COOKIES, media.cookieJar());
        assertSame(Authenticator.NONE, media.authenticator());

        try (LocalResponse server = new LocalResponse("206 Partial Content",
                "Content-Range: bytes 17-22/64\r\n", expected)) {
            DataSpec spec = rangeSpec(server);
            assertArrayEquals(expected, readRange(media, spec));
            Map<String, String> request = server.request();
            assertEquals("GET /media?fixture=range HTTP/1.1", request.get(":request"));
            assertEquals("bytes=17-22", request.get("range"));
            assertEquals("NewTube-local-test", request.get("user-agent"));
            assertEquals("local-test", request.get("x-media-header"));
            assertNull(request.get("cookie"));
            assertNull(request.get("authorization"));
            assertNull(request.get("x-api-only"));
            assertNull(request.get("x-api-network-only"));
            assertEquals(0, apiActivity.get());
        } finally {
            closeClient(media);
        }
    }

    @Test
    public void adapterSkipsIgnoredRangeExactlyOnceAndStopsAtRequestedLength() throws Exception {
        byte[] original = fixtureBytes();
        OkHttpClient media = MediaHttpClient.create(new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build());
        try (LocalResponse server = new LocalResponse("200 OK", "", original)) {
            assertArrayEquals(Arrays.copyOfRange(original, 17, 23), readRange(media, rangeSpec(server)));
            assertEquals("bytes=17-22", server.request().get("range"));
        } finally {
            closeClient(media);
        }
    }

    @Test
    public void rejectedRangeIsTerminalAndDoesNotRequestAnotherTransport() throws Exception {
        OkHttpClient media = MediaHttpClient.create(new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build());
        OkHttpDataSource source = new OkHttpDataSource.Factory(media).createDataSource();
        try (LocalResponse server = new LocalResponse("403 Forbidden", "", new byte[0])) {
            DataSpec spec = rangeSpec(server);
            HttpDataSource.InvalidResponseCodeException failure = assertThrows(
                    HttpDataSource.InvalidResponseCodeException.class, () -> source.open(spec));
            assertEquals(403, failure.responseCode);
            assertSame(spec, failure.dataSpec);
            assertEquals("bytes=17-22", server.request().get("range"));

            AtomicInteger transportChanges = new AtomicInteger();
            Media3SourceFactory.FailFastLoadErrorPolicy policy =
                    new Media3SourceFactory.FailFastLoadErrorPolicy(transportChanges::incrementAndGet);
            LoadEventInfo event = new LoadEventInfo(1, spec, spec.uri, Collections.emptyMap(), 0, 5, 0);
            for (int dataType : new int[] {C.DATA_TYPE_MEDIA, C.DATA_TYPE_MEDIA_INITIALIZATION}) {
                assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(
                        new LoadErrorHandlingPolicy.LoadErrorInfo(event,
                                new MediaLoadData(dataType), new IOException("chunk", failure), 1)));
            }
            assertEquals(0, transportChanges.get());
        } finally {
            source.close();
            closeClient(media);
        }
    }

    @Test
    public void originChallengeCannotInvokeTheApiAuthenticator() throws Exception {
        AtomicInteger apiActivity = new AtomicInteger();
        OkHttpClient media = MediaHttpClient.create(apiClient(apiActivity));
        OkHttpDataSource source = new OkHttpDataSource.Factory(media).createDataSource();
        try (LocalResponse server = new LocalResponse("401 Unauthorized",
                "WWW-Authenticate: Basic realm=local-test\r\n", new byte[0])) {
            HttpDataSource.InvalidResponseCodeException failure = assertThrows(
                    HttpDataSource.InvalidResponseCodeException.class, () -> source.open(rangeSpec(server)));
            assertEquals(401, failure.responseCode);
            assertNull(server.request().get("authorization"));
            assertEquals(0, apiActivity.get());
        } finally {
            source.close();
            closeClient(media);
        }
    }

    private static void closeClient(OkHttpClient media) {
        media.dispatcher().cancelAll();
        media.connectionPool().evictAll();
        media.dispatcher().executorService().shutdownNow();
    }

    private static OkHttpClient apiClient(AtomicInteger activity) {
        return new OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .callTimeout(1, TimeUnit.MILLISECONDS)
                .addInterceptor(chain -> {
                    activity.incrementAndGet();
                    return chain.proceed(chain.request().newBuilder().header("X-Api-Only", "test").build());
                })
                .addNetworkInterceptor(chain -> {
                    activity.incrementAndGet();
                    return chain.proceed(chain.request().newBuilder().header("X-Api-Network-Only", "test").build());
                })
                .cookieJar(new CookieJar() {
                    @Override public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                        activity.incrementAndGet();
                    }

                    @Override public List<Cookie> loadForRequest(HttpUrl url) {
                        activity.incrementAndGet();
                        return Collections.singletonList(new Cookie.Builder()
                                .name("api-session").value("local-test").hostOnlyDomain(url.host()).build());
                    }
                })
                .authenticator((route, response) -> {
                    activity.incrementAndGet();
                    return null;
                })
                .build();
    }

    private static DataSpec rangeSpec(LocalResponse server) {
        return new DataSpec.Builder().setUri(server.url())
                .setPosition(17).setLength(6)
                .setHttpRequestHeaders(Collections.singletonMap("X-Media-Header", "local-test"))
                .build();
    }

    private static byte[] readRange(OkHttpClient media, DataSpec spec) throws IOException {
        OkHttpDataSource source = new OkHttpDataSource.Factory(media)
                .setUserAgent("NewTube-local-test").createDataSource();
        try {
            assertEquals(6, source.open(spec));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[2];
            for (int count; (count = source.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT;) {
                result.write(buffer, 0, count);
            }
            return result.toByteArray();
        } finally {
            source.close();
        }
    }

    private static byte[] fixtureBytes() {
        byte[] bytes = new byte[64];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 3 + 1);
        }
        return bytes;
    }

    private static final class LocalResponse implements AutoCloseable {
        private final ServerSocket mServer;
        private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
        private final Future<Map<String, String>> mRequest;

        LocalResponse(String status, String responseHeaders, byte[] body) throws IOException {
            mServer = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            mServer.setSoTimeout(2_000);
            mRequest = mExecutor.submit(() -> {
                try (Socket socket = mServer.accept()) {
                    socket.setSoTimeout(2_000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    Map<String, String> headers = new LinkedHashMap<>();
                    headers.put(":request", reader.readLine());
                    for (String line; (line = reader.readLine()) != null && !line.isEmpty();) {
                        int separator = line.indexOf(':');
                        if (separator > 0) {
                            headers.put(line.substring(0, separator).toLowerCase(Locale.ROOT),
                                    line.substring(separator + 1).trim());
                        }
                    }
                    socket.getOutputStream().write(("HTTP/1.1 " + status + "\r\n"
                            + responseHeaders + "Content-Length: " + body.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                    return headers;
                }
            });
        }

        String url() {
            return "http://127.0.0.1:" + mServer.getLocalPort() + "/media?fixture=range";
        }

        Map<String, String> request() throws Exception {
            return mRequest.get(3, TimeUnit.SECONDS);
        }

        @Override public void close() throws IOException {
            mServer.close();
            mExecutor.shutdownNow();
        }
    }
}
