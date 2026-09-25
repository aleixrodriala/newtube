package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.net.Network;
import android.net.NetworkCapabilities;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetwork;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DefaultNetworkRecoveryCallbackTest {
    private final Network mWifi = ShadowNetwork.newInstance(101);
    private final Network mCellular = ShadowNetwork.newInstance(102);
    private final Queue<Runnable> mMainQueue = new ArrayDeque<>();
    private final List<Network> mRecoveries = new ArrayList<>();

    @Test
    public void handoverWithoutLostWaitsForReplacementValidation() {
        DefaultNetworkRecoveryCallback callback = callback(mWifi, true);
        callback.onAvailable(mWifi);
        callback.onCapabilitiesChanged(mWifi, capabilities(true));

        // registerDefaultNetworkCallback can hand over without ever sending onLost(wifi).
        callback.onAvailable(mCellular);
        callback.onCapabilitiesChanged(mCellular, capabilities(false));
        assertTrue(mMainQueue.isEmpty());
        callback.onCapabilitiesChanged(mCellular, capabilities(true));

        assertEquals(1, mMainQueue.size());
        drainMainQueue();
        assertEquals(List.of(mCellular), mRecoveries);
    }

    @Test
    public void alreadyValidatedReplacementRecoversWithoutAnyDisconnectedCallback() {
        DefaultNetworkRecoveryCallback callback = callback(mWifi, true);
        callback.onAvailable(mCellular);
        callback.onCapabilitiesChanged(mCellular, capabilities(true));
        drainMainQueue();
        assertEquals(List.of(mCellular), mRecoveries);
    }

    @Test
    public void healthyRegistrationReplayUsesNetworkEqualityAndNeverRetries() {
        DefaultNetworkRecoveryCallback callback = callback(mWifi, true);
        Network replayedWifi = ShadowNetwork.newInstance(101);
        callback.onAvailable(replayedWifi);
        callback.onCapabilitiesChanged(replayedWifi, capabilities(true));
        callback.onAvailable(replayedWifi);
        callback.onCapabilitiesChanged(replayedWifi, capabilities(true));
        assertTrue(mMainQueue.isEmpty());
        assertTrue(mRecoveries.isEmpty());
    }

    @Test
    public void duplicateValidationsAndFlappingPostOnlyOneRetryPerRegistration() {
        DefaultNetworkRecoveryCallback callback = callback(mWifi, true);
        callback.onAvailable(mCellular);
        callback.onCapabilitiesChanged(mCellular, capabilities(true));
        callback.onCapabilitiesChanged(mCellular, capabilities(true));
        callback.onCapabilitiesChanged(mCellular, capabilities(false));
        callback.onCapabilitiesChanged(mCellular, capabilities(true));
        assertEquals(1, mMainQueue.size());

        drainMainQueue();
        callback.onAvailable(mWifi);
        callback.onCapabilitiesChanged(mWifi, capabilities(true));
        callback.run(); // even a duplicate executor delivery cannot retry twice
        assertEquals(List.of(mCellular), mRecoveries);
        assertTrue(mMainQueue.isEmpty());
    }

    @Test
    public void lostNetworkCanReturnWithTheSameIdentity() {
        DefaultNetworkRecoveryCallback callback = callback(mWifi, true);
        callback.onLost(mWifi);
        callback.onAvailable(mWifi);
        callback.onCapabilitiesChanged(mWifi, capabilities(true));
        drainMainQueue();
        assertEquals(List.of(mWifi), mRecoveries);
    }

    @Test
    public void initiallyDisconnectedRegistrationRecovers() {
        DefaultNetworkRecoveryCallback callback = callback(null, false);
        callback.onAvailable(mWifi);
        callback.onCapabilitiesChanged(mWifi, capabilities(true));
        drainMainQueue();
        assertEquals(List.of(mWifi), mRecoveries);
    }

    @Test
    public void initiallyUnvalidatedNetworkRecoversWithoutChangingIdentity() {
        DefaultNetworkRecoveryCallback callback = callback(mWifi, false);
        callback.onAvailable(mWifi);
        callback.onCapabilitiesChanged(mWifi, capabilities(true));
        drainMainQueue();
        assertEquals(List.of(mWifi), mRecoveries);
    }

    @Test
    public void sameNetworkValidationLossAndRestoreRecovers() {
        DefaultNetworkRecoveryCallback callback = callback(mWifi, true);
        callback.onCapabilitiesChanged(mWifi, capabilities(false));
        callback.onCapabilitiesChanged(mWifi, capabilities(true));
        drainMainQueue();
        assertEquals(List.of(mWifi), mRecoveries);
    }

    @Test
    public void oldNetworksCallbacksCannotValidateTheReplacement() {
        DefaultNetworkRecoveryCallback callback = callback(mWifi, true);
        callback.onAvailable(mCellular);
        callback.onLost(mWifi);
        callback.onCapabilitiesChanged(mWifi, capabilities(true));
        assertTrue(mMainQueue.isEmpty());
        callback.onCapabilitiesChanged(mCellular, capabilities(true));
        drainMainQueue();
        assertEquals(List.of(mCellular), mRecoveries);
    }

    @Test
    public void cancelledQueuedRetryCannotFireIntoANewEpisode() {
        DefaultNetworkRecoveryCallback oldCallback = callback(mWifi, true);
        oldCallback.onAvailable(mCellular);
        oldCallback.onCapabilitiesChanged(mCellular, capabilities(true));
        oldCallback.cancel();

        DefaultNetworkRecoveryCallback nextCallback = callback(mCellular, true);
        nextCallback.onAvailable(mCellular);
        nextCallback.onCapabilitiesChanged(mCellular, capabilities(true));
        drainMainQueue();
        assertTrue(mRecoveries.isEmpty());

        nextCallback.onAvailable(mWifi);
        nextCallback.onCapabilitiesChanged(mWifi, capabilities(true));
        drainMainQueue();
        assertEquals(List.of(mWifi), mRecoveries);
    }

    @Test
    public void cancellationDuringPostRejectsTheRetryAtDelivery() {
        DefaultNetworkRecoveryCallback[] registration = new DefaultNetworkRecoveryCallback[1];
        registration[0] = new DefaultNetworkRecoveryCallback(mWifi, true, retry -> {
            // Interleave main-thread disarm/removeCallbacks with an in-flight callback that
            // has decided to post but has not enqueued yet. Queue removal alone misses this.
            registration[0].cancel();
            mMainQueue.add(retry);
        }, mRecoveries::add);
        registration[0].onAvailable(mCellular);
        registration[0].onCapabilitiesChanged(mCellular, capabilities(true));
        assertEquals(1, mMainQueue.size());
        drainMainQueue();
        assertTrue(mRecoveries.isEmpty());
    }

    @Test
    public void callbacksAfterCancellationCannotQueueARetry() {
        DefaultNetworkRecoveryCallback callback = callback(mWifi, true);
        callback.cancel();
        callback.onLost(mWifi);
        callback.onAvailable(mCellular);
        callback.onCapabilitiesChanged(mCellular, capabilities(true));
        assertTrue(mMainQueue.isEmpty());
    }

    @Test
    public void blockedReplayOfAValidatedNetworkWaitsForUnblock() {
        // Pixel 2026-09-25: the app's network blocked (OEM_DENY chain). getActiveNetwork() is null,
        // so the controller arms "disconnected", yet the replay hands over the validated default
        // network - its blocked status only arrives after the capabilities.
        boolean[] usable = {false};
        DefaultNetworkRecoveryCallback callback = new DefaultNetworkRecoveryCallback(
                null, false, mMainQueue::add, mRecoveries::add, () -> usable[0]);
        callback.onAvailable(mCellular);
        callback.onCapabilitiesChanged(mCellular, capabilities(true));
        callback.onBlockedStatusChanged(mCellular, true);
        drainMainQueue();
        assertTrue("a blocked network is not a restored one", mRecoveries.isEmpty());

        // Bandwidth-estimate updates keep re-sending validated caps while blocked.
        callback.onCapabilitiesChanged(mCellular, capabilities(true));
        callback.onCapabilitiesChanged(mCellular, capabilities(true));
        assertTrue(mMainQueue.isEmpty());

        usable[0] = true;
        callback.onBlockedStatusChanged(mCellular, false);
        drainMainQueue();
        assertEquals(List.of(mCellular), mRecoveries);
    }

    @Test
    public void unusableAtDeliveryStaysArmedForTheNextEdge() {
        // API < 29 never reports blocked status: the delivery check alone must hold the retry.
        boolean[] usable = {false};
        DefaultNetworkRecoveryCallback callback = new DefaultNetworkRecoveryCallback(
                null, false, mMainQueue::add, mRecoveries::add, () -> usable[0]);
        callback.onAvailable(mWifi);
        callback.onCapabilitiesChanged(mWifi, capabilities(true));
        drainMainQueue();
        assertTrue(mRecoveries.isEmpty());

        usable[0] = true;
        callback.onCapabilitiesChanged(mWifi, capabilities(true));
        drainMainQueue();
        assertEquals(List.of(mWifi), mRecoveries);
    }

    @Test
    public void rejectedDeliveryRechecksUntilTheAppCanUseTheNetwork() {
        // API 24-28: no blocked-status callback, and the restriction lifts without any event.
        boolean[] usable = {false};
        List<Runnable> delayed = new ArrayList<>();
        DefaultNetworkRecoveryCallback callback = new DefaultNetworkRecoveryCallback(
                null, false, mMainQueue::add, mRecoveries::add, () -> usable[0],
                (task, delayMs) -> delayed.add(task));
        callback.onAvailable(mCellular);
        callback.onCapabilitiesChanged(mCellular, capabilities(true));
        drainMainQueue();
        assertEquals(1, delayed.size());

        delayed.remove(0).run(); // still blocked: one more re-check, nothing fired
        assertTrue(mRecoveries.isEmpty());
        assertEquals(1, delayed.size());

        usable[0] = true;
        delayed.remove(0).run();
        assertEquals(List.of(mCellular), mRecoveries);
        assertTrue("fired once: no further re-checks", delayed.isEmpty());
    }

    @Test
    public void pollSlowsDownButNeverStopsWhileArmedAndStopsOnCancel() {
        List<Runnable> delayed = new ArrayList<>();
        List<Long> delays = new ArrayList<>();
        DefaultNetworkRecoveryCallback callback = new DefaultNetworkRecoveryCallback(
                null, false, mMainQueue::add, mRecoveries::add, () -> false,
                (task, delayMs) -> {
                    delayed.clear(); // the real poster replaces a pending identical runnable
                    delayed.add(task);
                    delays.add(delayMs);
                });
        callback.onAvailable(mWifi);
        callback.onCapabilitiesChanged(mWifi, capabilities(true));
        drainMainQueue();

        // API 24-28 re-send validated caps on every bandwidth update: those rejected edge
        // deliveries must not use up the fast phase.
        for (int i = 0; i < 200; i++) {
            callback.run();
        }
        assertEquals(Long.valueOf(DefaultNetworkRecoveryCallback.RECHECK_MS), delays.get(delays.size() - 1));

        for (int i = 0; i < 300; i++) {
            delayed.remove(0).run();
        }
        assertEquals("still armed and polling", 1, delayed.size());
        assertEquals(Long.valueOf(DefaultNetworkRecoveryCallback.SLOW_RECHECK_MS), delays.get(delays.size() - 1));
        assertTrue(mRecoveries.isEmpty());

        callback.cancel();
        delayed.remove(0).run();
        assertTrue("a cancelled registration's last poll does not reschedule", delayed.isEmpty());
    }

    @Test
    public void blockingTheHealthyNetworkThenUnblockingRecovers() {
        DefaultNetworkRecoveryCallback callback = callback(mWifi, true);
        callback.onBlockedStatusChanged(mWifi, true);
        callback.onCapabilitiesChanged(mWifi, capabilities(true));
        assertTrue(mMainQueue.isEmpty());
        callback.onBlockedStatusChanged(mWifi, false);
        drainMainQueue();
        assertEquals(List.of(mWifi), mRecoveries);
    }

    @Test
    public void healthyUnblockedReplayStaysQuiet() {
        DefaultNetworkRecoveryCallback callback = callback(mWifi, true);
        callback.onAvailable(mWifi);
        callback.onCapabilitiesChanged(mWifi, capabilities(true));
        callback.onBlockedStatusChanged(mWifi, false);
        assertTrue(mMainQueue.isEmpty());
    }

    private DefaultNetworkRecoveryCallback callback(Network initial, boolean validated) {
        return new DefaultNetworkRecoveryCallback(initial, validated, mMainQueue::add, mRecoveries::add);
    }

    private static NetworkCapabilities capabilities(boolean validated) {
        NetworkCapabilities capabilities = new NetworkCapabilities();
        if (validated) {
            shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }
        return capabilities;
    }

    private void drainMainQueue() {
        while (!mMainQueue.isEmpty()) {
            mMainQueue.remove().run();
        }
    }
}
