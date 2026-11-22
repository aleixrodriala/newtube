package com.newtube.mobile.car;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Switch that lets the Android Auto screen keep showing video while the car is moving.
 *
 * <p>On by default. The car app then hands the player surface to the host as soon as a video is
 * playing. It can be turned off from Settings or from the car screen itself, in which case the
 * car app shows a message instead of a video surface. Only passengers may watch; the driver must
 * keep their eyes on the road.</p>
 */
public final class CarModePrefs {
    private static final String PREFS = "newtube_car";
    private static final String KEY_PASSENGER_MODE = "passenger_mode";

    private CarModePrefs() {
    }

    public static boolean isPassengerModeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PASSENGER_MODE, true);
    }

    public static void setPassengerModeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PASSENGER_MODE, enabled).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
