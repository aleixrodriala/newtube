package com.newtube.mobile.casting;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.liskovsoft.mediaserviceinterfaces.data.CastScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistence for paired Lounge screens ("Link with TV code" + mdx-shim pairings), so they
 * reappear in the cast picker across sessions. DIAL-discovered TVs are NOT stored - they
 * rediscover themselves. The stored NAME matters beyond display: mdx pairings persist under the
 * mDNS device name, which is the key CastPickerSheet uses to merge a saved screen back into its
 * live Cast device's row.
 *
 * <p>Storage: a private {@link SharedPreferences} file (the mobile flavor's other one-off stores,
 * e.g. FeedCache/SessionWarmup, use plain app-private files the same way rather than growing the
 * shared AppPrefs surface). Screens serialize as {@code screenId\u0001name} entries joined with
 * {@code \u0002}; both separators are unprintable, so names can't collide with them.</p>
 */
public final class CastPrefs {

    private static final String PREFS_NAME = "newtube_cast";
    private static final String KEY_PAIRED_SCREENS = "paired_screens";
    private static final String FIELD_SEP = "\u0001";
    private static final String ENTRY_SEP = "\u0002";

    private CastPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** All persisted screens, in the order they were paired. */
    public static List<CastScreen> getPairedScreens(Context context) {
        List<CastScreen> result = new ArrayList<>();
        String data = prefs(context).getString(KEY_PAIRED_SCREENS, null);
        if (TextUtils.isEmpty(data)) {
            return result;
        }
        for (String entry : data.split(ENTRY_SEP, -1)) {
            String[] fields = entry.split(FIELD_SEP, -1);
            if (fields.length >= 2 && !TextUtils.isEmpty(fields[0])) {
                result.add(new CastScreen(fields[0], fields[1]));
            }
        }
        return result;
    }

    /** Add (or refresh the name of) a paired screen. */
    public static void addPairedScreen(Context context, CastScreen screen) {
        if (screen == null || TextUtils.isEmpty(screen.getScreenId())) {
            return;
        }
        List<CastScreen> screens = getPairedScreens(context);
        List<CastScreen> updated = new ArrayList<>();
        for (CastScreen existing : screens) {
            if (!existing.getScreenId().equals(screen.getScreenId())) {
                updated.add(existing);
            }
        }
        updated.add(screen);
        save(context, updated);
    }

    public static void removePairedScreen(Context context, String screenId) {
        if (TextUtils.isEmpty(screenId)) {
            return;
        }
        List<CastScreen> updated = new ArrayList<>();
        for (CastScreen existing : getPairedScreens(context)) {
            if (!screenId.equals(existing.getScreenId())) {
                updated.add(existing);
            }
        }
        save(context, updated);
    }

    private static void save(Context context, List<CastScreen> screens) {
        StringBuilder sb = new StringBuilder();
        for (CastScreen screen : screens) {
            if (sb.length() > 0) {
                sb.append(ENTRY_SEP);
            }
            String name = screen.getName() != null ? screen.getName() : "";
            sb.append(screen.getScreenId()).append(FIELD_SEP).append(name);
        }
        prefs(context).edit().putString(KEY_PAIRED_SCREENS, sb.toString()).apply();
    }
}
