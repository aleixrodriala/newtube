package com.newtube.mobile.car;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;

import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.proxy.ProxyManager;

/** One Android Auto session, showing the live player on the car display. */
public class NewTubeCarSession extends Session {
    private static boolean sServicesReady;

    @NonNull
    @Override
    public Screen onCreateScreen(@NonNull Intent intent) {
        initServices(getCarContext().getApplicationContext());
        return new CarVideoScreen(getCarContext());
    }

    /**
     * Android Auto can start the app without opening any phone screen. The phone app sets up the
     * YouTube services (account, language, proxy) on its first screen (SplashPresenter), so do
     * the same minimal setup here, or the car menu cannot load anything. Both calls are safe to
     * repeat: the first screen opened later on the phone finds them already done.
     */
    private static void initServices(Context context) {
        if (sServicesReady) {
            return;
        }
        sServicesReady = true;
        GlobalPreferences.instance(context);
        if (GeneralData.instance(context).isProxyEnabled()) {
            new ProxyManager(context).configureSystemProxy();
        }
    }
}
