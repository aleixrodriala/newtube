package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.NetworkCapabilities;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultAllocator;

import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowSystemProperties;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.robolectric.Shadows.shadowOf;

/**
 * The metered forward-buffer ceiling: the pure policy, and the wrapper around the REAL preset
 * DefaultLoadControl (no player, no network) - it may only turn "continue" into "hold" on a
 * metered link, and everything else must be the preset's own decision.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MeteredBufferLoadControlTest {
    private static final long S = 1_000_000L;
    private static final long HIGH_MAX_US = 75 * S;
    private static final PlayerId PLAYER = new PlayerId("metered-test");

    private final boolean[] metered = {true};
    private final long[] nowMs = {1_000_000L};
    private Media3PlayerInitializer initializer;
    private Timeline timeline;
    private MediaPeriodId periodId;
    private long positionUs;

    @Before
    public void setUp() {
        ShadowSystemProperties.override("debug.arc.start_buffer_ms", "");
        ShadowSystemProperties.override("debug.arc.rebuffer_gate_ms", "");
        initializer = new Media3PlayerInitializer(RuntimeEnvironment.getApplication());
        PlayerData.instance(RuntimeEnvironment.getApplication()).setVideoBufferType(PlayerData.BUFFER_HIGH);
        timeline = new SinglePeriodTimeline(600 * S, true, false, false, null,
                MediaItem.fromUri("https://media.invalid/generated.mpd"));
        periodId = new MediaPeriodId(timeline.getUidOfPeriod(0));
    }

    @After
    public void tearDown() {
        MeteredNetworkMonitor.setMeteredForTest(null);
    }

    // ---------------------------------------------------------------------------------
    // Pure policy
    // ---------------------------------------------------------------------------------

    @Test
    public void unmeteredNeverImposesACeiling() {
        for (boolean playing : new boolean[] {true, false}) {
            for (boolean rebuffering : new boolean[] {true, false}) {
                assertEquals(C.TIME_UNSET, MeteredBufferLoadControl.targetBufferUs(
                        false, playing, rebuffering, 0, 1f, HIGH_MAX_US));
                assertFalse(MeteredBufferLoadControl.shouldHold(
                        false, playing, rebuffering, 0, 1f, HIGH_MAX_US, 500 * S));
            }
        }
    }

    @Test
    public void meteredPlayingStartsAtThirtySecondsAndGrowsWithPlayedTimeUpToThePreset() {
        assertEquals(30 * S, target(true, false, 0, 1f, HIGH_MAX_US));
        assertEquals(40 * S, target(true, false, 10 * S, 1f, HIGH_MAX_US));
        assertEquals(75 * S, target(true, false, 45 * S, 1f, HIGH_MAX_US));
        assertEquals(75 * S, target(true, false, 3_600 * S, 1f, HIGH_MAX_US));
    }

    @Test
    public void meteredPausedHoldsAtTwentySeconds() {
        assertEquals(20 * S, target(false, false, 0, 1f, HIGH_MAX_US));
        assertEquals(20 * S, target(false, false, 600 * S, 1f, HIGH_MAX_US));
    }

    @Test
    public void rebufferingIsNeverCapped() {
        assertEquals(C.TIME_UNSET, target(true, true, 0, 1f, HIGH_MAX_US));
        assertEquals(C.TIME_UNSET, target(false, true, 0, 1f, HIGH_MAX_US));
    }

    @Test
    public void floorIsAWallClockCushionAboveNormalSpeed() {
        assertEquals(60 * S, target(true, false, 0, 2f, HIGH_MAX_US));
        assertEquals(75 * S, target(true, false, 0, 3f, HIGH_MAX_US));
        assertEquals(30 * S, target(true, false, 0, 0.5f, HIGH_MAX_US)); // never below 30 s
    }

    @Test
    public void neverBelowThirtySecondsWhilePlayingOnAnyPreset() {
        long[] presetMax = {30 * S, 50 * S, 75 * S, 120 * S};
        float[] speeds = {0.25f, 0.5f, 1f, 1.5f, 2f};
        for (long max : presetMax) {
            for (float speed : speeds) {
                for (long played = 0; played <= 200 * S; played += 7 * S) {
                    long target = target(true, false, played, speed, max);
                    assertTrue(target >= Math.min(30 * S, max));
                    assertTrue(target <= max);
                }
            }
            // The LOW preset (30 s max) is therefore untouched while playing.
            assertEquals(Math.min(30 * S, max), target(true, false, 0, 1f, max));
        }
    }

    @Test
    public void holdIsInclusiveAtTheTarget() {
        assertFalse(MeteredBufferLoadControl.shouldHold(true, true, false, 0, 1f, HIGH_MAX_US, 30 * S - 1));
        assertTrue(MeteredBufferLoadControl.shouldHold(true, true, false, 0, 1f, HIGH_MAX_US, 30 * S));
    }

    @Test
    @Config(sdk = 34)
    public void onlyAPositivelyMeteredCapabilitySetCountsAsMetered() {
        assertFalse(MeteredNetworkMonitor.isMetered(null));

        NetworkCapabilities cellular = ShadowNetworkCapabilities.newInstance();
        shadowOf(cellular).clearCapabilities();
        shadowOf(cellular).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        assertTrue(MeteredNetworkMonitor.isMetered(cellular));

        NetworkCapabilities wifi = ShadowNetworkCapabilities.newInstance();
        shadowOf(wifi).clearCapabilities();
        shadowOf(wifi).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        assertFalse(MeteredNetworkMonitor.isMetered(wifi));

        NetworkCapabilities unmetered5g = ShadowNetworkCapabilities.newInstance();
        shadowOf(unmetered5g).clearCapabilities();
        shadowOf(unmetered5g).addCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED);
        assertFalse(MeteredNetworkMonitor.isMetered(unmetered5g));
    }

    // ---------------------------------------------------------------------------------
    // Delegation around the real preset DefaultLoadControl
    // ---------------------------------------------------------------------------------

    @Test
    public void unmeteredDecisionsAreBitForBitThePresets() {
        metered[0] = false;
        DefaultLoadControl reference = initializer.createLoadControl();
        MeteredBufferLoadControl wrapped = wrap(initializer.createLoadControl());
        reference.onPrepared(PLAYER);
        wrapped.onPrepared(PLAYER);
        try {
            // Sweep up past max and back down past min so both hysteresis branches are exercised.
            for (long buffered = 0; buffered <= 80 * S; buffered += S / 2) {
                assertSameDecision(reference, wrapped, buffered, true);
            }
            for (long buffered = 80 * S; buffered >= 0; buffered -= S / 2) {
                assertSameDecision(reference, wrapped, buffered, true);
            }
            for (long buffered = 0; buffered <= 80 * S; buffered += S / 2) {
                assertSameDecision(reference, wrapped, buffered, false);
            }
        } finally {
            reference.onReleased(PLAYER);
            wrapped.onReleased(PLAYER);
        }
    }

    @Test
    public void meteredPlaybackHoldsAtThirtySecondsWhereThePresetWouldContinue() {
        DefaultLoadControl reference = initializer.createLoadControl();
        MeteredBufferLoadControl wrapped = wrap(initializer.createLoadControl());
        reference.onPrepared(PLAYER);
        wrapped.onPrepared(PLAYER);
        try {
            assertTrue(reference.shouldContinueLoading(params(PLAYER, 30 * S, true, false)));
            assertTrue(wrapped.shouldContinueLoading(params(PLAYER, 30 * S - 1, true, false)));
            assertFalse(wrapped.shouldContinueLoading(params(PLAYER, 30 * S, true, false)));
            // Draining below the target resumes loading at once (no gap below the floor).
            assertTrue(wrapped.shouldContinueLoading(params(PLAYER, 29 * S, true, false)));
        } finally {
            reference.onReleased(PLAYER);
            wrapped.onReleased(PLAYER);
        }
    }

    @Test
    public void meteredPauseHoldsAtTwentySecondsAndPlayResumesTheLargerTarget() {
        MeteredBufferLoadControl wrapped = wrap(initializer.createLoadControl());
        wrapped.onPrepared(PLAYER);
        try {
            assertTrue(wrapped.shouldContinueLoading(params(PLAYER, 19 * S, false, false)));
            assertFalse(wrapped.shouldContinueLoading(params(PLAYER, 20 * S, false, false)));
            assertTrue(wrapped.shouldContinueLoading(params(PLAYER, 20 * S, true, false)));
        } finally {
            wrapped.onReleased(PLAYER);
        }
    }

    @Test
    public void playedTimeRaisesTheCeilingButSeeksAndPausesDoNotCount() {
        MeteredBufferLoadControl wrapped = wrap(initializer.createLoadControl());
        wrapped.onPrepared(PLAYER);
        try {
            // 10 s of real playback, sampled every 500 ms like the loader's re-polls.
            wrapped.shouldContinueLoading(params(PLAYER, 10 * S, true, false));
            for (int i = 0; i < 20; i++) {
                play(500);
                wrapped.shouldContinueLoading(params(PLAYER, 10 * S, true, false));
            }
            assertTrue(wrapped.shouldContinueLoading(params(PLAYER, 39 * S, true, false)));
            assertFalse(wrapped.shouldContinueLoading(params(PLAYER, 40 * S, true, false)));

            // A 5-minute forward seek within 1 s of wall clock credits at most ~1.25 s.
            nowMs[0] += 1_000;
            positionUs += 300 * S;
            wrapped.shouldContinueLoading(params(PLAYER, 10 * S, true, false));
            assertFalse(wrapped.shouldContinueLoading(params(PLAYER, 42 * S, true, false)));

            // Ten paused minutes: the clock runs, the playhead does not.
            wrapped.shouldContinueLoading(params(PLAYER, 10 * S, false, false));
            nowMs[0] += 600_000;
            wrapped.shouldContinueLoading(params(PLAYER, 10 * S, true, false));
            assertFalse(wrapped.shouldContinueLoading(params(PLAYER, 42 * S, true, false)));
        } finally {
            wrapped.onReleased(PLAYER);
        }
    }

    @Test
    public void aNewPlaybackStartsBackAtTheFloor() {
        MeteredBufferLoadControl wrapped = wrap(initializer.createLoadControl());
        wrapped.onPrepared(PLAYER);
        try {
            wrapped.shouldContinueLoading(params(PLAYER, 10 * S, true, false));
            for (int i = 0; i < 40; i++) {
                play(500);
                wrapped.shouldContinueLoading(params(PLAYER, 10 * S, true, false));
            }
            assertTrue(wrapped.shouldContinueLoading(params(PLAYER, 45 * S, true, false)));

            wrapped.onStopped(PLAYER); // every open in this app is stop + prepare
            wrapped.onPrepared(PLAYER);
            positionUs = 0;
            assertFalse(wrapped.shouldContinueLoading(params(PLAYER, 30 * S, true, false)));
        } finally {
            wrapped.onReleased(PLAYER);
        }
    }

    @Test
    public void rebufferingAndThePreloadPlayerAreNeverCapped() {
        DefaultLoadControl reference = initializer.createLoadControl();
        MeteredBufferLoadControl wrapped = wrap(initializer.createLoadControl());
        reference.onPrepared(PLAYER);
        wrapped.onPrepared(PLAYER);
        reference.onPrepared(PlayerId.PRELOAD);
        wrapped.onPrepared(PlayerId.PRELOAD);
        try {
            assertTrue(wrapped.shouldContinueLoading(params(PLAYER, 40 * S, true, true)));
            for (long buffered = 0; buffered <= 10 * S; buffered += S / 2) {
                assertEquals(reference.shouldContinueLoading(params(PlayerId.PRELOAD, buffered, false, false)),
                        wrapped.shouldContinueLoading(params(PlayerId.PRELOAD, buffered, false, false)));
            }
        } finally {
            reference.onReleased(PLAYER);
            wrapped.onReleased(PLAYER);
            reference.onReleased(PlayerId.PRELOAD);
            wrapped.onReleased(PlayerId.PRELOAD);
        }
    }

    @Test
    public void startGatesBackBufferAndAllocatorArePureDelegation() {
        MeteredBufferLoadControl wrapped = wrap(initializer.createLoadControl());
        wrapped.onPrepared(PLAYER);
        try {
            // TTFF gate 500 ms and rebuffer gate 1500 ms, unchanged on a metered link.
            assertFalse(wrapped.shouldStartPlayback(params(PLAYER, 499_000, true, false)));
            assertTrue(wrapped.shouldStartPlayback(params(PLAYER, 500_000, true, false)));
            assertFalse(wrapped.shouldStartPlayback(params(PLAYER, 1_499_000, true, true)));
            assertTrue(wrapped.shouldStartPlayback(params(PLAYER, 1_500_000, true, true)));
            assertEquals(120 * S, wrapped.getBackBufferDurationUs(PLAYER));
            assertTrue(wrapped.retainBackBufferFromKeyframe(PLAYER));
        } finally {
            wrapped.onReleased(PLAYER);
        }
    }

    @Test
    public void everyOtherPlayerCallReachesTheDelegateUnchanged() {
        List<String> calls = new ArrayList<>();
        Allocator allocator = new DefaultAllocator(true, 65_536);
        LoadControl recorder = (LoadControl) Proxy.newProxyInstance(LoadControl.class.getClassLoader(),
                new Class<?>[] {LoadControl.class}, (proxy, method, args) -> {
                    calls.add(method.getName());
                    switch (method.getName()) {
                        case "getAllocator": return allocator;
                        case "getBackBufferDurationUs": return 7L;
                        case "retainBackBufferFromKeyframe": return true;
                        case "shouldStartPlayback": return true;
                        case "shouldContinuePreloading": return true;
                        case "shouldContinueLoading": return true;
                        default: return null;
                    }
                });
        MeteredBufferLoadControl wrapped = wrap(recorder);
        LoadControl.Parameters parameters = params(PLAYER, 30 * S, true, false);

        wrapped.onPrepared(PLAYER);
        wrapped.onTracksSelected(parameters, TrackGroupArray.EMPTY, new ExoTrackSelection[0]);
        assertSame(allocator, wrapped.getAllocator(PLAYER));
        assertEquals(7L, wrapped.getBackBufferDurationUs(PLAYER));
        assertTrue(wrapped.retainBackBufferFromKeyframe(PLAYER));
        assertTrue(wrapped.shouldStartPlayback(parameters));
        assertTrue(wrapped.shouldContinuePreloading(PLAYER, timeline, periodId, 0));
        assertFalse(wrapped.shouldContinueLoading(parameters)); // the one changed decision
        wrapped.onStopped(PLAYER);
        wrapped.onReleased(PLAYER);

        assertEquals(Arrays.asList("onPrepared", "onTracksSelected", "getAllocator",
                "getBackBufferDurationUs", "retainBackBufferFromKeyframe", "shouldStartPlayback",
                "shouldContinuePreloading", "shouldContinueLoading", "onStopped", "onReleased"), calls);
    }

    @Test
    public void thePlayersLoadControlIsTheMeteredWrapperOverTheSamePreset() {
        LoadControl control = initializer.createPlayerLoadControl();
        assertTrue(control instanceof MeteredBufferLoadControl);
        control.onPrepared(PLAYER);
        try {
            MeteredNetworkMonitor.setMeteredForTest(false);
            assertTrue(control.shouldContinueLoading(params(PLAYER, 49 * S, true, false)));
            assertTrue(control.shouldContinueLoading(params(PLAYER, 74 * S, true, false)));
            assertFalse(control.shouldContinueLoading(params(PLAYER, 75 * S, true, false)));
            assertTrue(control.shouldContinueLoading(params(PLAYER, 49 * S, true, false)));
            MeteredNetworkMonitor.setMeteredForTest(true);
            assertFalse(control.shouldContinueLoading(params(PLAYER, 30 * S, true, false)));
        } finally {
            control.onReleased(PLAYER);
        }
    }

    // ---------------------------------------------------------------------------------

    private static long target(boolean playing, boolean rebuffering, long playedUs, float speed,
            long presetMaxUs) {
        return MeteredBufferLoadControl.targetBufferUs(true, playing, rebuffering, playedUs, speed,
                presetMaxUs);
    }

    private MeteredBufferLoadControl wrap(LoadControl delegate) {
        return new MeteredBufferLoadControl(delegate, HIGH_MAX_US, () -> metered[0], () -> nowMs[0]);
    }

    private void play(long ms) {
        nowMs[0] += ms;
        positionUs += ms * 1000L;
    }

    private void assertSameDecision(DefaultLoadControl reference, LoadControl wrapped,
            long bufferedUs, boolean playWhenReady) {
        assertEquals("buffered=" + bufferedUs,
                reference.shouldContinueLoading(params(PLAYER, bufferedUs, playWhenReady, false)),
                wrapped.shouldContinueLoading(params(PLAYER, bufferedUs, playWhenReady, false)));
    }

    private LoadControl.Parameters params(PlayerId playerId, long bufferedUs, boolean playWhenReady,
            boolean rebuffering) {
        return new LoadControl.Parameters(playerId, timeline, periodId, positionUs, bufferedUs, 1f,
                playWhenReady, rebuffering, C.TIME_UNSET, C.TIME_UNSET);
    }
}
