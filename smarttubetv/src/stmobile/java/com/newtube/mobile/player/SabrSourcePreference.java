package com.newtube.mobile.player;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;
import com.liskovsoft.youtubeapi.videoinfo.models.SabrVodCapability;

/**
 * Two independent roles for the same decoder. Debug overrides are transient and never consulted
 * by release builds.
 *
 * <p><b>Fallback</b> (DEBUG-ONLY, never offered in the UI) decides what happens to a response
 * that has NO playable links. It was built default-ON, turned off before release, and then
 * withdrawn entirely once it was shown it cannot work: without a PO token the server serves only
 * up to ~60 s (measured between 56.2 s and 60.0 s on three videos) before
 * STREAM_PROTECTION_STATUS turns ATTESTATION_REQUIRED and no media comes back. The fallback only
 * ever sees link-less clients, and those are exactly the walled ones, so it is capped at the
 * first minute of any video. Enabling it would trade a clean skip for a minute of playback that
 * then dies - and it costs the client ring a candidate plus four recovery attempts, so it can
 * even break a video that a later client would have played. Kept behind the debug property
 * because the diagnostics still arm it deliberately. See HANDOFF section 28.
 *
 * <p><b>Preferred</b> (default OFF) is the experiment: use SABR even when DASH links work. It is
 * a data saving (~11% fewer bytes) paid for with startup (~66 ms), so it stays opt-in.
 */
public final class SabrSourcePreference {
    private static final String PREFS = "newtube_playback_sources";
    /** Historical key: the "prefer SABR over working DASH links" experiment. */
    private static final String KEY_PREFER = "sabr_vod";
    private SabrSourcePreference() {}

    /** SABR replaces a working DASH route. Experiment; off unless the user asks for it. */
    public static boolean isPreferred(Context context) {
        Boolean override = debugOverride("debug.arc.sabr_vod");
        if (override != null) return override;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PREFER, false);
    }

    /**
     * SABR carries a response with no playable links. Always off in a release build - there is no
     * stored preference to consult, so an install that had the old switch turned on loses it too.
     */
    public static boolean isFallbackEnabled(Context context) {
        Boolean override = debugOverride("debug.arc.sabr_fallback");
        return override != null && override;
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
