package com.newtube.mobile.ui.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.view.ContextThemeWrapper;

import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * NEWTUBE(theme-dark-only): the phone theme has ONE (dark) palette whatever the system mode. With
 * the old DayNight parent, system light mode drew unchecked radios/checkboxes #060606 on the
 * #0F0F0F background (invisible). Also pins sentence-case button text (NEWTUBE(buttons)).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MobileThemeTest {

    @Test
    @Config(qualifiers = "notnight")
    public void systemLightModeStillDrawsLightControlsOnTheDarkBackground() {
        assertControlsReadableOnDark();
    }

    @Test
    @Config(qualifiers = "night")
    public void systemDarkModeDrawsTheSameControls() {
        assertControlsReadableOnDark();
    }

    @Test
    @Config(qualifiers = "notnight")
    public void buttonTextIsSentenceCase() {
        Context themed = themed();
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

    private static void assertControlsReadableOnDark() {
        TypedArray a = themed().obtainStyledAttributes(new int[] {
                R.attr.colorControlNormal, android.R.attr.textColorPrimary});
        ColorStateList controlNormal = a.getColorStateList(0);
        ColorStateList textPrimary = a.getColorStateList(1);
        a.recycle();

        assertTrue("colorControlNormal too dark for #0F0F0F: "
                        + Integer.toHexString(controlNormal.getDefaultColor()),
                luminance(controlNormal.getDefaultColor()) > 0.4);
        assertTrue("textColorPrimary too dark for #0F0F0F: "
                        + Integer.toHexString(textPrimary.getDefaultColor()),
                luminance(textPrimary.getDefaultColor()) > 0.8);
    }

    private static Context themed() {
        return new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_NewTube);
    }

    /** Perceived brightness 0..1 (alpha ignored: both colours are drawn on the opaque app background). */
    private static double luminance(int color) {
        return (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
    }
}
