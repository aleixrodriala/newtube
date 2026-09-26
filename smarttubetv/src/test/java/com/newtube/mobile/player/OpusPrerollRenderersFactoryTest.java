package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.ArrayList;
import java.util.List;

/** The player's audio renderer is the pre-roll skipping one, in the stock position; off = stock. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class OpusPrerollRenderersFactoryTest {
    @Test
    public void audioRendererIsReplacedInPlace() {
        List<Class<?>> types = rendererTypes(new Media3PlayerInitializer.PrerollRenderersFactory(
                RuntimeEnvironment.getApplication(), true));
        assertEquals(1, types.stream().filter(t -> MediaCodecAudioRenderer.class.isAssignableFrom(t)).count());
        List<Class<?>> stock = rendererTypes(new Media3PlayerInitializer.PrerollRenderersFactory(
                RuntimeEnvironment.getApplication(), false));
        assertEquals(stock.size(), types.size());
        int audioIndex = stock.indexOf(MediaCodecAudioRenderer.class);
        assertSame(OpusPrerollAudioRenderer.class, types.get(audioIndex));
    }

    @Test
    public void switchedOffKeepsTheStockRenderer() {
        List<Class<?>> stock = rendererTypes(new Media3PlayerInitializer.PrerollRenderersFactory(
                RuntimeEnvironment.getApplication(), false));
        assertEquals(true, stock.contains(MediaCodecAudioRenderer.class));
        assertEquals(false, stock.contains(OpusPrerollAudioRenderer.class));
    }

    private static List<Class<?>> rendererTypes(Media3PlayerInitializer.PrerollRenderersFactory factory) {
        Handler handler = new Handler(Looper.getMainLooper());
        Renderer[] renderers = factory.createRenderers(handler,
                new VideoRendererEventListener() { }, new AudioRendererEventListener() { },
                cues -> { }, metadata -> { });
        List<Class<?>> types = new ArrayList<>();
        for (Renderer renderer : renderers) {
            types.add(renderer.getClass());
        }
        return types;
    }
}
