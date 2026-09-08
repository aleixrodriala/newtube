package com.newtube.sabr;

import java.io.IOException;

/** Terminal, deliberately sanitized source failure: never retain a URL/body-bearing cause. */
public final class SabrException extends IOException {
    public final String reason;
    public final int httpStatus;

    public SabrException(String reason) { this(reason, -1); }

    public SabrException(String reason, int httpStatus) {
        super("SABR stopped: " + reason + (httpStatus > 0 ? " (HTTP " + httpStatus + ")" : ""));
        this.reason = reason;
        this.httpStatus = httpStatus;
    }
}
