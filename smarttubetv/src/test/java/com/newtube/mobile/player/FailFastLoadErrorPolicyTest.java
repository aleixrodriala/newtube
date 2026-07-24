package com.newtube.mobile.player;

import androidx.media3.common.C;

import org.junit.Test;

import java.io.IOException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FailFastLoadErrorPolicyTest {

    @Test
    public void dnsAndDisconnectedErrorsAreFatalAtMediaLayer() {
        assertTrue(Media3SourceFactory.FailFastLoadErrorPolicy
                .isFatalTransportError(new UnknownHostException("youtube.com")));
        assertTrue(Media3SourceFactory.FailFastLoadErrorPolicy
                .isFatalTransportError(new NoRouteToHostException("offline")));
        assertTrue(Media3SourceFactory.FailFastLoadErrorPolicy.isFatalTransportError(
                new IOException("Exception in CronetUrlRequest: net::ERR_NAME_NOT_RESOLVED")));
        assertTrue(Media3SourceFactory.FailFastLoadErrorPolicy.isFatalTransportError(
                new RuntimeException(new IOException("net::ERR_INTERNET_DISCONNECTED"))));
    }

    @Test
    public void transientReadFailureStillUsesMediaRetries() {
        assertFalse(Media3SourceFactory.FailFastLoadErrorPolicy
                .isFatalTransportError(new IOException("connection reset")));
    }

    @Test
    public void zeroProgressInitializationTimeoutFailsOverImmediately() {
        assertTrue(Media3SourceFactory.FailFastLoadErrorPolicy.isStartupNoProgressTimeout(
                C.DATA_TYPE_MEDIA_INITIALIZATION,
                0,
                new IOException("Cronet read", new SocketTimeoutException())));
        assertTrue(Media3SourceFactory.FailFastLoadErrorPolicy.isStartupNoProgressTimeout(
                C.DATA_TYPE_MEDIA_INITIALIZATION,
                0,
                new IOException("Exception in CronetUrlRequest: net::ERR_TIMED_OUT")));
    }

    @Test
    public void partialOrMidstreamTimeoutKeepsPatientRetries() {
        SocketTimeoutException timeout = new SocketTimeoutException();
        assertFalse(Media3SourceFactory.FailFastLoadErrorPolicy.isStartupNoProgressTimeout(
                C.DATA_TYPE_MEDIA_INITIALIZATION, 1, timeout));
        assertFalse(Media3SourceFactory.FailFastLoadErrorPolicy.isStartupNoProgressTimeout(
                C.DATA_TYPE_MEDIA, 0, timeout));
        assertFalse(Media3SourceFactory.FailFastLoadErrorPolicy.isStartupNoProgressTimeout(
                C.DATA_TYPE_MEDIA_INITIALIZATION, 0, new IOException("connection reset")));
    }
}
