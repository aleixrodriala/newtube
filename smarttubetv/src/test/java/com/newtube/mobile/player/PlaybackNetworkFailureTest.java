package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.app.Application;
import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Replays playback failure classes locally without signed URLs, accounts, or external requests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlaybackNetworkFailureTest {
    @Test
    public void rejectedDeepRangeSurfacesImmediatelyWithoutChangingTransport() throws Exception {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(2_000);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> requestRange = executor.submit(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(2_000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.US_ASCII));
                String range = null;
                for (String line; (line = reader.readLine()) != null && !line.isEmpty();) {
                    if (line.regionMatches(true, 0, "Range: ", 0, 7)) {
                        range = line.substring(7);
                    }
                }
                socket.getOutputStream().write(("HTTP/1.1 403 Forbidden\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                return range;
            }
        });
        DefaultHttpDataSource source = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(2_000).setReadTimeoutMs(2_000).createDataSource();
        try {
            DataSpec spec = new DataSpec.Builder()
                    .setUri("http://127.0.0.1:" + server.getLocalPort() + "/media")
                    .setPosition(3_000_000).setLength(128_000).build();
            HttpDataSource.InvalidResponseCodeException failure = assertThrows(
                    HttpDataSource.InvalidResponseCodeException.class, () -> source.open(spec));
            assertEquals(403, failure.responseCode);
            assertEquals("bytes=3000000-3127999", requestRange.get(3, TimeUnit.SECONDS));

            AtomicInteger transportChanges = new AtomicInteger();
            Media3SourceFactory.FailFastLoadErrorPolicy policy =
                    new Media3SourceFactory.FailFastLoadErrorPolicy(transportChanges::incrementAndGet);
            assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(
                    loadError(spec, C.DATA_TYPE_MEDIA, 0, new IOException("chunk", failure))));
            assertEquals(0, transportChanges.get());
        } finally {
            source.close();
            server.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void onlyZeroProgressInitializationTimeoutRequestsTransportFallback() {
        AtomicInteger transportChanges = new AtomicInteger();
        Media3SourceFactory.FailFastLoadErrorPolicy policy =
                new Media3SourceFactory.FailFastLoadErrorPolicy(transportChanges::incrementAndGet);
        DataSpec spec = new DataSpec(Uri.parse("https://media.invalid/init"));
        IOException timeout = new IOException("read", new SocketTimeoutException());

        assertEquals(0, policy.getRetryDelayMsFor(
                loadError(spec, C.DATA_TYPE_MEDIA, 0, timeout)));
        assertEquals(0, policy.getRetryDelayMsFor(
                loadError(spec, C.DATA_TYPE_MEDIA_INITIALIZATION, 1, timeout)));
        assertEquals(0, transportChanges.get());

        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(
                loadError(spec, C.DATA_TYPE_MEDIA_INITIALIZATION, 0, timeout)));
        assertEquals(1, transportChanges.get());
    }

    private static LoadErrorHandlingPolicy.LoadErrorInfo loadError(
            DataSpec spec, int dataType, long bytesLoaded, IOException exception) {
        return new LoadErrorHandlingPolicy.LoadErrorInfo(
                new LoadEventInfo(1, spec, spec.uri, Collections.emptyMap(), 0, 4_000, bytesLoaded),
                new MediaLoadData(dataType), exception, 1);
    }
}
