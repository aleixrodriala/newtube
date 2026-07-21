package com.newtube.mobile.player;

import org.junit.Test;

import java.io.IOException;
import java.net.NoRouteToHostException;
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
}
