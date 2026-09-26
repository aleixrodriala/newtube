package com.newtube.mobile.player;

import static com.newtube.mobile.player.MediaPathVerdicts.Kind.CRONET_STALL;
import static com.newtube.mobile.player.MediaPathVerdicts.Kind.V6_STALL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.Nullable;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NEWTUBE(media-path): per-network verdicts that survive process death but never their attachment,
 * their boot, a VPN or proxy, or fresh evidence against them - replayed on a fake clock, network,
 * boot and store.
 */
public class MediaPathVerdictsTest {
    private static final String CELL = "cell:108";
    /** The same SIM and carrier after a Wi-Fi period: a NEW cellular attachment (Pixel: 112 -> 114). */
    private static final String CELL_NEXT = "cell:114";
    private static final String MOVISTAR = "carrier:1:21407:21407";
    private static final String WIFI = "wifi:112";
    private static final String CJOL = "rr4---sn-uxax4vopj5xn-cjol.googlevideo.com";
    private static final String CJOL2 = "rr1---sn-uxax4vopj5xn-cjol.googlevideo.com";
    private static final String CJOE = "rr8---sn-uxax4vopj5xn-cjoe.googlevideo.com";
    private static final String CJOL8 = "rr8---sn-uxax4vopj5xn-cjol.googlevideo.com";
    private static final long MIN = 60_000L;

    /** The persisted value outlives every "process" (book instance) of the test. */
    private final FakeStore mStore = new FakeStore();
    private final FakeEnv mEnv = new FakeEnv(mStore);
    private final MediaPathVerdicts mBook = newProcess();

    // --- scope: the carrier on cellular, the attachment elsewhere; never a VPN or a proxy -----

    @Test
    public void aNewCellularAttachmentOnTheSameSimAndCarrierInheritsTheVerdict() {
        assertTrue(mBook.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered"));
        assertEquals("media-path verdict on kind=cronet-stall network=cell:108 scope=carrier"
                + " key=" + MOVISTAR + " host=" + CJOL + " evidence=okhttp-answered"
                + " ttlMs=" + MediaPathVerdicts.SINGLE_EDGE_TTL_MS + "/" + MediaPathVerdicts.TTL_MS
                + " reprobeMs=" + MediaPathVerdicts.FIRST_REPROBE_MS + "/"
                + MediaPathVerdicts.REPROBE_MS + " persisted=y", mEnv.lines.get(0));

        mEnv.network = WIFI; // at home...
        assertFalse(mBook.isActive(CRONET_STALL, WIFI)); // Wi-Fi is never penalised
        mEnv.network = CELL_NEXT; // ...leaving home: Android made a new cellular network
        assertTrue(mBook.isActive(CRONET_STALL, CELL_NEXT));
        assertEquals("scope=carrier verdicts=cronet-stall:ageMs=0:seenAgoMs=0:count=1:edges=1"
                + ":restored=n", mBook.describe(CELL_NEXT));
        assertFalse(mBook.isActive(V6_STALL, CELL_NEXT)); // kinds are independent
    }

    @Test
    public void anotherSimAnotherServingNetworkOrRoamingDoNotInherit() {
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");

        mEnv.network = "cell:120";
        mEnv.scopes.put("cell:120", "carrier:2:21407:21407"); // the other SIM, same carrier
        assertFalse(mBook.isActive(V6_STALL, "cell:120"));
        mEnv.network = "cell:121";
        mEnv.scopes.put("cell:121", "carrier:1:21401:21407"); // served by another network
        assertFalse(mBook.isActive(V6_STALL, "cell:121"));
        mEnv.network = "cell:122";
        mEnv.scopes.put("cell:122", "cell:122/rmnet_data1"); // roaming: attachment scope only
        assertFalse(mBook.isActive(V6_STALL, "cell:122"));
        // ...and what a roaming attachment learns stays on that attachment.
        mBook.observe(V6_STALL, "cell:122", CJOL, "v6-handshake-stall");
        mEnv.network = "cell:123";
        mEnv.scopes.put("cell:123", "cell:123/rmnet_data1");
        assertFalse(mBook.isActive(V6_STALL, "cell:123"));
        assertTrue(mEnv.lines.stream().anyMatch(l -> l.contains("network=cell:122 scope=attachment")));
    }

    @Test
    public void wifiVerdictsStayOnTheirAttachment() {
        mEnv.network = WIFI;
        mBook.observe(V6_STALL, WIFI, CJOL, "v6-handshake-stall");
        assertTrue(mBook.isActive(V6_STALL, WIFI));

        mEnv.network = "wifi:113"; // reconnecting (or another SSID) is another attachment
        mEnv.scopes.put("wifi:113", "wifi:113/wlan0");
        assertFalse(mBook.isActive(V6_STALL, "wifi:113"));
        // A reused id on another interface is another scope key too.
        mEnv.network = WIFI;
        mEnv.scopes.put(WIFI, "wifi:112/wlan1");
        assertFalse(mBook.isActive(V6_STALL, WIFI));
    }

    @Test
    public void aVpnNeverGetsOneBecauseItFollowsTheUnderlyingNetworkAround() {
        mEnv.network = "vpn:130";
        assertFalse(mBook.observe(V6_STALL, "vpn:130", CJOL, "v6-handshake-stall"));

        assertFalse(mBook.isActive(V6_STALL, "vpn:130"));
        assertTrue(mBook.isEmpty());
        assertEquals("media-path verdict skipped kind=v6-stall network=vpn:130 reason=vpn"
                + " evidence=v6-handshake-stall", mEnv.lines.get(0));
        for (String other : new String[] {"bluetooth:3", "other:4", null, "", "unknown", "none"}) {
            assertFalse(mBook.observe(V6_STALL, other, CJOL, "v6-handshake-stall"));
        }
        assertTrue(mBook.isEmpty());
        assertNull(mStore.value);
        // A verdict held for the carrier never reaches a VPN riding on it either.
        mEnv.network = CELL;
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.network = "vpn:130";
        assertFalse(mBook.isActive(V6_STALL, "vpn:130"));
    }

    @Test
    public void behindAProxyNothingIsLearntAndNothingApplied() {
        mEnv.direct = false;
        assertFalse(mBook.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered"));
        assertTrue(mEnv.lines.get(0).endsWith("reason=proxy evidence=okhttp-answered"));

        mEnv.direct = true;
        mBook.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered");
        mEnv.direct = false; // the user configures a proxy: its path is not this verdict's
        assertFalse(mBook.isActive(CRONET_STALL, CELL));
        mEnv.direct = true; // ...and it is back once media goes direct again
        assertTrue(mBook.isActive(CRONET_STALL, CELL));
    }

    @Test
    public void anUnidentifiableNetworkLearnsNothing() {
        mEnv.scopes.put(CELL, null);
        assertFalse(mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall"));
        assertTrue(mEnv.lines.get(0).contains("reason=no-scope"));
    }

    // --- persistence --------------------------------------------------------------------------

    @Test
    public void aNewProcessOfTheSameBootRestoresItInTheBackground() {
        mBook.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered");
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.now += 30 * MIN; // the process died; the attachment lives on

        MediaPathVerdicts next = newProcess();
        int reads = mEnv.storeReads.get();
        int boots = mEnv.bootCountReads.get();
        // Caller threads never read the store: before load() the book knows nothing...
        assertTrue(next.isEmpty());
        assertFalse(next.isActive(CRONET_STALL, CELL));
        assertNull(next.claimProbe(CRONET_STALL, CELL));
        assertEquals(reads, mEnv.storeReads.get());
        assertEquals(boots, mEnv.bootCountReads.get());

        next.load(); // ...the background load restores it.

        assertTrue(next.isActive(CRONET_STALL, CELL));
        assertTrue(next.isActive(V6_STALL, CELL));
        long left = MediaPathVerdicts.SINGLE_EDGE_TTL_MS - 30 * MIN;
        assertEquals("media-path restore records=2 stored=present current=cell:108 scope=carrier"
                + " active=[cronet-stall:ageMs=" + 30 * MIN + ":seenAgoMs=" + 30 * MIN
                + ":count=1:edges=1,v6-stall:ageMs=" + 30 * MIN + ":seenAgoMs=" + 30 * MIN
                + ":count=1:edges=1] all=[carrier/cronet-stall:edges=1:seenAgoMs=" + 30 * MIN
                + ":leftMs=" + left + ",carrier/v6-stall:edges=1:seenAgoMs=" + 30 * MIN
                + ":leftMs=" + left + "] lastOff=none", mEnv.lines.get(mEnv.lines.size() - 1));
        assertEquals("scope=carrier verdicts=cronet-stall:ageMs=" + 30 * MIN + ":seenAgoMs="
                + 30 * MIN + ":count=1:edges=1:restored=y,v6-stall:ageMs=" + 30 * MIN
                + ":seenAgoMs=" + 30 * MIN + ":count=1:edges=1:restored=y", next.describe(CELL));
        // The owner's next day out: a new cellular attachment, same SIM - restored and applied.
        mEnv.network = CELL_NEXT;
        assertTrue(next.isActive(CRONET_STALL, CELL_NEXT));
    }

    @Test
    public void whatAProcessLearnsBeforeItsLoadCompletesIsKeptAndSaved() {
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.now += MIN;
        MediaPathVerdicts next = newProcess();
        next.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered"); // before its load
        assertTrue(mStore.value.contains("v6-stall")); // nothing overwritten yet
        assertFalse(mStore.value.contains("cronet-stall"));

        next.load();

        assertTrue(next.isActive(V6_STALL, CELL));
        assertTrue(next.isActive(CRONET_STALL, CELL));
        assertTrue(mStore.value.contains("cronet-stall") && mStore.value.contains("v6-stall"));
    }

    @Test
    public void aRebootDropsAttachmentVerdictsButKeepsTheCarriersRebased() {
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");      // carrier scope
        mEnv.network = WIFI;
        mBook.observe(CRONET_STALL, WIFI, CJOL, "okhttp-answered");     // attachment scope
        long learntWall = mEnv.bootWallMs + mEnv.now;
        // Reboot 20 minutes later; the new boot has been up 5 minutes.
        mEnv.bootCount++;
        mEnv.now = 5 * MIN;
        mEnv.bootWallMs = learntWall + 20 * MIN - mEnv.now;

        MediaPathVerdicts next = newProcessLoaded();

        assertTrue(mEnv.lines.get(mEnv.lines.size() - 1).contains(
                "records=1 stored=present dropped=[other-boot:cronet-stall@attachment]"));
        mEnv.network = "cell:101"; // network ids restarted; same SIM and carrier
        mEnv.scopes.put("cell:101", MOVISTAR);
        assertTrue(next.isActive(V6_STALL, "cell:101"));
        // Its age survived the reboot on the wall clock: learnt 20 minutes ago.
        assertTrue(next.describe("cell:101").contains("v6-stall:ageMs=" + 20 * MIN + ":"));
        mEnv.network = WIFI; // "wifi:112" after a reboot may be any network: nothing applies
        assertFalse(next.isActive(CRONET_STALL, WIFI));
    }

    @Test
    public void aCarrierVerdictThatLapsedWhileRebootingIsDropped() {
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        long learntWall = mEnv.bootWallMs + mEnv.now;
        mEnv.bootCount++;
        mEnv.now = 5 * MIN;
        mEnv.bootWallMs = learntWall + MediaPathVerdicts.SINGLE_EDGE_TTL_MS - mEnv.now;

        assertTrue(newProcessLoaded().isEmpty());
        assertTrue(mEnv.lines.get(mEnv.lines.size() - 1).contains("expired:v6-stall@carrier"));
    }

    @Test
    public void aClockJumpAcrossARebootCanOnlyDropACarrierVerdict() {
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.bootCount++;
        mEnv.now = 5 * MIN;
        mEnv.bootWallMs -= 3 * 60 * MIN; // the new boot's clock is hours behind: "the future"

        assertTrue(newProcessLoaded().isEmpty());
        assertTrue(mEnv.lines.get(mEnv.lines.size() - 1).contains("dropped=[corrupt]"));
    }

    @Test
    public void withoutBootCountOnlyAttachmentVerdictsAreDropped() {
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.network = WIFI;
        mBook.observe(V6_STALL, WIFI, CJOL, "v6-handshake-stall");
        mEnv.bootCount = -1; // this device does not report Settings.Global.BOOT_COUNT

        MediaPathVerdicts next = newProcessLoaded();

        assertFalse(next.isActive(V6_STALL, WIFI));
        mEnv.network = CELL;
        assertTrue(next.isActive(V6_STALL, CELL)); // same boot wall clock: times unchanged
    }

    @Test
    public void bootIdentityNeedsTheCountAndAMatchingWallTime() {
        assertTrue(MediaPathVerdicts.sameBoot(7, 1_000_000, 7, 1_060_000)); // clock corrected
        assertFalse(MediaPathVerdicts.sameBoot(7, 1_000_000, 8, 1_000_000));
        assertFalse(MediaPathVerdicts.sameBoot(7, 1_000_000, 7,
                1_000_000 + MediaPathVerdicts.BOOT_WALL_TOLERANCE_MS + 1));
        assertFalse(MediaPathVerdicts.sameBoot(-1, 1_000_000, -1, 1_000_000)); // count unknown
        assertFalse(MediaPathVerdicts.sameBoot(7, 1_000_000, -1, 1_000_000));
        assertFalse(MediaPathVerdicts.sameBoot(7, 0, 7, 1_000_000));
    }

    @Test
    public void aStoredTimeAheadOfTheClockIsRejected() {
        mEnv.network = WIFI;
        mBook.observe(V6_STALL, WIFI, CJOL, "v6-handshake-stall");
        mEnv.now = 500; // elapsedRealtime went backwards: rebooted, whatever the count says

        assertFalse(newProcessLoaded().isActive(V6_STALL, WIFI));
        assertTrue(mEnv.lines.get(mEnv.lines.size() - 1).contains("dropped=[corrupt]"));
    }

    @Test
    public void aDamagedSnapshotCanOnlyRestoreLess() {
        String header = "v3," + mEnv.bootCount + "," + mEnv.bootWallMs;
        long t = mEnv.now - MIN;
        mStore.value = header
                + "\n" + MOVISTAR + ",v6-stall," + t + "," + t + ",2," + CJOL + ";" + CJOL2
                + "\n" + MOVISTAR + ",cronet-stall," + t + "," + (t - 1) + ",1," + CJOL
                + "\nwifi:7/wlan0,teleport-stall," + t + "," + t + ",1," + CJOL
                + "\nvpn:9/tun0,v6-stall," + t + "," + t + ",1," + CJOL
                + "\ncell:10/rmnet0,v6-stall," + t + "," + t + ",0," + CJOL
                + "\ncell:11/rmnet0,v6-stall,abc," + t + ",1," + CJOL
                + "\ncell:12/rm net,v6-stall," + t + "," + t + ",1," + CJOL
                + "\ncarrier:1:214:21407,v6-stall," + t + "," + t + ",1," + CJOL
                + "\ncell:13,v6-stall," + t + "," + t + ",1," + CJOL
                + "\nwifi:14/wlan0,cronet-stall," + t + "," + t + ",1,evil.example;" + CJOE
                + "\njunk";

        MediaPathVerdicts next = newProcessLoaded();

        assertTrue(next.isActive(V6_STALL, CELL));
        assertTrue(next.describe(CELL).contains("edges=2"));
        assertFalse(next.isActive(CRONET_STALL, CELL));
        assertEquals(java.util.Arrays.asList(MOVISTAR, "wifi:14/wlan0"), next.scopes());
        mEnv.scopes.put("wifi:14", "wifi:14/wlan0");
        mEnv.network = "wifi:14";
        assertTrue(next.isActive(CRONET_STALL, "wifi:14")); // with the valid host only
        assertTrue(next.describe("wifi:14").contains("edges=1"));

        mStore.value = "v2,42,1790000000000\ncell:108,v6-stall,1,1,1,rmnet0," + CJOL; // old format
        assertTrue(newProcessLoaded().isEmpty());
        mStore.value = "complete garbage";
        assertTrue(newProcessLoaded().isEmpty());
    }

    // --- lifetime -----------------------------------------------------------------------------

    @Test
    public void aSingleEdgeVerdictLapsesSooner() {
        mBook.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered");
        mEnv.now += MediaPathVerdicts.SINGLE_EDGE_TTL_MS - 1;
        assertTrue(mBook.isActive(CRONET_STALL, CELL));

        mEnv.now += 1;
        assertFalse(mBook.isActive(CRONET_STALL, CELL));
        assertTrue(mEnv.lines.get(mEnv.lines.size() - 1).startsWith(
                "media-path verdict off kind=cronet-stall scope=carrier key=" + MOVISTAR
                        + " reason=expired"));
        assertNull(mStore.value);
    }

    @Test
    public void aMultiEdgeVerdictLivesAFullDayFromItsLastRenewal() {
        mBook.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered");
        mEnv.episode = "ep=2 video=b";
        mBook.observe(CRONET_STALL, CELL, CJOL2, "okhttp-answered"); // a second edge
        mEnv.now += MediaPathVerdicts.TTL_MS - 1;
        assertTrue(mBook.isActive(CRONET_STALL, CELL));
        mEnv.now += 1;
        assertFalse(mBook.isActive(CRONET_STALL, CELL));
    }

    @Test
    public void anExpiredRecordIsNotRestored() {
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.now += MediaPathVerdicts.SINGLE_EDGE_TTL_MS;

        assertFalse(newProcessLoaded().isActive(V6_STALL, CELL));
        assertTrue(mEnv.lines.get(mEnv.lines.size() - 1)
                .contains("dropped=[expired:v6-stall@carrier]"));
    }

    @Test
    public void clearingOneKindKeepsTheOther() {
        mBook.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered");
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");

        mBook.clear(V6_STALL, CELL, "test");
        mBook.clear(V6_STALL, CELL, "test"); // absent: silent

        assertTrue(mBook.isActive(CRONET_STALL, CELL));
        assertFalse(mBook.isActive(V6_STALL, CELL));
        assertEquals(1, mEnv.lines.stream().filter(l -> l.contains("verdict off")).count());
        MediaPathVerdicts next = newProcessLoaded();
        assertTrue(next.isActive(CRONET_STALL, CELL));
        assertFalse(next.isActive(V6_STALL, CELL));
    }

    // --- evidence counting and re-probes ------------------------------------------------------

    @Test
    public void audioAndVideoStallingInOneOpenAreOnePieceOfEvidence() {
        mEnv.episode = "ep=1 video=a";
        mBook.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered"); // itag 251
        mBook.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered"); // itag 248, same open

        assertTrue(mBook.describe(CELL).contains(":count=1:"));
        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS; // so the quick first re-probe stands
        assertNotNull(mBook.claimProbe(CRONET_STALL, CELL));

        mEnv.episode = "ep=2 video=b";
        mBook.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered");
        assertTrue(mBook.describe(CELL).contains(":count=2:"));
    }

    @Test
    public void theFirstProbeRechecksTheStalledEdgeThenExploresAnother() {
        mBook.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered");
        mBook.noteHost(CELL, CJOE); // the next open's media reached another edge over OkHttp
        assertNull(mBook.claimProbe(CRONET_STALL, CELL)); // just observed

        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        MediaPathVerdicts.ProbeTicket first = mBook.claimProbe(CRONET_STALL, CELL);
        assertEquals(CJOL, first.host);
        assertFalse(first.explore);
        assertNull(mBook.claimProbe(CRONET_STALL, CELL)); // one claim at a time
        mBook.onProbeResult(first, MediaPathVerdicts.ProbeOutcome.STALLED, 6_000, "timeout", true);

        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        MediaPathVerdicts.ProbeTicket second = mBook.claimProbe(CRONET_STALL, CELL);
        assertEquals(CJOE, second.host);
        assertTrue(second.explore);
    }

    @Test
    public void aSingleBrokenEdgeCannotKeepTheNetworkOffCronetByItself() {
        // Codex review P1: re-probing the one edge that stalls must never renew the verdict,
        // while other edges answer - the network drifts back to Cronet.
        mBook.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered");
        mBook.noteHost(CELL, CJOE);
        long learntAt = mEnv.now;
        while (mEnv.now < learntAt + MediaPathVerdicts.SINGLE_EDGE_TTL_MS - 2 * MIN) {
            mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
            MediaPathVerdicts.ProbeTicket ticket = mBook.claimProbe(CRONET_STALL, CELL);
            assertNotNull(ticket);
            mBook.onProbeResult(ticket, ticket.explore
                    ? MediaPathVerdicts.ProbeOutcome.RECOVERED   // the healthy edge answers
                    : MediaPathVerdicts.ProbeOutcome.STALLED,    // the broken one still stalls
                    ticket.explore ? 120 : 6_000, "x", true);
            assertTrue(mBook.isActive(CRONET_STALL, CELL)); // one healthy edge clears nothing
        }
        mEnv.now = learntAt + MediaPathVerdicts.SINGLE_EDGE_TTL_MS;
        assertFalse(mBook.isActive(CRONET_STALL, CELL));
        assertTrue(mEnv.lines.stream().anyMatch(l -> l.endsWith("applied=n reason=known-edge")));
        assertTrue(mEnv.lines.stream().anyMatch(l -> l.endsWith("applied=n reason=healthy-edge")));
    }

    @Test
    public void aSecondStallingEdgeFoundByExploringRenewsForAFullDay() {
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mBook.noteHost(CELL, CJOL2);
        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        MediaPathVerdicts.ProbeTicket recheck = mBook.claimProbe(V6_STALL, CELL);
        mBook.onProbeResult(recheck, MediaPathVerdicts.ProbeOutcome.STALLED, 6_000, "timeout", true);
        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        MediaPathVerdicts.ProbeTicket explore = mBook.claimProbe(V6_STALL, CELL);
        assertEquals(CJOL2, explore.host);

        mBook.onProbeResult(explore, MediaPathVerdicts.ProbeOutcome.STALLED, 6_000, "timeout", true);

        assertTrue(mBook.describe(CELL).contains(":edges=2"));
        long renewedAt = mEnv.now;
        // The relaxed cadence applies now...
        mEnv.now = renewedAt + MediaPathVerdicts.REPROBE_MS - 1;
        assertNull(mBook.claimProbe(V6_STALL, CELL));
        mEnv.now = renewedAt + MediaPathVerdicts.REPROBE_MS;
        assertNotNull(mBook.claimProbe(V6_STALL, CELL));
        // ...and the verdict lives a full day from that renewal.
        mEnv.now = renewedAt + MediaPathVerdicts.TTL_MS - 1;
        assertTrue(mBook.isActive(V6_STALL, CELL));
        mEnv.now = renewedAt + MediaPathVerdicts.TTL_MS;
        assertFalse(mBook.isActive(V6_STALL, CELL));
    }

    @Test
    public void aRecheckThatAnswersClearsTheVerdictSoAFixedNetworkRecovers() {
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        MediaPathVerdicts.ProbeTicket ticket = mBook.claimProbe(V6_STALL, CELL);

        mBook.onProbeResult(ticket, MediaPathVerdicts.ProbeOutcome.RECOVERED, 140, "http=204", true);

        assertFalse(mBook.isActive(V6_STALL, CELL));
        assertTrue(newProcessLoaded().isEmpty());
        assertTrue(mEnv.lines.contains("media-path probe kind=v6-stall network=cell:108"
                + " scope=carrier host=" + CJOL + " role=recheck outcome=recovered elapsedMs=140"
                + " detail=http=204 applied=y"));
    }

    @Test
    public void aProbeOnTheNextAttachmentStillClearsTheCarrierVerdict() {
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.network = CELL_NEXT;
        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        MediaPathVerdicts.ProbeTicket ticket = mBook.claimProbe(V6_STALL, CELL_NEXT);
        assertEquals(CELL_NEXT, ticket.network); // the probe runs on today's attachment...
        assertEquals(CJOL, ticket.host);          // ...against the edge that stalled before

        mBook.onProbeResult(ticket, MediaPathVerdicts.ProbeOutcome.RECOVERED, 130, "http=204", true);

        assertFalse(mBook.isActive(V6_STALL, CELL_NEXT));
        mEnv.network = CELL;
        assertFalse(mBook.isActive(V6_STALL, CELL)); // one carrier verdict, cleared once
        assertTrue(mEnv.lines.stream().anyMatch(l -> l.startsWith("media-path probe kind=v6-stall"
                + " network=cell:114 scope=carrier host=" + CJOL + " role=recheck outcome=recovered")));
    }

    @Test
    public void aProbeResultForAnEarlierVerdictIsIgnored() {
        // Codex review P2: a probe claimed for one verdict must not clear the next one.
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        MediaPathVerdicts.ProbeTicket old = mBook.claimProbe(V6_STALL, CELL);
        mBook.clear(V6_STALL, CELL, "test");
        mEnv.episode = "ep=7 video=c";
        mBook.observe(V6_STALL, CELL, CJOL2, "v6-handshake-stall"); // re-learnt meanwhile

        mBook.onProbeResult(old, MediaPathVerdicts.ProbeOutcome.RECOVERED, 90, "http=204", true);

        assertTrue(mBook.isActive(V6_STALL, CELL));
        assertTrue(mEnv.lines.get(mEnv.lines.size() - 1).endsWith("applied=n reason=stale-verdict"));
    }

    @Test
    public void aMediaStallDuringTheProbeWinsOverItsAnswer() {
        mBook.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered");
        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        MediaPathVerdicts.ProbeTicket ticket = mBook.claimProbe(CRONET_STALL, CELL);
        mEnv.now += 2_000;
        mEnv.episode = "ep=9 video=d";
        mBook.observe(CRONET_STALL, CELL, CJOL2, "okhttp-answered"); // fresh real evidence

        mBook.onProbeResult(ticket, MediaPathVerdicts.ProbeOutcome.RECOVERED, 150, "http=204", true);

        assertTrue(mBook.isActive(CRONET_STALL, CELL));
        assertTrue(mEnv.lines.get(mEnv.lines.size() - 1).endsWith("applied=n reason=newer-evidence"));
    }

    @Test
    public void inconclusiveOrCrossNetworkProbesChangeNothing() {
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        MediaPathVerdicts.ProbeTicket ticket = mBook.claimProbe(V6_STALL, CELL);

        mBook.onProbeResult(ticket, MediaPathVerdicts.ProbeOutcome.INCONCLUSIVE, 30, "no-v6-address", true);
        mBook.onProbeResult(ticket, MediaPathVerdicts.ProbeOutcome.RECOVERED, 90, "http=204", false);

        assertTrue(mBook.isActive(V6_STALL, CELL));
        assertTrue(mEnv.lines.get(mEnv.lines.size() - 1).endsWith("applied=n reason=network-changed"));
    }

    @Test
    public void aVerdictWithoutAKnownHostIsNeverProbed() {
        mBook.observe(CRONET_STALL, CELL, "not-a-media-host.example", "okhttp-answered");
        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        assertTrue(mBook.isActive(CRONET_STALL, CELL));
        assertNull(mBook.claimProbe(CRONET_STALL, CELL));
    }

    @Test
    public void useIsReportedOnlyForAnActiveVerdict() {
        List<String> uses = new ArrayList<>();
        mBook.setUseListener((kind, network) -> uses.add(kind.label + "@" + network));
        mBook.noteUse(V6_STALL, CELL);
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mBook.noteUse(V6_STALL, CELL);
        mBook.noteUse(V6_STALL, WIFI);

        assertEquals(Collections.singletonList("v6-stall@cell:108"), uses);
    }

    // --- Pixel 2026-09-25/26: the overnight loss -----------------------------------------------

    @Test
    public void aProcessThatStartsOnWifiAppliesTheCarrierVerdictOnceItMovesToLte() {
        mBook.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered");
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.now += 40 * MIN;
        mEnv.network = WIFI; // the next process starts at home

        MediaPathVerdicts next = newProcessLoaded();

        String restore = mEnv.lines.get(mEnv.lines.size() - 1);
        assertTrue(restore, restore.startsWith("media-path restore records=2 stored=present"
                + " current=wifi:112 scope=attachment active=[] all=[carrier/cronet-stall:edges=1"));
        assertFalse(next.isActive(CRONET_STALL, WIFI));
        assertFalse(next.isActive(V6_STALL, WIFI));
        // ...then leaves home: a new cellular attachment, same SIM, same process.
        mEnv.network = CELL_NEXT;
        assertTrue(next.isActive(CRONET_STALL, CELL_NEXT));
        assertTrue(next.isActive(V6_STALL, CELL_NEXT));
        assertTrue(next.describe(CELL_NEXT).contains("restored=y"));
    }

    @Test
    public void anExploreRenewalIsPersistedAndOutlivesTheSingleEdgeLifetime() {
        // The r3e sequence: learn on cell:102 (one edge, 2 h) ...
        mEnv.network = "cell:102";
        mEnv.scopes.put("cell:102", MOVISTAR);
        mBook.observe(V6_STALL, "cell:102", CJOL, "v6-handshake-stall");
        mBook.observe(CRONET_STALL, "cell:102", CJOL, "okhttp-answered");
        // ... a new process on cell:104 restores it, RECHECKs rr4 (stalls, known edge) and
        // EXPLOREs rr8 (stalls: a second edge, applied=y) ...
        mEnv.now += 90_000;
        mEnv.network = "cell:104";
        mEnv.scopes.put("cell:104", MOVISTAR);
        MediaPathVerdicts b = newProcessLoaded();
        b.noteHost("cell:104", CJOL8);
        for (MediaPathVerdicts.Kind kind : MediaPathVerdicts.Kind.values()) {
            mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
            MediaPathVerdicts.ProbeTicket recheck = b.claimProbe(kind, "cell:104");
            b.onProbeResult(recheck, MediaPathVerdicts.ProbeOutcome.STALLED, 6_000, "timeout", true);
        }
        for (MediaPathVerdicts.Kind kind : MediaPathVerdicts.Kind.values()) {
            mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
            MediaPathVerdicts.ProbeTicket explore = b.claimProbe(kind, "cell:104");
            assertEquals(CJOL8, explore.host);
            b.onProbeResult(explore, MediaPathVerdicts.ProbeOutcome.STALLED, 6_000, "timeout", true);
        }
        assertTrue(b.describe("cell:104").contains(":count=2:edges=2"));
        // ... and the NEXT MORNING (13 h: long past the 2 h single-edge lifetime) a process on a
        // new attachment still restores both, two edges each.
        mEnv.now += 13 * 60 * MIN;
        mEnv.network = "cell:121";
        mEnv.scopes.put("cell:121", MOVISTAR);

        MediaPathVerdicts morning = newProcessLoaded();

        assertTrue(mStore.value.contains(CJOL + ";" + CJOL8));
        assertTrue(mEnv.lines.get(mEnv.lines.size() - 1).contains(
                "all=[carrier/cronet-stall:edges=2"));
        assertTrue(morning.isActive(CRONET_STALL, "cell:121"));
        assertTrue(morning.isActive(V6_STALL, "cell:121"));
    }

    @Test
    public void aTwoEdgeVerdictNeedsTwoAnsweringRechecksToGo() {
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.episode = "ep=2 video=b";
        mBook.observe(V6_STALL, CELL, CJOL8, "v6-handshake-stall");
        mEnv.now += MediaPathVerdicts.REPROBE_MS;
        MediaPathVerdicts.ProbeTicket first = mBook.claimProbe(V6_STALL, CELL);
        assertFalse(first.explore);

        mBook.onProbeResult(first, MediaPathVerdicts.ProbeOutcome.RECOVERED, 150, "http=204", true);

        assertTrue(mBook.isActive(V6_STALL, CELL)); // one answer: not yet
        assertTrue(mEnv.lines.get(mEnv.lines.size() - 1).endsWith(
                "applied=n reason=confirm-pending answered=1/2"));
        // The confirming RECHECK comes at the quick cadence, on the OTHER edge.
        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        MediaPathVerdicts.ProbeTicket second = mBook.claimProbe(V6_STALL, CELL);
        assertFalse(second.explore);
        assertFalse(first.host.equals(second.host));

        mBook.onProbeResult(second, MediaPathVerdicts.ProbeOutcome.RECOVERED, 140, "http=204", true);

        assertFalse(mBook.isActive(V6_STALL, CELL));
    }

    @Test
    public void theSameEdgeAnsweringTwiceNeverClearsATwoEdgeVerdict() {
        // Codex review: A answers, B cannot be probed (inconclusive), rotation returns to A.
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.episode = "ep=2 video=b";
        mBook.observe(V6_STALL, CELL, CJOL8, "v6-handshake-stall");
        mEnv.now += MediaPathVerdicts.REPROBE_MS;
        MediaPathVerdicts.ProbeTicket a = mBook.claimProbe(V6_STALL, CELL);
        assertEquals(CJOL, a.host);
        mBook.onProbeResult(a, MediaPathVerdicts.ProbeOutcome.RECOVERED, 150, "http=204", true);
        for (int i = 0; i < 3; i++) {
            mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
            MediaPathVerdicts.ProbeTicket next = mBook.claimProbe(V6_STALL, CELL);
            assertEquals(CJOL8, next.host); // only the edge that has not answered yet
            mBook.onProbeResult(next, MediaPathVerdicts.ProbeOutcome.INCONCLUSIVE, 30,
                    "no-v6-address", true);
        }
        // A answering again (e.g. a result that arrives late) cannot complete the pair.
        mBook.onProbeResult(a, MediaPathVerdicts.ProbeOutcome.RECOVERED, 150, "http=204", true);
        assertTrue(mBook.isActive(V6_STALL, CELL));
    }

    @Test
    public void aPendingClearSurvivesTheProcessAndCompletesOnTheOtherEdge() {
        mBook.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered");
        mEnv.episode = "ep=2 video=b";
        mBook.observe(CRONET_STALL, CELL, CJOL8, "okhttp-answered");
        mEnv.now += MediaPathVerdicts.REPROBE_MS;
        MediaPathVerdicts.ProbeTicket a = mBook.claimProbe(CRONET_STALL, CELL);
        mBook.onProbeResult(a, MediaPathVerdicts.ProbeOutcome.RECOVERED, 150, "http=204", true);
        mEnv.now += 5 * MIN; // the short session ends; the next one starts later

        MediaPathVerdicts next = newProcessLoaded();
        MediaPathVerdicts.ProbeTicket b = next.claimProbe(CRONET_STALL, CELL);
        assertEquals(CJOL8, b.host);
        next.onProbeResult(b, MediaPathVerdicts.ProbeOutcome.RECOVERED, 140, "http=204", true);

        assertFalse(next.isActive(CRONET_STALL, CELL));
        assertTrue(mEnv.lines.stream().anyMatch(l -> l.contains("reason=probe-answered host="
                + CJOL8 + " after=[" + CJOL + "]")));
    }

    @Test
    public void aV3SnapshotStillRestores() {
        long t = mEnv.now - MIN;
        mStore.value = "v3," + mEnv.bootCount + "," + mEnv.bootWallMs
                + "\n" + MOVISTAR + ",v6-stall," + t + "," + t + ",2," + CJOL + ";" + CJOL8;
        assertTrue(newProcessLoaded().isActive(V6_STALL, CELL));
    }

    @Test
    public void aStallBetweenTheTwoAnswersStartsTheConfirmationOver() {
        mBook.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered");
        mEnv.episode = "ep=2 video=b";
        mBook.observe(CRONET_STALL, CELL, CJOL8, "okhttp-answered");
        mEnv.now += MediaPathVerdicts.REPROBE_MS;
        MediaPathVerdicts.ProbeTicket a = mBook.claimProbe(CRONET_STALL, CELL);
        mBook.onProbeResult(a, MediaPathVerdicts.ProbeOutcome.RECOVERED, 150, "http=204", true);
        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        MediaPathVerdicts.ProbeTicket b = mBook.claimProbe(CRONET_STALL, CELL);
        mBook.onProbeResult(b, MediaPathVerdicts.ProbeOutcome.STALLED, 6_000, "timeout", true);
        mEnv.now += MediaPathVerdicts.REPROBE_MS;
        MediaPathVerdicts.ProbeTicket c = mBook.claimProbe(CRONET_STALL, CELL);

        mBook.onProbeResult(c, MediaPathVerdicts.ProbeOutcome.RECOVERED, 150, "http=204", true);

        assertTrue(mBook.isActive(CRONET_STALL, CELL)); // pending again, not cleared
    }

    @Test
    public void rechecksTakeTheStalledEdgesInTurn() {
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.episode = "ep=2 video=b";
        mBook.observe(V6_STALL, CELL, CJOL8, "v6-handshake-stall");
        List<String> rechecked = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            mEnv.now += MediaPathVerdicts.REPROBE_MS;
            MediaPathVerdicts.ProbeTicket ticket = mBook.claimProbe(V6_STALL, CELL);
            rechecked.add(ticket.host); // no used host noted: every claim is a RECHECK
            mBook.onProbeResult(ticket, MediaPathVerdicts.ProbeOutcome.STALLED, 6_000, "t", true);
        }
        assertEquals(java.util.Arrays.asList(CJOL, CJOL8, CJOL, CJOL8), rechecked);
    }

    @Test
    public void evictionSparesTheCarrierVerdictAndIsLogged() {
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall"); // the oldest scope
        for (int i = 0; i < MediaPathVerdicts.MAX_SCOPES; i++) {
            mEnv.now += MIN;
            mEnv.network = "wifi:" + (300 + i);
            mEnv.scopes.put(mEnv.network, mEnv.network + "/wlan0");
            mBook.observe(V6_STALL, mEnv.network, CJOL, "v6-handshake-stall");
        }
        assertTrue(mBook.scopes().contains(MOVISTAR));
        assertFalse(mBook.scopes().contains("wifi:300/wlan0"));
        assertTrue(mEnv.lines.stream().anyMatch(l -> l.startsWith(
                "media-path verdict off kind=v6-stall scope=attachment key=wifi:300/wlan0"
                        + " reason=evicted")));
    }

    @Test
    public void aRestoreKeepsTheCarrierWhenAttachmentsCompeteForTheSlots() {
        // Codex review: three stored attachments + the carrier, and this process already learnt
        // a fourth attachment before its load merged the snapshot.
        StringBuilder snapshot = new StringBuilder("v4," + mEnv.bootCount + "," + mEnv.bootWallMs);
        long t = mEnv.now - 10 * MIN;
        for (int i = 0; i < 3; i++) {
            snapshot.append("\nwifi:").append(400 + i).append("/wlan0,v6-stall,").append(t + i)
                    .append(',').append(t + i).append(",1,").append(CJOL).append(',');
        }
        snapshot.append("\n").append(MOVISTAR).append(",v6-stall,").append(t).append(',')
                .append(t).append(",1,").append(CJOL).append(',');
        mStore.value = snapshot.toString();
        MediaPathVerdicts next = newProcess();
        mEnv.network = "wifi:500";
        mEnv.scopes.put("wifi:500", "wifi:500/wlan0");
        next.observe(V6_STALL, "wifi:500", CJOL, "v6-handshake-stall"); // before its load

        next.load();

        assertTrue(next.scopes().contains(MOVISTAR));
        assertTrue(next.scopes().contains("wifi:500/wlan0")); // the fresh one outlives stored ones
        assertEquals(MediaPathVerdicts.MAX_SCOPES, next.scopes().size());
        assertTrue(mEnv.lines.stream().anyMatch(l -> l.contains("reason=evicted")));
        assertTrue(mStore.lastOff.contains(",attachment,1,evicted")); // named, and left behind
    }

    @Test
    public void anOldStoredAttachmentNeverEvictsANewerOneLearntBeforeTheLoad() {
        // Codex follow-up: three stored carriers + one OLD attachment; this process learnt a
        // NEWER attachment verdict before its load merged the snapshot.
        StringBuilder snapshot = new StringBuilder("v4," + mEnv.bootCount + "," + mEnv.bootWallMs);
        long t = mEnv.now - 30 * MIN;
        for (int i = 1; i <= 3; i++) {
            snapshot.append("\ncarrier:").append(i).append(":21407:21407,v6-stall,").append(t)
                    .append(',').append(t).append(",1,").append(CJOL).append(',');
        }
        snapshot.append("\nwifi:400/wlan0,v6-stall,").append(t).append(',').append(t)
                .append(",1,").append(CJOL).append(',');
        mStore.value = snapshot.toString();
        MediaPathVerdicts next = newProcess();
        mEnv.network = "wifi:500";
        mEnv.scopes.put("wifi:500", "wifi:500/wlan0");
        next.observe(V6_STALL, "wifi:500", CJOL, "v6-handshake-stall"); // newer, before load

        next.load();

        assertTrue(next.scopes().contains("wifi:500/wlan0"));
        assertFalse(next.scopes().contains("wifi:400/wlan0"));
        assertTrue(mEnv.lines.get(mEnv.lines.size() - 1).contains(
                "dropped=[overflow:v6-stall@attachment]"));
    }

    @Test
    public void aProbeResultIsNotAppliedAfterTheNetworkStartedRoaming() {
        // Codex follow-up: claimed while cell:108 was home; it roams before the result lands.
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        MediaPathVerdicts.ProbeTicket ticket = mBook.claimProbe(V6_STALL, CELL);
        mEnv.scopes.put(CELL, CELL + "/rmnet_data1"); // same attachment, now roaming

        mBook.onProbeResult(ticket, MediaPathVerdicts.ProbeOutcome.RECOVERED, 120, "http=204", true);

        assertTrue(mEnv.lines.get(mEnv.lines.size() - 1).endsWith("applied=n reason=scope-changed"));
        mEnv.scopes.put(CELL, MOVISTAR); // back home: the carrier verdict is untouched
        assertTrue(mBook.isActive(V6_STALL, CELL));
    }

    @Test
    public void onlyLiveCarriersLeftDeclineANewAttachmentButAnExpiredOneIsPrunedFirst() {
        for (int i = 0; i < MediaPathVerdicts.MAX_SCOPES; i++) {
            mEnv.now += MIN;
            mEnv.network = "cell:" + (600 + i);
            mEnv.scopes.put(mEnv.network, "carrier:" + (i + 1) + ":21407:21407");
            mBook.observe(V6_STALL, mEnv.network, CJOL, "v6-handshake-stall");
        }
        mEnv.network = WIFI;
        assertFalse(mBook.observe(V6_STALL, WIFI, CJOL, "v6-handshake-stall"));
        assertTrue(mEnv.lines.get(mEnv.lines.size() - 1).contains("reason=full-of-carriers"));
        assertTrue(mBook.scopes().contains("carrier:1:21407:21407"));

        // Once the oldest carrier has lapsed, it gives up its slot instead.
        mEnv.now += MediaPathVerdicts.SINGLE_EDGE_TTL_MS - 3 * MIN;
        assertTrue(mBook.observe(V6_STALL, WIFI, CJOL, "v6-handshake-stall"));
        assertFalse(mBook.scopes().contains("carrier:1:21407:21407"));
        assertTrue(mBook.scopes().contains("carrier:4:21407:21407"));
    }

    @Test
    public void aVerdictThatExpiresAtRestoreLeavesABreadcrumbToo() {
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.now += MediaPathVerdicts.SINGLE_EDGE_TTL_MS + MIN;

        newProcessLoaded(); // drops it as expired
        newProcessLoaded(); // ...and the next process can still tell why nothing is stored

        String restore = mEnv.lines.get(mEnv.lines.size() - 1);
        assertTrue(restore, restore.contains("stored=none"));
        assertTrue(restore, restore.contains("lastOff=v6-stall@carrier:edges=1:expired-at-restore"));
    }

    @Test
    public void aRemovalAndItsBreadcrumbAreOneWrite() {
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mStore.saves.clear();

        mBook.clear(V6_STALL, CELL, "test");

        assertEquals(Collections.singletonList("null|note"), mStore.saves);
    }

    @Test
    public void theNextProcessReportsWhatRemovedTheLastVerdict() {
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        MediaPathVerdicts.ProbeTicket ticket = mBook.claimProbe(V6_STALL, CELL);
        mBook.onProbeResult(ticket, MediaPathVerdicts.ProbeOutcome.RECOVERED, 140, "http=204", true);
        assertNull(mStore.value);
        mEnv.now += 10 * 60 * MIN;

        newProcessLoaded();

        assertEquals("media-path restore records=0 stored=none current=cell:108 scope=carrier"
                + " active=[] all=[] lastOff=v6-stall@carrier:edges=1:probe-answered_host=" + CJOL
                + ":agoMs=" + 10 * 60 * MIN, mEnv.lines.get(mEnv.lines.size() - 1));
    }

    @Test
    public void theBookIsBoundedToTheMostRecentlyRenewedScopes() {
        for (int i = 0; i < MediaPathVerdicts.MAX_SCOPES + 2; i++) {
            mEnv.now += MIN;
            mEnv.network = "wifi:" + (200 + i);
            mEnv.scopes.put(mEnv.network, mEnv.network + "/wlan0");
            mBook.observe(V6_STALL, mEnv.network, CJOL, "v6-handshake-stall");
        }
        assertEquals(MediaPathVerdicts.MAX_SCOPES, mBook.scopes().size());
        assertFalse(mBook.scopes().contains("wifi:200/wlan0"));
        assertTrue(mBook.isActive(V6_STALL, "wifi:205"));
        assertEquals(MediaPathVerdicts.MAX_SCOPES, newProcessLoaded().scopes().size());
    }

    @Test
    public void networksAndScopeKeysAreValidatedStrictly() {
        assertTrue(MediaPathVerdicts.isVerdictNetwork("cell:108"));
        assertTrue(MediaPathVerdicts.isVerdictNetwork("wifi:-1234"));
        assertTrue(MediaPathVerdicts.isVerdictNetwork("ethernet:7"));
        assertFalse(MediaPathVerdicts.isVerdictNetwork("vpn:108"));
        assertFalse(MediaPathVerdicts.isVerdictNetwork("cell:"));
        assertFalse(MediaPathVerdicts.isVerdictNetwork("cell:-"));
        assertFalse(MediaPathVerdicts.isVerdictNetwork("cell:1-2"));
        assertTrue(MediaPathVerdicts.isScopeKey(MOVISTAR));
        assertTrue(MediaPathVerdicts.isScopeKey("carrier:3:310260:310260"));
        assertTrue(MediaPathVerdicts.isScopeKey("wifi:112/wlan0"));
        assertTrue(MediaPathVerdicts.isScopeKey("cell:108/rmnet_data1"));
        assertFalse(MediaPathVerdicts.isScopeKey("carrier:1:2140:21407"));   // 4-digit PLMN
        assertFalse(MediaPathVerdicts.isScopeKey("carrier:-1:21407:21407"));
        assertFalse(MediaPathVerdicts.isScopeKey("carrier:1:21407"));
        assertFalse(MediaPathVerdicts.isScopeKey("vpn:9/tun0"));
        assertFalse(MediaPathVerdicts.isScopeKey("wifi:112"));               // no interface
        assertFalse(MediaPathVerdicts.isScopeKey("wifi:112/wlan0,x"));
        assertFalse(MediaPathVerdicts.isScopeKey(null));
    }

    // ------------------------------------------------------------------------------------------

    private MediaPathVerdicts newProcess() {
        return new MediaPathVerdicts(mEnv, true);
    }

    /** A new process whose background load has already run (the common case at first request). */
    private MediaPathVerdicts newProcessLoaded() {
        MediaPathVerdicts book = newProcess();
        book.load();
        return book;
    }

    {
        mBook.load(); // this test's first process: nothing stored yet
        mEnv.lines.clear(); // (its "restore records=0 stored=none" line)
    }

    static final class FakeStore {
        @Nullable volatile String value;
        @Nullable volatile String lastOff;
        /** Every save as "<snapshot or null>|<note or null>": one call = one transaction. */
        final List<String> saves = new CopyOnWriteArrayList<>();
    }

    static final class FakeEnv implements MediaPathVerdicts.Env {
        final FakeStore store;
        long now = 10 * 60 * MIN;
        @Nullable volatile String network = CELL;
        /** Scope per network id (cell:108 and cell:114: the same Movistar SIM). */
        final Map<String, String> scopes = new HashMap<>();
        volatile boolean direct = true;
        @Nullable volatile String episode = "ep=1 video=a";
        long bootCount = 42;
        long bootWallMs = 1_790_000_000_000L;
        final AtomicInteger storeReads = new AtomicInteger();
        final AtomicInteger bootCountReads = new AtomicInteger();
        final List<String> lines = new CopyOnWriteArrayList<>();

        FakeEnv(FakeStore store) {
            this.store = store;
            scopes.put(CELL, MOVISTAR);
            scopes.put(CELL_NEXT, MOVISTAR);
            scopes.put(WIFI, WIFI + "/wlan0");
        }

        @Override public long nowMs() {
            return now;
        }

        @Nullable @Override public String networkKey() {
            return network;
        }

        @Nullable @Override public String scope(String key) {
            return key.equals(network) ? scopes.get(key) : null;
        }

        @Override public boolean direct() {
            return direct;
        }

        @Nullable @Override public String episode() {
            return episode;
        }

        @Override public long bootCount() {
            bootCountReads.incrementAndGet();
            return bootCount;
        }

        @Override public long bootWallMs() {
            return bootWallMs;
        }

        @Nullable @Override public String load() {
            storeReads.incrementAndGet();
            return store.value;
        }

        @Override public void save(@Nullable String snapshot, @Nullable String lastOff) {
            store.value = snapshot;
            if (lastOff != null) {
                store.lastOff = lastOff;
            }
            store.saves.add((snapshot == null ? "null" : "snapshot") + "|"
                    + (lastOff == null ? "null" : "note"));
        }

        @Nullable @Override public String loadLastOff() {
            return store.lastOff;
        }

        @Override public void log(String line) {
            lines.add(line);
        }
    }
}
