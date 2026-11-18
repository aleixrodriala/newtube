package com.newtube.mobile.auto;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

/**
 * NEWTUBE(android-auto): Android Auto entry point. Any host is accepted: Android Auto itself decides
 * whether to list the app (see scripts/android-auto for installs it accepts outside Google Play).
 */
public class AutoCarAppService extends CarAppService {
    private static boolean sInitialized;

    @Override
    public void onCreate() {
        super.onCreate();
        initOnce(this);
    }

    /**
     * Android Auto can start the app's process without the phone's Splash screen ever running
     * (phone locked in a pocket). Splash is where the account store, the global RxJava error
     * handler and the proxy are set up; without them the feeds come back signed out and a late
     * network error crashes the process. Same steps, in the same order, idempotent.
     */
    static synchronized void initOnce(android.content.Context context) {
        if (sInitialized) return;
        sInitialized = true;
        android.content.Context app = context.getApplicationContext();
        AutoLog.init(app);
        AutoLog.i("process started by Android Auto");
        com.liskovsoft.sharedutils.rx.RxHelper.setupGlobalErrorHandler();
        com.liskovsoft.sharedutils.prefs.GlobalPreferences.instance(app);
        try {
            if (com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData.instance(app).isProxyEnabled()) {
                new com.liskovsoft.smartyoutubetv2.common.proxy.ProxyManager(app).configureSystemProxy();
            }
        } catch (Throwable ignored) {
        }
    }
    @NonNull
    @Override
    public HostValidator createHostValidator() {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new AutoSession();
    }
}
