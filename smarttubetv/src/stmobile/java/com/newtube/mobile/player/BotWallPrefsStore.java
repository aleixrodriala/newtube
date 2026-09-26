package com.newtube.mobile.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService;

/**
 * NEWTUBE(botwall): persists VideoInfoService's bot-wall book (the walled network attachments,
 * their probe backoff, and the account route's benches) across process restarts, within one boot.
 *
 * <p>Deliberately dumb, like AuthRouteQuarantineStore: one string, validated on the way back in by
 * MediaServiceCore's {@code BotWallBook.restore} (another boot, a damaged or stale value restores
 * nothing). The preferences file is opened by {@link #load()}, which VideoInfoService calls once
 * on its own restore thread, so neither the main thread nor the first /player waits for the disk;
 * {@link #save} only ever runs after that and never blocks ({@code apply}).</p>
 */
public final class BotWallPrefsStore implements VideoInfoService.BotWallStore {
    private static final String PREFS = "newtube_player_routes";
    private static final String KEY = "bot_wall";

    private final Context mContext;
    @Nullable
    private volatile SharedPreferences mPrefs;

    public BotWallPrefsStore(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }

    private SharedPreferences prefs() {
        SharedPreferences prefs = mPrefs;
        if (prefs == null) {
            prefs = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            mPrefs = prefs;
        }
        return prefs;
    }

    @Nullable
    @Override
    public String load() {
        return prefs().getString(KEY, null);
    }

    @Override
    public void save(@Nullable String snapshot) {
        if (snapshot == null) {
            prefs().edit().remove(KEY).apply();
        } else {
            prefs().edit().putString(KEY, snapshot).apply();
        }
    }

    @Override
    public long bootCount() {
        try {
            return Settings.Global.getInt(mContext.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException | RuntimeException e) {
            return -1;
        }
    }
}
