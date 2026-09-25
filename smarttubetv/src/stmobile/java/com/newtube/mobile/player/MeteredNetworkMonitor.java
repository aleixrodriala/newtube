package com.newtube.mobile.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

/**
 * NEWTUBE(metered): process-wide "should the player save data" gate for its discretionary byte
 * policies - the forward-buffer ceiling ({@link MeteredBufferLoadControl}) and the inline-box rung
 * cap ({@link VideoViewportCap}). The load control asks on every loading decision - every ~10 ms
 * while it is holding - so the answer must be a field read, never a binder call: one
 * {@code registerDefaultNetworkCallback} and one {@code ACTION_RESTRICT_BACKGROUND_CHANGED}
 * receiver keep two volatile flags current.
 *
 * <p><b>Gate = metered AND Data Saver</b> ({@link #shouldSaveData}). Android reports cellular as
 * metered even on unlimited plans, and the owner's LTE is one: there, stability and quality beat
 * megabytes. So "metered" alone no longer saves anything; the user must also have Android's Data
 * Saver restricting this app ({@code getRestrictBackgroundStatus() == ENABLED}; WHITELISTED -
 * "unrestricted data" for this app - and DISABLED do not save). Toggling Data Saver mid-playback
 * flips both policies for NEW chunks / loading decisions; nothing buffered is discarded.</p>
 *
 * <p>Registration (and its one seeding read) runs on a one-shot background thread, NOT inside
 * {@code createPlayer()} on main: that is the TTFF path, and the answer is not needed there - the
 * ceiling it feeds can only bind once 20-30 s of media are buffered, seconds after the first
 * callback has landed. Until then the flag reads "unknown" = unmetered = no ceiling.</p>
 *
 * <p>Deliberately conservative: anything uncertain (no default network, no capabilities, the
 * registration failing) reads as NOT metered, so the policy that hangs off this can only ever
 * save bytes on a network Android positively reports as metered - it never throttles Wi-Fi.
 * {@code NET_CAPABILITY_TEMPORARILY_NOT_METERED} (API 30, e.g. unmetered 5G plans) also counts
 * as unmetered: the platform documents it as "treat like NOT_METERED for large transfers".</p>
 *
 * <p>Registered once for the process lifetime and never unregistered (same shape as
 * {@code NetworkDiagnostics}); the platform fans default-network callbacks out to every
 * registrant, so a second one costs nothing measurable.</p>
 */
final class MeteredNetworkMonitor {
    /**
     * {@code NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED} (API 30), spelled out so the
     * minSdk-24 build does not inline a newer-API field; {@code hasCapability} of a capability an
     * older release does not know is simply false there.
     */
    private static final int NET_CAPABILITY_TEMPORARILY_NOT_METERED = 25;

    private static volatile boolean sMetered;
    /** Data Saver is on and restricts this app (RESTRICT_BACKGROUND_STATUS_ENABLED). */
    private static volatile boolean sDataSaver;
    private static volatile boolean sKnown;
    /** Test seam: when set, wins over the live value (the live thread may still write under it). */
    @Nullable private static volatile Boolean sTestOverride;
    /** Test seam for the Data Saver half, same contract. */
    @Nullable private static volatile Boolean sDataSaverTestOverride;
    private static boolean sStarted;
    /** Last published gate, so only a real flip reaches the viewport log. Guarded by the class. */
    private static boolean sPublishedSaving;

    private MeteredNetworkMonitor() {
    }

    /** Cheap: a volatile read. False until the first network report, and whenever unknown. */
    static boolean isMetered() {
        Boolean override = sTestOverride;
        return override != null ? override : sMetered;
    }

    /** Cheap: a volatile read. False until the registration has read the status, and on failure. */
    static boolean isDataSaverOn() {
        Boolean override = sDataSaverTestOverride;
        return override != null ? override : sDataSaver;
    }

    /** The gate both byte policies hang off: metered AND Data Saver restricting this app. */
    static boolean shouldSaveData() {
        return isMetered() && isDataSaverOn();
    }

    /** For logs: "metered=y dataSaver=n"; "pending" until the background registration reported. */
    static String describe() {
        boolean metered = sKnown || sTestOverride != null;
        boolean dataSaver = sKnown || sDataSaverTestOverride != null;
        return "metered=" + (metered ? yn(isMetered()) : "pending")
                + " dataSaver=" + (dataSaver ? yn(isDataSaverOn()) : "pending");
    }

    private static String yn(boolean value) {
        return value ? "y" : "n";
    }

    /** Idempotent and non-blocking. Safe from any thread; the first player creation calls it. */
    static void start(@Nullable Context context) {
        synchronized (MeteredNetworkMonitor.class) {
            if (sStarted || context == null) {
                return;
            }
            sStarted = true;
        }
        final Context appContext = context.getApplicationContext();
        Thread thread = new Thread(() -> register(appContext), "MeteredNetworkMonitor");
        thread.setDaemon(true);
        thread.start();
    }

    private static void register(Context context) {
        ConnectivityManager manager;
        try {
            manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        } catch (RuntimeException e) {
            manager = null;
        }
        if (manager == null) {
            NetPath.log("buffer-cap network unavailable manager=none -> metered=n");
            return;
        }

        final ConnectivityManager cm = manager;
        registerDataSaverReceiver(context, cm);
        try {
            update(isMetered(cm.getNetworkCapabilities(cm.getActiveNetwork())), "start");
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Nullable private Network mCurrent;

                @Override
                public void onAvailable(Network network) {
                    synchronized (this) {
                        mCurrent = network;
                    }
                    // API 26+ always follows with onCapabilitiesChanged, and a synchronous query
                    // from inside a callback can race it (documented NetworkCallback caveat). Only
                    // API 24/25, which do not guarantee that follow-up, need the query.
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
                        update(isMetered(cm.getNetworkCapabilities(network)), "available");
                    }
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    synchronized (this) {
                        mCurrent = network;
                    }
                    update(isMetered(capabilities), "capabilities");
                }

                @Override
                public void onLost(Network network) {
                    // A handover can report the old default lost AFTER the new one is available;
                    // only a loss of the CURRENT default means "no network" (which reads as unknown
                    // = unmetered until the next default network reports its capabilities).
                    synchronized (this) {
                        if (mCurrent != null && !mCurrent.equals(network)) {
                            return;
                        }
                        mCurrent = null;
                    }
                    update(false, "lost");
                }
            });
        } catch (RuntimeException e) {
            // SecurityException (no ACCESS_NETWORK_STATE) or a platform callback-limit failure.
            update(false, "failed");
            NetPath.log("buffer-cap network monitor failed error=" + e.getClass().getSimpleName());
        }
    }

    /**
     * Data Saver toggles (and per-app "unrestricted data" changes) arrive only as this broadcast,
     * and only to runtime-registered receivers. The seeding read happens here too, silently: the
     * "start" network report right after publishes the pair.
     */
    private static void registerDataSaverReceiver(Context context, final ConnectivityManager cm) {
        try {
            sDataSaver = isDataSaverOn(cm.getRestrictBackgroundStatus());
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    updateDataSaver(isDataSaverOn(cm.getRestrictBackgroundStatus()));
                }
            };
            // System broadcast; RECEIVER_NOT_EXPORTED still receives it (API 34+ wants a flag).
            ContextCompat.registerReceiver(context, receiver,
                    new IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (RuntimeException e) {
            // Unknown reads as "not saving": the byte policies stay off, never the reverse.
            NetPath.log("buffer-cap data-saver monitor failed error=" + e.getClass().getSimpleName());
        }
    }

    /** Pure: only ENABLED restricts this app; WHITELISTED ("unrestricted data") and DISABLED do not. */
    static boolean isDataSaverOn(int restrictBackgroundStatus) {
        return restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED;
    }

    /** Pure: metered exactly when Android does not report the link as (temporarily) unmetered. */
    static boolean isMetered(@Nullable NetworkCapabilities capabilities) {
        if (capabilities == null) {
            return false;
        }
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                && !capabilities.hasCapability(NET_CAPABILITY_TEMPORARILY_NOT_METERED);
    }

    private static synchronized void update(boolean metered, String event) {
        boolean previous = sMetered;
        boolean first = !sKnown;
        sMetered = metered;
        sKnown = true;
        if (previous != metered || first) {
            publish(event);
        }
    }

    private static synchronized void updateDataSaver(boolean dataSaver) {
        if (sDataSaver != dataSaver) {
            sDataSaver = dataSaver;
            publish("data-saver");
        }
    }

    /** One line per change of either half, saying what the policies do about it. */
    private static void publish(String event) {
        boolean saving = sMetered && sDataSaver;
        NetPath.log("buffer-cap network metered=" + yn(sMetered) + " dataSaver=" + yn(sDataSaver)
                + " event=" + event + " -> " + (saving ? "on" : "off"));
        if (saving != sPublishedSaving) {
            sPublishedSaving = saving;
            // The inline rung cap reads shouldSaveData() per selection by itself; this only logs it.
            VideoViewportCap.shared().onSavingChanged(saving, event);
        }
    }

    /** Test seam: pin the answer (null = live value again) without a ConnectivityManager. */
    static void setMeteredForTest(@Nullable Boolean metered) {
        sTestOverride = metered;
    }

    /** Test seam: pin the Data Saver half (null = live value again). */
    static void setDataSaverForTest(@Nullable Boolean dataSaver) {
        sDataSaverTestOverride = dataSaver;
    }
}
