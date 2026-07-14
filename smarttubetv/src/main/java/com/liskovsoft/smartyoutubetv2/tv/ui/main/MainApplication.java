package com.liskovsoft.smartyoutubetv2.tv.ui.main;

import android.app.Application;
import android.app.Activity;
import android.os.Build.VERSION;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.AddDeviceView;
import com.liskovsoft.smartyoutubetv2.common.app.views.AppDialogView;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelUploadsView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelView;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SearchView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SignInView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SplashView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.app.views.WebBrowserView;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.NetworkData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

import org.conscrypt.Conscrypt;

import java.lang.Thread.UncaughtExceptionHandler;
import java.security.Provider;
import java.security.Security;

public class MainApplication extends Application { // multidex is native at minSdk 24 (legacy MultiDexApplication dropped with the AGP 8 round)
    static {
        // fix youtube bandwidth throttling (best - false)???
        // false is better for streams (less buffering)
        System.setProperty("http.keepAlive", "false");
        // fix ipv6 infinite video buffering???
        // Better to remove this fix at all. Users complain about infinite loading.
        //System.setProperty("java.net.preferIPv6Addresses", "true");
        // Another IPv6 fix (no effect)
        // https://stackoverflow.com/questions/1920623/sometimes-httpurlconnection-getinputstream-executes-too-slowly
        //System.setProperty("java.net.preferIPv4Stack" , "true");
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // ByeByeDPI fix
        // https://android-review.googlesource.com/c/platform/external/conscrypt/+/89408/
        // NOTE: Android 10+ (API 29+) uses system Conscrypt TLS; custom Security providers are unnecessary
        // NOTE: May cause 'Unexpected playback error null'
        //if (Build.VERSION.SDK_INT < 29 && Conscrypt.isAvailable()) {
        //    Security.insertProviderAt(Conscrypt.newProvider(), 1);
        //}

        // Important: Initialize the native Conscrypt provider BEFORE reading any configs/SharedPreferences.
        // Otherwise, early disk I/O shifts the ClassLoader on some Android TV devices, causing silent JNI linking errors.
        Provider conscryptProvider = null;
        try {
            conscryptProvider = Conscrypt.newProvider();
        } catch (Throwable e) {
            // UnsatisfiedLinkError
        }

        if (conscryptProvider != null && NetworkData.instance(this).isConscryptEnabled()) {
            try {
                Security.insertProviderAt(conscryptProvider, 1);
            } catch (Throwable e) {
                // UnsatisfiedLinkError
            }
        }

        setupGlobalExceptionHandler();
        setupViewManager();
    }

    /** Mobile overrides this to expand an existing system-PiP player on launcher relaunch. */
    public boolean restorePictureInPictureFromLauncher(Activity launcher) {
        return false;
    }

    private void setupViewManager() {
        // NEWTUBE(phone-only): only the SplashView->SplashActivity mapping survives here. Every
        // other View->Activity mapping (and the root activity) is re-registered by
        // MobileMainApplication, which calls super.onCreate() then overwrites the rest for the
        // touch shell. SplashView is the one mapping mobile never re-registers, so it must stay.
        ViewManager viewManager = ViewManager.instance(this);
        viewManager.register(SplashView.class, SplashActivity.class); // no parent, because it's root activity
    }

    private void setupGlobalExceptionHandler() {
        UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        if (defaultHandler == null) {
            return;
        }

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            if (shouldIgnore(e)) {
                return;
            }

            applyCrashFixes(e);
            //e = wrapWithAdditionalInfo(e);

            defaultHandler.uncaughtException(t, e);
        });
    }

    private boolean shouldIgnore(Throwable e) {
        if (Helpers.containsAny(e.getMessage(), "KatnissVoiceInteractionService", "ListenableFuture")
                || e.getClass().getName().startsWith("org.chromium")) {
            // IllegalStateException: Not allowed to start service Intent { act=android.service.voice.VoiceInteractionService
            // cmp=com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService (has extras) }:
            // app is in background uid UidRecord{40e7240 u0a19 CEM idle change:cached procs:1 seq(0,0,0)}

            // java.lang.NoSuchMethodError: No interface method addListener(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)V
            // in class Lcom/google/common/util/concurrent/ListenableFuture; or its super classes
            // (declaration of 'com.google.common.util.concurrent.ListenableFuture' appears in /system/framework/libsetting.jar)

            // Fatal Exception: org.chromium.base.JniAndroid$UncaughtExceptionException
            // 1) Caused by java.lang.InterruptedException
            // 2) Caused by java.lang.SecurityException: The calling process has already registered an InputDevicesChangedListener.
            return true;
        }

        return false;
    }

    private Throwable wrapWithAdditionalInfo(Throwable e) {
        if (Helpers.equalsAny(e.getMessage(),
                "parameter must be a descendant of this view",
                "Attempt to invoke virtual method 'android.view.ViewGroup$LayoutParams android.view.View.getLayoutParams()' on a null object reference")) {
            Class<?> view = ViewManager.instance(getApplicationContext()).getTopView();
            BrowseSection section = null;

            if (view == BrowseView.class) {
                section = BrowsePresenter.instance(getApplicationContext()).getCurrentSection();
            }

            e = new RuntimeException("A crash in the view " + view.getSimpleName() + ", section id " + (section != null ? section.getId() : "-1"), e);
        }
        return e;
    }

    private void applyCrashFixes(Throwable e) {
        if (e instanceof OutOfMemoryError || e.getCause() instanceof OutOfMemoryError) {
            Class<?> view = ViewManager.instance(getApplicationContext()).getTopView();
            if (view == PlaybackView.class) {
                PlayerTweaksData tweaksData = PlayerTweaksData.instance(getApplicationContext());
                PlayerData playerData = PlayerData.instance(getApplicationContext());
                int playerDataSource = tweaksData.getPlayerDataSource();
                int videoBufferType = playerData.getVideoBufferType();
                if (playerDataSource == PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP) {
                    tweaksData.setPlayerDataSource(PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT);
                    tweaksData.persistNow();
                } else if (videoBufferType == PlayerData.BUFFER_HIGH || videoBufferType == PlayerData.BUFFER_HIGHEST) {
                    playerData.setVideoBufferType(PlayerData.BUFFER_MEDIUM);
                    playerData.persistNow();
                }
            }
        }
    }
}
