package com.newtube.mobile.player;

import static com.newtube.mobile.player.MediaPathVerdicts.Kind.CRONET_STALL;
import static com.newtube.mobile.player.MediaPathVerdicts.Kind.V6_STALL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.TelephonyNetworkSpecifier;
import android.telephony.TelephonyManager;

import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowNetworkInfo;
import org.robolectric.shadows.ShadowSubscriptionManager;

/** NEWTUBE(media-path): the Android store, the verdict scope and the preconnect route. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MediaPathRoutingTest {
    private static final String CELL = "cell:108";
    private static final String MOVISTAR = "carrier:1:21407:21407";
    private static final String CJOL = "rr4---sn-uxax4vopj5xn-cjol.googlevideo.com";

    private final Context mContext = RuntimeEnvironment.getApplication();

    @Test
    public void aVerdictIsWrittenToTheNetworkPrefsAndReadByTheNextProcess() {
        android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                android.provider.Settings.Global.BOOT_COUNT, 5);
        MediaPathRouting.ScopeReader cell = (context, network) ->
                CELL.equals(network) ? MOVISTAR : null;
        MediaPathVerdicts first = MediaPathRouting.newBookForTest(mContext, cell, true);
        first.load();
        first.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered");

        String stored = mContext.getSharedPreferences(Media3SourceFactory.NETWORK_PREFS_NAME,
                Context.MODE_PRIVATE).getString("media_path_verdicts", null);
        assertNotNull(stored);
        assertTrue(stored.startsWith("v4,5,"));
        assertTrue(stored.contains("\n" + MOVISTAR + ",cronet-stall,"));
        assertTrue(stored.endsWith(",1," + CJOL + ","));

        MediaPathVerdicts next = MediaPathRouting.newBookForTest(mContext, cell, true);
        assertFalse(next.isActive(CRONET_STALL, CELL)); // nothing until the background load
        next.load();
        assertTrue(next.isActive(CRONET_STALL, CELL));
        assertFalse(next.isActive(CRONET_STALL, "wifi:112"));
    }

    @Test
    public void theRemovalBreadcrumbSurvivesInThePrefsWhenTheVerdictKeyIsDeleted() {
        android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                android.provider.Settings.Global.BOOT_COUNT, 5);
        MediaPathRouting.ScopeReader cell = (context, network) ->
                CELL.equals(network) ? MOVISTAR : null;
        MediaPathVerdicts first = MediaPathRouting.newBookForTest(mContext, cell, true);
        first.load();
        first.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        first.clear(V6_STALL, CELL, "test-clear");

        android.content.SharedPreferences prefs = mContext.getSharedPreferences(
                Media3SourceFactory.NETWORK_PREFS_NAME, Context.MODE_PRIVATE);
        assertNull(prefs.getString("media_path_verdicts", null));
        String note = prefs.getString("media_path_last_off", null);
        assertNotNull(note);
        assertTrue(note, note.endsWith(",v6-stall,carrier,1,test-clear"));
    }

    @Test
    public void attachmentVerdictsNeedTheSameBootCount() {
        android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                android.provider.Settings.Global.BOOT_COUNT, 5);
        MediaPathRouting.ScopeReader wifi = (context, network) ->
                "wifi:112".equals(network) ? "wifi:112/wlan0" : null;
        MediaPathVerdicts first = MediaPathRouting.newBookForTest(mContext, wifi, true);
        first.load();
        first.observe(V6_STALL, "wifi:112", CJOL, "v6-handshake-stall");

        MediaPathVerdicts sameBoot = MediaPathRouting.newBookForTest(mContext, wifi, true);
        sameBoot.load();
        assertTrue(sameBoot.isActive(V6_STALL, "wifi:112"));

        android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                android.provider.Settings.Global.BOOT_COUNT, 6); // rebooted
        MediaPathVerdicts nextBoot = MediaPathRouting.newBookForTest(mContext, wifi, true);
        nextBoot.load();
        assertFalse(nextBoot.isActive(V6_STALL, "wifi:112"));
    }

    @Test
    public void behindAProxyTheAndroidBookLearnsNothing() {
        MediaPathVerdicts book = MediaPathRouting.newBookForTest(mContext,
                (context, network) -> MOVISTAR, /* direct= */ false);
        book.load();
        assertFalse(book.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered"));
        assertTrue(book.isEmpty());
    }

    @Test
    public void theWarmMovesToOkHttpOnlyWhereCronetIsProvenStalled() {
        MediaPathVerdictsTest.FakeEnv env =
                new MediaPathVerdictsTest.FakeEnv(new MediaPathVerdictsTest.FakeStore());
        MediaPathVerdicts book = new MediaPathVerdicts(env, false);
        assertFalse(MediaPathRouting.warmsOverOkHttp(book, CJOL, CELL));

        book.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered");

        assertTrue(MediaPathRouting.warmsOverOkHttp(book, CJOL, CELL));
        assertTrue(MediaPathRouting.warmsOverOkHttp(book,
                "rr1---sn-uxax4vopj5xn-cjoe.googlevideo.com", CELL)); // any edge on that carrier
        assertFalse(MediaPathRouting.warmsOverOkHttp(book, "www.youtube.com", CELL));
        env.network = "wifi:112";
        assertFalse(MediaPathRouting.warmsOverOkHttp(book, CJOL, "wifi:112")); // Wi-Fi keeps Cronet
        // An IPv6-only verdict leaves the warm on Cronet: Cronet may still answer there.
        env.network = CELL;
        book.clear(CRONET_STALL, CELL, "test");
        book.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        assertFalse(MediaPathRouting.warmsOverOkHttp(book, CJOL, CELL));
    }

    @Test
    public void aRoamingFlipDropsTheCachedScopeOfThatNetworkAtOnce() {
        // Codex review: the 60 s scope cache must not carry a carrier scope into roaming.
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        Network network = ShadowNetwork.newInstance(7);
        String key = "cell:" + network.hashCode();
        String[] answer = {MOVISTAR};
        MediaPathRouting.AndroidEnv env = new MediaPathRouting.AndroidEnv((context, id) -> {
            reads.incrementAndGet();
            return answer[0];
        }, () -> true);
        env.mContext = mContext;
        assertEquals(MOVISTAR, env.scope(key));
        assertEquals(MOVISTAR, env.scope(key));
        assertEquals(1, reads.get()); // cached

        NetworkCapabilities home = ShadowNetworkCapabilities.newInstance();
        shadowOf(home).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        shadowOf(home).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
        env.onDefaultCapabilities(network, home); // a bandwidth update: nothing changes
        assertEquals(MOVISTAR, env.scope(key));
        assertEquals(1, reads.get());

        NetworkCapabilities roaming = ShadowNetworkCapabilities.newInstance();
        shadowOf(roaming).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        answer[0] = key + "/rmnet_data1";
        env.onDefaultCapabilities(network, roaming); // same attachment, now roaming

        assertEquals(key + "/rmnet_data1", env.scope(key));
        assertEquals(2, reads.get());
    }

    // --- the real scope reader (API 33: TelephonyNetworkSpecifier) ----------------------------

    @Test
    @Config(sdk = 33)
    public void homeCellularIsScopedToSubscriptionAndCarrier() throws Exception {
        telephony(2, "21407", "21407");
        Network cell = activate(ConnectivityManager.TYPE_MOBILE,
                NetworkCapabilities.TRANSPORT_CELLULAR, "rmnet_data1", 2, /* roaming= */ false);
        String key = NetPath.networkId(mContext);
        assertEquals("cell:" + cell.hashCode(), key);

        assertEquals("carrier:2:21407:21407",
                MediaPathRouting.AndroidEnv.readScope(mContext, key));
        // Any other id (not the default network right now) reads nothing.
        assertNull(MediaPathRouting.AndroidEnv.readScope(mContext, "cell:999"));
    }

    @Test
    @Config(sdk = 33)
    public void roamingIsNeverCarrierScoped() throws Exception {
        telephony(1, "20801", "21407"); // a Movistar SIM served by Orange France
        activate(ConnectivityManager.TYPE_MOBILE, NetworkCapabilities.TRANSPORT_CELLULAR,
                "rmnet_data1", 1, /* roaming= */ true);
        String key = NetPath.networkId(mContext);

        assertEquals(key + "/rmnet_data1", MediaPathRouting.AndroidEnv.readScope(mContext, key));
    }

    @Test
    @Config(sdk = 33)
    public void withoutAReadableCarrierCellularFallsBackToTheAttachment() throws Exception {
        // Subscription 3 has no TelephonyManager (no SIM info): attachment scope only.
        activate(ConnectivityManager.TYPE_MOBILE, NetworkCapabilities.TRANSPORT_CELLULAR,
                "rmnet_data4", 3, false);
        String key = NetPath.networkId(mContext);
        assertEquals(key + "/rmnet_data4", MediaPathRouting.AndroidEnv.readScope(mContext, key));

        telephony(3, "", "21407"); // not registered yet: no serving PLMN
        assertEquals(key + "/rmnet_data4", MediaPathRouting.AndroidEnv.readScope(mContext, key));
    }

    @Test
    @Config(sdk = 33)
    public void theDefaultDataSubscriptionIsUsedWhenTheNetworkNamesNone() throws Exception {
        telephony(4, "21401", "21401");
        ShadowSubscriptionManager.setDefaultDataSubscriptionId(4);
        activate(ConnectivityManager.TYPE_MOBILE, NetworkCapabilities.TRANSPORT_CELLULAR,
                "rmnet_data0", -1, false);

        assertEquals("carrier:4:21401:21401",
                MediaPathRouting.AndroidEnv.readScope(mContext, NetPath.networkId(mContext)));
    }

    @Test
    @Config(sdk = 33)
    public void wifiIsScopedToItsAttachmentAndNeedsAnInterface() throws Exception {
        Network wifi = activate(ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI,
                "wlan0", -1, false);
        String key = NetPath.networkId(mContext);
        assertEquals("wifi:" + wifi.hashCode(), key);
        assertEquals(key + "/wlan0", MediaPathRouting.AndroidEnv.readScope(mContext, key));

        activate(ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI, null, -1, false);
        assertNull(MediaPathRouting.AndroidEnv.readScope(mContext, NetPath.networkId(mContext)));
    }

    private void telephony(int subId, String serving, String sim) {
        TelephonyManager manager = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        shadowOf(manager).setNetworkOperator(serving);
        shadowOf(manager).setSimOperator(sim);
        shadowOf(manager).setTelephonyManagerForSubscriptionId(subId, manager);
    }

    private Network activate(int type, int transport, String iface, int subId, boolean roaming)
            throws Exception {
        ConnectivityManager manager = (ConnectivityManager)
                mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = ShadowNetwork.newInstance(type); // the shadow maps type -> netId
        NetworkInfo info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
                type, 0, true, NetworkInfo.State.CONNECTED);
        shadowOf(manager).addNetwork(network, info);
        shadowOf(manager).setActiveNetworkInfo(info);
        NetworkCapabilities caps = ShadowNetworkCapabilities.newInstance();
        shadowOf(caps).addTransportType(transport);
        if (!roaming) {
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
        }
        if (subId >= 0) {
            shadowOf(caps).setNetworkSpecifier(
                    new TelephonyNetworkSpecifier.Builder().setSubscriptionId(subId).build());
        }
        shadowOf(manager).setNetworkCapabilities(network, caps);
        LinkProperties link = new LinkProperties();
        if (iface != null) {
            // Hidden setter in the SDK stubs; the real framework class runs under Robolectric.
            LinkProperties.class.getMethod("setInterfaceName", String.class).invoke(link, iface);
        }
        shadowOf(manager).setLinkProperties(network, link);
        return network;
    }
}
