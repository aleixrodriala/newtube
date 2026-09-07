package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** One cancellable recovery notification per default-network callback registration. */
final class DefaultNetworkRecoveryCallback extends ConnectivityManager.NetworkCallback implements Runnable {
    private final Executor mMainExecutor;
    private final Consumer<Network> mOnRecovered;
    // Network callbacks are serialized by ConnectivityManager; only cancellation crosses threads.
    private Network mDefaultNetwork;
    private boolean mNeedsRecovery;
    private boolean mRetryPosted;
    private Network mRecoveredNetwork;
    private volatile boolean mActive = true;

    DefaultNetworkRecoveryCallback(Network initialNetwork, boolean initialValidated,
            Executor mainExecutor, Consumer<Network> onRecovered) {
        mDefaultNetwork = initialNetwork;
        mNeedsRecovery = !initialValidated;
        mMainExecutor = mainExecutor;
        mOnRecovered = onRecovered;
    }

    @Override
    public void onAvailable(Network network) {
        if (!mActive) {
            return;
        }
        // A default-network handover need not deliver onLost(old): Android stops tracking the
        // old default after onAvailable(new). Its replacement must still validate before retrying.
        if (!Objects.equals(mDefaultNetwork, network)) {
            mNeedsRecovery = true;
        }
        mDefaultNetwork = network;
    }

    @Override
    public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
        if (!mActive || !Objects.equals(mDefaultNetwork, network)) {
            return;
        }
        if (capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            mNeedsRecovery = true;
        } else if (mNeedsRecovery && !mRetryPosted) {
            mRetryPosted = true;
            mRecoveredNetwork = network;
            mMainExecutor.execute(this);
        }
    }

    @Override
    public void onLost(Network network) {
        if (mActive && Objects.equals(mDefaultNetwork, network)) {
            mDefaultNetwork = null;
            mNeedsRecovery = true;
        }
    }

    /** Called on the main thread before unregistering, including when registration fails. */
    void cancel() {
        mActive = false;
    }

    /** Runs on the main thread, serialized with cancel and the controller's other retry triggers. */
    @Override
    public void run() {
        // unregister/removeCallbacks cannot stop an already-dispatched network callback from
        // posting afterward. This registration's token must also be checked at delivery time.
        if (mActive) {
            mActive = false;
            mOnRecovered.accept(mRecoveredNetwork);
        }
    }
}
