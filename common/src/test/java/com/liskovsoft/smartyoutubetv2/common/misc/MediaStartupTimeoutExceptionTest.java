package com.liskovsoft.smartyoutubetv2.common.misc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

/** The transport verdict ErrorFixerController reads to keep the /player client on a dead host. */
public class MediaStartupTimeoutExceptionTest {
    @Test
    public void verdictIsFoundThroughThePlayerErrorWrapping() {
        // ExoPlaybackException(Source error) <- HttpDataSourceException <- verdict <- Cronet cause
        Throwable playerError = new RuntimeException("Source error",
                new IOException("open", new MediaStartupTimeoutException(
                        "media host sent no response headers in 8000 ms",
                        new IOException("cronet", new SocketTimeoutException()))));

        assertTrue(MediaStartupTimeoutException.isInChain(playerError));
    }

    @Test
    public void aPlainTimeoutIsNotTheVerdict() {
        // Mid-stream read stalls and unclassified timeouts keep the existing client recovery.
        assertFalse(MediaStartupTimeoutException.isInChain(
                new RuntimeException(new IOException(new SocketTimeoutException()))));
        assertFalse(MediaStartupTimeoutException.isInChain(null));
    }

    @Test
    public void hostDeadOnlyWhenEveryTransportWasTried() {
        Throwable deadHost = new IOException(new MediaStartupTimeoutException("x", null, true));
        Throwable cronetOnly = new IOException(new MediaStartupTimeoutException("x", null));

        assertTrue(MediaStartupTimeoutException.isHostDeadInChain(deadHost));
        assertFalse(MediaStartupTimeoutException.isHostDeadInChain(cronetOnly));
        assertTrue(MediaStartupTimeoutException.isInChain(cronetOnly)); // still transport blame
        assertFalse(MediaStartupTimeoutException.isHostDeadInChain(null));
    }

    @Test
    public void itStillReadsAsATimeoutToEveryExistingClassifier() {
        assertTrue(new MediaStartupTimeoutException("x", null) instanceof SocketTimeoutException);
    }
}
