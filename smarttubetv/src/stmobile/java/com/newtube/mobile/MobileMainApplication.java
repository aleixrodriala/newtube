package com.newtube.mobile;

import com.liskovsoft.smartyoutubetv2.common.app.views.AppDialogView;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.tv.ui.main.MainApplication;
import com.newtube.mobile.ui.browse.MobileBrowseActivity;
import com.newtube.mobile.ui.dialog.MobileAppDialogActivity;
import com.newtube.mobile.ui.playback.MobilePlaybackActivity;

/**
 * stmobile flavor application class.
 *
 * Wave 1 vertical slice: keep every existing TV init (GlobalPreferences, exception
 * handler, the full {@link ViewManager} mapping for every other screen) by calling
 * {@code super.onCreate()}, then re-point only the {@link BrowseView} (Home) mapping
 * at the touch {@link MobileBrowseActivity}.
 *
 * Wave 2: also re-point the {@link PlaybackView} mapping at the touch
 * {@link MobilePlaybackActivity}. Without this, {@code PlaybackView} would still
 * resolve to the inherited TV mapping ({@code MainApplication.setupViewManager()}
 * registers it as {@code PlaybackView -> PlaybackActivity (Leanback), parent
 * BrowseActivity (Leanback)}), so tapping a video card would launch the Leanback
 * player on a touch phone instead of the new touch one.
 *
 * Wave 3: also re-point the {@link AppDialogView} mapping at the touch
 * {@link MobileAppDialogActivity} (parent: {@link MobileBrowseActivity}, mirroring TV's
 * {@code AppDialogView -> AppDialogActivity, parent BrowseActivity}). Without this override
 * every settings screen and every long-press context menu - which all funnel through
 * {@code AppDialogPresenter} - would still launch the Leanback {@code AppDialogActivity}.
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

        // Override just the Home + Playback mappings (and the cold-launch root) with
        // the touch screens. Every other View->Activity mapping from
        // MainApplication.setupViewManager() (Search, Channel, AppDialog, SignIn, ...)
        // is left intact for later waves.
        viewManager.register(BrowseView.class, MobileBrowseActivity.class);
        viewManager.register(PlaybackView.class, MobilePlaybackActivity.class, MobileBrowseActivity.class);
        viewManager.register(AppDialogView.class, MobileAppDialogActivity.class, MobileBrowseActivity.class);
        viewManager.setRoot(MobileBrowseActivity.class);
    }
}
