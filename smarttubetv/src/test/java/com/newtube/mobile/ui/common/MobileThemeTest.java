package com.newtube.mobile.ui.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.view.ContextThemeWrapper;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * NEWTUBE(theme): Theme.NewTube is DayNight with a palette per side (issue #8). The dark side is the
 * palette the app always had; the light side must draw dark controls and text on its white pages
 * (a DayNight parent under the old dark palette drew unchecked radios #060606 on #0F0F0F). The
 * player chrome is dark on both sides. Also pins sentence-case button text (NEWTUBE(buttons)).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MobileThemeTest {

    @Test
    @Config(qualifiers = "night")
    public void darkSideKeepsTheDarkPaletteAndLightControls() {
        Context context = themed(R.style.Theme_NewTube);
        assertEquals(0xFF0F0F0F, color(context, R.color.mobile_color_background));
        assertEquals(0xFFFFFFFF, color(context, R.color.mobile_color_on_surface));
        assertEquals(0xFF1E1E1E, color(context, R.color.mobile_color_surface));
        assertTrue(lightControls(context));
    }

    @Test
    @Config(qualifiers = "notnight")
    public void lightSideDrawsDarkControlsOnWhitePages() {
        Context context = themed(R.style.Theme_NewTube);
        assertEquals(0xFFFFFFFF, color(context, R.color.mobile_color_background));
        assertEquals(0xFF0F0F0F, color(context, R.color.mobile_color_on_surface));
        assertFalse(lightControls(context));
    }

    @Test
    @Config(qualifiers = "notnight")
    public void playerChromeStaysDarkOnTheLightSide() {
        // The video area's theme overlay: dark ripples and control colours over the video.
        Context chrome = new ContextThemeWrapper(themed(R.style.Theme_NewTube_Player),
                R.style.ThemeOverlay_NewTube_PlayerChrome);
        assertTrue(lightControls(chrome));
        assertEquals(0xFFFFFFFF, color(chrome, R.color.mobile_player_on_video));
    }

    @Test
    @Config(qualifiers = "night")
    public void playerColoursAreTheSameOnTheDarkSide() {
        Context context = themed(R.style.Theme_NewTube_Player);
        assertEquals(0xFFFFFFFF, color(context, R.color.mobile_player_on_video));
        assertEquals(0xFF4FC3F7, color(context, R.color.mobile_player_cast_active));
        assertEquals(0x26FFFFFF, color(context, R.color.mobile_player_pill));
    }

    @Test
    @Config(qualifiers = "notnight")
    public void buttonTextIsSentenceCase() {
        Context themed = themed(R.style.Theme_NewTube);
        TypedArray theme = themed.obtainStyledAttributes(new int[] {R.attr.textAppearanceButton});
        int appearance = theme.getResourceId(0, 0);
        theme.recycle();

        TypedArray style = themed.obtainStyledAttributes(appearance,
                new int[] {android.R.attr.textAllCaps, android.R.attr.letterSpacing});
        boolean allCaps = style.getBoolean(0, true);
        float letterSpacing = style.getFloat(1, -1f);
        style.recycle();

        assertFalse("button text must not be ALL CAPS", allCaps);
        assertEquals(0f, letterSpacing, 0.0001f);
    }

    @Test
    public void themeModesMapToOneNightModeEverywhere() {
        assertEquals(Configuration.UI_MODE_NIGHT_UNDEFINED, ThemeMode.forcedNight(ThemeMode.SYSTEM));
        assertEquals(Configuration.UI_MODE_NIGHT_NO, ThemeMode.forcedNight(ThemeMode.LIGHT));
        assertEquals(Configuration.UI_MODE_NIGHT_YES, ThemeMode.forcedNight(ThemeMode.DARK));
        // AppCompat's dialogs apply this mode to the screen they open on: it must agree.
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemeMode.appCompatMode(ThemeMode.SYSTEM));
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeMode.appCompatMode(ThemeMode.LIGHT));
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemeMode.appCompatMode(ThemeMode.DARK));
    }

    @Test
    public void anInstallThatRanBeforeIsAnUpgrade() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences migrations = context.getSharedPreferences("theme_test_migrations", Context.MODE_PRIVATE);
        migrations.edit().clear().commit();
        assertFalse("a fresh install has no migration entries", ThemeMode.isExistingInstall(context, migrations));

        migrations.edit().putBoolean("caption_style_white_default", true).commit();
        assertTrue(ThemeMode.isExistingInstall(context, migrations));
    }

    @Test
    @Config(qualifiers = "night")
    public void pureBlackBlackensThePageAndKeepsRaisedSurfacesApart() {
        Context dark = themed(R.style.Theme_NewTube);
        assertEquals(0xFF0F0F0F, ThemeMode.color(dark, R.attr.newtubeColorBackground));
        assertEquals(0xFF1E1E1E, ThemeMode.color(dark, R.attr.newtubeColorSurface));

        Context black = themed(R.style.Theme_NewTube);
        black.getTheme().applyStyle(R.style.ThemeOverlay_NewTube_PureBlack, true);
        assertEquals(0xFF000000, ThemeMode.color(black, R.attr.newtubeColorBackground));
        assertEquals(0xFF000000, ThemeMode.color(black, R.attr.newtubeColorNavigationBar));
        assertEquals(0xFF000000, ThemeMode.color(black, R.attr.newtubeColorField));
        assertEquals(0xFF121212, ThemeMode.color(black, R.attr.newtubeColorSurface));
        assertEquals(0xFF121212, ThemeMode.color(black, R.attr.newtubeColorCard));
        // What the framework and Material read: window, colorBackground, the sheets' colorSurface.
        TypedArray a = black.obtainStyledAttributes(new int[] {android.R.attr.windowBackground,
                android.R.attr.colorBackground, R.attr.colorSurface});
        assertEquals(0xFF000000, a.getColor(1, 0));
        assertEquals(0xFF121212, a.getColor(2, 0));
        a.recycle();
        // The other palette text keeps its dark-theme values.
        assertEquals(0xFFFFFFFF, color(black, R.color.mobile_color_on_surface));
    }

    @Test
    @Config(qualifiers = "night")
    public void pureBlackFollowsTheSwitchOnTheDarkSide() {
        Context app = RuntimeEnvironment.getApplication();
        try {
            ThemeMode.setPureBlack(app, true);
            Context screen = themed(R.style.Theme_NewTube);
            ThemeMode.applyPalette(screen);
            assertEquals(0xFF000000, ThemeMode.color(screen, R.attr.newtubeColorBackground));
            assertEquals(ThemeMode.LOOK_PURE_BLACK | Configuration.UI_MODE_NIGHT_YES, ThemeMode.currentLook(screen));

            ThemeMode.setPureBlack(app, false);
            Context next = themed(R.style.Theme_NewTube);
            ThemeMode.applyPalette(next);
            assertEquals(0xFF0F0F0F, ThemeMode.color(next, R.attr.newtubeColorBackground));
            assertEquals(Configuration.UI_MODE_NIGHT_YES, ThemeMode.currentLook(next));
            // (Taking it off a live screen is a theme rebuild: rebuiltThemesFollowTheSwitchEachWay.)
        } finally {
            ThemeMode.setPureBlack(app, false);
        }
    }

    @Test
    @Config(qualifiers = "notnight")
    public void pureBlackNeverReachesTheLightSide() {
        Context app = RuntimeEnvironment.getApplication();
        try {
            ThemeMode.setPureBlack(app, true);
            Context screen = themed(R.style.Theme_NewTube);
            ThemeMode.applyPalette(screen);
            assertEquals(0xFFFFFFFF, ThemeMode.color(screen, R.attr.newtubeColorBackground));
            assertEquals(Configuration.UI_MODE_NIGHT_NO, ThemeMode.currentLook(screen));
        } finally {
            ThemeMode.setPureBlack(app, false);
        }
        assertEquals(Configuration.UI_MODE_NIGHT_NO, ThemeMode.look(Configuration.UI_MODE_NIGHT_NO, true));
        int black = ThemeMode.look(Configuration.UI_MODE_NIGHT_YES, true);
        assertEquals(Configuration.UI_MODE_NIGHT_YES, ThemeMode.night(black));
        assertTrue((black & ThemeMode.LOOK_PURE_BLACK) != 0);
    }

    /**
     * A screen that changes in place (the player) gets a rebuilt theme, and the drawables it
     * inflates next must follow it each way, however many times the switch flips.
     */
    @Test
    @Config(qualifiers = "night")
    public void rebuiltThemesFollowTheSwitchEachWay() {
        Context app = RuntimeEnvironment.getApplication();
        java.util.List<Integer> styles = java.util.Collections.singletonList(R.style.Theme_NewTube_Player);
        boolean[] flips = {true, false, true, false, true};
        for (boolean black : flips) {
            android.content.res.Resources.Theme theme = ThemeMode.buildTheme(app.getResources(), null, styles, black);
            Context screen = new ContextThemeWrapper(app, 0) {
                @Override
                public android.content.res.Resources.Theme getTheme() {
                    return theme;
                }
            };
            assertEquals(black ? 0xFF000000 : 0xFF0F0F0F, ThemeMode.color(screen, R.attr.newtubeColorBackground));
            assertEquals(black ? 0xFF121212 : 0xFF1E1E1E, cardColor(screen));
        }
    }

    /**
     * Pure black reaches a view only through the theme attributes (attrs_mobile.xml): a layout or
     * drawable that names the page colours' @color tokens directly would stay dark grey.
     */
    @Test
    public void layoutsReadThePageColoursThroughTheTheme() throws java.io.IOException {
        java.util.regex.Pattern direct = java.util.regex.Pattern.compile(
                "@color/mobile_color_(surface|card|field|background_scrim|navigation_bar|comment_parent)\\b"
                        + "|android:(background|endColor|startColor|centerColor)=\"@color/mobile_color_background\""
                        + "|<solid android:color=\"@color/mobile_color_background\"");
        java.io.File res = new java.io.File("src/stmobile/res");
        assertTrue("run from the module directory: " + res.getAbsolutePath(), res.isDirectory());
        StringBuilder offenders = new StringBuilder();
        for (String dir : new String[] {"layout", "drawable"}) {
            java.io.File[] files = new java.io.File(res, dir).listFiles();
            if (files == null) {
                continue;
            }
            for (java.io.File file : files) {
                String xml = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (direct.matcher(xml).find()) {
                    offenders.append(dir).append('/').append(file.getName()).append(' ');
                }
            }
        }
        assertEquals("use ?attr/newtubeColor* instead", "", offenders.toString());
    }

    private static int cardColor(Context context) {
        android.graphics.drawable.RippleDrawable card = (android.graphics.drawable.RippleDrawable)
                ContextCompat.getDrawable(context, R.drawable.bg_watch_card);
        android.graphics.drawable.GradientDrawable shape =
                (android.graphics.drawable.GradientDrawable) card.getDrawable(0);
        return shape.getColor().getDefaultColor();
    }

    private static boolean lightControls(Context context) {
        TypedArray a = context.obtainStyledAttributes(new int[] {
                R.attr.colorControlNormal, android.R.attr.textColorPrimary});
        ColorStateList controlNormal = a.getColorStateList(0);
        ColorStateList textPrimary = a.getColorStateList(1);
        a.recycle();
        boolean light = luminance(controlNormal.getDefaultColor()) > 0.4;
        assertEquals("text and controls on the same side: " + Integer.toHexString(textPrimary.getDefaultColor()),
                light, luminance(textPrimary.getDefaultColor()) > 0.5);
        return light;
    }

    private static int color(Context context, int res) {
        return ContextCompat.getColor(context, res);
    }

    private static Context themed(int style) {
        return new ContextThemeWrapper(RuntimeEnvironment.getApplication(), style);
    }

    /** Perceived brightness 0..1 (alpha ignored: both colours are drawn on the opaque app background). */
    private static double luminance(int color) {
        return (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
    }
}
