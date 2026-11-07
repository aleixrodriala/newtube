package com.newtube.mobile.ui.common;

import android.content.res.Configuration;
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
import com.newtube.mobile.ui.playback.MiniPlayerBridge;

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

    /**
     * NEWTUBE(theme): the look (night bits + pure black, {@link ThemeMode#desiredLook}) this
     * screen's views were built with (see {@link #checkTheme}).
     */
    private int mThemedLook;

    /**
     * NEWTUBE(theme): every style set as this screen's theme, in order (the manifest's, and any
     * setTheme after it), so {@link ThemeMode#rebuildTheme} can theme it again from scratch.
     */
    private final java.util.List<Integer> mAppliedThemeStyles = new java.util.ArrayList<>();

    @Override
    protected void onApplyThemeResource(android.content.res.Resources.Theme theme, int resid, boolean first) {
        super.onApplyThemeResource(theme, resid, first);
        mAppliedThemeStyles.add(resid);
    }

    java.util.List<Integer> getAppliedThemeStyles() {
        return mAppliedThemeStyles;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // NEWTUBE(theme): pure black, before anything reads the theme (the window background, the
        // decor, the first inflation). The night bits are already this screen's (attachBaseContext).
        ThemeMode.applyPalette(this);
        super.onCreate(savedInstanceState);
        MobileSnackbar.install(getApplication()); // NEWTUBE(snackbar): tracks the screen in front
        mThemedLook = ThemeMode.currentLook(this);
        ThemeMode.register(this);
    }

    @Override
    protected void onDestroy() {
        ThemeMode.unregister(this);
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        checkTheme();
    }

    /**
     * NEWTUBE(theme): brings this screen to the side the Theme setting wants now. Every touch screen
     * lists uiMode in its manifest configChanges, so neither a system day/night switch nor the
     * setting relaunches anything by itself; this is called on every configuration change, on
     * resume, and by {@link ThemeMode#set} for each open screen. A screen whose views are on the
     * other side is rebuilt by {@link #onThemeChanged}; one whose views are right but whose
     * resources were reset to the override it was created with (the player, after an in-place
     * change) only gets its resources put back.
     */
    public final void checkTheme() {
        if (isFinishing() || isDestroyed() || mRecreating) {
            return;
        }
        int desired = ThemeMode.desiredLook(this);
        int night = ThemeMode.night(desired);
        if (desired == mThemedLook) {
            ThemeMode.syncResources(this, night);
            return;
        }
        com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log("theme screen=" + getClass().getSimpleName()
                + " night=" + (night == Configuration.UI_MODE_NIGHT_YES ? "y" : "n")
                + " black=" + ((desired & ThemeMode.LOOK_PURE_BLACK) != 0 ? "y" : "n"));
        if (onThemeChanged(night)) {
            mThemedLook = desired;
        }
    }

    /**
     * Rebuild this screen for the other side, or for pure black switched on or off (the
     * resources and theme of a screen that changes in place get both from
     * {@link ThemeMode#syncResources}). Default: recreate it, which creates it again under
     * the new override (screens already keep their state across a recreation: Browse its You
     * panel, Settings its level stack). The player overrides this to keep playing.
     *
     * @return true if the views now show {@code night} (a recreation returns false: this instance
     * is going away)
     */
    protected boolean onThemeChanged(int night) {
        if (!mResumed && com.newtube.mobile.ui.playback.MobilePlaybackActivity.isCoveringScreens()) {
            // Paused under the full-window player: relaunched there, this screen would pass
            // through onResume and take the player's place in ViewManager's stack (see
            // isCoveringScreens). Its own next onResume brings it over instead.
            com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log("theme screen=" + getClass().getSimpleName()
                    + " deferred reason=under-player");
            return false;
        }
        mRecreating = true; // one relaunch, however many checks run before it happens
        recreate();
        return false;
    }

    private boolean mRecreating;
    private boolean mResumed;

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        mContentLayoutId = layoutResID;
        installContentInsets();
    }

    /** NEWTUBE(theme): the layout {@link #recolourInPlace} inflates again. */
    private int mContentLayoutId;

    /**
     * NEWTUBE(theme): changes this screen's side without recreating it, for a screen whose live
     * state a recreation would lose (the sign-in and pairing screens restart their code and its
     * poll): resources and theme first, then the colours of a fresh inflation of the same layout
     * copied onto the live views (ThemeRefresh), the window background and the bars.
     *
     * @return false if the content isn't a plain setContentView(layout) tree (recreate instead)
     */
    protected final boolean recolourInPlace(int night) {
        android.view.ViewGroup content = findViewById(android.R.id.content);
        if (mContentLayoutId == 0 || content == null || content.getChildCount() != 1) {
            return false;
        }
        ThemeMode.syncResources(this, night);
        View fresh = getLayoutInflater().inflate(mContentLayoutId, content, false);
        java.util.List<androidx.recyclerview.widget.RecyclerView> lists = new java.util.ArrayList<>();
        int skipped = ThemeRefresh.copyColors(content.getChildAt(0), fresh, lists);
        for (androidx.recyclerview.widget.RecyclerView list : lists) {
            ThemeRefresh.rebuildRows(list);
        }
        android.content.res.TypedArray window = getTheme().obtainStyledAttributes(
                new int[] {android.R.attr.windowBackground});
        getWindow().setBackgroundDrawable(window.getDrawable(0));
        window.recycle();
        applyFullscreenModeIfNeeded();
        com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log("theme screen=" + getClass().getSimpleName()
                + " in-place skipped=" + skipped);
        return true;
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

    /**
     * NEWTUBE(update-flow): false for a transient sheet over whatever screen is below it (the update
     * sheet), which must stay out of ViewManager's back stack: addTop() of a class with no parent
     * mapping CLEARS that stack, and finish() then read "no parent" as "leave the app" - closing the
     * sheet sent the whole app to the background.
     */
    protected boolean isBackStackScreen() {
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Mandatory: keeps the ViewManager back-stack/parent lookup correct.
        if (isBackStackScreen()) {
            getViewManager().addTop(this);
        }

        // While a resumed touch Activity exists, ViewManager launches new screens from it (so
        // they join THIS task). Without it, app-context NEW_TASK launches resolve by affinity
        // and can land inside the player's pinned picture-in-picture task.
        ViewManager.setForegroundActivity(this);

        // NEWTUBE(theme): a screen that was in the back stack (or under the player) when the
        // theme changed.
        mResumed = true;
        checkTheme();
    }

    @Override
    protected void onPause() {
        mResumed = false;
        super.onPause();

        ViewManager.unsetForegroundActivity(this);
    }

    @Override
    public void finish() {
        if (!isBackStackScreen()) {
            super.finish(); // just this sheet; the screen below is already there
            return;
        }

        if (!getViewManager().hasParentView(this)) {
            if (MiniPlayerBridge.isActive()) {
                // Root screen (Home) with a docked mini player. properlyFinishTheApp would
                // force-finish the hidden playback activity (killing the mini session) and,
                // with the player buried mid-stack, strand whatever activity sat underneath as
                // a corrupted task root (observed: a stale Search screen became the app).
                // Instead background the whole task like YouTube: the mini session and its
                // audio survive, and reopening the app restores Home with the card docked.
                moveTaskToBack(true);
            } else {
                // Root screen (Home): back closes the app instead of leaving an empty stack.
                Utils.properlyFinishTheApp(this);
            }
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
        //
        // With a docked mini player the screen physically below is the MINIMIZED playback
        // activity - popping this one would reveal the fullscreen video instead of the logical
        // parent screen. Route through the explicit parent launch, which reorders the parent
        // over the buried player before this activity goes away.
        if (getViewManager().isNewViewPending()) {
            getViewManager().removeTop(this);
        } else if (isTaskRoot() || MiniPlayerBridge.isActive()) {
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
     * background, icons that contrast with it, and the decor fitting system windows so layouts
     * never end up under the bars. Idempotent - safe to call on resume/rotation.
     *
     * <p>Half of this only reaches Android 15 and below. From targetSdk 36 the platform ignores
     * {@code setDecorFitsSystemWindows}, {@code setStatusBarColor} and
     * {@code setNavigationBarColor}: the bars are transparent and the window is edge-to-edge no
     * matter what is requested here. There the same look comes from the theme's window background
     * showing through the transparent bars, with {@link #installContentInsets()} keeping content
     * off them - see {@code styles_mobile.xml} for the two-device measurement. Both paths are
     * wanted: minSdk is 24, so the calls below are still load-bearing on older devices.</p>
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

        window.setStatusBarColor(ThemeMode.color(this, R.attr.newtubeColorBackground));
        // NEWTUBE(theme): the page colour, except black below Android 8.1 in the light theme -
        // those can't draw dark navigation buttons (mobile_color_navigation_bar).
        window.setNavigationBarColor(ThemeMode.color(this, R.attr.newtubeColorNavigationBar));

        // NEWTUBE(theme): dark icons over the light theme's white bars, light ones over the dark
        // theme's (and over the player's black status band in both - isStatusBarOverDarkContent).
        boolean lightTheme = ThemeMode.isLight(this);
        boolean darkStatusIcons = lightTheme && !isStatusBarOverDarkContent();
        boolean darkNavigationIcons = lightTheme && Build.VERSION.SDK_INT >= 27;
        int legacyFlags = View.SYSTEM_UI_FLAG_VISIBLE;
        if (darkStatusIcons) {
            legacyFlags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (darkNavigationIcons) {
            legacyFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            if (Build.VERSION.SDK_INT == 30) {
                // Android 11 re-derives the bars' state from these legacy flags on window
                // attribute changes, and the player's fullscreen sets hiding ones there (issue
                // #68, MobilePlaybackActivity.applyImmersiveSystemBars): clear them first.
                window.getDecorView().setSystemUiVisibility(legacyFlags);
            }
            window.setDecorFitsSystemWindows(true);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_DEFAULT);
                controller.setSystemBarsAppearance(
                        (darkStatusIcons ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0)
                                | (darkNavigationIcons ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0),
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(legacyFlags);
        }
    }

    /**
     * NEWTUBE(theme): true while this screen draws something dark behind the status bar whatever
     * the theme (the player's black video band), so its status icons stay light.
     */
    protected boolean isStatusBarOverDarkContent() {
        return false;
    }
}
