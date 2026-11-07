package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.app.Application;
import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Build;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(theme): Settings > User interface > Theme - System default / Light / Dark (issue #8).
 *
 * <p>The palette is a resource split (values/ light, values-night/ dark), so choosing a side is
 * choosing the night bit of each screen's configuration:</p>
 * <ul>
 *     <li>every screen gets it as a configuration override when it is created
 *     ({@link MotherActivity#setNightModeSource}); System default sets none;</li>
 *     <li>{@link AppCompatDelegate#setDefaultNightMode} gets the same mode. Our screens are not
 *     AppCompat activities, but Material's BottomSheetDialog and AlertDialog are AppCompat
 *     dialogs, and each one "applies" AppCompat's night mode to the resources of the screen it
 *     opens on - with a different mode it would flip the screen under it back;</li>
 *     <li>on Android 12+ {@link UiModeManager#setApplicationNightMode} gets it too, which is what
 *     themes the system's launch splash (otherwise a Dark user on a light phone would see a white
 *     splash on every cold start).</li>
 * </ul>
 *
 * <p>Screens already open when the side changes (the setting, or the system switching while on
 * System default) are rebuilt by {@link MobileActivity#checkTheme}: recreated, except the player,
 * which re-colours its watch page in place so the video never stops.</p>
 *
 * <p>Pure black (issues #73, #74) rides on the dark side: when it is on and a screen is dark (Dark,
 * or System default while the system is dark), {@link #applyPalette} lays
 * {@code ThemeOverlay.NewTube.PureBlack} over the screen's theme before its window and views
 * exist. The page colours are theme attributes ({@code attrs_mobile.xml}), so that one overlay
 * reaches the window, every view, and every sheet, dialog and menu opened from the screen (their
 * themes are copies of its theme). A screen's look is its night bits plus {@link #LOOK_PURE_BLACK};
 * {@link MobileActivity#checkTheme} rebuilds a screen whose look is not the wanted one, the same way
 * as for a day/night switch.</p>
 */
public final class ThemeMode {
    public static final int SYSTEM = 0;
    public static final int LIGHT = 1;
    public static final int DARK = 2;

    private static final String PREFS = "newtube_theme";
    private static final String KEY_MODE = "mode";
    private static final String KEY_PURE_BLACK = "pure_black";

    /**
     * Set in a look ({@link #desiredLook}) on top of the night bits when the screen is pure black.
     * Outside {@link Configuration#UI_MODE_NIGHT_MASK}, so {@link #night} recovers the night bits.
     */
    static final int LOOK_PURE_BLACK = 0x100;

    private static volatile int sMode = SYSTEM;
    private static volatile boolean sPureBlack;
    private static final List<WeakReference<MobileActivity>> sScreens = new ArrayList<>();

    private ThemeMode() {
    }

    /**
     * Startup, before any screen exists. The first run of a version with this setting picks its
     * default: System default for a new install, Dark for an install upgrading from the dark-only
     * app, so nobody's app changes colour on update.
     *
     * @param existingInstall this install ran an earlier version (see {@link #isExistingInstall})
     */
    public static void init(@NonNull Application app, boolean existingInstall) {
        SharedPreferences prefs = prefs(app);
        if (!prefs.contains(KEY_MODE)) {
            int initial = existingInstall ? DARK : SYSTEM;
            prefs.edit().putInt(KEY_MODE, initial).apply();
            NetPath.log("theme default mode=" + name(initial) + " existing=" + (existingInstall ? "y" : "n"));
        }
        sMode = sanitize(prefs.getInt(KEY_MODE, SYSTEM));
        sPureBlack = prefs.getBoolean(KEY_PURE_BLACK, false);
        MotherActivity.setNightModeSource(context -> forcedNight(sMode));
        applyProcessWide(app, sMode);
    }

    /**
     * An install that ran an earlier version: the one-time migrations file already holds entries
     * (every version since 2026-07 writes one on its first launch), or the package was updated in
     * place since it was installed. Must be read before this launch's own migrations write.
     */
    public static boolean isExistingInstall(@NonNull Context context, @NonNull SharedPreferences migrations) {
        if (!migrations.getAll().isEmpty()) {
            return true;
        }
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.lastUpdateTime > info.firstInstallTime;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    public static int get() {
        return sMode;
    }

    /** The Theme setting's choice: saved, applied, and every open screen brought over to it. */
    public static void set(@NonNull Context context, int mode) {
        mode = sanitize(mode);
        if (mode == sMode) {
            return;
        }
        sMode = mode;
        Context app = context.getApplicationContext();
        prefs(app).edit().putInt(KEY_MODE, mode).apply();
        NetPath.log("theme set mode=" + name(mode));
        applyProcessWide(app, mode);
        for (MobileActivity screen : liveScreens()) {
            screen.checkTheme();
        }
    }

    public static boolean isPureBlack() {
        return sPureBlack;
    }

    /**
     * The Pure black switch: saved, and every open screen brought over to it. Only screens on the
     * dark side change; a light one already shows what it should.
     */
    public static void setPureBlack(@NonNull Context context, boolean on) {
        if (on == sPureBlack) {
            return;
        }
        sPureBlack = on;
        prefs(context.getApplicationContext()).edit().putBoolean(KEY_PURE_BLACK, on).apply();
        NetPath.log("theme set pure-black=" + (on ? "y" : "n"));
        for (MobileActivity screen : liveScreens()) {
            screen.checkTheme();
        }
    }

    /** The look a screen should be drawn with now: {@link #desiredNight}, plus pure black. */
    public static int desiredLook(@NonNull Context context) {
        return look(desiredNight(context), sPureBlack);
    }

    /** The look a themed context resolves with now: its night bits, plus pure black if its theme has it. */
    public static int currentLook(@NonNull Context context) {
        return currentNight(context) | (hasPureBlack(context.getTheme()) ? LOOK_PURE_BLACK : 0);
    }

    /** The night bits of a look. */
    static int night(int look) {
        return look & Configuration.UI_MODE_NIGHT_MASK;
    }

    /** Pure black is a dark-side setting: the light side never gets it. */
    static int look(int night, boolean pureBlack) {
        return night | (pureBlack && night == Configuration.UI_MODE_NIGHT_YES ? LOOK_PURE_BLACK : 0);
    }

    /**
     * Lays the pure-black overlay over a theme that should have it and doesn't yet: a screen's,
     * before its window and views exist (MobileActivity.onCreate), and a dialog's whose own style is
     * a whole app theme (the comment composer), which resets the page colours. Taking it off again
     * is {@link #syncResources}'s job: a theme can't drop a style laid over it.
     */
    public static void applyPalette(@NonNull Context context) {
        if (wantsPureBlack(context) && !hasPureBlack(context.getTheme())) {
            context.getTheme().applyStyle(R.style.ThemeOverlay_NewTube_PureBlack, true);
        }
    }

    private static boolean wantsPureBlack(@NonNull Context context) {
        return (look(currentNight(context), sPureBlack) & LOOK_PURE_BLACK) != 0;
    }

    /**
     * Rebuilds a live screen's theme with or without the pure-black overlay, for a screen that
     * changes in place (the player): from its base context's theme plus the styles the screen set
     * ({@link MobileActivity#onApplyThemeResource}), exactly as the screen was first themed, then
     * copied over its theme.
     *
     * <p>Why not just lay the other overlay on top: Android 15's ThemeKey de-duplicates a style
     * applied a second time by moving it to the end of the key without rehashing it, and the
     * drawable cache's keys share those arrays, so a theme that went black, back, and black again
     * matched the cache entry of the middle step - the watch page's cards kept the dark grey
     * (verified on an API 35 emulator). A rebuilt theme only ever holds each style once.</p>
     */
    static void rebuildTheme(@NonNull MobileActivity screen, boolean pureBlack) {
        Resources.Theme fresh = buildTheme(screen.getResources(), screen.getBaseContext().getTheme(),
                screen.getAppliedThemeStyles(), pureBlack);
        screen.getTheme().setTo(fresh);
    }

    static Resources.Theme buildTheme(@NonNull Resources res, Resources.Theme base, @NonNull List<Integer> styles,
                                      boolean pureBlack) {
        Resources.Theme fresh = res.newTheme();
        if (base != null) {
            fresh.setTo(base);
        }
        for (int style : styles) {
            fresh.applyStyle(style, true);
        }
        if (pureBlack) {
            fresh.applyStyle(R.style.ThemeOverlay_NewTube_PureBlack, true);
        }
        return fresh;
    }

    /** A page colour of the context's theme (attrs_mobile.xml), for code that paints one itself. */
    public static int color(@NonNull Context context, int attr) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true)) {
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data;
            }
            if (value.resourceId != 0) {
                return ContextCompat.getColor(context, value.resourceId);
            }
        }
        return Color.BLACK;
    }

    private static boolean hasPureBlack(@NonNull Resources.Theme theme) {
        TypedValue value = new TypedValue();
        return theme.resolveAttribute(R.attr.newtubePureBlack, value, true)
                && value.type == TypedValue.TYPE_INT_BOOLEAN && value.data != 0;
    }

    /**
     * The night bits a screen should be drawn with now: the forced side, or on System default the
     * system's, read off the application (never a screen, whose own configuration may still carry
     * the override it was created with).
     */
    public static int desiredNight(@NonNull Context context) {
        int forced = forcedNight(sMode);
        if (forced != Configuration.UI_MODE_NIGHT_UNDEFINED) {
            return forced;
        }
        int system = context.getApplicationContext().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return system == Configuration.UI_MODE_NIGHT_YES ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
    }

    /** The night bits a context's resources resolve with now. */
    public static int currentNight(@NonNull Context context) {
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
    }

    public static boolean isLight(@NonNull Context context) {
        return currentNight(context) != Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Brings a screen that is NOT recreated (the player) to {@code night}: its resources and its
     * theme, so what it inflates from here on - sheets, rows, the refreshed watch page - resolves
     * to that side, pure black or not ({@link #rebuildTheme}). The framework rebuilds a screen's
     * resources from the override it was created with on every later configuration change, so this is re-run after each one (see
     * {@link MobileActivity#checkTheme}). The same technique AppCompat uses for activities that
     * handle uiMode themselves.
     */
    @SuppressWarnings("deprecation")
    public static void syncResources(@NonNull Activity activity, int night) {
        Resources res = activity.getResources();
        Configuration current = res.getConfiguration();
        if ((current.uiMode & Configuration.UI_MODE_NIGHT_MASK) != night) {
            Configuration updated = new Configuration(current);
            updated.uiMode = (updated.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
            res.updateConfiguration(updated, null);
            if (Build.VERSION.SDK_INT >= 29) {
                activity.getTheme().rebase(); // re-applies the overlays too, in order
            } else {
                int themeRes = manifestTheme(activity); // resets the page colours to the palette
                if (themeRes != 0) {
                    activity.getTheme().applyStyle(themeRes, true);
                }
            }
        }
        boolean pureBlack = wantsPureBlack(activity);
        if (hasPureBlack(activity.getTheme()) != pureBlack) {
            if (activity instanceof MobileActivity) {
                rebuildTheme((MobileActivity) activity, pureBlack);
            } else {
                applyPalette(activity);
            }
        }
    }

    static void register(@NonNull MobileActivity screen) {
        synchronized (sScreens) {
            prune();
            sScreens.add(new WeakReference<>(screen));
        }
    }

    static void unregister(@NonNull MobileActivity screen) {
        synchronized (sScreens) {
            for (int i = sScreens.size() - 1; i >= 0; i--) {
                MobileActivity live = sScreens.get(i).get();
                if (live == null || live == screen) {
                    sScreens.remove(i);
                }
            }
        }
    }

    private static List<MobileActivity> liveScreens() {
        List<MobileActivity> screens = new ArrayList<>();
        synchronized (sScreens) {
            prune();
            for (WeakReference<MobileActivity> ref : sScreens) {
                MobileActivity screen = ref.get();
                if (screen != null) {
                    screens.add(screen);
                }
            }
        }
        return screens;
    }

    private static void prune() {
        for (int i = sScreens.size() - 1; i >= 0; i--) {
            if (sScreens.get(i).get() == null) {
                sScreens.remove(i);
            }
        }
    }

    /** {@link Configuration#UI_MODE_NIGHT_YES}/{@code NO} for a forced side, UNDEFINED for System default. */
    static int forcedNight(int mode) {
        switch (mode) {
            case LIGHT:
                return Configuration.UI_MODE_NIGHT_NO;
            case DARK:
                return Configuration.UI_MODE_NIGHT_YES;
            default:
                return Configuration.UI_MODE_NIGHT_UNDEFINED;
        }
    }

    static int appCompatMode(int mode) {
        switch (mode) {
            case LIGHT:
                return AppCompatDelegate.MODE_NIGHT_NO;
            case DARK:
                return AppCompatDelegate.MODE_NIGHT_YES;
            default:
                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }

    private static void applyProcessWide(Context app, int mode) {
        AppCompatDelegate.setDefaultNightMode(appCompatMode(mode));
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                UiModeManager uiModeManager = app.getSystemService(UiModeManager.class);
                if (uiModeManager != null) {
                    uiModeManager.setApplicationNightMode(mode == LIGHT ? UiModeManager.MODE_NIGHT_NO
                            : mode == DARK ? UiModeManager.MODE_NIGHT_YES : UiModeManager.MODE_NIGHT_AUTO);
                }
            } catch (RuntimeException e) {
                // Only the launch splash depends on it; the screens have their own override.
                NetPath.log("theme app-night-mode failed " + e.getClass().getSimpleName());
            }
        }
    }

    private static int manifestTheme(Activity activity) {
        try {
            ActivityInfo info = activity.getPackageManager().getActivityInfo(activity.getComponentName(), 0);
            return info.getThemeResource();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private static int sanitize(int mode) {
        return mode == LIGHT || mode == DARK ? mode : SYSTEM;
    }

    static String name(int mode) {
        return mode == LIGHT ? "light" : mode == DARK ? "dark" : "system";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
