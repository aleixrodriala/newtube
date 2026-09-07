package com.newtube.mobile.casting;

import com.liskovsoft.mediaserviceinterfaces.data.CastScreen;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class CastPickerMergeTest {
    private static CastTarget paired(String id, String name, CastTarget.ReceiverApp app) {
        return CastTarget.fromPairedScreen(new CastScreen(id, name), app);
    }

    @Test public void allDiscoveryOrdersPreserveSmartTubeAndPreferItToYouTube() {
        List<CastTarget> targets = Arrays.asList(
                paired("smart", " Living Room ", CastTarget.ReceiverApp.SMARTTUBE),
                paired("youtube", "Living Room", CastTarget.ReceiverApp.YOUTUBE),
                CastTarget.fromDial("Living Room", "youtube", "http://192.0.2.1/apps", "http://192.0.2.1/desc"),
                CastTarget.fromCastDevice("living room", "192.0.2.1", 8009));
        for (List<CastTarget> order : permutations(targets)) {
            CastDeviceRegistry registry = new CastDeviceRegistry();
            for (CastTarget target : order) registry.add(target);
            assertEquals(1, registry.devices().size());
            List<CastTarget> routes = registry.devices().get(0).routes();
            assertEquals(3, routes.size());
            assertEquals("smart", routes.get(0).getScreen().getScreenId());
            assertTrue(routes.get(0).isAdFree());
            assertEquals(CastTarget.Route.CAST_V2, routes.get(1).getRoute());
            assertEquals(CastTarget.Route.LOUNGE_MDX, routes.get(2).getRoute());
        }
    }

    @Test public void multiplePairedAppsAreNotDiscardedByNameMerge() {
        CastDeviceRegistry registry = new CastDeviceRegistry();
        registry.add(paired("smart-1", "TV", CastTarget.ReceiverApp.SMARTTUBE));
        registry.add(paired("smart-2", "TV", CastTarget.ReceiverApp.SMARTTUBE));
        registry.add(paired("legacy", "TV", CastTarget.ReceiverApp.UNKNOWN));
        registry.add(CastTarget.fromCastDevice("TV", "192.0.2.1", 8009));
        assertEquals(5, registry.devices().get(0).routes().size());
    }

    @Test public void sameNamedPhysicalTvsDoNotAbsorbAnAmbiguousPairing() {
        CastDeviceRegistry registry = new CastDeviceRegistry();
        registry.add(CastTarget.fromCastDevice("TV", "192.0.2.1", 8009));
        registry.add(CastTarget.fromCastDevice("TV", "192.0.2.2", 8009));
        CastTarget saved = paired("smart", "TV", CastTarget.ReceiverApp.SMARTTUBE);
        registry.add(saved);
        assertEquals(3, registry.devices().size());
        assertEquals(1, registry.deviceFor(saved).routes().size());
    }

    @Test public void pendingSavedRowUpgradesWhenCastArrives() {
        CastDeviceRegistry registry = new CastDeviceRegistry();
        CastTarget saved = paired("yt", "TV", CastTarget.ReceiverApp.YOUTUBE);
        registry.add(saved);
        assertTrue(CastPickerSheet.shouldWaitForDiscovery(false, registry.deviceFor(saved).routes().get(0)));
        registry.add(CastTarget.fromCastDevice("TV", "192.0.2.1", 8009));
        CastTarget preferred = registry.deviceFor(saved).routes().get(0);
        assertEquals(CastTarget.Route.CAST_V2, preferred.getRoute());
        assertFalse(CastPickerSheet.shouldWaitForDiscovery(false, preferred));
    }

    @Test public void noCastReceiverStillAllowsYoutubeAfterDiscoveryWindow() {
        CastTarget saved = paired("yt", "TV", CastTarget.ReceiverApp.YOUTUBE);
        assertFalse(CastPickerSheet.shouldWaitForDiscovery(true, saved));
    }

    @Test public void identifyingLegacyPairingRetainsItsIdAndMovesItAheadOfYoutube() {
        CastDeviceRegistry registry = new CastDeviceRegistry();
        CastTarget legacy = paired("old", "TV", CastTarget.ReceiverApp.UNKNOWN);
        registry.add(legacy);
        registry.add(paired("yt", "TV", CastTarget.ReceiverApp.YOUTUBE));
        registry.add(legacy.withReceiverApp(CastTarget.ReceiverApp.SMARTTUBE));
        assertEquals(2, registry.deviceFor(legacy).routes().size());
        assertEquals("old", registry.deviceFor(legacy).routes().get(0).getScreen().getScreenId());
        assertTrue(registry.deviceFor(legacy).routes().get(0).isAdFree());
    }

    @Test public void receiverAppIsNeverGuessedFromItsName() {
        assertFalse(paired("old", "SmartTube", CastTarget.ReceiverApp.UNKNOWN).isAdFree());
    }

    private static List<List<CastTarget>> permutations(List<CastTarget> values) {
        List<List<CastTarget>> result = new ArrayList<>();
        if (values.isEmpty()) { result.add(new ArrayList<>()); return result; }
        for (int i = 0; i < values.size(); i++) {
            List<CastTarget> rest = new ArrayList<>(values);
            CastTarget first = rest.remove(i);
            for (List<CastTarget> tail : permutations(rest)) {
                tail.add(0, first);
                result.add(tail);
            }
        }
        return result;
    }
}
