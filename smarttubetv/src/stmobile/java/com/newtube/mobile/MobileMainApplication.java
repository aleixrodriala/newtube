package com.newtube.mobile;

import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.tv.ui.main.MainApplication;
import com.newtube.mobile.ui.browse.MobileBrowseActivity;

/**
 * stmobile flavor application class.
 *
 * Wave 1 vertical slice: keep every existing TV init (GlobalPreferences, exception
 * handler, the full {@link ViewManager} mapping for every other screen) by calling
 * {@code super.onCreate()}, then re-point only the {@link BrowseView} (Home) mapping
 * at the touch {@link MobileBrowseActivity}.
 *
 * NOTE: We also re-point {@link ViewManager#setRoot}. The TV {@code MainApplication}
 * sets the root activity to the Leanback {@code BrowseActivity} inside
 * {@code setupViewManager()}. {@code ViewManager.startDefaultView()} (used by
 * {@code SplashPresenter}'s "last resort" intent-chain handler, i.e. a normal cold
 * launch from the launcher icon) falls back directly to that root Activity class -
 * not through the {@link #register} mapping - so without this override a fresh
 * install would briefly launch the Leanback Home before anything touch-specific ever
 * runs.
 */
public class MobileMainApplication extends MainApplication {

    @Override
    public void onCreate() {
        // Keep ALL existing TV init: Conscrypt, GlobalPreferences, multidex, the
        // global exception handler and every other View->Activity mapping.
        super.onCreate();

        ViewManager viewManager = ViewManager.instance(this);

        // Override just the Home mapping + the cold-launch root with the touch screen.
        viewManager.register(BrowseView.class, MobileBrowseActivity.class);
        viewManager.setRoot(MobileBrowseActivity.class);
    }
}
