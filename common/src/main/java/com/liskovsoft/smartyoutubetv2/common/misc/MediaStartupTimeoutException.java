package com.liskovsoft.smartyoutubetv2.common.misc;

import java.net.SocketTimeoutException;

/**
 * The media host never answered: the FIRST request of a freshly built media source got no
 * response headers (zero bytes, no HTTP status) within its startup budget, on every transport the
 * source layer tried. Thrown by the phone flavor's media source layer (StartupFailoverDataSource)
 * and read by {@code ErrorFixerController}.
 *
 * <p>It is a transport/edge-host verdict, not a verdict on the /player client that minted the
 * URLs: the client's answer was fine, the host behind its URLs was unreachable. Recovery should
 * therefore fetch fresh URLs WITHOUT circuit-breaking the client ring (which also resets the
 * PO-token cache and starts the recovery walk on a slower web client).</p>
 *
 * <p>Extends {@link SocketTimeoutException} so every existing timeout/connectivity classifier
 * (media3 load policy, connectivity markers) keeps treating it exactly as before.</p>
 */
public final class MediaStartupTimeoutException extends SocketTimeoutException {
    private final boolean mEveryTransportTried;

    public MediaStartupTimeoutException(String message, Throwable cause) {
        this(message, cause, false);
    }

    /**
     * @param everyTransportTried the request already failed over to a different transport and
     *                            that one got no answer either: the host is dead, no single
     *                            transport is to blame.
     */
    public MediaStartupTimeoutException(String message, Throwable cause,
            boolean everyTransportTried) {
        super(message);
        mEveryTransportTried = everyTransportTried;
        if (cause != null) {
            initCause(cause);
        }
    }

    public boolean isEveryTransportTried() {
        return mEveryTransportTried;
    }

    /** True when the chain carries this verdict AND every transport was tried (dead host). */
    public static boolean isHostDeadInChain(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 12; cause = cause.getCause(), depth++) {
            if (cause instanceof MediaStartupTimeoutException) {
                return ((MediaStartupTimeoutException) cause).mEveryTransportTried;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /** True when {@code error}'s cause chain carries this verdict. */
    public static boolean isInChain(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 12; cause = cause.getCause(), depth++) {
            if (cause instanceof MediaStartupTimeoutException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
