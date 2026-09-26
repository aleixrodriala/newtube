package com.newtube.mobile.player;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NEWTUBE(debug-blackhole): DEBUG/BENCHMARK-only simulation of a googlevideo edge host that
 * accepts nothing useful: matching media requests are re-pointed at a local TLS tarpit
 * (127.0.0.1, accepts the TCP connection and never answers the handshake). The REAL transports
 * then time out exactly as they do against a dead edge (Cronet's connection timeout, OkHttp's
 * handshake read timeout / call cancel), so the startup budget, the transport failover and the
 * app-level recovery are exercised end to end with no radio or router manipulation.
 *
 * <pre>
 *   adb shell setprop debug.arc.blackhole_via cronet    # only the Cronet legs (default: all)
 *   adb shell setprop debug.arc.blackhole_host any      # any googlevideo media host, or a
 *                                                       # host substring such as rr8--- / cjoe
 *   adb shell setprop debug.arc.blackhole_scope always  # default "episode": only the first
 *                                                       # open that hits it; later opens clean
 *   adb shell setprop debug.arc.blackhole_host ""       # off
 * </pre>
 *
 * <p>Scope "episode" re-arms whenever the host property VALUE changes (alternate {@code any} and
 * {@code googlevideo}); the default keeps the automatic recovery reload clean so the whole
 * dead-host episode can be read in one pass. Release builds never construct this class.</p>
 *
 * <p>NEWTUBE(media-path): {@code via cronet} + {@code scope always} is a Cronet-only TLS stall on
 * any network (the Movistar symptom): the first open learns the persisted {@code cronet-stall}
 * verdict for the current network, and the verdict's background Cronet re-probe hits the same
 * tarpit ({@link #probeAuthority}) until the host property is cleared - then the next due probe
 * answers and drops the verdict.</p>
 */
@UnstableApi
final class DebugHostBlackhole implements DataSource {

    static final String PROP_HOST = "debug.arc.blackhole_host";
    static final String PROP_VIA = "debug.arc.blackhole_via";
    static final String PROP_SCOPE = "debug.arc.blackhole_scope";

    @Nullable private static ServerSocket sTarpit;
    @Nullable private static String sArmedValue;
    @Nullable private static String sArmedEpisode;
    @Nullable private static String sLastLogKey;

    private final DataSource mUpstream;
    private final boolean mCronetLeg;

    private DebugHostBlackhole(DataSource upstream, boolean cronetLeg) {
        mUpstream = upstream;
        mCronetLeg = cronetLeg;
    }

    static DataSource wrap(DataSource upstream, boolean cronetLeg) {
        return new DebugHostBlackhole(upstream, cronetLeg);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        return mUpstream.open(redirectIfBlackholed(dataSpec, mCronetLeg));
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        return mUpstream.read(buffer, offset, length);
    }

    private static DataSpec redirectIfBlackholed(DataSpec dataSpec, boolean cronetLeg) {
        String configured = DebugMediaShaper.prop(PROP_HOST);
        if (configured.isEmpty() || "none".equalsIgnoreCase(configured)) {
            return dataSpec;
        }
        if (!cronetLeg && "cronet".equalsIgnoreCase(DebugMediaShaper.prop(PROP_VIA))) {
            return dataSpec;
        }
        String host = dataSpec.uri.getHost();
        if (host == null || !host.endsWith(".googlevideo.com")
                || !("any".equalsIgnoreCase(configured) || host.contains(configured))) {
            return dataSpec;
        }
        String episode = NetPath.context();
        int port;
        synchronized (DebugHostBlackhole.class) {
            if (!configured.equals(sArmedValue)) {
                sArmedValue = configured;
                sArmedEpisode = null;
            }
            if (!"always".equalsIgnoreCase(DebugMediaShaper.prop(PROP_SCOPE))) {
                if (sArmedEpisode == null) {
                    sArmedEpisode = episode;
                } else if (!sArmedEpisode.equals(episode)) {
                    return dataSpec; // spent: later opens (incl. the recovery reload) are clean
                }
            }
            port = tarpitPort();
            String logKey = episode + '|' + cronetLeg;
            if (port > 0 && !logKey.equals(sLastLogKey)) {
                sLastLogKey = logKey;
                NetPath.log(episode + " debug-blackhole host=" + host
                        + " leg=" + (cronetLeg ? "cronet" : "okhttp")
                        + " -> tarpit 127.0.0.1:" + port);
            }
        }
        if (port <= 0) {
            return dataSpec;
        }
        Uri tarpit = dataSpec.uri.buildUpon().encodedAuthority("127.0.0.1:" + port).build();
        return dataSpec.withUri(tarpit);
    }

    /**
     * NEWTUBE(media-path): where a background re-probe of {@code host} must go so it sees the same
     * dead host the legs see - the tarpit's {@code 127.0.0.1:port}, or null for the real host.
     * Only with scope {@code always}: an "episode" blackhole is gone by the time a probe runs.
     */
    @Nullable
    static String probeAuthority(String host, boolean cronetLeg) {
        String configured = DebugMediaShaper.prop(PROP_HOST);
        if (configured.isEmpty() || "none".equalsIgnoreCase(configured)
                || !"always".equalsIgnoreCase(DebugMediaShaper.prop(PROP_SCOPE))
                || (!cronetLeg && "cronet".equalsIgnoreCase(DebugMediaShaper.prop(PROP_VIA)))
                || !host.endsWith(".googlevideo.com")
                || !("any".equalsIgnoreCase(configured) || host.contains(configured))) {
            return null;
        }
        int port;
        synchronized (DebugHostBlackhole.class) {
            port = tarpitPort();
        }
        return port > 0 ? "127.0.0.1:" + port : null;
    }

    /** Lazily binds the loopback tarpit; its accept thread holds connections open, silent. */
    private static int tarpitPort() {
        ServerSocket server = sTarpit;
        if (server == null) {
            try {
                server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            } catch (IOException e) {
                NetPath.log("debug-blackhole tarpit-unavailable " + e.getClass().getSimpleName());
                return -1;
            }
            sTarpit = server;
            ServerSocket acceptOn = server;
            Thread thread = new Thread(() -> holdConnections(acceptOn), "DebugBlackhole");
            thread.setDaemon(true);
            thread.start();
        }
        return server.getLocalPort();
    }

    private static void holdConnections(ServerSocket server) {
        List<Socket> held = new ArrayList<>();
        while (true) {
            try {
                held.add(server.accept()); // never read, never write: a host that never answers
                while (held.size() > 32) {
                    try {
                        held.remove(0).close();
                    } catch (IOException ignored) {
                        // already gone
                    }
                }
            } catch (IOException e) {
                return;
            }
        }
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        mUpstream.addTransferListener(transferListener);
    }

    @Nullable
    @Override
    public Uri getUri() {
        return mUpstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return mUpstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        mUpstream.close();
    }
}
