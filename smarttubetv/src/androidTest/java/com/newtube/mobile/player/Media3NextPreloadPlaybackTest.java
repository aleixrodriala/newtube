package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.AssetDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.newtube.mobile.player.TtffFixtureActivity.Snapshot;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Actual shared-builder preload -> SurfaceView/codec handoff, using only bundled generated media.
 * HTTPS is logical load-control metadata; the ONLY data source is AssetDataSource. No source here
 * can open a network socket. These are correctness checks, not YouTube/autoplay TTFF measurements.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class Media3NextPreloadPlaybackTest {
    @Test
    public void boundedPreloadHandsReadableMediaToHardwareDecoderAndCancellationStopsReads() throws Exception {
        try (ActivityScenario<TtffFixtureActivity> scenario = ActivityScenario.launch(TtffFixtureActivity.class)) {
            Harness harness = begin(scenario);
            try {
                CountingAssetFactory bytes = new CountingAssetFactory(harness.context, false);
                MediaSource raw = source(bytes, "offline-next");
                scenario.onActivity(a -> harness.preloader.offer("next", raw));
                await(() -> onMain(scenario, () -> harness.preloader.isReady("next")), 15_000,
                        "bounded next-source samples");
                long loadedBytes = bytes.bytes.get();
                assertTrue("preload must read actual media bytes", loadedBytes > 0);
                long assetLength;
                try (android.content.res.AssetFileDescriptor asset = harness.context.getAssets()
                        .openFd("ttff-fixture.mp4")) {
                    assetLength = asset.getLength();
                }
                assertTrue("short preload must not download the entire 54s asset", loadedBytes < assetLength);
                SystemClock.sleep(300);
                assertEquals("completed preload must stop reading", loadedBytes, bytes.bytes.get());

                scenario.onActivity(a -> {
                    harness.preloader.onReset("next");
                    harness.player.stop();
                    harness.player.clearMediaItems();
                    MediaSource warmed = harness.preloader.take("next", raw);
                    assertNotNull("completed source must be available", warmed);
                    assertNotSame("handoff must use the real preload wrapper", raw, warmed);
                    harness.preloader.onReset("next");
                    a.openFixtureSource(warmed, "preloaded-handoff");
                    harness.preloader.onSourceOpened(warmed);
                });
                await(() -> ready(scenario, 2), 15_000, "preloaded hardware first frame");
                scenario.onActivity(TtffFixtureActivity::finishExpectedTransition);
                Snapshot handoff = snapshot(scenario);
                report("preload-handoff", handoff, loadedBytes);
                await(() -> {
                    Snapshot current = snapshot(scenario);
                    return current.playing && current.positionMs >= handoff.positionMs + 3_000
                            && current.frames >= handoff.frames + 60;
                }, 8_000, "decoded progress after sample handoff");
                assertHealthy(snapshot(scenario));

                // A slow local reader keeps a new attempt in flight long enough to cancel during
                // a foreground seek. No synthetic network denial or third-party request is used.
                await(() -> onMain(scenario, () -> !harness.player.isLoading()), 10_000,
                        "foreground no longer loading");
                CountingAssetFactory cancelledBytes = new CountingAssetFactory(harness.context, true);
                MediaSource cancelled = source(cancelledBytes, "offline-cancel");
                scenario.onActivity(a -> harness.preloader.offer("cancel", cancelled));
                await(() -> cancelledBytes.bytes.get() > 0, 10_000, "in-flight local preload read");
                scenario.onActivity(a -> {
                    harness.preloader.cancel("seek");
                    a.seekFixture(10_000);
                });
                await(() -> cancelledBytes.openSources.get() == 0, 5_000, "cancelled loader close");
                long afterCancel = cancelledBytes.bytes.get();
                SystemClock.sleep(500);
                assertEquals("cancel must stop further media reads", afterCancel, cancelledBytes.bytes.get());
                assertTrue("prepared raw stash must be discarded", harness.discarded.get() == cancelled);
                await(() -> {
                    Snapshot current = snapshot(scenario);
                    return current.playing && current.state == Player.STATE_READY
                            && current.positionMs >= 10_300 && current.positionMs < 16_000;
                }, 10_000, "foreground seek after preload cancellation");
                scenario.onActivity(TtffFixtureActivity::finishExpectedTransition);
                assertHealthy(snapshot(scenario));
                report("preload-cancelled", snapshot(scenario), afterCancel);
            } finally {
                scenario.onActivity(a -> harness.release());
            }
        }
    }

    @Test
    public void explicitQualityPinLeavesForegroundChoiceUntouchedAndDoesNotReadNextMedia() {
        try (ActivityScenario<TtffFixtureActivity> scenario = ActivityScenario.launch(TtffFixtureActivity.class)) {
            Harness harness = begin(scenario);
            try {
                CountingAssetFactory bytes = new CountingAssetFactory(harness.context, false);
                scenario.onActivity(a -> {
                    boolean pinned = false;
                    for (Tracks.Group group : harness.player.getCurrentTracks().getGroups()) {
                        if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
                        for (int index = 0; index < group.length; index++) {
                            if (!group.isTrackSelected(index)) continue;
                            harness.selector.setParameters(harness.selector.buildUponParameters()
                                    .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), index)));
                            pinned = true;
                            break;
                        }
                    }
                    assertTrue("fixture must expose a pinnable video track", pinned);
                    harness.preloader.offer("manual-next", source(bytes, "offline-manual-next"));
                });
                SystemClock.sleep(800);
                assertEquals("manual quality must not start speculative media", 0, bytes.bytes.get());
                assertTrue(onMain(scenario, () -> !harness.selector.getParameters().overrides.isEmpty()));
                assertHealthy(snapshot(scenario));
            } finally {
                scenario.onActivity(a -> harness.release());
            }
        }
    }

    private static Harness begin(ActivityScenario<TtffFixtureActivity> scenario) {
        await(() -> snapshot(scenario).initialized, 10_000, "fixture engine and surface");
        scenario.onActivity(a -> a.openFixture("preload-current"));
        await(() -> ready(scenario, 1), 15_000, "foreground first frame");
        scenario.onActivity(TtffFixtureActivity::finishExpectedTransition);
        AtomicReference<Harness> result = new AtomicReference<>();
        scenario.onActivity(a -> result.set(new Harness(a)));
        return result.get();
    }

    private static boolean ready(ActivityScenario<TtffFixtureActivity> scenario, int episode) {
        Snapshot value = snapshot(scenario);
        assertEquals("fixture playback error: " + value.error, 0, value.errors);
        return value.episode == episode && value.state == Player.STATE_READY && value.playing
                && value.firstFrameMs >= 0 && value.readyMs >= 0 && value.frames >= 3;
    }

    private static Snapshot snapshot(ActivityScenario<TtffFixtureActivity> scenario) {
        AtomicReference<Snapshot> result = new AtomicReference<>();
        scenario.onActivity(a -> result.set(a.snapshot()));
        return result.get();
    }

    private static boolean onMain(ActivityScenario<TtffFixtureActivity> scenario, BooleanSupplier check) {
        AtomicReference<Boolean> result = new AtomicReference<>();
        scenario.onActivity(a -> result.set(check.getAsBoolean()));
        return result.get();
    }

    private static void await(BooleanSupplier condition, long timeoutMs, String description) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Timed out waiting for " + description);
    }

    private static void assertHealthy(Snapshot snapshot) {
        assertEquals("unexpected fixture error: " + snapshot.error, 0, snapshot.errors);
        assertEquals("unplanned foreground rebuffer", 0, snapshot.unexpectedBuffering);
    }

    private static void report(String phase, Snapshot snapshot, long preloadBytes) {
        Bundle metrics = new Bundle();
        metrics.putString("fixturePhase", phase);
        metrics.putLong("preloadBytes", preloadBytes);
        metrics.putLong("firstFrameMs", snapshot.firstFrameMs);
        metrics.putLong("readyMs", snapshot.readyMs);
        metrics.putLong("positionMs", snapshot.positionMs);
        metrics.putInt("frames", snapshot.frames);
        metrics.putInt("errors", snapshot.errors);
        metrics.putInt("rebuffers", snapshot.unexpectedBuffering);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, metrics);
    }

    private static MediaSource source(DataSource.Factory data, String id) {
        return new ProgressiveMediaSource.Factory(data).createMediaSource(new MediaItem.Builder()
                .setMediaId(id).setUri("https://fixture.invalid/preload.mp4")
                .setMimeType(MimeTypes.VIDEO_MP4).build());
    }

    private static final class Harness implements Player.Listener {
        final Context context;
        final ExoPlayer player;
        final DefaultTrackSelector selector;
        final AtomicReference<MediaSource> discarded = new AtomicReference<>();
        final Media3NextPreloader preloader;

        Harness(TtffFixtureActivity activity) {
            context = activity.getApplicationContext();
            player = activity.fixturePlayer();
            selector = (DefaultTrackSelector) player.getTrackSelector();
            Media3PlayerInitializer initializer = activity.claimPreloadComponents();
            preloader = new Media3NextPreloader(initializer.getPreloadManagerBuilder(), selector,
                    initializer.getPreloadTrackSelector(), () -> player, discarded::set);
            player.addListener(this);
        }

        @Override public void onTracksChanged(Tracks tracks) {
            if (!tracks.getGroups().isEmpty()) preloader.onForegroundTracksChanged();
        }
        @Override public void onIsLoadingChanged(boolean loading) { preloader.update(); }
        @Override public void onPlaybackStateChanged(int state) { preloader.update(); }
        @Override public void onPlayWhenReadyChanged(boolean play, int reason) { preloader.update(); }
        void release() { player.removeListener(this); preloader.release(); }
    }

    /** This factory has no HTTP implementation: even an unexpected URI resolves only to the asset. */
    private static final class CountingAssetFactory implements DataSource.Factory {
        final AtomicLong bytes = new AtomicLong();
        final AtomicLong openSources = new AtomicLong();
        final Context context;
        final boolean slow;

        CountingAssetFactory(Context context, boolean slow) { this.context = context; this.slow = slow; }

        @Override public DataSource createDataSource() {
            return new DataSource() {
                final AssetDataSource asset = new AssetDataSource(context);
                boolean opened;
                @Override public void addTransferListener(TransferListener listener) { asset.addTransferListener(listener); }
                @Override public long open(DataSpec spec) throws IOException {
                    long length = asset.open(spec.withUri(Uri.parse("asset:///ttff-fixture.mp4")));
                    opened = true;
                    openSources.incrementAndGet();
                    return length;
                }
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (slow) SystemClock.sleep(5);
                    int read = asset.read(buffer, offset, slow ? Math.min(length, 4096) : length);
                    if (read > 0) bytes.addAndGet(read);
                    return read;
                }
                @Override public Uri getUri() { return asset.getUri(); }
                @Override public void close() throws IOException {
                    try {
                        asset.close();
                    } finally {
                        if (opened) openSources.decrementAndGet();
                        opened = false;
                    }
                }
            };
        }
    }
}
