package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem.VideoPreset;
import com.liskovsoft.smartyoutubetv2.common.misc.AppDataSourceManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Tests for {@link NetworkQualityPrefs} (#130: Wi-Fi vs mobile data default quality). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class NetworkQualityPrefsTest {

    private Application context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        NetworkQualityPrefs.setMobileQualityKey(context, NetworkQualityPrefs.QUALITY_SAME_AS_WIFI);
    }

    @After
    public void tearDown() {
        NetworkQualityPrefs.setMobileQualityKey(context, NetworkQualityPrefs.QUALITY_SAME_AS_WIFI);
    }

    @Test
    public void defaultMobileSettingFollowsWifi() {
        assertEquals(NetworkQualityPrefs.QUALITY_SAME_AS_WIFI,
                NetworkQualityPrefs.getMobileQualityKey(context));
        assertFalse(NetworkQualityPrefs.hasMobileOverride(context));
    }

    @Test
    public void settingMobileOverridePersistsAndClears() {
        NetworkQualityPrefs.setMobileQualityKey(context, "720p    60fps    vp9");
        assertTrue(NetworkQualityPrefs.hasMobileOverride(context));
        assertEquals("720p    60fps    vp9", NetworkQualityPrefs.getMobileQualityKey(context));

        NetworkQualityPrefs.setMobileQualityKey(context, null);
        assertFalse(NetworkQualityPrefs.hasMobileOverride(context));
        assertEquals(NetworkQualityPrefs.QUALITY_SAME_AS_WIFI,
                NetworkQualityPrefs.getMobileQualityKey(context));
    }

    @Test
    public void resolvePresetSelectsWifiAutoOrNamedPreset() {
        VideoPreset[] presets = AppDataSourceManager.instance().getVideoPresets();
        VideoPreset preset720 = presets[0];
        for (VideoPreset preset : presets) {
            if (preset.getHeight() == 720) {
                preset720 = preset;
                break;
            }
        }
        FormatItem wifi = FormatItem.VIDEO_4K_VP9_60;
        FormatItem auto = FormatItem.VIDEO_FHD_VP9_60;

        assertSame(wifi, NetworkQualityPrefs.resolvePreset(
                NetworkQualityPrefs.QUALITY_SAME_AS_WIFI, wifi, auto, presets));
        assertSame(auto, NetworkQualityPrefs.resolvePreset(
                NetworkQualityPrefs.QUALITY_AUTO, wifi, auto, presets));
        assertSame(preset720.format, NetworkQualityPrefs.resolvePreset(
                preset720.name, wifi, auto, presets));
        assertSame(wifi, NetworkQualityPrefs.resolvePreset(
                "missing-preset-name", wifi, auto, presets));
    }
}
