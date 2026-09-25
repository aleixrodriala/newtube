package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;

import java.util.ArrayList;
import java.util.List;

/** The registration lifecycle around {@link DefaultNetworkRecoveryCallback} used by the feed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
public class DefaultNetworkRecoveryWatcherTest {
    private final List<Network> mRecoveries = new ArrayList<>();
    private final DefaultNetworkRecoveryWatcher mWatcher = new DefaultNetworkRecoveryWatcher("test-retry");
    private Context mContext;
    private ShadowConnectivityManager mShadow;
    private Network mActive;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
        mShadow = shadowOf(cm);
        mActive = cm.getActiveNetwork();
        assertNotNull("Robolectric should expose a default network", mActive);
        mShadow.setNetworkCapabilities(mActive, capabilities(true));
    }

    @Test
    public void healthyReplayAtRegistrationStaysQuiet() {
        mWatcher.arm(mContext, mRecoveries::add);
        NetworkCallback callback = registered();
        callback.onAvailable(mActive);
        callback.onCapabilitiesChanged(mActive, capabilities(true));
        idleMain();

        assertTrue(mRecoveries.isEmpty());
        assertTrue(mWatcher.isArmed());
        mWatcher.disarm();
        assertTrue(mShadow.getNetworkCallbacks().isEmpty());
        assertFalse(mWatcher.isArmed());
    }

    @Test
    public void armIsIdempotent() {
        mWatcher.arm(mContext, mRecoveries::add);
        mWatcher.arm(mContext, mRecoveries::add);
        assertEquals(1, mShadow.getNetworkCallbacks().size());
    }

    @Test
    public void validationEdgeNotifiesOnceAndReleasesTheRegistration() {
        mWatcher.arm(mContext, mRecoveries::add);
        NetworkCallback callback = registered();
        callback.onCapabilitiesChanged(mActive, capabilities(false));
        callback.onCapabilitiesChanged(mActive, capabilities(true));
        callback.onCapabilitiesChanged(mActive, capabilities(true));
        idleMain();

        assertEquals(List.of(mActive), mRecoveries);
        assertFalse(mWatcher.isArmed());
        assertTrue(mShadow.getNetworkCallbacks().isEmpty());

        // The retry it triggered failed: arming again is a fresh registration.
        mWatcher.arm(mContext, mRecoveries::add);
        assertEquals(1, mShadow.getNetworkCallbacks().size());
    }

    @Test
    public void armedWhileOfflineNotifiesOnTheFirstValidation() {
        mShadow.setNetworkCapabilities(mActive, capabilities(false));
        mWatcher.arm(mContext, mRecoveries::add);
        NetworkCallback callback = registered();
        callback.onAvailable(mActive);
        callback.onCapabilitiesChanged(mActive, capabilities(true));
        idleMain();

        assertEquals(List.of(mActive), mRecoveries);
    }

    @Test
    public void disarmDropsANotificationAlreadyQueued() {
        mWatcher.arm(mContext, mRecoveries::add);
        NetworkCallback callback = registered();
        callback.onCapabilitiesChanged(mActive, capabilities(false));
        callback.onCapabilitiesChanged(mActive, capabilities(true)); // posted, not yet run
        mWatcher.disarm(); // e.g. the feed view paused in between
        idleMain();

        assertTrue(mRecoveries.isEmpty());
    }

    private NetworkCallback registered() {
        assertEquals(1, mShadow.getNetworkCallbacks().size());
        return mShadow.getNetworkCallbacks().iterator().next();
    }

    private static NetworkCapabilities capabilities(boolean validated) {
        NetworkCapabilities capabilities = new NetworkCapabilities();
        if (validated) {
            shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }
        return capabilities;
    }

    private static void idleMain() {
        shadowOf(Looper.getMainLooper()).idle();
    }
}
