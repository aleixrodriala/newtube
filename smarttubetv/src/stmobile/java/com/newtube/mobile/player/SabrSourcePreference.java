package com.newtube.mobile.player;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;
import com.liskovsoft.youtubeapi.videoinfo.models.SabrVodCapability;

/**
 * Two independent roles for the same decoder. Debug overrides are transient and never consulted
 * by release builds.
 *
 * <p><b>Fallback</b> (default OFF) only decides what happens to a response that has NO playable
 * links: some clients now answer with adaptive formats that carry no URL at all and a SABR
 * endpoint instead. It was built default-ON and turned off before release, because measurement
 * did not support the claim it was built on: across seven unpinned opens on 2026-09-08 the ring
 * never reached a link-less client at all (VISIONOS leads and still hands out URLs), and when the
 * path was forced (client pinned to IOS) every SABR POST answered RELOAD_PLAYER_RESPONSE. It has
 * therefore never carried a video that would not otherwise play. It is also not free: accepting a
 * link-less answer stops the client ring at that client instead of walking on, and the failure
 * then costs ErrorFixerController's bounded retries (4, measured) before anything else is tried.
 * Turn it on when the reload handshake returns media - see HANDOFF section 28.
 *
 * <p><b>Preferred</b> (default OFF) is the experiment: use SABR even when DASH links work. It is
 * a data saving (~11% fewer bytes) paid for with startup (~66 ms), so it stays opt-in.
 */
public final class SabrSourcePreference {
    private static final String PREFS = "newtube_playback_sources";
    /** Historical key: the "prefer SABR over working DASH links" experiment. */
    private static final String KEY_PREFER = "sabr_vod";
    /** Separate key so this switch cannot be read as a user choice from the old switch. */
    private static final String KEY_FALLBACK = "sabr_vod_fallback";
    private SabrSourcePreference() {}

    /** SABR replaces a working DASH route. Experiment; off unless the user asks for it. */
    public static boolean isPreferred(Context context) {
        Boolean override = debugOverride("debug.arc.sabr_vod");
        if (override != null) return override;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PREFER, false);
    }

    /** SABR carries responses that have no playable links. Off until the reload handshake works. */
    public static boolean isFallbackEnabled(Context context) {
        Boolean override = debugOverride("debug.arc.sabr_fallback");
        if (override != null) return override;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FALLBACK, false);
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
