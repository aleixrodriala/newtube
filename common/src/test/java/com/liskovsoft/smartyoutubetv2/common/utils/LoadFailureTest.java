package com.liskovsoft.smartyoutubetv2.common.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLHandshakeException;

public class LoadFailureTest {
    @Test
    public void transportFailuresWrappedByRetrofitHelperAreNoConnection() {
        // RetrofitHelper: throw new IllegalStateException(e) for any IOException.
        assertEquals(LoadFailure.NO_CONNECTION, LoadFailure.classify(
                new IllegalStateException(new UnknownHostException("Unable to resolve host \"www.youtube.com\"")), true));
        assertEquals(LoadFailure.NO_CONNECTION, LoadFailure.classify(
                new IllegalStateException(new SocketTimeoutException("timeout")), true));
        assertEquals(LoadFailure.NO_CONNECTION, LoadFailure.classify(
                new IllegalStateException(new ConnectException("Failed to connect to /127.0.0.1:1")), true));
        assertEquals(LoadFailure.NO_CONNECTION, LoadFailure.classify(
                new IllegalStateException(new SSLHandshakeException("Connection closed by peer")), true));
        // OkHttp callTimeout: a bare InterruptedIOException("timeout").
        assertEquals(LoadFailure.NO_CONNECTION, LoadFailure.classify(
                new IllegalStateException(new InterruptedIOException("timeout")), true));
    }

    @Test
    public void aDeviceWithoutAWorkingNetworkIsNoConnectionWhateverTheErrorSays() {
        assertEquals(LoadFailure.NO_CONNECTION, LoadFailure.classify(null, false));
        assertEquals(LoadFailure.NO_CONNECTION,
                LoadFailure.classify(new IllegalStateException("fromNullable result is null"), false));
        assertEquals(LoadFailure.NO_CONNECTION, LoadFailure.classify(new RuntimeException("parse"), false));
    }

    @Test
    public void aNullAnswerOnAWorkingNetworkIsNeutralEmptyNotAnError() {
        // Completed with nothing, or RxHelper's cause-less "result is null": an empty/deleted
        // playlist looks exactly like this, so it must not be called an error.
        assertEquals(LoadFailure.EMPTY, LoadFailure.classify(null, true));
        assertEquals(LoadFailure.EMPTY,
                LoadFailure.classify(new IllegalStateException("fromNullable result is null"), true));
    }

    @Test
    public void otherFailuresOnAWorkingNetworkAreErrors() {
        assertEquals(LoadFailure.ERROR, LoadFailure.classify(new NullPointerException(), true));
        assertEquals(LoadFailure.ERROR, LoadFailure.classify(
                new IllegalStateException(new IOException("unexpected end of stream on youtube.com")), true));
        assertEquals(LoadFailure.ERROR, LoadFailure.classify(
                new IllegalStateException(new InterruptedIOException("thread interrupted")), true));
    }
}
