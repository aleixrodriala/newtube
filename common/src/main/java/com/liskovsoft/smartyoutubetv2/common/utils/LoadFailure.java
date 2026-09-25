package com.liskovsoft.smartyoutubetv2.common.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;

/**
 * NEWTUBE(page-load-errors): why a page's FIRST load put nothing on screen, in the three shapes a
 * phone user can act on. The channel and uploads/playlist pages used to log the failure and leave
 * a blank grid with no message and no retry - the "nothing loads" reports.
 *
 * <p>The service layer makes the classification harder than it looks: {@code RetrofitHelper}
 * turns a transport {@code IOException} into an {@code IllegalStateException} WITH its cause (so
 * the markers below find it), but swallows a {@code ConnectException} and every non-2xx response
 * into a plain {@code null}, which then surfaces either as a load that completes with nothing or
 * as RxHelper's cause-less {@code IllegalStateException("fromNullable result is null")}. An empty
 * or deleted playlist produces exactly the same shape (the browse service drops empty groups to
 * {@code null}), so that shape is reported as {@link #EMPTY} - neutral wording, never a claimed
 * error - and the pages still offer a retry on it. Whatever the error says, a device with no
 * validated network is {@link #NO_CONNECTION} (the Home feed asks the device the same way).</p>
 */
public final class LoadFailure {
    /** Nothing came back and nothing says why - an empty destination, or a swallowed failure. */
    public static final int EMPTY = 0;
    /** The request never reached YouTube (no network, DNS, refused/reset/timed-out socket). */
    public static final int NO_CONNECTION = 1;
    /** Anything else: YouTube answered, or the answer broke on the way in. */
    public static final int ERROR = 2;

    private LoadFailure() {
    }

    /** {@link #classify(Throwable, boolean)} against the device's current default network. */
    public static int classify(@Nullable Context context, @Nullable Throwable error) {
        return classify(error, hasValidatedNetwork(context));
    }

    /**
     * @param error what the load failed with, or {@code null} when it completed without content
     * @param hasValidatedNetwork whether the device's default network is validated right now
     */
    public static int classify(@Nullable Throwable error, boolean hasValidatedNetwork) {
        if (!hasValidatedNetwork || hasConnectivityMarker(error)) {
            return NO_CONNECTION;
        }

        if (error == null || isVerdictFree(error)) {
            return EMPTY;
        }

        return ERROR;
    }

    /**
     * RxHelper's "the call returned null" error: an {@code IllegalStateException} with no cause.
     * It carries no reason at all, so it cannot honestly be called an error.
     */
    private static boolean isVerdictFree(Throwable error) {
        return error instanceof IllegalStateException && error.getCause() == null;
    }

    private static boolean hasConnectivityMarker(@Nullable Throwable error) {
        Throwable cause = error;

        for (int depth = 0; cause != null && depth < 12; cause = cause.getCause(), depth++) {
            if (cause instanceof UnknownHostException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof ConnectException
                    || cause instanceof SocketException // incl. NoRouteToHost, connection reset
                    || cause instanceof SSLException) { // handshake cut off, captive portal
                return true;
            }

            // OkHttp's callTimeout (the only TOTAL bound, see OkHttpManager) throws a bare
            // InterruptedIOException("timeout") - a link too slow to answer, not a thread kill.
            if (cause instanceof InterruptedIOException && Helpers.contains(cause.getMessage(), "timeout")) {
                return true;
            }

            if (Helpers.containsAny(cause.getMessage(),
                    "Unable to resolve host", "Unable to connect to", "Failed to connect to",
                    "ERR_INTERNET_DISCONNECTED", "ERR_NAME_NOT_RESOLVED", "ERR_ADDRESS_UNREACHABLE",
                    "ERR_CONNECTION_", "ERR_TIMED_OUT", "ERR_NETWORK_CHANGED", "ERR_PROXY_CONNECTION_FAILED")) {
                return true;
            }
        }

        return false;
    }

    /** Does the device have a working default network? {@code true} when it can't tell. */
    public static boolean hasValidatedNetwork(@Nullable Context context) {
        ConnectivityManager cm = context != null
                ? (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE) : null;
        if (cm == null) {
            return true; // can't tell - keep the error-based verdict
        }

        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
