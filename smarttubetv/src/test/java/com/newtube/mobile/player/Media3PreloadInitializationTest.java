package com.newtube.mobile.player;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.app.Application;

import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
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

/** Real builders without media preparation: disabled experiments add no preload initialization. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class Media3PreloadInitializationTest {
    private Media3PlayerInitializer initializer;
    private DefaultTrackSelector foreground;
    private DefaultBandwidthMeter bandwidth;
    private ExoPlayer player;
    private DefaultPreloadManager manager;

    @Before
    public void setUp() {
        ShadowSystemProperties.override("debug.arc.next_media_preload", "");
        Application context = RuntimeEnvironment.getApplication();
        initializer = new Media3PlayerInitializer(context);
        foreground = initializer.createTrackSelector();
        bandwidth = new DefaultBandwidthMeter.Builder(context).build();
    }

    @After
    public void tearDown() {
        if (manager != null) {
            manager.release();
        } else if (initializer.getPreloadTrackSelector() != null) {
            initializer.getPreloadTrackSelector().release();
        }
        if (player != null) player.release();
        ShadowSystemProperties.override("debug.arc.next_media_preload", "");
    }

    @Test
    public void unsetFlagKeepsOriginalPlayerBuilderAndCreatesNoPreloadComponents() {
        player = initializer.createPlayer(foreground, bandwidth);
        assertSame(foreground, player.getTrackSelector());
        assertNull(initializer.getPreloadManagerBuilder());
        assertNull(initializer.getPreloadTrackSelector());
    }

    @Test
    public void optedInPlayerAndManagerShareBuilderButNotMutableTrackSelector() {
        ShadowSystemProperties.override("debug.arc.next_media_preload", "1");
        player = initializer.createPlayer(foreground, bandwidth);
        assertSame(foreground, player.getTrackSelector());
        assertNotNull(initializer.getPreloadManagerBuilder());
        assertNotNull(initializer.getPreloadTrackSelector());
        assertNotSame(foreground, initializer.getPreloadTrackSelector());
        manager = initializer.getPreloadManagerBuilder().build();
        assertNotNull(manager);
        assertSame(foreground, player.getTrackSelector());
    }
}
