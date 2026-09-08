package com.newtube.mobile.player;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;
import com.liskovsoft.youtubeapi.videoinfo.models.SabrVodCapability;

/**
 * Two independent roles for the same decoder. Debug overrides are transient and never consulted
 * by release builds.
 *
 * <p><b>Fallback</b> (default ON) only decides what happens to a response that has NO playable
 * links: some clients now answer with adaptive formats that carry no URL at all and a SABR
 * endpoint instead. Without SABR such a response is simply unplayable - measured on the Pixel 9
 * on 2026-09-08, the app skipped to the next video. It never displaces a working DASH route, so
 * it cannot make a video that plays today slower or worse.
 *
 * <p><b>Preferred</b> (default OFF) is the experiment: use SABR even when DASH links work. It is
 * a data saving (~11% fewer bytes) paid for with startup (~66 ms), so it stays opt-in.
 */
public final class SabrSourcePreference {
    private static final String PREFS = "newtube_playback_sources";
    /** Historical key: the "prefer SABR over working DASH links" experiment. */
    private static final String KEY_PREFER = "sabr_vod";
    /** Separate key so the default flip cannot be read as a user choice from the old switch. */
    private static final String KEY_FALLBACK = "sabr_vod_fallback";
    private SabrSourcePreference() {}

    /** SABR replaces a working DASH route. Experiment; off unless the user asks for it. */
    public static boolean isPreferred(Context context) {
        Boolean override = debugOverride("debug.arc.sabr_vod");
        if (override != null) return override;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PREFER, false);
    }

    /** SABR carries responses that have no playable links. On unless the user turns it off. */
    public static boolean isFallbackEnabled(Context context) {
        Boolean override = debugOverride("debug.arc.sabr_fallback");
        if (override != null) return override;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FALLBACK, true);
    }

    /** Whether the metadata layer may accept a link-less response at all. */
    public static boolean isEnabled(Context context) {
        return isFallbackEnabled(context) || isPreferred(context);
    }

    public static void initialize(Context context) {
        SabrVodCapability.setEnabled(isEnabled(context));
    }

    public static void setPreferred(Context context, boolean preferred) {
        apply(context, KEY_PREFER, preferred);
    }

    public static void setFallbackEnabled(Context context, boolean enabled) {
        apply(context, KEY_FALLBACK, enabled);
    }

    private static void apply(Context context, String key, boolean value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply();
        initialize(context);
        // Capability changes apply to the next open. Do not reuse old unsupported/accepted DTOs.
        YouTubeMediaItemService.instance().invalidateCache();
    }

    private static Boolean debugOverride(String property) {
        if (!BuildConfig.DEBUG && !BuildConfig.BENCHMARK) return null;
        String override = DebugMediaShaper.prop(property);
        if ("1".equals(override)) return Boolean.TRUE;
        if ("0".equals(override)) return Boolean.FALSE;
        return null;
    }
}
