package com.newtube.mobile.casting.proxy;

/**
 * Logging shim for the cast proxy. Delegates to the house logger
 * ({@link com.liskovsoft.sharedutils.mylogger.Log}) on device; falls back to stdout when the
 * Android framework is absent (pure-JVM unit tests run against the mockable android.jar, where
 * {@code android.util.Log} throws "not mocked"). Every proxy class logs through this instead of
 * the house logger directly so the server/rewriter stay unit-testable without Robolectric.
 */
final class ProxyLog {
    /** Flips permanently after the first framework failure so tests don't pay an exception per line. */
    private static volatile boolean sFallback;

    private ProxyLog() {
    }

    static void d(String tag, String msg) {
        if (sFallback) {
            System.out.println("D/" + tag + ": " + msg);
            return;
        }
        try {
            com.liskovsoft.sharedutils.mylogger.Log.d(tag, msg);
        } catch (Throwable e) {
            sFallback = true;
            System.out.println("D/" + tag + ": " + msg);
        }
    }

    static void e(String tag, String msg) {
        if (sFallback) {
            System.out.println("E/" + tag + ": " + msg);
            return;
        }
        try {
            com.liskovsoft.sharedutils.mylogger.Log.e(tag, msg);
        } catch (Throwable e) {
            sFallback = true;
            System.out.println("E/" + tag + ": " + msg);
        }
    }
}
