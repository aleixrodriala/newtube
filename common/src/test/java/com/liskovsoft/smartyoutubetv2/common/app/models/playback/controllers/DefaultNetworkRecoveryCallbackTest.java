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
