package com.newtube.mobile.player;

import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;

/**
 * NEWTUBE(switch): opt-in open-path experiments for the watch page, readable outside this package.
 * Every switch here is inert in release builds (the debug.arc.* properties are only read by debug
 * and benchmark builds), so an experiment can be A/B-timed on a benchmark build before it ships.
 */
public final class SwitchExperiments {
    private static final long MAX_TOUCH_PREFETCH_MS = 500;

    private SwitchExperiments() {
    }

    /**
     * Still-finger time after which a related row resolves its /player before the tap lands
     * ({@code adb shell setprop debug.arc.touch_prefetch_ms 60}); 0 = off (the default, and always
     * in release). OFF by default because every rest-then-scroll gesture costs one /player that is
     * never played - YouTube's bot heuristics count /player volume. Measure the waste on the Pixel
     * ({@code touch-prefetch fire} vs {@code touch-prefetch used} lines) before enabling it.
     */
    public static long touchPrefetchStillMs() {
        if (!(BuildConfig.DEBUG || BuildConfig.BENCHMARK)) {
            return 0;
        }
        int value = DebugMediaShaper.propInt("debug.arc.touch_prefetch_ms", 0);
        return value <= 0 ? 0 : Math.min(value, MAX_TOUCH_PREFETCH_MS);
    }
}
