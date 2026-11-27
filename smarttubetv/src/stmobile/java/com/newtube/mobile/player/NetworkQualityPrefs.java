package com.newtube.mobile.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem.VideoPreset;
import com.liskovsoft.smartyoutubetv2.common.misc.AppDataSourceManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;

/**
 * NEWTUBE(issue #130): separate default video quality on cellular (mobile data) vs Wi-Fi.
 * Wi-Fi keeps using {@link PlayerData#getFormat(int)} ({@link FormatItem#TYPE_VIDEO}); this store
 * holds an optional override key for mobile data ("Same as Wi-Fi" when empty, {@code "auto"} for
 * Auto, or a {@link VideoPreset#name} such as {@code "1080p    60fps    vp9"}).
 */
public final class NetworkQualityPrefs {

    private static final String PREFS_NAME = "newtube_network_quality";
    private static final String KEY_MOBILE_QUALITY = "mobile_quality";

    /** Empty key means use the same default quality as Wi-Fi. */
    public static final String QUALITY_SAME_AS_WIFI = "";
    /** Explicit Auto cap on mobile data, even when Wi-Fi uses a specific cap. */
    public static final String QUALITY_AUTO = "auto";

    private NetworkQualityPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String getMobileQualityKey(Context context) {
        String key = prefs(context).getString(KEY_MOBILE_QUALITY, QUALITY_SAME_AS_WIFI);
        return key != null ? key : QUALITY_SAME_AS_WIFI;
    }

    public static void setMobileQualityKey(Context context, @Nullable String key) {
        String value = key != null ? key : QUALITY_SAME_AS_WIFI;
        prefs(context).edit().putString(KEY_MOBILE_QUALITY, value).apply();
    }

    public static boolean hasMobileOverride(Context context) {
        return !getMobileQualityKey(context).isEmpty();
    }

    /**
     * Resolves the effective default video format for the current transport: returns the Wi-Fi
     * format when not on cellular or when mobile data is set to "Same as Wi-Fi".
     */
    public static FormatItem resolveVideoFormat(Context context, PlayerData playerData, boolean cellular) {
        FormatItem wifiFormat = playerData.getFormat(FormatItem.TYPE_VIDEO);
        if (!cellular) {
            return wifiFormat;
        }
        String key = getMobileQualityKey(context);
        return resolvePreset(key, wifiFormat, playerData.getDefaultVideoFormat(),
                AppDataSourceManager.instance().getVideoPresets());
    }

    static FormatItem resolvePreset(@Nullable String key, FormatItem wifiFormat, FormatItem autoFormat,
            @Nullable VideoPreset[] presets) {
        if (key == null || key.isEmpty()) {
            return wifiFormat;
        }
        if (QUALITY_AUTO.equals(key)) {
            return autoFormat;
        }
        if (presets != null) {
            for (VideoPreset preset : presets) {
                if (preset != null && key.equals(preset.name)) {
                    return preset.format;
                }
            }
        }
        return wifiFormat;
    }
}
