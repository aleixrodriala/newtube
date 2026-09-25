package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One cancellable recovery notification per default-network callback registration.
 *
 * NEWTUBE(blocked-uid): a network can be validated for the device and still refuse THIS app - the
 * app's mobile data switched off, background data restricted, an always-on VPN in lockdown while it
 * reconnects. {@code getActiveNetwork()} then answers null (the controllers arm with
 * seedDisconnected=y), but the registration replay still hands us the validated default network -
 * and before its {@link #onBlockedStatusChanged} does. Firing on that replay was an unbounded loop
 * on the Pixel (2026-09-25): cap -> arm -> "restored" 10 ms later -> 4 reloads walking 4 /player
 * clients each -> cap, every ~4 s for as long as the app stayed blocked. So a network only counts as
 * restored when it is validated AND not blocked for us, and the delivery re-checks that the app can
 * really use its default network before spending the retry; a rejected delivery waits for the next
 * edge (typically {@code onBlockedStatusChanged(false)}) and, because API 24-28 never report blocked
 * status and a restriction can lift without any callback at all, also re-checks on its own poll
 * ({@link #RECHECK_MS} for {@link #FAST_RECHECKS} ticks, then {@link #SLOW_RECHECK_MS}) for as long
 * as the registration stays armed, so it can never sit armed without a way to fire.
 */
final class DefaultNetworkRecoveryCallback extends ConnectivityManager.NetworkCallback implements Runnable {
    static final long RECHECK_MS = 5_000;
    /** The first 2 minutes poll fast (a typical restriction toggle); after that a cheap slow poll. */
    static final int FAST_RECHECKS = 24;
    static final long SLOW_RECHECK_MS = 30_000;

    private final Executor mMainExecutor;
    private final BiConsumer<Runnable, Long> mDelayedPoster;
    private final Consumer<Network> mOnRecovered;
    private final BooleanSupplier mUsableAtDelivery;
    // Callbacks arrive on the ConnectivityManager thread, delivery on the main thread: every field
    // below is guarded by this object's monitor.
    private Network mDefaultNetwork;
    private boolean mNeedsRecovery;
    private boolean mValidated;
    private boolean mBlocked;
    private boolean mRetryPosted;
    private Network mRecoveredNetwork;
    // Only the poll's own ticks count toward the fast phase: rejected edge deliveries (API 24-28
    // re-send validated caps on every bandwidth update) must not use it up.
    private final Runnable mPoll = () -> deliver(true);
    private int mPollTicks;
    private boolean mRejectionLogged;
    private volatile boolean mActive = true;

    DefaultNetworkRecoveryCallback(Network initialNetwork, boolean initialValidated,
            Executor mainExecutor, Consumer<Network> onRecovered) {
        this(initialNetwork, initialValidated, mainExecutor, onRecovered, () -> true, null);
    }

    DefaultNetworkRecoveryCallback(Network initialNetwork, boolean initialValidated,
            Executor mainExecutor, Consumer<Network> onRecovered, BooleanSupplier usableAtDelivery) {
        this(initialNetwork, initialValidated, mainExecutor, onRecovered, usableAtDelivery,
                Utils::postDelayed);
    }

    /**
     * @param usableAtDelivery asked on the main thread right before notifying: can the app use its
     *                         default network right now? See {@link #isDefaultNetworkUsable}.
     * @param delayedPoster    schedules the bounded re-check of a rejected delivery on the main
     *                         thread, replacing a pending one ({@link Utils#postDelayed}); the owner's
     *                         removeCallbacks(this) cancels it. Null disables re-checks.
     */
    DefaultNetworkRecoveryCallback(Network initialNetwork, boolean initialValidated,
            Executor mainExecutor, Consumer<Network> onRecovered, BooleanSupplier usableAtDelivery,
            BiConsumer<Runnable, Long> delayedPoster) {
        mDefaultNetwork = initialNetwork;
        mNeedsRecovery = !initialValidated;
        mValidated = initialValidated;
        mMainExecutor = mainExecutor;
        mOnRecovered = onRecovered;
        mUsableAtDelivery = usableAtDelivery;
        mDelayedPoster = delayedPoster;
    }

    /**
     * The app's own view of its default network: {@code getActiveNetwork()} is null while the
     * default network is blocked for this UID, so a validated answer here is one the app can use.
     */
    static boolean isDefaultNetworkUsable(ConnectivityManager cm) {
        if (cm == null) {
            return true;
        }
        try {
            Network active = cm.getActiveNetwork();
            NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException e) { // restricted OEM builds: don't strand the retry
            return true;
        }
    }

    @Override
    public synchronized void onAvailable(Network network) {
        if (!mActive) {
            return;
        }
        // A default-network handover need not deliver onLost(old): Android stops tracking the
        // old default after onAvailable(new). Its replacement must still validate before retrying.
        if (!Objects.equals(mDefaultNetwork, network)) {
            mNeedsRecovery = true;
            mValidated = false;
            mBlocked = false;
        }
        mDefaultNetwork = network;
    }

    @Override
    public synchronized void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
        if (!mActive || !Objects.equals(mDefaultNetwork, network)) {
            return;
        }
        mValidated = capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        if (!mValidated) {
            mNeedsRecovery = true;
        } else {
            maybePostRecovery(network);
        }
    }

    @Override
    public synchronized void onBlockedStatusChanged(Network network, boolean blocked) {
        if (!mActive || !Objects.equals(mDefaultNetwork, network)) {
            return;
        }
        mBlocked = blocked;
        if (blocked) {
            mNeedsRecovery = true;
        } else {
            maybePostRecovery(network);
        }
    }

    @Override
    public synchronized void onLost(Network network) {
        if (mActive && Objects.equals(mDefaultNetwork, network)) {
            mDefaultNetwork = null;
            mNeedsRecovery = true;
            mValidated = false;
            mBlocked = false;
        }
    }

    private void maybePostRecovery(Network network) {
        if (mNeedsRecovery && mValidated && !mBlocked && !mRetryPosted) {
            mRetryPosted = true;
            mRecoveredNetwork = network;
            mMainExecutor.execute(this);
        }
    }

    /** Called on the main thread before unregistering, including when registration fails. */
    void cancel() {
        mActive = false;
    }

    /** Runs on the main thread, serialized with cancel and the controller's other retry triggers. */
    @Override
    public void run() {
        deliver(false);
    }

    private void deliver(boolean fromPoll) {
        Network recovered;
        synchronized (this) {
            // unregister/removeCallbacks cannot stop an already-dispatched network callback from
            // posting afterward. This registration's token must also be checked at delivery time.
            // A cancelled registration's pending poll runs once more and stops here.
            if (!mActive) {
                return;
            }
            // The replay's validated caps arrive before its blocked status, and API < 29 never
            // reports blocked status at all: only an app that can use its default network NOW
            // spends the retry. Otherwise stay armed for the next edge, and keep polling.
            if (!mUsableAtDelivery.getAsBoolean()) {
                mRetryPosted = false;
                mNeedsRecovery = true;
                if (fromPoll) {
                    mPollTicks++;
                }
                if (mDelayedPoster != null) {
                    // Re-posting the same poll runnable replaces a pending one, so polls never stack.
                    mDelayedPoster.accept(mPoll, mPollTicks < FAST_RECHECKS ? RECHECK_MS : SLOW_RECHECK_MS);
                }
                if (!mRejectionLogged) {
                    mRejectionLogged = true;
                    NetPath.log("network-restored rejected reason=unusable-for-app poll="
                            + (mDelayedPoster != null ? "y" : "n"));
                }
                return;
            }
            mActive = false;
            recovered = mRecoveredNetwork;
        }
        mOnRecovered.accept(recovered);
    }
}
