package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * The one-time MEDIUM -> HIGH "Video buffer" default alignment (Media3PlayerInitializer): the v2
 * pass for installs the debounced v1 left on MEDIUM, the ordering that keeps a flag from outliving
 * its value, and the user-choice marker the settings radio records.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class BufferDefaultAlignmentTest {
    private static final String V1 = "buffer_default_aligned";
    private static final String V2 = "buffer_default_aligned_v2";

    private Application app;
    private SharedPreferences prefs;
    private PlayerData playerData;
    private Runnable persistTask;
    /** Strong refs: SharedPreferences keeps its listeners in a weak map. */
    private final List<SharedPreferences.OnSharedPreferenceChangeListener> listeners = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();
        prefs = app.getSharedPreferences(PlayerData.NEWTUBE_PLAYER_PREFS, Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        playerData = PlayerData.instance(app);
        Field field = PlayerData.class.getDeclaredField("mPersistStateInt");
        field.setAccessible(true);
        persistTask = (Runnable) field.get(playerData);
        idle();
    }

    @After
    public void tearDown() {
        for (SharedPreferences.OnSharedPreferenceChangeListener listener : listeners) {
            prefs.unregisterOnSharedPreferenceChangeListener(listener);
        }
        idle();
        prefs.edit().clear().commit();
        playerData.setVideoBufferType(PlayerData.BUFFER_HIGH);
    }

    @Test
    public void installStuckOnMediumByTheV1DebounceIsAlignedOnceMore() {
        // The owner's Pixel 9: v1 flag written at first install, PlayerData still MEDIUM.
        prefs.edit().putBoolean(V1, true).commit();
        playerData.setVideoBufferType(PlayerData.BUFFER_MEDIUM);

        new Media3PlayerInitializer(app);

        assertEquals(PlayerData.BUFFER_HIGH, playerData.getVideoBufferType());
        assertFalse("the flag waits behind the value's write", prefs.contains(V2));
        idle();
        assertTrue(prefs.getBoolean(V2, false));

        // Done for good: a later MEDIUM (however it got there) is left alone.
        playerData.setVideoBufferType(PlayerData.BUFFER_MEDIUM);
        new Media3PlayerInitializer(app);
        idle();
        assertEquals(PlayerData.BUFFER_MEDIUM, playerData.getVideoBufferType());
    }

    @Test
    public void flagIsOnlyWrittenAfterTheValueItVouchesForIsPersisted() {
        prefs.edit().putBoolean(V1, true).commit();
        playerData.setVideoBufferType(PlayerData.BUFFER_MEDIUM);
        boolean[] persistPendingWhenFlagged = {true};
        listen((p, key) -> {
            if (V2.equals(key)) {
                persistPendingWhenFlagged[0] = Utils.sHandler.hasCallbacks(persistTask);
            }
        });

        new Media3PlayerInitializer(app);
        assertTrue(Utils.sHandler.hasCallbacks(persistTask));
        idle(); // runs only what is due now - a 10 s debounced write would still be pending
        assertFalse(Utils.sHandler.hasCallbacks(persistTask));
        // ...and it had already run when the flag landed.
        assertFalse(persistPendingWhenFlagged[0]);
        assertTrue(prefs.getBoolean(V2, false));
    }

    @Test
    public void freshInstallSetsBothPassesAtOnce() {
        playerData.setVideoBufferType(PlayerData.BUFFER_MEDIUM); // PlayerData's parse default

        new Media3PlayerInitializer(app);
        idle();

        assertEquals(PlayerData.BUFFER_HIGH, playerData.getVideoBufferType());
        assertTrue(prefs.getBoolean(V1, false));
        assertTrue(prefs.getBoolean(V2, false));
    }

    @Test
    public void otherPresetsAreNeverTouched() {
        prefs.edit().putBoolean(V1, true).commit();
        playerData.setVideoBufferType(PlayerData.BUFFER_LOW);

        new Media3PlayerInitializer(app);
        idle();

        assertEquals(PlayerData.BUFFER_LOW, playerData.getVideoBufferType());
        assertTrue(prefs.getBoolean(V2, false));
    }

    @Test
    public void settingsPickOfMediumIsRememberedAndNeverOverridden() {
        // v2 has not run yet: the user opens Settings before the first video on this build.
        prefs.edit().putBoolean(V1, true).commit();
        playerData.setVideoBufferType(PlayerData.BUFFER_HIGH);
        boolean[] persistPendingWhenMarked = {true};
        listen((p, key) -> {
            if (PlayerData.KEY_BUFFER_USER_CHOSEN.equals(key)) {
                persistPendingWhenMarked[0] = Utils.sHandler.hasCallbacks(persistTask);
            }
        });

        OptionCategory buffer = AppDialogUtil.createVideoBufferCategory(app);
        buffer.options.get(1).onSelect(true); // LOW, MEDIUM, HIGH, HIGHEST
        assertEquals(PlayerData.BUFFER_MEDIUM, playerData.getVideoBufferType());
        idle();
        assertTrue(prefs.getBoolean(PlayerData.KEY_BUFFER_USER_CHOSEN, false));
        assertFalse("the marker waits behind the value's write", persistPendingWhenMarked[0]);

        new Media3PlayerInitializer(app);
        idle();

        assertEquals(PlayerData.BUFFER_MEDIUM, playerData.getVideoBufferType());
        assertTrue(prefs.getBoolean(V2, false));
    }

    @Test
    public void programmaticBufferChangesDoNotCountAsAUserChoice() {
        // The OOM fallbacks call the plain setter.
        playerData.setVideoBufferType(PlayerData.BUFFER_MEDIUM);
        idle();
        assertFalse(prefs.contains(PlayerData.KEY_BUFFER_USER_CHOSEN));
    }

    private void listen(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        listeners.add(listener);
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
