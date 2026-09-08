package com.newtube.mobile.player;

import static org.junit.Assert.*;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.newtube.mobile.player.TtffFixtureActivity.Snapshot;
import com.newtube.sabr.FixtureLink;
import com.newtube.sabr.SabrFixtures;
import com.newtube.sabr.SabrMediaSource;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real Pixel decoders, synthetic server/link only. No Internet requests or persistent settings. */
@RunWith(AndroidJUnit4.class)
public class SabrShapedComparisonTest {
    @Test public void pairedStartupSteadyPlaybackAndSeeks() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("Explicit longer benchmark opt-in required",
                "true".equals(args.getString("allow_shaped_comparison")));
        assertEquals("Disable speculative background work", "1", DebugMediaShaper.prop("debug.arc.benchmark_fixture"));
        String profile = args.getString("profile", "moderate");
        int rate, latency;
        boolean interruption = false;
        switch (profile) {
            case "fast": rate = 20000; latency = 20; break;
            case "moderate": rate = 4000; latency = 80; break;
            case "constrained": rate = 1500; latency = 160; break;
            case "interruption": rate = 3000; latency = 80; interruption = true; break;
            default: throw new AssertionError("Unknown synthetic profile");
        }
        int opens = Integer.parseInt(args.getString("opens", "8"));
        assertTrue("Balanced, bounded ABBA blocks", opens >= 4 && opens <= 16 && opens % 4 == 0);
        final boolean injectInterruption = interruption;
        try (ActivityScenario<TtffFixtureActivity> scenario = ActivityScenario.launch(TtffFixtureActivity.class)) {
            await(scenario, s -> s.initialized, 10000);
            scenario.onActivity(a -> a.fixturePlayer().setTrackSelectionParameters(a.fixturePlayer()
                    .getTrackSelectionParameters().buildUpon().setMaxVideoSize(1280, 720)
                    .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264).setPreferredAudioMimeTypes(MimeTypes.AUDIO_AAC)
                    .setForceHighestSupportedBitrate(true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()));
            // Exclude the first engine/decoder initialization for BOTH source types.
            for (int warm = 0; warm < 2; warm++) {
                SabrFixtures fixture = fixture();
                MediaSource source = source(fixture, new FixtureLink(0, 20000), warm == 1);
                scenario.onActivity(a -> a.openFixtureSource(source, "comparison-warmup"));
                await(scenario, s -> s.playing && s.firstFrameMs >= 0 && s.frames >= 3, 12000);
            }
            scenario.onActivity(a -> a.fixturePlayer().stop());
            for (int iteration = 0; iteration < opens; iteration++) {
                boolean sabr = iteration % 4 == 1 || iteration % 4 == 2;
                String kind = sabr ? "sabr" : "dash";
                SabrFixtures fixture = fixture();
                FixtureLink link = new FixtureLink(latency, rate);
                MediaSource source = source(fixture, link, sabr);
                Telemetry telemetry = new Telemetry();
                long cpuStart = Process.getElapsedCpuTime();
                long wallStart = SystemClock.elapsedRealtime();
                scenario.onActivity(a -> {
                    a.fixturePlayer().addListener(telemetry);
                    a.openFixtureSource(source, profile + "-" + kind);
                });
                try {
                    await(scenario, s -> s.firstFrameMs >= 0 && s.playing && s.frames >= 3, 20000);
                    scenario.onActivity(TtffFixtureActivity::finishExpectedTransition);
                    long interruptionPosition = -1, interruptionBuffer = -1;
                    if (injectInterruption) {
                        await(scenario, s -> s.positionMs >= 2000, 15000);
                        Snapshot before = snapshot(scenario);
                        interruptionPosition = before.positionMs;
                        interruptionBuffer = before.bufferedPositionMs - before.positionMs;
                        link.pauseFor(6000);
                    }
                    await(scenario, s -> s.positionMs >= 8000 && s.frames >= 100, 45000);
                    Snapshot state = snapshot(scenario);
                    assertEquals("Matched resolution", 1280, state.width);
                    assertEquals("Matched resolution", 720, state.height);
                    Bundle metrics = base(profile, kind, iteration, rate, latency, link);
                    metrics.putLong("firstFrameMs", state.firstFrameMs);
                    metrics.putLong("readyMs", state.readyMs);
                    metrics.putLong("wallTo8sMs", SystemClock.elapsedRealtime() - wallStart);
                    metrics.putLong("processCpuMs", Process.getElapsedCpuTime() - cpuStart);
                    metrics.putLong("positionMs", state.positionMs);
                    metrics.putLong("bufferedPositionMs", state.bufferedPositionMs);
                    metrics.putInt("frames", state.frames);
                    metrics.putInt("dropped", state.dropped);
                    metrics.putInt("errors", state.errors);
                    scenario.onActivity(a -> {
                        metrics.putInt("rebuffers", telemetry.rebuffers);
                        metrics.putLong("rebufferMs", telemetry.rebufferMs);
                    });
                    metrics.putLong("interruptionPositionMs", interruptionPosition);
                    metrics.putLong("interruptionBufferMs", interruptionBuffer);
                    report("steady", metrics);
                    // First revisit retained samples, then seek far ahead. Record whether each
                    // target was buffered; SABR currently resets state even for a buffered seek.
                    seek(scenario, telemetry, link, profile, kind, iteration, rate, latency, 2000, "backward");
                    seek(scenario, telemetry, link, profile, kind, iteration, rate, latency, 52000, "forward");
                } catch (AssertionError failure) {
                    Bundle metrics = base(profile, kind, iteration, rate, latency, link);
                    Snapshot state = snapshot(scenario);
                    metrics.putString("error", state.error);
                    metrics.putInt("frames", state.frames);
                    metrics.putInt("state", state.state);
                    report("failed", metrics);
                    throw failure;
                } finally {
                    scenario.onActivity(a -> {
                        a.fixturePlayer().removeListener(telemetry);
                        a.fixturePlayer().stop();
                    });
                }
            }
        }
    }

    private static void seek(ActivityScenario<TtffFixtureActivity> scenario, Telemetry telemetry,
            FixtureLink link, String profile, String source, int iteration, int rate, int latency,
            long target, String direction) {
        Snapshot before = snapshot(scenario);
        long beforeBytes = link.responseBytes.get();
        int beforeRequests = link.requests.get();
        scenario.onActivity(a -> {
            a.pauseFixture();
            telemetry.seeking = true;
            telemetry.seekFrameMs = -1;
            telemetry.seekReadyMs = -1;
            telemetry.seekStartMs = SystemClock.elapsedRealtime();
            a.seekFixture(target);
            if (a.fixturePlayer().getPlaybackState() == Player.STATE_READY) telemetry.seekReadyMs = 0;
        });
        await(scenario, s -> s.state == Player.STATE_READY && !s.playing
                && s.positionMs >= target && telemetry.seekFrameMs >= 0, 20000);
        Bundle metrics = base(profile, source, iteration, rate, latency, link);
        metrics.putString("direction", direction);
        metrics.putLong("targetMs", target);
        metrics.putLong("fromPositionMs", before.positionMs);
        metrics.putLong("bufferedBeforeMs", before.bufferedPositionMs);
        metrics.putLong("seekResponseBytes", link.responseBytes.get() - beforeBytes);
        metrics.putInt("seekRequests", link.requests.get() - beforeRequests);
        metrics.putBoolean("remainedPaused", !snapshot(scenario).playing);
        scenario.onActivity(a -> {
            metrics.putLong("seekFirstFrameMs", telemetry.seekFrameMs);
            metrics.putLong("seekReadyMs", telemetry.seekReadyMs);
            a.finishExpectedTransition();
            a.resumeFixture();
        });
        await(scenario, s -> s.playing && s.positionMs >= target + 1000 && s.positionMs < target + 4000, 15000);
        metrics.putBoolean("resumedAndAdvanced", true);
        report("seek", metrics);
    }

    private static SabrFixtures fixture() throws Exception {
        return SabrFixtures.comparison720p(name -> InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open("sabr-comparison/" + name));
    }
    private static MediaSource source(SabrFixtures fixture, FixtureLink link, boolean sabr) throws Exception {
        if (!sabr) return fixture.dashSource(link::wrap);
        return new SabrMediaSource(fixture.info, link.wrap(fixture), new DefaultBandwidthMeter.Builder(
                InstrumentationRegistry.getInstrumentation().getTargetContext()).build());
    }
    private static Bundle base(String profile, String source, int iteration, int rate, int latency, FixtureLink link) {
        Bundle result = new Bundle();
        result.putBoolean("syntheticLinkOnly", true);
        result.putString("profile", profile);
        result.putString("source", source);
        result.putInt("iteration", iteration);
        result.putInt("sharedDownlinkKbps", rate);
        result.putInt("requestLatencyMs", latency);
        result.putLong("responseBytes", link.responseBytes.get());
        result.putLong("requestBodyBytes", link.requestBodyBytes.get());
        result.putInt("requests", link.requests.get());
        return result;
    }
    private static void report(String phase, Bundle metrics) {
        metrics.putString("comparisonPhase", phase);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, metrics);
    }
    private static Snapshot snapshot(ActivityScenario<TtffFixtureActivity> scenario) {
        AtomicReference<Snapshot> state = new AtomicReference<>();
        scenario.onActivity(a -> state.set(a.snapshot()));
        return state.get();
    }
    private static void await(ActivityScenario<TtffFixtureActivity> scenario, Predicate<Snapshot> condition, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        Snapshot state;
        do {
            state = snapshot(scenario);
            assertEquals("Playback failed: " + state.error, 0, state.errors);
            if (condition.test(state)) return;
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Fixture milestone missing: state=" + state.state + " frames=" + state.frames
                + " position=" + state.positionMs + " buffered=" + state.bufferedPositionMs);
    }

    private static final class Telemetry implements Player.Listener {
        boolean started, seeking;
        int rebuffers;
        long bufferStartMs = -1, rebufferMs;
        long seekStartMs;
        volatile long seekFrameMs = -1, seekReadyMs = -1;
        @Override public void onPlaybackStateChanged(int state) {
            long now = SystemClock.elapsedRealtime();
            if (state == Player.STATE_READY) {
                if (bufferStartMs >= 0) { rebufferMs += now - bufferStartMs; bufferStartMs = -1; }
                started = true;
                if (seeking && seekReadyMs < 0) seekReadyMs = now - seekStartMs;
            } else if (state == Player.STATE_BUFFERING && started && !seeking) {
                rebuffers++;
                bufferStartMs = now;
            } else if (state == Player.STATE_BUFFERING && seeking) {
                seekReadyMs = -1;
            }
        }
        @Override public void onRenderedFirstFrame() {
            if (seeking && seekFrameMs < 0) seekFrameMs = SystemClock.elapsedRealtime() - seekStartMs;
        }
    }
}
