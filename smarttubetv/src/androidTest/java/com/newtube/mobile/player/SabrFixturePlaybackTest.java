package com.newtube.mobile.player;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.os.SystemClock;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.SabrSubtitleSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.newtube.sabr.SabrFixtures;
import com.newtube.sabr.SabrMediaSource;
import com.newtube.mobile.player.TtffFixtureActivity.Snapshot;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

/** Real Pixel rendering. These synthetic-fixture results do not establish YouTube delivery. */
@RunWith(AndroidJUnit4.class)
public class SabrFixturePlaybackTest {
    @Test public void avcAndWebmRenderSeekPauseSwitchAndEnd() throws Exception {
        try (ActivityScenario<TtffFixtureActivity> scenario = ActivityScenario.launch(TtffFixtureActivity.class)) {
            await(scenario, s -> s.initialized, 10000);
            SabrFixtures avc = new SabrFixtures(name -> InstrumentationRegistry.getInstrumentation().getTargetContext()
                    .getAssets().open("sabr/" + name), false, true);
            scenario.onActivity(a -> a.openFixtureSource(source(avc), "sabr-avc"));
            awaitFrame(scenario, 1);
            report("avc-ready", snapshot(scenario), avc);
            await(scenario, s -> s.positionMs > 2300 && s.frames > 35, 6000);
            scenario.onActivity(TtffFixtureActivity::pauseFixture);
            scenario.onActivity(a -> a.seekFixture(7500));
            await(scenario, s -> s.state == Player.STATE_READY && s.positionMs >= 7500, 6000);
            SystemClock.sleep(500);
            assertFalse(snapshot(scenario).playing);
            assertTrue(Math.abs(snapshot(scenario).positionMs - 7500) < 100);
            scenario.onActivity(TtffFixtureActivity::resumeFixture);
            await(scenario, s -> s.playing && s.positionMs >= 8100, 4000);
            scenario.onActivity(a -> a.seekFixture(1000));
            await(scenario, s -> s.playing && s.state == Player.STATE_READY && s.positionMs >= 1500 && s.positionMs < 4000, 5000);
            report("avc-seeks-passed", snapshot(scenario), avc);
            assertTrue(avc.mediaRequests() >= 4);
            scenario.onActivity(a -> {
                for (Tracks.Group group : a.fixturePlayer().getCurrentTracks().getGroups()) {
                    if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
                    for (int index = 0; index < group.length; index++) {
                        if (group.getTrackFormat(index).width == 160) {
                            a.fixturePlayer().setTrackSelectionParameters(a.fixturePlayer().getTrackSelectionParameters()
                                    .buildUpon().addOverride(new TrackSelectionOverride(group.getMediaTrackGroup(), index)).build());
                        }
                    }
                }
            });
            await(scenario, s -> s.playing && s.width == 160 && s.positionMs > 2000, 5000);
            report("manual-quality-passed", snapshot(scenario), avc);

            SabrFixtures webm = fixture(true);
            scenario.onActivity(a -> a.openFixtureSource(source(webm), "sabr-webm"));
            awaitFrame(scenario, 2);
            report("webm-ready", snapshot(scenario), webm);
            await(scenario, s -> s.playing && s.positionMs >= 1800 && s.frames >= 25, 5000);
            scenario.onActivity(a -> a.seekFixture(10000));
            await(scenario, s -> s.state == Player.STATE_ENDED, 7000);
            report("webm-eos", snapshot(scenario), webm);
            assertTrue(webm.mediaRequests() >= 4);
        }
    }

    @Test public void subtitlesAreLazyAndSelectedCaptionsProduceCues() throws Exception {
        try (ActivityScenario<TtffFixtureActivity> scenario = ActivityScenario.launch(TtffFixtureActivity.class)) {
            await(scenario, s -> s.initialized, 10000);
            SabrFixtures fixture = fixture(false);
            AtomicInteger spanish = new AtomicInteger();
            AtomicInteger french = new AtomicInteger();
            AtomicInteger cues = new AtomicInteger();
            MediaSource media = new MergingMediaSource(source(fixture), subtitle("es", spanish), subtitle("fr", french));
            scenario.onActivity(a -> {
                a.fixturePlayer().setTrackSelectionParameters(a.fixturePlayer().getTrackSelectionParameters()
                        .buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
                a.fixturePlayer().addListener(new Player.Listener() {
                    @Override public void onCues(androidx.media3.common.text.CueGroup group) {
                        if (!group.cues.isEmpty()) cues.incrementAndGet();
                    }
                });
                a.openFixtureSource(media, "sabr-lazy-subtitles");
            });
            awaitFrame(scenario, 1);
            assertEquals(0, spanish.get());
            assertEquals(0, french.get());
            scenario.onActivity(a -> a.fixturePlayer().setTrackSelectionParameters(a.fixturePlayer()
                    .getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setPreferredTextLanguage("es").build()));
            await(scenario, s -> cues.get() > 0 && s.playing && s.frames > 20, 5000);
            assertEquals(1, spanish.get());
            assertEquals(0, french.get());
            report("lazy-captions-passed", snapshot(scenario), fixture);
        }
    }

    @Test public void replacingABlockedSourceClosesItsRequestsWithoutStalePlayback() throws Exception {
        try (ActivityScenario<TtffFixtureActivity> scenario = ActivityScenario.launch(TtffFixtureActivity.class)) {
            await(scenario, s -> s.initialized, 10000);
            SabrFixtures old = fixture(false);
            old.blockReads = true;
            scenario.onActivity(a -> a.openFixtureSource(source(old), "sabr-cancel-old"));
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (old.openCount.get() < 2 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
            assertEquals(2, old.openCount.get());
            SabrFixtures next = fixture(false);
            scenario.onActivity(a -> a.openFixtureSource(source(next), "sabr-cancel-new"));
            awaitFrame(scenario, 2);
            assertEquals(2, old.closedCount.get());
            assertEquals(2, old.openCount.get());
            assertEquals(0, old.mediaRequests());
            report("cancellation-passed", snapshot(scenario), next);
        }
    }

    @Test public void pairedLocalDashAndSabrFirstFrameComparison() throws Exception {
        try (ActivityScenario<TtffFixtureActivity> scenario = ActivityScenario.launch(TtffFixtureActivity.class)) {
            await(scenario, s -> s.initialized, 10000);
            for (int iteration = 0; iteration < 10; iteration++) {
                // ABBA ordering over five pairs reduces systematic decoder-warmth bias.
                boolean sabr = iteration % 4 == 1 || iteration % 4 == 2;
                SabrFixtures fixture = fixture(false);
                MediaSource selected = sabr ? source(fixture) : fixture.dashSource();
                String phase = (sabr ? "local-sabr-" : "local-dash-") + iteration;
                scenario.onActivity(a -> a.openFixtureSource(selected, phase));
                awaitFrame(scenario, iteration + 1);
                await(scenario, s -> s.positionMs >= 1200 && s.frames >= 20, 5000);
                report(phase, snapshot(scenario), fixture);
            }
        }
    }

    private static SabrFixtures fixture(boolean webm) throws Exception {
        return new SabrFixtures(name -> InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getAssets().open("sabr/" + name), webm);
    }
    private static MediaSource subtitle(String language, AtomicInteger opens) {
        byte[] bytes = "WEBVTT\n\n00:00.000 --> 00:12.000\nSABR caption fixture\n".getBytes(StandardCharsets.UTF_8);
        DataSource.Factory factory = () -> new DataSource() {
            final ByteArrayDataSource delegate = new ByteArrayDataSource(bytes);
            @Override public long open(DataSpec spec) throws IOException { opens.incrementAndGet(); return delegate.open(spec); }
            @Override public int read(byte[] data, int offset, int length) throws IOException { return delegate.read(data, offset, length); }
            @Override public void close() throws IOException { delegate.close(); }
            @Override public Uri getUri() { return delegate.getUri(); }
            @Override public void addTransferListener(TransferListener listener) { delegate.addTransferListener(listener); }
        };
        return SabrSubtitleSourceFactory.create(factory, new Format.Builder().setId(language)
                .setLanguage(language).setSampleMimeType(MimeTypes.TEXT_VTT).build(),
                Uri.parse("https://fixture.invalid/" + language + ".vtt"));
    }
    private static MediaSource source(SabrFixtures fixture) {
        return new SabrMediaSource(fixture.info, fixture, new DefaultBandwidthMeter.Builder(
                InstrumentationRegistry.getInstrumentation().getTargetContext()).build());
    }
    private static void awaitFrame(ActivityScenario<TtffFixtureActivity> scenario, int episode) {
        await(scenario, s -> s.episode == episode && s.firstFrameMs >= 0 && s.state == Player.STATE_READY
                && s.playing && s.frames >= 3, 12000);
        scenario.onActivity(TtffFixtureActivity::finishExpectedTransition);
    }
    private static void await(ActivityScenario<TtffFixtureActivity> scenario, Predicate<Snapshot> condition, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        Snapshot value;
        do {
            value = snapshot(scenario);
            assertEquals("player error: " + value.error, 0, value.errors);
            if (condition.test(value)) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("state=" + value.state + " position=" + value.positionMs + " frames=" + value.frames);
    }
    private static Snapshot snapshot(ActivityScenario<TtffFixtureActivity> scenario) {
        AtomicReference<Snapshot> result = new AtomicReference<>();
        scenario.onActivity(a -> result.set(a.snapshot()));
        return result.get();
    }
    private static void report(String phase, Snapshot state, SabrFixtures fixture) {
        Bundle result = new Bundle();
        result.putString("sabrPhase", phase);
        result.putBoolean("localFixtureOnly", true);
        result.putLong("firstFrameMs", state.firstFrameMs);
        result.putLong("readyMs", state.readyMs);
        result.putLong("positionMs", state.positionMs);
        result.putInt("frames", state.frames);
        result.putInt("width", state.width);
        result.putInt("height", state.height);
        result.putInt("dropped", state.dropped);
        result.putInt("rebuffers", state.unexpectedBuffering);
        result.putInt("requests", fixture.openCount.get());
        result.putLong("mediaTransportBytes", fixture.bytesRead.get());
        InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
    }
}
