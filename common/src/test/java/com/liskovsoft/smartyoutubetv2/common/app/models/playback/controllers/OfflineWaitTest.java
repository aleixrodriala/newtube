package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;

import org.junit.Before;
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

/**
 * NEWTUBE(offline-wait): Pixel 9, Wi-Fi and data both off, parked session -> Next. The open's
 * /player fetch failed in ~10 ms with no traffic ("fromNullable result is null", net=none), yet it
 * reloaded 4 times 1 s apart, hit the cap, and every timer step (5 s, 15 s) reset the count and ran
 * another 4-reload burst. With no network the player must wait for one and retry on its return.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class OfflineWaitTest {
    private final Video video = new Video();
    private boolean validatedNetwork;
    private int reloads;
    private LivenessProbe probe;
    private final ErrorFixerController controller = new ErrorFixerController() {
        @Override public Context getContext() { return RuntimeEnvironment.getApplication(); }
        @Override public PlaybackView getPlayer() { return player(); }
        @Override public Video getVideo() { return video; }
        @Override boolean hasValidatedNetwork() { return validatedNetwork; }
    };

    @Before
    public void setUp() {
        video.videoId = "lan0cI27nv4";
        ReflectionHelpers.setField(controller, "mVideoLoaderController", new VideoLoaderController() {
            @Override public void reloadVideo() { reloads++; }
            @Override public void reloadVideoAfterUrlRemint() { reloads++; }
        });
        // A liveness probe that never answers and never schedules: no real network in a unit test.
        probe = new LivenessProbe(() -> false, (task, delayMs) -> { }, Runnable::run, () -> 0L, () -> { });
        ReflectionHelpers.setField(controller, "mLivenessProbe", probe);
    }

    @Test
    public void onlyAnAbsentNetworkWaits() {
        assertTrue(ErrorFixerController.shouldWaitForNetwork(false, false));
        assertFalse("a validated (maybe silently dead) network keeps the old recovery",
                ErrorFixerController.shouldWaitForNetwork(true, false));
        assertFalse("a downloaded video never needs the network",
                ErrorFixerController.shouldWaitForNetwork(false, true));
    }

    @Test
    public void onlyTheTimerDefersAndOnlyWithTheNetworkCallbackArmed() {
        assertTrue(ErrorFixerController.defersTimerRetry("timer", true, false, false));
        assertFalse("the network edge is the retry",
                ErrorFixerController.defersTimerRetry("network", true, false, false));
        assertFalse("an answering probe is evidence of a link",
                ErrorFixerController.defersTimerRetry("probe", true, false, false));
        assertFalse("callback registration failed: the timer is the only way back",
                ErrorFixerController.defersTimerRetry("timer", false, false, false));
        assertFalse(ErrorFixerController.defersTimerRetry("timer", true, true, false));
    }

    @Test
    public void offlineOpenWaitsForTheNetworkInsteadOfBurstingReloads() {
        validatedNetwork = false;

        controller.runFormatErrorAction(new IllegalStateException("fromNullable result is null"));

        assertEquals("no reload burst with no network", 0, reloads);
        assertTrue((boolean) ReflectionHelpers.getField(controller, "mErrorCapped"));
        assertEquals(0, (int) ReflectionHelpers.getField(controller, "mConsecutiveAutoFixCount"));
        assertNotNull("armed to retry on the next validated network",
                ReflectionHelpers.getField(controller, "mNetworkCallback"));

        // The whole timer ladder elapses offline: every step defers, nothing reloads, no budget spent.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10));
        assertEquals(0, reloads);
        assertEquals(0, (int) ReflectionHelpers.getField(controller, "mAutoRetryAttempt"));
        assertTrue((boolean) ReflectionHelpers.getField(controller, "mErrorCapped"));
    }

    @Test
    public void offlineMediaLoadErrorAlsoWaits() {
        validatedNetwork = false;

        controller.onEngineError(PlayerEventListener.ERROR_TYPE_SOURCE, -1,
                new java.net.UnknownHostException("rr7---sn.googlevideo.com"));

        assertEquals(0, reloads);
        assertTrue((boolean) ReflectionHelpers.getField(controller, "mErrorCapped"));
    }

    /**
     * Review 3: only failures that look like the missing network wait. Known local causes keep their
     * own recovery (parser/token fixes, the OOM buffer step-down) even while offline.
     */
    @Test
    public void onlyTransportFailuresAndTheNullResultOutageWait() {
        assertTrue(ErrorFixerController.isOfflineWaitCandidate(
                new IllegalStateException("fromNullable result is null")));
        assertTrue(ErrorFixerController.isOfflineWaitCandidate(
                new java.net.UnknownHostException("www.youtube.com")));
        assertTrue(ErrorFixerController.isOfflineWaitCandidate(new java.io.IOException("source",
                new java.net.ConnectException("Failed to connect"))));
        assertTrue(ErrorFixerController.isOfflineWaitCandidate(
                new java.io.IOException("Exception in CronetUrlRequest: net::ERR_INTERNET_DISCONNECTED")));

        assertFalse(ErrorFixerController.isOfflineWaitCandidate(
                new IllegalStateException("Unexpected token '<'")));
        assertFalse(ErrorFixerController.isOfflineWaitCandidate(
                new RuntimeException("ReferenceError: sig is not defined")));
        assertFalse(ErrorFixerController.isOfflineWaitCandidate(new PoTokenException()));
        assertFalse(ErrorFixerController.isOfflineWaitCandidate(
                new RuntimeException("load", new OutOfMemoryError())));
        assertFalse("a server verdict is not the missing network",
                ErrorFixerController.isOfflineWaitCandidate(new java.io.IOException("Response code: 403")));
        assertFalse(ErrorFixerController.isOfflineWaitCandidate(null));
    }

    @Test
    public void aServerVerdictWhileOfflineKeepsItsNormalRecovery() {
        validatedNetwork = false;

        controller.runFormatErrorAction(new java.io.IOException("Response code: 403"));

        assertEquals(1, reloads);
        assertFalse((boolean) ReflectionHelpers.getField(controller, "mErrorCapped"));
    }

    /**
     * Review 2: a network that validates between the offline check and the callback registration
     * must count as the recovery edge; an episode first seen ONLINE stays edge-only.
     */
    @Test
    public void anOfflineEpisodeSeedsTheCallbackDisconnected() {
        assertTrue("validated in between: its replay is the edge",
                ErrorFixerController.seedsDisconnected(/* observedOffline= */ true, /* validatedNow= */ true));
        assertTrue(ErrorFixerController.seedsDisconnected(true, false));
        assertFalse("seen online: its own replay must not fire",
                ErrorFixerController.seedsDisconnected(false, true));
        assertTrue(ErrorFixerController.seedsDisconnected(false, false));
    }

    /**
     * Review 1: on a network that reaches YouTube but never gets VALIDATED no network edge ever comes,
     * so the probe's FIRST answer has to count for an episode that began offline - and only then.
     */
    @Test
    public void anOfflineEpisodeLetsTheFirstProbeAnswerRetry() {
        validatedNetwork = false;
        controller.runFormatErrorAction(new IllegalStateException("fromNullable result is null"));
        assertTrue("offline episode: no failed probe needed",
                (boolean) ReflectionHelpers.getField(probe, "mSawFailure"));
    }

    @Test
    public void anOnlineCappedEpisodeStillWantsAFailedProbeFirst() {
        validatedNetwork = true;
        for (int i = 0; i < 4; i++) {
            controller.runFormatErrorAction(new IllegalStateException("fromNullable result is null"));
        }
        assertTrue("4th consecutive failure caps", (boolean) ReflectionHelpers.getField(controller, "mErrorCapped"));
        assertEquals("the probe was started for this episode",
                1, (int) ReflectionHelpers.getField(probe, "mGeneration"));
        assertFalse((boolean) ReflectionHelpers.getField(probe, "mSawFailure"));
    }

    /** With a network, the first failure still gets the normal quick reload (unchanged path). */
    @Test
    public void onlineFailureKeepsTheQuickReload() {
        validatedNetwork = true;

        controller.runFormatErrorAction(new IllegalStateException("fromNullable result is null"));

        assertEquals(1, reloads);
        assertFalse((boolean) ReflectionHelpers.getField(controller, "mErrorCapped"));
        assertEquals(1, (int) ReflectionHelpers.getField(controller, "mConsecutiveAutoFixCount"));
    }

    /** Stands in for youtubeapi's PoTokenException: the recovery matches it by simple class name. */
    private static final class PoTokenException extends RuntimeException {
    }

    private PlaybackView player() {
        return (PlaybackView) Proxy.newProxyInstance(PlaybackView.class.getClassLoader(),
                new Class<?>[] {PlaybackView.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getPositionMs": return 0L;
                        case "getDurationMs": return 600_000L;
                        default:
                            Class<?> type = method.getReturnType();
                            if (type == boolean.class) return false;
                            if (type == int.class) return 0;
                            if (type == long.class) return 0L;
                            if (type == float.class) return 0f;
                            return null;
                    }
                });
    }
}
