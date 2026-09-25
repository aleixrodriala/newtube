package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

/**
 * NEWTUBE(feed-retry): one armed {@link DefaultNetworkRecoveryCallback} registration, for callers
 * outside this package (the Browse feed's error re-poll). Same contract as
 * {@code ErrorFixerController.armConnectivityRetry}, which keeps its own copy of this dance:
 * <ul>
 *     <li>STRICTLY edge-triggered. The detector is seeded from the active network's VALIDATED bit
 *     at arm time, so the registration replay of an already-healthy default network stays quiet;
 *     only a disconnected-&gt;validated transition, or a replacement default network that
 *     validates, notifies (HANDOFF section 14).</li>
 *     <li>One notification per arm. The registration is released as it fires; arm again after the
 *     retry it triggered fails.</li>
 *     <li>Registered on the APPLICATION context, never an Activity.</li>
 * </ul>
 * Main thread only: arm/disarm and the notification (posted through {@link Utils#post}) are
 * serialized there, and a cancelled registration's in-flight callback is dropped by its own token.
 */
public final class DefaultNetworkRecoveryWatcher {
    private static final String TAG = DefaultNetworkRecoveryWatcher.class.getSimpleName();

    public interface Listener {
        void onNetworkRecovered(Network network);
    }

    private final String mLogTag;
    private DefaultNetworkRecoveryCallback mCallback;
    private ConnectivityManager mConnectivityManager;

    /** @param logTag NetPath line prefix, e.g. {@code feed-retry} */
    public DefaultNetworkRecoveryWatcher(String logTag) {
        mLogTag = logTag;
    }

    public boolean isArmed() {
        return mCallback != null;
    }

    /** Idempotent: an armed registration is kept as is (its seed stays the original one). */
    public void arm(Context context, Listener listener) {
        if (mCallback != null || context == null || listener == null) {
            return;
        }

        Context appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return;
        }

        Network active;
        boolean seedDisconnected;
        try {
            active = cm.getActiveNetwork();
            NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
            seedDisconnected = caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException e) { // restricted OEM builds
            Log.e(TAG, "Failed to read the default network: %s", e.getMessage());
            return;
        }

        NetPath.log(mLogTag + " network-arm seedDisconnected=" + (seedDisconnected ? "y" : "n")
                + ' ' + NetPath.networkSnapshot(appContext));

        DefaultNetworkRecoveryCallback[] self = new DefaultNetworkRecoveryCallback[1];
        DefaultNetworkRecoveryCallback callback = new DefaultNetworkRecoveryCallback(
                active, !seedDisconnected, Utils::post, network -> {
                    // Spent: one notification per registration. Release it before notifying so
                    // the listener's own failure path can arm a fresh one.
                    if (mCallback == self[0]) {
                        disarm();
                    }
                    NetPath.log(mLogTag + " network-restored " + NetPath.networkSnapshot(appContext, network));
                    listener.onNetworkRecovered(network);
                }, () -> DefaultNetworkRecoveryCallback.isDefaultNetworkUsable(cm));
        self[0] = callback;

        // Publish before callbacks can arrive (see ErrorFixerController.armConnectivityRetry).
        mCallback = callback;
        mConnectivityManager = cm;
        try {
            cm.registerDefaultNetworkCallback(callback);
        } catch (RuntimeException e) { // e.g. TOO_MANY_REQUESTS or a restricted OEM build
            callback.cancel();
            Utils.removeCallbacks(callback);
            mCallback = null;
            mConnectivityManager = null;
            Log.e(TAG, "Failed to register %s network callback: %s", mLogTag, e.getMessage());
        }
    }

    public void disarm() {
        DefaultNetworkRecoveryCallback callback = mCallback;
        ConnectivityManager cm = mConnectivityManager;
        mCallback = null;
        mConnectivityManager = null;
        if (callback == null) {
            return;
        }

        // Cancel first: a callback already in flight can still post, but its token is dead.
        callback.cancel();
        Utils.removeCallbacks(callback);
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (RuntimeException e) { // never registered / already unregistered
                Log.e(TAG, "Failed to unregister %s network callback: %s", mLogTag, e.getMessage());
            }
        }
    }
}
