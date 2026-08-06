package com.newtube.mobile.casting.proxy;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.okhttp.OkHttpManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Phone-side media proxy for Route A "Direct cast" (CASTING.md): a Chromecast's Default Media
 * Receiver cannot fetch googlevideo URLs itself (IP-bound URLs, PoToken/UA identity, expiry), so
 * the phone serves the rewritten DASH manifest ({@link MpdRewriter}) and relays every segment
 * fetch through the app's own authenticated network path. Network relay only - no transcoding.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code GET /manifest.mpd} - the currently loaded video's rewritten manifest (404 if none).</li>
 *   <li>{@code GET|HEAD /seg/<token>} - relay of the tokenized upstream URL. The inbound
 *       {@code Range} header passes through to upstream UNCHANGED, and range information is NEVER
 *       mirrored into query params - googlevideo prioritizes the query and answers 416 / offset
 *       200s that poison caches (see the GOOGLEVIDEO_RANGE_QUERY post-mortem in HANDOFF.md).
 *       Upstream status (200/206/416) and Content-Type / Content-Length / Content-Range /
 *       Accept-Ranges mirror back verbatim; bodies stream through a fixed buffer.</li>
 * </ul>
 *
 * <p>Security: the server is deliberately unauthenticated on the LAN - a Chromecast cannot present
 * credentials. Exposure is bounded: it only ever serves the manifest of the video currently being
 * cast, and only relays URLs that were already handed out inside that manifest; tokens are opaque
 * but not secrets. To keep it from becoming an open relay, {@code /seg} refuses any decoded URL
 * whose host is not *.googlevideo.com / *.youtube.com.</p>
 *
 * <p>Single-slot rule (CLAUDE.md): {@link #loadVideo(byte[])} swaps only the manifest reference.
 * In-flight {@code /seg} relays for the PREVIOUS video keep working across the swap because
 * tokens are self-contained upstream URLs - the segment path never consults the manifest slot.
 * Keep it that way: routing {@code /seg} through any per-video state would make a new load kill
 * the old video's tail requests mid-stream.</p>
 */
public class CastProxyServer {
    private static final String TAG = CastProxyServer.class.getSimpleName();

    /**
     * Keep in sync with Media3SourceFactory.USER_AGENT (private there; duplicated so this package
     * stays standalone). The relay must present the same UA as the app's player traffic - the
     * googlevideo URLs are tied to the identity that requested them. Integrator can override via
     * {@link #setUserAgent(String)}.
     */
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36";

    /** Manifest + audio + video arrive concurrently; a few spare for ranged retries. */
    private static final int POOL_SIZE = 6;
    private static final int SOCKET_TIMEOUT_MS = 15_000;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final int MAX_REQUEST_LINE_BYTES = 8192;

    /**
     * On every response (success AND error): the Default Media Receiver runs as an https page on
     * a gstatic origin and fetches adaptive media via XHR - without CORS the receiver's browser
     * silently discards the response and reports LOAD_FAILED (Grayjay sets the same header on all
     * of its cast handlers). Expose the range/length headers so MPL can read them cross-origin.
     */
    private static final String CORS_HEADERS =
            "Access-Control-Allow-Origin: *\r\n"
                    + "Access-Control-Expose-Headers: Content-Type, Content-Length, Content-Range, Accept-Ranges\r\n";

    private static final Map<Integer, String> REASONS = new HashMap<>();

    static {
        REASONS.put(200, "OK");
        REASONS.put(206, "Partial Content");
        REASONS.put(400, "Bad Request");
        REASONS.put(403, "Forbidden");
        REASONS.put(404, "Not Found");
        REASONS.put(405, "Method Not Allowed");
        REASONS.put(416, "Range Not Satisfiable");
        REASONS.put(500, "Internal Server Error");
        REASONS.put(502, "Bad Gateway");
    }

    @Nullable
    private final OkHttpClient mInjectedClient;
    private final Set<Socket> mOpenSockets = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicInteger mThreadIndex = new AtomicInteger();

    private volatile ServerSocket mServerSocket;
    private volatile ExecutorService mPool;
    private volatile Thread mAcceptThread;
    private volatile boolean mStopped;
    /** The single manifest slot - see the class javadoc for why /seg never reads it. */
    private volatile byte[] mManifest;
    private volatile String mUserAgent = DEFAULT_USER_AGENT;
    /** Test hook (package-private): lets unit tests point tokens at a localhost fake upstream. */
    volatile boolean mAllowAnyUpstreamHost;

    public CastProxyServer() {
        this(null);
    }

    /** @param upstreamClient client for googlevideo fetches; null = the app's shared OkHttp client */
    public CastProxyServer(@Nullable OkHttpClient upstreamClient) {
        mInjectedClient = upstreamClient;
    }

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    /**
     * Binds to a random free port on all interfaces (it serves LAN peers) and starts accepting.
     *
     * @return the bound port
     */
    public synchronized int start() throws IOException {
        if (mServerSocket != null) {
            return mServerSocket.getLocalPort();
        }
        mStopped = false;
        mServerSocket = new ServerSocket(0);
        mPool = Executors.newFixedThreadPool(POOL_SIZE, runnable -> {
            Thread thread = new Thread(runnable, "CastProxyWorker-" + mThreadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        mAcceptThread = new Thread(this::acceptLoop, "CastProxyAccept");
        mAcceptThread.setDaemon(true);
        mAcceptThread.start();
        int port = mServerSocket.getLocalPort();
        ProxyLog.d(TAG, "started on port " + port);
        return port;
    }

    /** Closes the listener, the worker pool and any open client sockets. Idempotent. */
    public synchronized void stop() {
        mStopped = true;
        ServerSocket serverSocket = mServerSocket;
        mServerSocket = null;
        if (serverSocket != null) {
            closeQuietly(serverSocket);
        }
        ExecutorService pool = mPool;
        mPool = null;
        if (pool != null) {
            pool.shutdownNow();
        }
        // Blocking socket I/O is not interruptible - close the sockets to unblock workers.
        for (Socket socket : mOpenSockets) {
            closeQuietly(socket);
        }
        mOpenSockets.clear();
        mAcceptThread = null;
        mManifest = null;
        ProxyLog.d(TAG, "stopped");
    }

    /**
     * Atomically replaces the manifest served at {@code /manifest.mpd}. One video at a time - this
     * is a cast session, not a CDN. Old-video {@code /seg} relays already in flight are unaffected
     * (tokens are self-contained; see class javadoc).
     */
    public void loadVideo(byte[] rewrittenMpd) {
        mManifest = rewrittenMpd;
    }

    /** Bound port, or -1 when not started. */
    public int getPort() {
        ServerSocket serverSocket = mServerSocket;
        return serverSocket != null ? serverSocket.getLocalPort() : -1;
    }

    /** Base URL for {@link MpdRewriter}, e.g. {@code http://192.168.1.23:40123}. Null off-wifi/stopped. */
    @Nullable
    public String getBaseUrl() {
        String ip = getLocalIpv4();
        int port = getPort();
        return ip != null && port > 0 ? "http://" + ip + ":" + port : null;
    }

    /** The URL to hand to the Cast LOAD request. Null when not started or no wifi IPv4. */
    @Nullable
    public String getManifestUrl() {
        String base = getBaseUrl();
        return base != null ? base + "/manifest.mpd" : null;
    }

    /** Override the upstream User-Agent (must match the identity that fetched the video info). */
    public void setUserAgent(String userAgent) {
        mUserAgent = userAgent;
    }

    /**
     * The phone's wifi IPv4. Prefers wlan interfaces, then any RFC1918 site-local address;
     * loopback and IPv6 are skipped (Chromecast receivers are v4-first on home LANs).
     */
    @Nullable
    public static String getLocalIpv4() {
        try {
            String fallback = null;
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                        continue;
                    }
                    String ip = address.getHostAddress();
                    if (nif.getName().startsWith("wlan")) {
                        return ip;
                    }
                    if (address.isSiteLocalAddress() && fallback == null) {
                        fallback = ip;
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            ProxyLog.e(TAG, "getLocalIpv4 failed: " + e);
            return null;
        }
    }

    // ---------------------------------------------------------------------------------
    // Accept loop + request handling
    // ---------------------------------------------------------------------------------

    private void acceptLoop() {
        ServerSocket serverSocket = mServerSocket;
        while (!mStopped && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                ExecutorService pool = mPool;
                if (pool == null) {
                    closeQuietly(socket);
                    break;
                }
                pool.execute(() -> handleConnection(socket));
            } catch (IOException e) {
                if (!mStopped) {
                    ProxyLog.e(TAG, "accept failed: " + e);
                }
                break;
            }
        }
    }

    private void handleConnection(Socket socket) {
        mOpenSockets.add(socket);
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return; // client connected and went away - normal
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) {
                writeSimpleResponse(out, 400, "malformed request line");
                return;
            }
            String method = parts[0];
            String target = parts[1];

            Map<String, String> headers = readHeaders(in);
            if (headers == null) {
                writeSimpleResponse(out, 400, "malformed headers");
                return;
            }

            int query = target.indexOf('?');
            String path = query >= 0 ? target.substring(0, query) : target;
            ProxyLog.d(TAG, "request: " + method + " " + path);

            // CORS preflight: the Default Media Receiver is an https gstatic-origin page doing
            // XHR against this server - without CORS the browser discards every response and the
            // LOAD dies as LOAD_FAILED with the fetch looking "successful" server-side. Ranged
            // GETs are non-simple requests, so the preflight MUST allow the Range header.
            if (method.equals("OPTIONS")) {
                writePreflightResponse(out, headers.get("access-control-request-headers"));
                return;
            }
            if (!method.equals("GET") && !method.equals("HEAD")) {
                writeSimpleResponse(out, 405, "only GET/HEAD");
                return;
            }
            boolean headOnly = method.equals("HEAD");

            if (path.equals("/manifest.mpd")) {
                serveManifest(out, headOnly);
            } else if (path.startsWith("/seg/")) {
                relaySegment(out, path.substring("/seg/".length()), headers, headOnly);
            } else {
                writeSimpleResponse(out, 404, "unknown path");
            }
        } catch (IOException e) {
            // Includes SocketTimeoutException and mid-parse disconnects - normal client behavior.
            ProxyLog.d(TAG, "connection dropped: " + e);
        } catch (Exception e) {
            ProxyLog.e(TAG, "handler error: " + e);
        } finally {
            mOpenSockets.remove(socket);
            closeQuietly(socket);
        }
    }

    private void serveManifest(OutputStream out, boolean headOnly) throws IOException {
        byte[] manifest = mManifest;
        if (manifest == null) {
            writeSimpleResponse(out, 404, "no video loaded");
            return;
        }
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 200 OK\r\n");
        head.append("Content-Type: ").append(MpdRewriter.MIME_TYPE).append("\r\n");
        head.append("Content-Length: ").append(manifest.length).append("\r\n");
        head.append(CORS_HEADERS);
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!headOnly) {
            out.write(manifest);
        }
        out.flush();
    }

    /**
     * Relay one segment fetch to its tokenized upstream URL.
     *
     * <p>HARD RULE (GOOGLEVIDEO_RANGE_QUERY post-mortem): the inbound {@code Range} header is
     * forwarded byte-for-byte and range information NEVER goes into the URL's query string.</p>
     */
    private void relaySegment(OutputStream out, String token, Map<String, String> headers,
            boolean headOnly) throws IOException {
        String upstreamUrl = SegmentToken.decode(token);
        if (upstreamUrl == null) {
            writeSimpleResponse(out, 404, "bad token");
            return;
        }
        if (!mAllowAnyUpstreamHost && !SegmentToken.isAllowedUpstream(upstreamUrl)) {
            ProxyLog.e(TAG, "refused non-allowlisted upstream host");
            writeSimpleResponse(out, 403, "upstream host not allowed");
            return;
        }

        Request.Builder builder = new Request.Builder().url(upstreamUrl)
                .header("User-Agent", mUserAgent)
                // Defeat OkHttp's transparent gzip so upstream Content-Length survives verbatim
                // (media bytes never compress anyway).
                .header("Accept-Encoding", "identity");
        String range = headers.get("range");
        if (range != null) {
            builder.header("Range", range); // UNCHANGED - never mirrored into query params
        }
        if (headOnly) {
            builder.head();
        }

        Response upstream;
        try {
            upstream = upstreamClient().newCall(builder.build()).execute();
        } catch (IOException e) {
            ProxyLog.e(TAG, "upstream fetch failed: " + e);
            writeSimpleResponse(out, 502, "upstream fetch failed");
            return;
        }
        try {
            mirrorResponse(out, upstream, headOnly);
        } finally {
            upstream.close();
        }
    }

    /** Mirror upstream status (200/206/416/...) + the cache/range headers, then stream the body. */
    private static void mirrorResponse(OutputStream out, Response upstream, boolean headOnly)
            throws IOException {
        int code = upstream.code();
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(' ').append(reasonOf(code)).append("\r\n");
        for (String header : new String[]{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"}) {
            String value = upstream.header(header);
            if (value != null) {
                head.append(header).append(": ").append(value).append("\r\n");
            }
        }
        boolean hasBody = !headOnly && upstream.body() != null && code != 204 && code != 304;
        boolean chunked = hasBody && upstream.header("Content-Length") == null;
        if (chunked) {
            head.append("Transfer-Encoding: chunked\r\n");
        }
        head.append(CORS_HEADERS);
        head.append("Connection: close\r\n\r\n");

        try {
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (hasBody) {
                copyBody(upstream.body().byteStream(), out, chunked);
            }
            out.flush();
        } catch (IOException e) {
            // Chromecast aborts ranged fetches constantly (seeks, ABR churn) - this is NORMAL.
            ProxyLog.d(TAG, "client closed mid-stream (normal for cast): " + e);
        }
    }

    /** Fixed-buffer streaming - never holds a whole segment in memory. */
    private static void copyBody(InputStream body, OutputStream out, boolean chunked)
            throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        int read;
        while ((read = body.read(buffer)) != -1) {
            if (chunked) {
                out.write(Integer.toHexString(read).getBytes(StandardCharsets.ISO_8859_1));
                out.write('\r');
                out.write('\n');
                out.write(buffer, 0, read);
                out.write('\r');
                out.write('\n');
            } else {
                out.write(buffer, 0, read);
            }
        }
        if (chunked) {
            out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    private OkHttpClient upstreamClient() {
        OkHttpClient injected = mInjectedClient;
        // Resolved lazily so unit tests never touch the app-configured singleton.
        // Streaming variant: the shared client now carries a 45s callTimeout (a total bound, so a
        // dribbling link can no longer hang a request forever). Proxying a media segment to a cast
        // device is a bulk transfer that can legitimately outlive that, so it takes the exempt
        // client - same pool and config, no total bound.
        return injected != null ? injected : OkHttpManager.instance().getStreamingClient();
    }

    // ---------------------------------------------------------------------------------
    // Minimal HTTP plumbing
    // ---------------------------------------------------------------------------------

    /** One header-safe line (CRLF or bare LF), ISO-8859-1. Null on EOF before any byte. */
    @Nullable
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(96);
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
            if (sb.length() > MAX_REQUEST_LINE_BYTES) {
                throw new IOException("request line too long");
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** Headers as a lowercase-name map. Null on malformed input. */
    @Nullable
    private static Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(in)) != null) {
            if (line.isEmpty()) {
                return headers;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                return null;
            }
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                    line.substring(colon + 1).trim());
        }
        return null; // EOF before the blank line
    }

    private static void writeSimpleResponse(OutputStream out, int code, String message) {
        try {
            byte[] body = message.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + code + ' ' + reasonOf(code) + "\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + CORS_HEADERS
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        } catch (IOException e) {
            ProxyLog.d(TAG, "client gone before error response: " + e);
        }
    }

    /** CORS preflight answer: allow any origin, GET/HEAD, and whatever headers were requested. */
    private static void writePreflightResponse(OutputStream out, @Nullable String requestedHeaders) {
        try {
            String head = "HTTP/1.1 204 No Content\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n"
                    + "Access-Control-Allow-Headers: "
                    + (requestedHeaders != null && !requestedHeaders.isEmpty() ? requestedHeaders : "Range")
                    + "\r\n"
                    + "Access-Control-Max-Age: 3600\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        } catch (IOException e) {
            ProxyLog.d(TAG, "client gone before preflight response: " + e);
        }
    }

    private static String reasonOf(int code) {
        String reason = REASONS.get(code);
        return reason != null ? reason : "Status";
    }

    private static void closeQuietly(@Nullable java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
