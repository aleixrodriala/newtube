package com.liskovsoft.smartyoutubetv2.common.app.models.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VideoLoaderController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerManager;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
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
    public void timedPathAdvancesToExpiry() {
        AtomicBoolean paused = new AtomicBoolean(false);
        AtomicBoolean listenerCalled = new AtomicBoolean(false);

        PlayerManager player = createPlayerMock(paused);
        SleepTimer.setPlayer(player);
        SleepTimer.setListener(() -> listenerCalled.set(true));

        SleepTimer.arm(15);
        assertTrue(SleepTimer.isArmed());
        assertFalse(paused.get());
        assertFalse(listenerCalled.get());

        // Advance 14 minutes: should not trigger yet
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(14));
        assertFalse(paused.get());
        assertFalse(listenerCalled.get());
        assertTrue(SleepTimer.isArmed());

        // Advance final minute: should trigger and disarm
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1));
        assertTrue("Player should be paused on expiry", paused.get());
        assertTrue("Listener should be called on expiry", listenerCalled.get());
        assertFalse("Sleep timer should be disarmed after expiry", SleepTimer.isArmed());
    }

    @Test
    public void timedPathCancelPreventsExpiry() {
        AtomicBoolean paused = new AtomicBoolean(false);
        AtomicBoolean listenerCalled = new AtomicBoolean(false);

        PlayerManager player = createPlayerMock(paused);
        SleepTimer.setPlayer(player);
        SleepTimer.setListener(() -> listenerCalled.set(true));

        SleepTimer.arm(15);
        assertTrue(SleepTimer.isArmed());

        // Cancel after 5 minutes
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5));
        SleepTimer.cancel();
        assertFalse(SleepTimer.isArmed());

        // Advance remaining time
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(15));
        assertFalse("Player should not be paused if cancelled", paused.get());
        assertFalse("Listener should not be called if cancelled", listenerCalled.get());
    }

    @Test
    public void deepSleepClockDesyncFiresOnCheckOverdue() {
        AtomicBoolean paused = new AtomicBoolean(false);
        AtomicBoolean listenerCalled = new AtomicBoolean(false);

        PlayerManager player = createPlayerMock(paused);
        SleepTimer.setPlayer(player);
        SleepTimer.setListener(() -> listenerCalled.set(true));

        SleepTimer.arm(15);

        // Simulate deep sleep: elapsedRealtime advanced 15m, but main looper was not dispatched
        ShadowSystemClock.advanceBy(Duration.ofMinutes(15));
        assertFalse(paused.get());

        // Re-check overdue on screen on / wake / menu opening
        SleepTimer.checkOverdue();
        assertTrue("Check overdue should trigger when elapsedRealtime reached deadline", paused.get());
        assertTrue(listenerCalled.get());
        assertFalse(SleepTimer.isArmed());
    }

    @Test
    public void onEndOfVideoReachedTriggersPlayerAndListener() {
        AtomicBoolean paused = new AtomicBoolean(false);
        AtomicBoolean listenerCalled = new AtomicBoolean(false);

        PlayerManager player = createPlayerMock(paused);
        SleepTimer.setPlayer(player);
        SleepTimer.setListener(() -> listenerCalled.set(true));

        SleepTimer.armEndOfVideo();
        assertTrue(SleepTimer.isEndOfVideoArmed());

        SleepTimer.onEndOfVideoReached();

        assertTrue("Player should have been paused", paused.get());
        assertTrue("Listener should have been notified", listenerCalled.get());
        assertFalse("Timer should be disarmed after trigger", SleepTimer.isArmed());
    }

    @Test
    public void endOfVideoWithRepeatOrQueueSuppressesNextAndRepeat() {
        AtomicBoolean paused = new AtomicBoolean(false);
        AtomicBoolean listenerCalled = new AtomicBoolean(false);

        PlaybackView playerView = (PlaybackView) Proxy.newProxyInstance(
                PlaybackView.class.getClassLoader(),
                new Class<?>[]{PlaybackView.class},
                (proxy, method, args) -> {
                    if ("setPlayWhenReady".equals(method.getName()) && args != null && args.length == 1) {
                        if (Boolean.FALSE.equals(args[0])) {
                            paused.set(true);
                        }
                    } else if ("getDurationMs".equals(method.getName())) {
                        return 100_000L;
                    }
                    return null;
                });

        SleepTimer.setPlayer(playerView);
        SleepTimer.setListener(() -> listenerCalled.set(true));
        SleepTimer.armEndOfVideo();
        assertTrue(SleepTimer.isEndOfVideoArmed());

        TestVideoLoaderController controller = new TestVideoLoaderController(
                playerView, PlayerConstants.PLAYBACK_MODE_ALL);

        controller.onPlayEnd();

        assertTrue("Playback should have been paused", paused.get());
        assertTrue("Listener should have been called", listenerCalled.get());
        assertFalse("Sleep timer should be disarmed", SleepTimer.isArmed());
        assertFalse("loadNext should NOT be called even though PLAYBACK_MODE_ALL was set", controller.nextLoaded);
    }

    @Test
    public void endOfVideoWithRepeatOneSuppressesRepeat() {
        AtomicBoolean paused = new AtomicBoolean(false);
        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        List<Long> seekPositions = new ArrayList<>();

        PlaybackView playerView = (PlaybackView) Proxy.newProxyInstance(
                PlaybackView.class.getClassLoader(),
                new Class<?>[]{PlaybackView.class},
                (proxy, method, args) -> {
                    if ("setPlayWhenReady".equals(method.getName()) && args != null && args.length == 1) {
                        if (Boolean.FALSE.equals(args[0])) {
                            paused.set(true);
                        }
                    } else if ("setPositionMs".equals(method.getName()) && args != null && args.length == 1) {
                        seekPositions.add((Long) args[0]);
                    } else if ("getDurationMs".equals(method.getName())) {
                        return 100_000L;
                    }
                    return null;
                });

        SleepTimer.setPlayer(playerView);
        SleepTimer.setListener(() -> listenerCalled.set(true));
        SleepTimer.armEndOfVideo();
        assertTrue(SleepTimer.isEndOfVideoArmed());

        TestVideoLoaderController controller = new TestVideoLoaderController(
                playerView, PlayerConstants.PLAYBACK_MODE_ONE);

        controller.onPlayEnd();

        assertTrue("Playback should have been paused", paused.get());
        assertTrue("Listener should have been called", listenerCalled.get());
        assertFalse("Sleep timer should be disarmed", SleepTimer.isArmed());
        assertTrue("stopPlayback should position at duration", seekPositions.contains(100_000L));
        assertFalse("Playback should NOT have rewound to 0 for repeat", seekPositions.contains(0L));
    }

    private static PlayerManager createPlayerMock(AtomicBoolean paused) {
        return (PlayerManager) Proxy.newProxyInstance(
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
    }

    private static class TestVideoLoaderController extends VideoLoaderController {
        private final PlaybackView mTestPlayer;
        private final int mTestPlaybackMode;
        boolean nextLoaded = false;

        TestVideoLoaderController(PlaybackView player, int playbackMode) {
            mTestPlayer = player;
            mTestPlaybackMode = playbackMode;
        }

        @Override
        public PlaybackView getPlayer() {
            return mTestPlayer;
        }

        @Override
        protected int getPlaybackMode() {
            return mTestPlaybackMode;
        }

        @Override
        public void loadNext() {
            nextLoaded = true;
        }
    }
}
