package com.newtube.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService;

/**
 * Persists the account-route 403 quarantine across process restarts.
 *
 * <p>Without it the quarantine is process-local, so the first open after every cold start re-probes
 * an account route this device has already proven dead on this transport. Measured 2026-09-07 on
 * the Pixel 9 over LTE, same video and same minute: the probe costs 5.48 s to first frame against
 * 2.80 s when the working client leads, plus a wasted {@code /player} round trip, two dead media
 * opens and a player reload. The value also carries each client's strike count, which is what lets
 * the cooldown keep escalating (10 min x 4^n, capped at 24 h) across restarts instead of every
 * cold start resetting it to ten minutes, and (since the v3 format) the partial "no media for a
 * video another client served" streak, so that two cold opens - one video each - add up to a
 * quarantine instead of each re-probing a SABR-only head (Pixel 9, 2026-09-25).
 *
 * <p>Deliberately dumb: one string, written from whichever thread quarantined the route (the walk or
 * the player's 403), read once per process. The meaning of the snapshot (transport keying, expiry,
 * strikes, which clients may appear, the legacy 1.9.0 format) belongs to MediaServiceCore's
 * {@code AuthRouteQuarantineSnapshot} and is validated there, so a stale or hand-edited value can
 * only ever quarantine less than intended, never more.
 */
public final class AuthRouteQuarantineStore implements VideoInfoService.AuthRouteQuarantineStore {
    private static final String PREFS = "newtube_player_routes";
    private static final String KEY = "auth_route_quarantine";

    private final SharedPreferences mPrefs;

    public AuthRouteQuarantineStore(@NonNull Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Nullable
    @Override
    public String load() {
        return mPrefs.getString(KEY, null);
    }

    @Override
    public void save(@Nullable String snapshot) {
        if (snapshot == null) {
            mPrefs.edit().remove(KEY).apply();
        } else {
            mPrefs.edit().putString(KEY, snapshot).apply();
        }
    }
}
