package com.newtube.mobile.casting.proxy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.OkHttpClient;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end proxy test on localhost: a raw-socket fake upstream (no MockWebServer on the test
 * classpath; adding deps is off-limits) records exactly what the proxy sends and returns canned
 * responses. The load-bearing assertions are the GOOGLEVIDEO_RANGE_QUERY rules: the Range header
 * passes through unchanged, the query string is NEVER touched, and upstream 206/416 + range
 * headers mirror back verbatim.
 */
public class CastProxyServerTest {
    private FakeUpstream mUpstream;
    private CastProxyServer mServer;
    private int mPort;

    @Before
    public void setUp() throws IOException {
        mUpstream = new FakeUpstream();
        mServer = new CastProxyServer(new OkHttpClient());
        mServer.mAllowAnyUpstreamHost = true; // tokens point at the localhost fake
        mPort = mServer.start();
    }

    @After
    public void tearDown() {
        mServer.stop();
        mUpstream.close();
    }

    // ---------------------------------------------------------------------------------
    // Manifest endpoint
    // ---------------------------------------------------------------------------------

    @Test
    public void manifestServedWithDashMimeAndSwappedAtomically() throws IOException {
        byte[] first = "<MPD>one</MPD>".getBytes(StandardCharsets.UTF_8);
        mServer.loadVideo(first);
        Http response = request("GET", "/manifest.mpd", null);
        assertEquals(200, response.code);
        assertEquals("application/dash+xml", response.header("Content-Type"));
        assertArrayEquals(first, response.body);

        byte[] second = "<MPD>two</MPD>".getBytes(StandardCharsets.UTF_8);
        mServer.loadVideo(second);
        assertArrayEquals(second, request("GET", "/manifest.mpd", null).body);
    }

    @Test
    public void manifestIs404BeforeAnyLoad() throws IOException {
        assertEquals(404, request("GET", "/manifest.mpd", null).code);
    }

    // ---------------------------------------------------------------------------------
    // Segment relay: the Range rules
    // ---------------------------------------------------------------------------------

    @Test
    public void rangePassesThroughUnchangedAnd206Mirrors() throws IOException {
        byte[] media = new byte[100];
        for (int i = 0; i < media.length; i++) {
            media[i] = (byte) i;
        }
        mUpstream.nextStatus = 206;
        mUpstream.nextHeaders.put("Content-Type", "video/mp4");
        mUpstream.nextHeaders.put("Content-Range", "bytes 100-199/123456789");
        mUpstream.nextHeaders.put("Accept-Ranges", "bytes");
        mUpstream.nextBody = media;

        String upstreamUrl = "http://127.0.0.1:" + mUpstream.port()
                + "/videoplayback?id=o-abc&itag=137&mime=video%2Fmp4&sig=AJfQ";
        Map<String, String> requestHeaders = new HashMap<>();
        requestHeaders.put("Range", "bytes=100-199");
        Http response = request("GET", "/seg/" + SegmentToken.encode(upstreamUrl), requestHeaders);

        // Mirrored downstream: status + range/cache headers + body.
        assertEquals(206, response.code);
        assertEquals("video/mp4", response.header("Content-Type"));
        assertEquals("bytes 100-199/123456789", response.header("Content-Range"));
        assertEquals("bytes", response.header("Accept-Ranges"));
        assertEquals("100", response.header("Content-Length"));
        assertArrayEquals(media, response.body);

        // Upstream saw the URL byte-for-byte: full query, and NO range/rn mirrored into it
        // (GOOGLEVIDEO_RANGE_QUERY post-mortem - the hard rule this proxy exists to respect).
        assertEquals("GET /videoplayback?id=o-abc&itag=137&mime=video%2Fmp4&sig=AJfQ HTTP/1.1",
                mUpstream.lastRequestLine);
        assertFalse(mUpstream.lastRequestLine.contains("range="));
        assertFalse(mUpstream.lastRequestLine.contains("rn="));
        // ... and the Range header arrived UNCHANGED.
        assertEquals("bytes=100-199", mUpstream.lastHeaders.get("range"));
        // The relay presents the app's player UA, not a proxy identity.
        assertTrue(mUpstream.lastHeaders.get("user-agent").startsWith("Mozilla/5.0 (Linux; Android 12)"));
    }

    @Test
    public void noRangeHeaderIsInventedWhenClientSendsNone() throws IOException {
        mUpstream.nextStatus = 200;
        mUpstream.nextHeaders.put("Content-Type", "video/mp4");
        mUpstream.nextBody = "full-body".getBytes(StandardCharsets.UTF_8);

        String upstreamUrl = "http://127.0.0.1:" + mUpstream.port() + "/videoplayback?itag=140";
        Http response = request("GET", "/seg/" + SegmentToken.encode(upstreamUrl), null);

        assertEquals(200, response.code);
        assertArrayEquals("full-body".getBytes(StandardCharsets.UTF_8), response.body);
        assertNull(mUpstream.lastHeaders.get("range"));
    }

    @Test
    public void upstream416MirrorsBack() throws IOException {
        mUpstream.nextStatus = 416;
        mUpstream.nextHeaders.put("Content-Range", "bytes */123456789");
        mUpstream.nextBody = new byte[0];

        String upstreamUrl = "http://127.0.0.1:" + mUpstream.port() + "/videoplayback?itag=137";
        Map<String, String> requestHeaders = new HashMap<>();
        requestHeaders.put("Range", "bytes=999999999999-");
        Http response = request("GET", "/seg/" + SegmentToken.encode(upstreamUrl), requestHeaders);

        assertEquals(416, response.code);
        assertEquals("bytes */123456789", response.header("Content-Range"));
        assertEquals("bytes=999999999999-", mUpstream.lastHeaders.get("range"));
    }

    @Test
    public void headIsForwardedAsHead() throws IOException {
        mUpstream.nextStatus = 200;
        mUpstream.nextHeaders.put("Content-Type", "video/mp4");
        mUpstream.nextBody = "not-sent-for-head".getBytes(StandardCharsets.UTF_8);

        String upstreamUrl = "http://127.0.0.1:" + mUpstream.port() + "/videoplayback?itag=137";
        Http response = request("HEAD", "/seg/" + SegmentToken.encode(upstreamUrl), null);

        assertEquals(200, response.code);
        assertEquals(0, response.body.length);
        assertTrue(mUpstream.lastRequestLine.startsWith("HEAD /videoplayback?itag=137"));
    }

    @Test
    public void customUserAgentIsForwarded() throws IOException {
        mServer.setUserAgent("NewTubeTest/1.0");
        mUpstream.nextStatus = 200;
        mUpstream.nextBody = "x".getBytes(StandardCharsets.UTF_8);

        String upstreamUrl = "http://127.0.0.1:" + mUpstream.port() + "/videoplayback?itag=140";
        request("GET", "/seg/" + SegmentToken.encode(upstreamUrl), null);

        assertEquals("NewTubeTest/1.0", mUpstream.lastHeaders.get("user-agent"));
    }

    // ---------------------------------------------------------------------------------
    // Error mapping
    // ---------------------------------------------------------------------------------

    @Test
    public void badTokenIs404() throws IOException {
        assertEquals(404, request("GET", "/seg/@@not-base64@@", null).code);
    }

    @Test
    public void unknownPathIs404() throws IOException {
        assertEquals(404, request("GET", "/nope", null).code);
    }

    @Test
    public void nonAllowlistedUpstreamHostIs403() throws IOException {
        mServer.mAllowAnyUpstreamHost = false; // production behavior
        String token = SegmentToken.encode("http://127.0.0.1:" + mUpstream.port() + "/videoplayback");
        assertEquals(403, request("GET", "/seg/" + token, null).code);
        assertNull(mUpstream.lastRequestLine); // never contacted
    }

    @Test
    public void upstreamFailureIs502() throws IOException {
        // A port nothing listens on: connection refused, instantly.
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }
        String token = SegmentToken.encode("http://127.0.0.1:" + deadPort + "/videoplayback");
        assertEquals(502, request("GET", "/seg/" + token, null).code);
    }

    // ---------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------

    private static final class Http {
        int code;
        Map<String, List<String>> headers;
        byte[] body;

        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (name.equalsIgnoreCase(entry.getKey()) && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }
    }

    private Http request(String method, String path, Map<String, String> headers) throws IOException {
        HttpURLConnection conn =
                (HttpURLConnection) new URL("http://127.0.0.1:" + mPort + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);
        conn.setRequestProperty("Connection", "close");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        Http result = new Http();
        result.code = conn.getResponseCode();
        result.headers = conn.getHeaderFields();
        InputStream in = result.code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        result.body = in != null ? readAll(in) : new byte[0];
        conn.disconnect();
        return result;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        in.close();
        return buffer.toByteArray();
    }

    /**
     * Raw-socket fake googlevideo edge: records the last request line + headers (names
     * lowercased) and answers with a configurable canned response.
     */
    private static final class FakeUpstream implements Closeable {
        final ServerSocket serverSocket;
        volatile String lastRequestLine;
        volatile Map<String, String> lastHeaders = new HashMap<>();
        volatile int nextStatus = 200;
        final Map<String, String> nextHeaders = new LinkedHashMap<>();
        volatile byte[] nextBody = new byte[0];

        FakeUpstream() throws IOException {
            serverSocket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            Thread thread = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try (Socket socket = serverSocket.accept()) {
                        handle(socket);
                    } catch (IOException e) {
                        return; // closed
                    }
                }
            }, "FakeUpstream");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void handle(Socket socket) throws IOException {
            InputStream in = socket.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null) {
                return;
            }
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                            line.substring(colon + 1).trim());
                }
            }
            lastRequestLine = requestLine;
            lastHeaders = headers;

            OutputStream out = socket.getOutputStream();
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(nextStatus).append(" Canned\r\n");
            for (Map.Entry<String, String> entry : nextHeaders.entrySet()) {
                head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
            head.append("Content-Length: ").append(nextBody.length).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (!requestLine.startsWith("HEAD ")) {
                out.write(nextBody);
            }
            out.flush();
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder(64);
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    int len = sb.length();
                    if (len > 0 && sb.charAt(len - 1) == '\r') {
                        sb.setLength(len - 1);
                    }
                    return sb.toString();
                }
                sb.append((char) b);
            }
            return sb.length() > 0 ? sb.toString() : null;
        }

        @Override
        public void close() {
            try {
                serverSocket.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
