package com.newtube.mobile.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowSystemProperties;
import org.robolectric.util.ReflectionHelpers;

/**
 * The real player the initializer builds keeps its codecs across stop()/prepare(): foreground mode
 * is what makes media3's stopInternal skip the renderer reset between two opens.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class Media3KeepCodecTest {
    private Media3PlayerInitializer initializer;
    private ExoPlayer player;

    @Before
    public void setUp() {
        ShadowSystemProperties.override("debug.arc.keep_codec", "");
        ShadowSystemProperties.override("debug.arc.next_media_preload", "");
        initializer = new Media3PlayerInitializer(RuntimeEnvironment.getApplication());
    }

    @After
    public void tearDown() {
        if (player != null) {
            player.release();
        }
        ShadowSystemProperties.override("debug.arc.keep_codec", "");
        ShadowSystemProperties.override("debug.arc.next_media_preload", "");
    }

    @Test
    public void builtPlayerKeepsCodecsAcrossOpens() {
        player = build();
        assertTrue(foregroundMode(player));
    }

    @Test
    public void preloadExperimentPlayerKeepsThemToo() {
        ShadowSystemProperties.override("debug.arc.next_media_preload", "1");
        player = build();
        assertTrue(foregroundMode(player));
        initializer.getPreloadManagerBuilder().build().release();
    }

    @Test
    public void debugSwitchRestoresTheStockReset() {
        ShadowSystemProperties.override("debug.arc.keep_codec", "0");
        player = build();
        assertFalse(foregroundMode(player));
    }

    private ExoPlayer build() {
        DefaultTrackSelector selector = initializer.createTrackSelector();
        return initializer.createPlayer(selector,
                new DefaultBandwidthMeter.Builder(RuntimeEnvironment.getApplication()).build());
    }

    /** ExoPlayerImpl mirrors the playback thread's flag; no public getter exists. */
    private static boolean foregroundMode(ExoPlayer player) {
        return ReflectionHelpers.getField(player, "foregroundMode");
    }
}
