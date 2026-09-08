package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.os.Looper;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.TerminalSourceException;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.util.ReflectionHelpers;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TerminalSourceErrorTest {
    @Test public void terminalSourceStopsAtUserNoticeWithoutTimersNetworkRecoveryOrPreferenceWrites() {
        List<String> calls = new ArrayList<>();
        PlaybackView player = (PlaybackView) Proxy.newProxyInstance(PlaybackView.class.getClassLoader(),
                new Class<?>[]{PlaybackView.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setTitle": case "showPlaybackNotice":
                        case "showProgressBar": case "showOverlay":
                            calls.add(method.getName()); return null;
                        default: throw new AssertionError("Unexpected recovery call: " + method.getName());
                    }
                });
        ErrorFixerController controller = new ErrorFixerController() {
            @Override public Context getContext() { return RuntimeEnvironment.getApplication(); }
            @Override public PlaybackView getPlayer() { return player; }
        };
        // Robolectric has no validated network: a plain IOException would schedule recovery.
        controller.onEngineError(0, -1, new TerminalSourceException("Experimental source stopped"));
        assertTrue((boolean) ReflectionHelpers.getField(controller, "mErrorCapped"));
        assertEquals(0, (int) ReflectionHelpers.getField(controller, "mAutoRetryAttempt"));
        assertNull(ReflectionHelpers.getField(controller, "mNetworkCallback"));
        assertTrue((boolean) ReflectionHelpers.getField(controller, "mTerminalSourceCapped"));
        ReflectionHelpers.callInstanceMethod(controller, "requestAutoRetry",
                ReflectionHelpers.ClassParameter.from(String.class, "stale-network-callback"),
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        controller.onLongBuffering();
        assertEquals(List.of("setTitle", "showPlaybackNotice", "showProgressBar", "showOverlay"), calls);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5));
        assertEquals(4, calls.size());
    }

    @Test public void activeStatefulSourceCannotEnterTheGenericLongBufferingRescue() {
        PlaybackView player = (PlaybackView) Proxy.newProxyInstance(PlaybackView.class.getClassLoader(),
                new Class<?>[]{PlaybackView.class}, (proxy, method, args) -> {
                    if (method.getName().equals("allowsAutomaticSourceRecovery")) return false;
                    throw new AssertionError("Unexpected recovery: " + method.getName());
                });
        new ErrorFixerController() {
            @Override public PlaybackView getPlayer() { return player; }
        }.onLongBuffering();
    }
}
