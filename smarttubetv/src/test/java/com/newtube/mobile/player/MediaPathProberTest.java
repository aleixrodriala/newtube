package com.newtube.mobile.player;

import static com.newtube.mobile.player.MediaPathVerdicts.Kind.CRONET_STALL;
import static com.newtube.mobile.player.MediaPathVerdicts.Kind.V6_STALL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** NEWTUBE(media-path): the background re-probe - claimed on use, applied only when it means something. */
public class MediaPathProberTest {
    private static final String CELL = "cell:108";
    private static final String CJOL = "rr4---sn-uxax4vopj5xn-cjol.googlevideo.com";

    private final MediaPathVerdictsTest.FakeEnv mEnv =
            new MediaPathVerdictsTest.FakeEnv(new MediaPathVerdictsTest.FakeStore());
    private final MediaPathVerdicts mBook = new MediaPathVerdicts(mEnv, false);
    private final List<String> mProbed = new ArrayList<>();
    private final List<Long> mDelays = new ArrayList<>();
    private final List<Runnable> mPending = new ArrayList<>();
    /** Captures scheduled probes; the test runs them by hand. */
    private final ScheduledThreadPoolExecutor mExecutor = new ScheduledThreadPoolExecutor(1) {
        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            mDelays.add(unit.toMillis(delay));
            mPending.add(command);
            return null;
        }
    };
    private MediaPathProber.Result mNextResult =
            MediaPathProber.Result.stalled(6_000, "timeout");

    @After
    public void tearDown() {
        mExecutor.shutdownNow();
    }

    @Test
    public void aDueVerdictIsProbedOnceOffTheOpenAndAStallKeepsIt() {
        MediaPathProber prober = newProber();
        mBook.setUseListener(prober);
        mBook.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered");

        mBook.noteUse(CRONET_STALL, CELL); // same open: not due yet
        assertTrue(mPending.isEmpty());

        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        mBook.noteUse(CRONET_STALL, CELL);
        mBook.noteUse(CRONET_STALL, CELL); // audio + video sources: one probe
        assertEquals(Collections.singletonList(MediaPathProber.PROBE_DELAY_MS), mDelays);

        runPending();
        assertEquals(Collections.singletonList("cronet:" + CJOL), mProbed);
        // Still stalling: kept - but the same edge never renews the verdict by itself.
        assertTrue(mBook.isActive(CRONET_STALL, CELL));
        assertTrue(mEnv.lines.get(mEnv.lines.size() - 1).endsWith("applied=n reason=known-edge"));
    }

    @Test
    public void anAnswerWithinTheBudgetClearsTheVerdict() {
        MediaPathProber prober = newProber();
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        mNextResult = MediaPathProber.Result.answered(180, "http=204");

        prober.onVerdictUsed(V6_STALL, CELL);
        runPending();

        assertEquals(Collections.singletonList("v6:" + CJOL), mProbed);
        assertFalse(mBook.isActive(V6_STALL, CELL));
    }

    @Test
    public void aPendingClearGetsItsConfirmingRecheckScheduled() {
        // Codex review: after the first answer, the second RECHECK is scheduled, not merely
        // made eligible - a single long video may produce no further verdict use.
        MediaPathProber prober = newProber();
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.episode = "ep=2 video=b";
        mBook.observe(V6_STALL, CELL, "rr8---sn-uxax4vopj5xn-cjol.googlevideo.com",
                "v6-handshake-stall");
        mEnv.now += MediaPathVerdicts.REPROBE_MS;
        mNextResult = MediaPathProber.Result.answered(150, "http=204");

        prober.onVerdictUsed(V6_STALL, CELL);
        runPending(); // the first RECHECK answers: confirm-pending, follow-up scheduled
        assertEquals(java.util.Arrays.asList(MediaPathProber.PROBE_DELAY_MS,
                MediaPathVerdicts.FIRST_REPROBE_MS), mDelays);
        assertTrue(mBook.isActive(V6_STALL, CELL));

        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        runPending(); // the scheduled follow-up claims the other edge...
        runPending(); // ...whose RECHECK answers too
        assertEquals(java.util.Arrays.asList("v6:" + CJOL,
                "v6:rr8---sn-uxax4vopj5xn-cjol.googlevideo.com"), mProbed);
        assertFalse(mBook.isActive(V6_STALL, CELL));
    }

    @Test
    public void aSlowAnswerIsNotARecovery() {
        MediaPathProber.Result slow = MediaPathProber.Result.answered(
                MediaPathProber.RECOVERED_WITHIN_MS + 1, "http=204");
        assertEquals(MediaPathVerdicts.ProbeOutcome.INCONCLUSIVE, slow.outcome);
        assertEquals("slow-http=204", slow.detail);
        assertEquals(MediaPathVerdicts.ProbeOutcome.RECOVERED, MediaPathProber.Result.answered(
                MediaPathProber.RECOVERED_WITHIN_MS, "http=204").outcome);

        MediaPathProber prober = newProber();
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        mNextResult = slow;
        prober.onVerdictUsed(V6_STALL, CELL);
        runPending();

        assertTrue(mBook.isActive(V6_STALL, CELL));
    }

    @Test
    public void aHandoverBeforeOrDuringTheProbeAppliesNothing() {
        MediaPathProber prober = newProber();
        mBook.observe(V6_STALL, CELL, CJOL, "v6-handshake-stall");
        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        prober.onVerdictUsed(V6_STALL, CELL);
        mEnv.network = "wifi:112"; // left the cell before the probe ran
        runPending();
        assertTrue(mProbed.isEmpty());

        mEnv.network = CELL;
        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        mNextResult = MediaPathProber.Result.answered(90, "http=204");
        prober.onVerdictUsed(V6_STALL, CELL);
        mSwitchDuringProbe = "wifi:112"; // ...and during it: the answer came over Wi-Fi
        runPending();

        mEnv.network = CELL;
        assertEquals(1, mProbed.size());
        assertTrue(mBook.isActive(V6_STALL, CELL));
    }

    @Test
    public void aThrowingProbeIsInconclusive() {
        MediaPathProber prober = newProber();
        mBook.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered");
        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;
        mThrow = true;

        prober.onVerdictUsed(CRONET_STALL, CELL);
        runPending();

        assertTrue(mBook.isActive(CRONET_STALL, CELL));
        assertTrue(mEnv.lines.get(mEnv.lines.size() - 1).contains(
                "outcome=inconclusive elapsedMs=-1 detail=error-IllegalStateException"));
    }

    @Test
    public void aKindWithoutAProbeIsNeverClaimed() {
        Map<MediaPathVerdicts.Kind, MediaPathProber.Probe> onlyV6 =
                new EnumMap<>(MediaPathVerdicts.Kind.class);
        onlyV6.put(V6_STALL, host -> mNextResult);
        MediaPathProber prober = new MediaPathProber(mBook, () -> mEnv.network, mExecutor,
                onlyV6, MediaPathProber.PROBE_DELAY_MS);
        mBook.observe(CRONET_STALL, CELL, CJOL, "okhttp-answered");
        mEnv.now += MediaPathVerdicts.FIRST_REPROBE_MS;

        prober.onVerdictUsed(CRONET_STALL, CELL);

        assertTrue(mPending.isEmpty());
        // The claim was not spent either: a prober that can probe it still may.
        assertTrue(mBook.claimProbe(CRONET_STALL, CELL) != null);
    }

    private String mSwitchDuringProbe;
    private boolean mThrow;

    private MediaPathProber newProber() {
        Map<MediaPathVerdicts.Kind, MediaPathProber.Probe> probes =
                new EnumMap<>(MediaPathVerdicts.Kind.class);
        probes.put(CRONET_STALL, host -> probe("cronet:" + host));
        probes.put(V6_STALL, host -> probe("v6:" + host));
        return new MediaPathProber(mBook, () -> mEnv.network, mExecutor, probes,
                MediaPathProber.PROBE_DELAY_MS);
    }

    private MediaPathProber.Result probe(String what) {
        mProbed.add(what);
        if (mSwitchDuringProbe != null) {
            mEnv.network = mSwitchDuringProbe;
            mSwitchDuringProbe = null;
        }
        if (mThrow) {
            throw new IllegalStateException("probe bug");
        }
        return mNextResult;
    }

    private void runPending() {
        List<Runnable> pending = new ArrayList<>(mPending);
        mPending.clear();
        for (Runnable runnable : pending) {
            runnable.run();
        }
    }
}
