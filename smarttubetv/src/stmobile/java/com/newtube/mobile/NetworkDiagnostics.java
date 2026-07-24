package com.newtube.mobile;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

/**
 * Process-wide default-network diagnostics for the phone flavor. Logs transitions only; it does
 * not request or modify connectivity. Values deliberately exclude SSID, carrier, IP and DNS data.
 */
final class NetworkDiagnostics {
    private static ConnectivityManager sManager;
    private static ConnectivityManager.NetworkCallback sCallback;
    private static String sLastState;

    private NetworkDiagnostics() {
    }

    static synchronized void start(Context context) {
        if (sCallback != null || context == null) {
            return;
        }

        Context appContext = context.getApplicationContext();
        ConnectivityManager manager = (ConnectivityManager)
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            NetPath.log("network-monitor unavailable manager=none");
            return;
        }

        ConnectivityManager.NetworkCallback callback =
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        logState(appContext, "available", network);
                    }

                    @Override
                    public void onCapabilitiesChanged(
                            Network network, NetworkCapabilities capabilities) {
                        logState(appContext, "capabilities", network);
                    }

                    @Override
                    public void onLosing(Network network, int maxMsToLive) {
                        NetPath.log("network-event losing net=" + network.hashCode()
                                + " ttlMs=" + maxMsToLive);
                    }

                    @Override
                    public void onLost(Network network) {
                        synchronized (NetworkDiagnostics.class) {
                            sLastState = null;
                        }
                        NetPath.log("network-event lost net=" + network.hashCode()
                                + " active={" + NetPath.networkSnapshot(appContext) + '}');
                    }

                    @Override
                    public void onUnavailable() {
                        NetPath.log("network-event unavailable");
                    }
                };

        try {
            sLastState = NetPath.networkSnapshot(appContext);
            manager.registerDefaultNetworkCallback(callback);
            sManager = manager;
            sCallback = callback;
            NetPath.log("network-monitor started " + sLastState);
        } catch (RuntimeException e) {
            NetPath.log("network-monitor failed error=" + e.getClass().getSimpleName()
                    + ':' + NetPath.trunc(e.getMessage(), 100));
        }
    }

    private static void logState(Context context, String event, Network network) {
        String state = NetPath.networkSnapshot(context, network);
        synchronized (NetworkDiagnostics.class) {
            if (state.equals(sLastState)) {
                return;
            }
            sLastState = state;
        }
        NetPath.log("network-event " + event + ' ' + state);
    }
}
