package com.liskovsoft.smartyoutubetv2.common.app.models.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerManager;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SleepTimerTest {

    @After
    public void cleanup() {
        SleepTimer.setPlayer(null);
        SleepTimer.setListener(null);
        SleepTimer.cancel();
    }

    @Test
    public void initialStateIsOff() {
        assertFalse(SleepTimer.isArmed());
        assertFalse(SleepTimer.isEndOfVideoArmed());
        assertEquals(SleepTimer.OPTION_OFF, SleepTimer.getSelectedOption());
    }

    @Test
    public void armingMinutesSetsOptionAndTarget() {
        SleepTimer.arm(15);

        assertTrue(SleepTimer.isArmed());
        assertFalse(SleepTimer.isEndOfVideoArmed());
        assertEquals(15, SleepTimer.getSelectedOption());
        assertTrue(SleepTimer.getTargetRealtimeMs() > 0);
    }

    @Test
    public void armingEndOfVideoSetsFlag() {
        SleepTimer.armEndOfVideo();

        assertTrue(SleepTimer.isArmed());
        assertTrue(SleepTimer.isEndOfVideoArmed());
        assertEquals(SleepTimer.OPTION_END_OF_VIDEO, SleepTimer.getSelectedOption());
        assertEquals(0, SleepTimer.getTargetRealtimeMs());
    }

    @Test
    public void cancelDisarmsTimer() {
        SleepTimer.arm(30);
        assertTrue(SleepTimer.isArmed());

        SleepTimer.cancel();
        assertFalse(SleepTimer.isArmed());
        assertEquals(SleepTimer.OPTION_OFF, SleepTimer.getSelectedOption());
        assertEquals(0, SleepTimer.getTargetRealtimeMs());
    }

    @Test
    public void onEndOfVideoReachedTriggersPlayerAndListener() {
        AtomicBoolean paused = new AtomicBoolean(false);
        AtomicBoolean listenerCalled = new AtomicBoolean(false);

        PlayerManager player = (PlayerManager) Proxy.newProxyInstance(
                PlayerManager.class.getClassLoader(),
                new Class<?>[]{PlayerManager.class},
                (proxy, method, args) -> {
                    if ("setPlayWhenReady".equals(method.getName()) && args != null && args.length == 1) {
                        if (Boolean.FALSE.equals(args[0])) {
                            paused.set(true);
                        }
                    }
                    return null;
                });

        SleepTimer.setPlayer(player);
        SleepTimer.setListener(() -> listenerCalled.set(true));

        SleepTimer.armEndOfVideo();
        assertTrue(SleepTimer.isEndOfVideoArmed());

        SleepTimer.onEndOfVideoReached();

        assertTrue("Player should have been paused", paused.get());
        assertTrue("Listener should have been notified", listenerCalled.get());
        assertFalse("Timer should be disarmed after trigger", SleepTimer.isArmed());
    }
}
