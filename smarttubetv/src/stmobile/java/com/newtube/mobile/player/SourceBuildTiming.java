package com.newtube.mobile.player;

/**
 * NEWTUBE(open-phases): where the info -&gt; prepare gap of one off-main source build goes. Written
 * by the main thread (queue), the build thread (start/built and the factory's XML split) and read
 * once on main at delivery, each field by one writer before a happens-before hop (executor
 * submit, Handler post) - so plain volatile fields are enough. Unset stages print {@code -1}.
 */
final class SourceBuildTiming {
    volatile long queuedAtMs;
    /** {@code NetPath.elapsedMs()} when the build was queued: minus {@code info +X} = main-thread
     *  work in the open dispatch before the build even started. */
    volatile long queuedAtOpenMs = -1;
    volatile long startedAtMs;
    volatile long builtAtMs;
    /** Filled by {@link Media3SourceFactory} for generated-MPD builds only. */
    volatile long genMs = -1;
    volatile long parseMs = -1;
    /** NEWTUBE(open-cpu): "direct" (DirectMpd events) or "xml" (printed and parsed text). */
    volatile String mpdRoute;

    String describe(long deliveredAtMs) {
        return "queuedAt=" + (queuedAtOpenMs >= 0 ? "+" + queuedAtOpenMs : "-1")
                + " queueMs=" + span(queuedAtMs, startedAtMs)
                + " genMs=" + genMs
                + " parseMs=" + parseMs
                + " buildMs=" + span(startedAtMs, builtAtMs)
                + " deliverMs=" + span(builtAtMs, deliveredAtMs)
                + (mpdRoute != null ? " mpd=" + mpdRoute : "");
    }

    private static long span(long fromMs, long toMs) {
        return fromMs > 0 && toMs >= fromMs ? toMs - fromMs : -1;
    }
}
