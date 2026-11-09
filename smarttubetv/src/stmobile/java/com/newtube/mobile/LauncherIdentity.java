package com.newtube.mobile;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.util.Arrays;

/** The aliases are permanent component names: Android retains their overrides across updates. */
public final class LauncherIdentity {
    private static final String PREFS = "newtube_launcher";
    private static final String KEY_YOUTUBE = "youtube";

    private LauncherIdentity() {
    }

    public static boolean isYouTube(Context context) {
        return prefs(context).getBoolean(KEY_YOUTUBE, false);
    }

    /** Repair an interrupted/restored switch; true means the caller needs a graceful restart. */
    public static boolean restore(Context context) {
        if (!prefs(context).contains(KEY_YOUTUBE)) {
            return false;
        }
        try {
            return apply(context, isYouTube(context));
        } catch (RuntimeException e) {
            Log.e("LauncherIdentity", "Unable to restore launcher appearance", e);
            return false;
        }
    }

    public static synchronized boolean setYouTube(Context context, boolean youtube) {
        SharedPreferences prefs = prefs(context);
        boolean previous = isYouTube(context);
        // Write ahead: an OS/process interruption between the older APIs' two calls must
        // leave enough information to finish the switch next time either icon is opened.
        if (!prefs.edit().putBoolean(KEY_YOUTUBE, youtube).commit()) {
            prefs.edit().putBoolean(KEY_YOUTUBE, previous).apply();
            return false;
        }
        try {
            apply(context, youtube);
            return true;
        } catch (RuntimeException e) {
            Log.e("LauncherIdentity", "Unable to change launcher appearance", e);
            prefs.edit().putBoolean(KEY_YOUTUBE, previous).commit();
            try {
                apply(context, previous);
            } catch (RuntimeException rollbackError) {
                // Enable-first below leaves a usable launcher even if rollback is refused.
                Log.e("LauncherIdentity", "Unable to restore previous launcher appearance", rollbackError);
            }
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean apply(Context context, boolean youtube) {
        PackageManager pm = context.getPackageManager();
        ComponentName selected = alias(context, youtube);
        ComponentName other = alias(context, !youtube);
        int enabled = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        int disabled = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        if (pm.getComponentEnabledSetting(selected) == enabled
                && pm.getComponentEnabledSetting(other) == disabled) {
            return false;
        }
        // Keep the real activity enabled; let the caller save state before restarting the app.
        int flags = PackageManager.DONT_KILL_APP;
        if (Build.VERSION.SDK_INT >= 33) {
            pm.setComponentEnabledSettings(Arrays.asList(
                    new PackageManager.ComponentEnabledSetting(selected, enabled, flags),
                    new PackageManager.ComponentEnabledSetting(other, disabled, flags)));
        } else {
            // Never disable the last launcher, including if enabling the destination throws.
            pm.setComponentEnabledSetting(selected, enabled, flags);
            pm.setComponentEnabledSetting(other, disabled, flags);
        }
        return true;
    }

    private static ComponentName alias(Context context, boolean youtube) {
        return new ComponentName(context.getPackageName(), context.getPackageName()
                + (youtube ? ".launcher.YouTube" : ".launcher.NewTube"));
    }
}
