package com.newtube.mobile.ui.common;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.common.misc.ScreensaverManager;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

/**
 * Mobile base Activity. Mirrors the lifecycle wiring that the TV base activity
 * ({@code com.liskovsoft.smartyoutubetv2.tv.ui.common.LeanbackActivity}) does on top
 * of {@link MotherActivity}, trimmed to what touch navigation actually needs:
 *
 * <ul>
 *     <li>{@code addTop(this)} on resume - keeps {@code ViewManager}'s hand-rolled
 *     back-stack in sync so {@code startParentView}/{@code hasParentView} work.</li>
 *     <li>{@code finish()}/{@code finishReally()} - back-button behavior: hand off to
 *     the parent screen if there is one, otherwise gracefully move the whole app to
 *     background via {@code ViewManager.properlyFinishTheApp}.</li>
 * </ul>
 *
 * Deliberately NOT ported from {@code LeanbackActivity}: the D-pad
 * {@code GlobalKeyTranslator}, double-back-press exit confirmation
 * ({@code DoubleBackManager2}) and the per-screen exit-shortcut branching
 * (player/search special cases) - none of those screens exist on the mobile flavor
 * yet, so back always behaves like a single press. Revisit once
 * MobilePlaybackFragment/Search land.
 */
public abstract class MobileActivity extends MotherActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    /**
     * No screensaver on mobile - it's TV burn-in protection that reads as "the screen randomly goes
     * dark" on a phone. Returning null means no dim overlay is ever added to the view hierarchy and
     * no idle timers run; every {@code MotherActivity}/controller usage is null-guarded (no-op).
     * The system display timeout rules the screen, and the player holds KEEP_SCREEN_ON while
     * actively playing.
     */
    @Override
    protected ScreensaverManager createScreensaverManager() {
        return null;
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Mandatory: keeps the ViewManager back-stack/parent lookup correct.
        getViewManager().addTop(this);
    }

    @Override
    public void finish() {
        if (!getViewManager().hasParentView(this)) {
            // Root screen (Home): back closes the app instead of leaving an empty stack.
            Utils.properlyFinishTheApp(this);
        } else {
            finishReally();
        }
    }

    @Override
    public void finishReally() {
        // Mandatory line. Fix un-proper view order (especially for playback view).
        //
        // Normally we explicitly relaunch the parent screen here because singleInstance
        // back-navigation is unreliable. BUT if another screen is being launched right now
        // (e.g. a long-press context-menu action like "Open channel"/"Open playlist" that
        // calls a presenter.openChannel() -> ViewManager.startView() and THEN closeDialog()),
        // relaunching this screen's parent (Home) would land on top of and hide that new
        // screen. In that race, just pop ourselves off the ViewManager back-stack and let the
        // freshly-started view stay in front. Plain Android back (nothing else launching) is
        // unaffected: isNewViewPending() is false then, so the parent is relaunched as before.
        if (getViewManager().isNewViewPending()) {
            getViewManager().removeTop(this);
        } else {
            getViewManager().startParentView(this);
        }
        super.finishReally();
    }

    /**
     * Inverse of {@code Helpers.makeActivityFullscreen2()}. {@code MotherActivity.onResume()}
     * unconditionally calls that whenever {@code GeneralData.isFullscreenModeEnabled()} (which
     * defaults to {@code true}) - i.e. on every mobile screen, not just the player - which goes
     * immersive-sticky and hides the status bar. On a cold launch that also triggers Android's
     * "now viewing in full screen" system toast on Home, which reads as a bug on a touch app
     * where the status bar (clock/battery/notifications) is expected to stay visible outside the
     * player. Subclasses call this after {@code super.onResume()}/on rotation to opt back in to
     * normal system bars; {@code MobilePlaybackActivity} calls it for portrait only and keeps
     * landscape immersive (that's the one screen where hiding the bars is actually desirable).
     */
    protected void showSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(true);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }
}
