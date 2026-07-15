package com.newtube.mobile.ui.common;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import androidx.core.content.ContextCompat;
import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.common.misc.ScreensaverManager;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;

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

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        installContentInsets();
    }

    /**
     * Android 15+ enforces edge-to-edge for modern target SDKs, so decorFitsSystemWindows no
     * longer keeps ordinary screens clear of status/navigation bars. Apply the delivered safe
     * insets to the Activity content container; the landscape player deliberately stays
     * full-bleed and opts out below.
     */
    private void installContentInsets() {
        View content = findViewById(android.R.id.content);
        if (content == null) {
            return;
        }

        ViewCompat.setOnApplyWindowInsetsListener(content, (view, windowInsets) -> {
            if (shouldInsetContentForSystemBars()) {
                Insets insets = windowInsets.getInsets(
                        WindowInsetsCompat.Type.systemBars()
                                | WindowInsetsCompat.Type.displayCutout());
                view.setPadding(insets.left, insets.top, insets.right,
                        shouldInsetContentForNavigationBar() ? insets.bottom : 0);
            } else {
                view.setPadding(0, 0, 0, 0);
            }
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(content);
    }

    /** Override for a screen that intentionally draws behind hidden system bars. */
    protected boolean shouldInsetContentForSystemBars() {
        return true;
    }

    /**
     * Most screens keep their whole content above the gesture/navigation area. A screen with a
     * bottom navigation component can instead let that component extend to the display edge and
     * apply the navigation inset inside its own background.
     */
    protected boolean shouldInsetContentForNavigationBar() {
        return true;
    }

    /** Re-evaluates content padding after an in-place orientation/configuration change. */
    protected final void refreshContentInsets() {
        View content = findViewById(android.R.id.content);
        if (content != null) {
            ViewCompat.requestApplyInsets(content);
        }
    }

    /** Registers an AndroidX back handler that also receives predictive-back gestures. */
    protected final void registerBackHandler(Runnable handler) {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handler.run();
            }
        });
    }

    /**
     * Keep the manifest theme. The TV base applies MainUIData's color-scheme theme here
     * (App.Theme.Leanback.*), which sets {@code android:windowFullscreen=true} +
     * {@code windowTranslucentNavigation=true} on EVERY window - that theme-level fullscreen flag
     * is what kept hiding the status bar on mobile no matter what the runtime calls requested.
     * Mobile screens use Theme.NewTube (Material, normal system bars) from the manifest.
     */
    @Override
    protected void initTheme() {
        // No TV color-scheme overlay on the touch flavor.
    }

    /**
     * No Slidr on mobile. The TV base wires a left-edge "slide away to close" gesture (Slidr,
     * grabbing the leftmost 18% of the screen) meant for cars/TV boxes without a back button.
     * On a phone it swallowed the drawer's edge swipe on Home (and would slide the whole app
     * away). DrawerLayout handles the left edge itself (incl. the gesture-nav exclusion rect).
     */
    @Override
    protected void initEdgeSlide() {
        // Left edge = navigation drawer / system back on the touch flavor.
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

        // While a resumed touch Activity exists, ViewManager launches new screens from it (so
        // they join THIS task). Without it, app-context NEW_TASK launches resolve by affinity
        // and can land inside the player's pinned picture-in-picture task.
        ViewManager.setForegroundActivity(this);
    }

    @Override
    protected void onPause() {
        super.onPause();

        ViewManager.unsetForegroundActivity(this);
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
        // Mobile activities share one task, so normal Back should simply remove this Activity and
        // reveal the already-rendered screen below. Relaunching the registered parent here was a
        // singleInstance-era TV workaround; on mobile it produced an unnecessary OPEN transition
        // immediately followed by this Activity's CLOSE transition.
        //
        // A true task-root/deep-link screen has nothing underneath, so retain the explicit parent
        // launch there. The pending-view race likewise only updates ViewManager's logical stack;
        // the newly launched destination must remain in front.
        if (getViewManager().isNewViewPending()) {
            getViewManager().removeTop(this);
        } else if (isTaskRoot()) {
            getViewManager().startParentView(this);
        } else {
            getViewManager().removeTop(this);
        }
        // NOT super.finishReally(): MotherActivity's version calls finishAndRemoveTask(), which
        // on TV merely cleaned up the finished screen's own singleInstance task from recents.
        // The touch flavor keeps EVERY screen in ONE shared task (see the stmobile manifest
        // note), so removing "the task" nuked Browse along with the player - back/X on a video
        // closed the whole app to the launcher. Plain finish() pops just this activity.
        super.finish();
    }

    /**
     * Replaces the TV window chrome wholesale. {@code MotherActivity.onResume()} used to call
     * {@code Helpers.makeActivityFullscreen2()} on every mobile screen (immersive-sticky, hidden
     * status bar, translucent flags, {@code decorFits=false}); subclasses then partially undid it,
     * and whichever call won the race decided whether content rendered under the clock - that's
     * exactly the "whole app merges with the status bar after player fullscreen" bug. Overriding
     * the hook means TV immersive state is never applied to a mobile screen in the first place.
     *
     * <p>{@code MobilePlaybackActivity} overrides this again: landscape keeps the true immersive
     * fullscreen (the one screen where hiding the bars is desirable), portrait uses this recipe.</p>
     */
    @Override
    protected void applyFullscreenModeIfNeeded() {
        applyMobileSystemBars();
    }

    /**
     * Standard phone window chrome: status + navigation bars visible, painted with the app
     * background, white icons (dark theme), and the decor fitting system windows so layouts
     * never end up under the bars. Idempotent - safe to call on resume/rotation.
     */
    protected void applyMobileSystemBars() {
        Window window = getWindow();

        // Undo anything makeActivityFullscreen2 or a TV theme might have left on this window -
        // FLAG_FULLSCREEN (theme windowFullscreen) requests a hidden status bar, and translucent
        // bars force layout-behind regardless of decorFits.
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        int barColor = ContextCompat.getColor(this, R.color.mobile_color_background);
        window.setStatusBarColor(barColor);
        window.setNavigationBarColor(barColor);

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(true);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_DEFAULT);
                // Dark backgrounds -> keep light (white) status/navigation icons.
                controller.setSystemBarsAppearance(
                        0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }
}
