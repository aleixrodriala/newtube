package com.newtube.mobile.player;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import androidx.media3.common.PlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.TerminalSourceException;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.youtubeapi.videoinfo.models.SabrVodCapability;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class,
        shadows = {Media3SeekBufferingTest.OfflineSources.class, SabrPlayerControllerTest.Overrides.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SabrPlayerControllerTest {
    @Before public void defaults() { Overrides.preferred = ""; Overrides.fallback = ""; }
    @After public void restore() { SabrVodCapability.setEnabled(false); }

    @Test public void everyPreferredSabrPlayerErrorIsTerminalWithoutReprepareOrNestedSecrets() {
        List<Throwable> errors = new ArrayList<>();
        Media3PlayerController controller = sabrControllerThatMustNotTouchThePlayer(errors, false);
        for (int code : new int[]{PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW}) {
            controller.onPlayerError(new PlaybackException("source failure", new IOException("private-fixture"), code));
        }
        assertEquals(3, errors.size());
        for (Throwable error : errors) {
            assertTrue(error instanceof TerminalSourceException);
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains("private-fixture"));
        }
    }

    /**
     * The fallback carries responses that have no links at all, so ending playback there is the
     * same dead end it was meant to prevent. It must hand the real cause to the shared fixer.
     */
    @Test public void aFailedFallbackSabrSourceDefersToTheNormalClientRecovery() {
        List<Throwable> errors = new ArrayList<>();
        Media3PlayerController controller = sabrControllerThatMustNotTouchThePlayer(errors, true);
        IOException cause = new IOException("private-fixture");
        controller.onPlayerError(new PlaybackException("source failure", cause,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS));
        assertEquals(1, errors.size());
        assertSame(cause, errors.get(0));
        assertFalse(errors.get(0) instanceof TerminalSourceException);
        assertTrue(controller.allowsAutomaticSourceRecovery());
    }

    /** A preferred SABR source displaced a working route, so recovery stays disabled for it. */
    @Test public void aPreferredSabrSourceStillBlocksAutomaticRecovery() {
        Media3PlayerController controller =
                sabrControllerThatMustNotTouchThePlayer(new ArrayList<>(), false);
        assertFalse(controller.allowsAutomaticSourceRecovery());
    }

    private Media3PlayerController sabrControllerThatMustNotTouchThePlayer(
            List<Throwable> errors, boolean fallback) {
        PlayerEventListener listener = (PlayerEventListener) Proxy.newProxyInstance(
                PlayerEventListener.class.getClassLoader(), new Class<?>[]{PlayerEventListener.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("onEngineError")) throw new AssertionError(method.getName());
                    errors.add((Throwable) args[2]);
                    return null;
                });
        Media3PlayerController controller = new Media3PlayerController(RuntimeEnvironment.getApplication(), listener);
        ReflectionHelpers.setField(controller, "mSabrSourceActive", true);
        ReflectionHelpers.setField(controller, "mSabrWasFallback", fallback);
        ExoPlayer player = (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(),
                new Class<?>[]{ExoPlayer.class}, (proxy, method, args) -> {
                    throw new AssertionError("Must not recover SABR: " + method.getName());
                });
        ReflectionHelpers.setField(controller, "mPlayer", player);
        return controller;
    }

    /**
     * The fallback is the reason a link-less response plays at all, so it ships on. The
     * experiment that displaces working links does not.
     */
    @Test public void theFallbackIsOnByDefaultAndThePreferenceIsNot() {
        Context context = RuntimeEnvironment.getApplication();
        assertTrue(SabrSourcePreference.isFallbackEnabled(context));
        assertFalse(SabrSourcePreference.isPreferred(context));
        assertTrue(SabrSourcePreference.isEnabled(context));
        SabrSourcePreference.initialize(context);
        assertTrue(SabrVodCapability.isEnabled());
    }

    @Test public void turningTheFallbackOffWithoutThePreferenceRemovesTheCapability() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("sabr_vod_fallback", false).commit();
        assertFalse(SabrSourcePreference.isEnabled(context));
        SabrSourcePreference.initialize(context);
        assertFalse(SabrVodCapability.isEnabled());

        // The experiment alone still enables the decoder.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("sabr_vod", true).commit();
        assertTrue(SabrSourcePreference.isEnabled(context));
        SabrSourcePreference.initialize(context);
        assertTrue(SabrVodCapability.isEnabled());
    }

    @Test public void theTwoSwitchesAreStoredIndependently() {
        Context context = RuntimeEnvironment.getApplication();
        SabrSourcePreference.setPreferred(context, true);
        assertTrue(SabrSourcePreference.isPreferred(context));
        assertTrue(SabrSourcePreference.isFallbackEnabled(context));

        SabrSourcePreference.setFallbackEnabled(context, false);
        assertTrue(SabrSourcePreference.isPreferred(context));
        assertFalse(SabrSourcePreference.isFallbackEnabled(context));
    }

    @Test public void transientDebugOverrideDoesNotChangeTheStoredPreference() {
        Context context = RuntimeEnvironment.getApplication();
        boolean testBuild = com.liskovsoft.smartyoutubetv2.tv.BuildConfig.DEBUG
                || com.liskovsoft.smartyoutubetv2.tv.BuildConfig.BENCHMARK;
        Overrides.preferred = "1";
        assertEquals(testBuild, SabrSourcePreference.isPreferred(context));
        assertFalse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("sabr_vod", false));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("sabr_vod", true).commit();
        Overrides.preferred = "0";
        assertEquals(!testBuild, SabrSourcePreference.isPreferred(context));
        Overrides.preferred = "";
        assertTrue(SabrSourcePreference.isPreferred(context));
    }

    @Test public void theFallbackHasItsOwnTransientDebugOverride() {
        Context context = RuntimeEnvironment.getApplication();
        boolean testBuild = com.liskovsoft.smartyoutubetv2.tv.BuildConfig.DEBUG
                || com.liskovsoft.smartyoutubetv2.tv.BuildConfig.BENCHMARK;
        Overrides.fallback = "0";
        assertEquals(!testBuild, SabrSourcePreference.isFallbackEnabled(context));
        assertTrue(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("sabr_vod_fallback", true));
        Overrides.fallback = "";
        assertTrue(SabrSourcePreference.isFallbackEnabled(context));
    }

    private static final String PREFS = "newtube_playback_sources";

    @Implements(DebugMediaShaper.class)
    public static class Overrides {
        static String preferred = "";
        static String fallback = "";
        @Implementation protected static String prop(String name) {
            if ("debug.arc.sabr_vod".equals(name)) return preferred;
            if ("debug.arc.sabr_fallback".equals(name)) return fallback;
            return "";
        }
    }
}
