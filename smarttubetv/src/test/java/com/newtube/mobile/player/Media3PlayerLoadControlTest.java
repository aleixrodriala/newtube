package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;

import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowSystemProperties;

/** Uses Media3's real startup/rebuffer/loading decisions, without a player or network requests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class Media3PlayerLoadControlTest {
    private static final String START_PROPERTY = "debug.arc.start_buffer_ms";
    private static final PlayerId PLAYER = new PlayerId("load-control-test");
    private Media3PlayerInitializer initializer;
    private PlayerData preferences;
    private Timeline timeline;
    private MediaPeriodId periodId;

    @Before
    public void setUp() {
        ShadowSystemProperties.override(START_PROPERTY, "");
        ShadowSystemProperties.override("debug.arc.rebuffer_gate_ms", "");
        initializer = new Media3PlayerInitializer(RuntimeEnvironment.getApplication());
        preferences = PlayerData.instance(RuntimeEnvironment.getApplication());
        preferences.setVideoBufferType(PlayerData.BUFFER_HIGH);
        // Generated DASH uses an HTTPS media item even when a media byte range is disk-cached.
        timeline = new SinglePeriodTimeline(600_000_000L, true, false, false, null,
                MediaItem.fromUri("https://media.invalid/generated.mpd"));
        periodId = new MediaPeriodId(timeline.getUidOfPeriod(0));
    }

    @Test
    public void initialReadinessStartsAtHalfSecondOfMedia() {
        DefaultLoadControl control = initializer.createLoadControl();
        assertFalse(control.shouldStartPlayback(parameters(499_000, 1f, false, C.TIME_UNSET)));
        assertTrue(control.shouldStartPlayback(parameters(500_000, 1f, false, C.TIME_UNSET)));
    }

    @Test
    public void rebufferStillNeedsOneAndAHalfSeconds() {
        DefaultLoadControl control = initializer.createLoadControl();
        assertFalse(control.shouldStartPlayback(parameters(500_000, 1f, true, C.TIME_UNSET)));
        assertFalse(control.shouldStartPlayback(parameters(1_499_000, 1f, true, C.TIME_UNSET)));
        assertTrue(control.shouldStartPlayback(parameters(1_500_000, 1f, true, C.TIME_UNSET)));
    }

    @Test
    public void fasterPlaybackRequiresEnoughMediaForTheSameWallClockCushion() {
        DefaultLoadControl control = initializer.createLoadControl();
        assertFalse(control.shouldStartPlayback(parameters(999_000, 2f, false, C.TIME_UNSET)));
        assertTrue(control.shouldStartPlayback(parameters(1_000_000, 2f, false, C.TIME_UNSET)));
        assertFalse(control.shouldStartPlayback(parameters(2_999_000, 2f, true, C.TIME_UNSET)));
        assertTrue(control.shouldStartPlayback(parameters(3_000_000, 2f, true, C.TIME_UNSET)));
    }

    @Test
    public void normalLiveOffsetUsesTheSameInitialAndRecoveryGates() {
        DefaultLoadControl control = initializer.createLoadControl();
        assertFalse(control.shouldStartPlayback(parameters(499_000, 1f, false, 15_000_000)));
        assertTrue(control.shouldStartPlayback(parameters(500_000, 1f, false, 15_000_000)));
        assertFalse(control.shouldStartPlayback(parameters(1_499_000, 1f, true, 15_000_000)));
        assertTrue(control.shouldStartPlayback(parameters(1_500_000, 1f, true, 15_000_000)));
    }

    @Test
    public void veryShortLiveOffsetRetainsMedia3sHalfOffsetLimit() {
        DefaultLoadControl control = initializer.createLoadControl();
        assertFalse(control.shouldStartPlayback(parameters(299_000, 1f, false, 600_000)));
        assertTrue(control.shouldStartPlayback(parameters(300_000, 1f, false, 600_000)));
        assertFalse(control.shouldStartPlayback(parameters(299_000, 1f, true, 600_000)));
        assertTrue(control.shouldStartPlayback(parameters(300_000, 1f, true, 600_000)));
    }

    @Test
    public void allForwardBufferPresetsKeepTheirRefillAndStopThresholds() {
        assertForwardBufferBand(PlayerData.BUFFER_LOW, 20_000_000, 30_000_000);
        assertForwardBufferBand(PlayerData.BUFFER_MEDIUM, 50_000_000, 50_000_000);
        assertForwardBufferBand(PlayerData.BUFFER_HIGH, 50_000_000, 75_000_000);
        assertForwardBufferBand(PlayerData.BUFFER_HIGHEST, 50_000_000, 120_000_000);
    }

    @Test
    public void backwardSeekBufferStillRetainsTwoMinutesFromKeyframe() {
        DefaultLoadControl control = initializer.createLoadControl();
        assertEquals(120_000_000, control.getBackBufferDurationUs(PLAYER));
        assertTrue(control.retainBackBufferFromKeyframe(PLAYER));
    }

    @Test
    public void quarterSecondDebugCandidateOnlyChangesInitialReadiness() {
        ShadowSystemProperties.override(START_PROPERTY, "250");
        DefaultLoadControl control = initializer.createLoadControl();
        assertFalse(control.shouldStartPlayback(parameters(249_000, 1f, false, C.TIME_UNSET)));
        assertTrue(control.shouldStartPlayback(parameters(250_000, 1f, false, C.TIME_UNSET)));
        assertFalse(control.shouldStartPlayback(parameters(1_499_000, 1f, true, C.TIME_UNSET)));
        assertTrue(control.shouldStartPlayback(parameters(1_500_000, 1f, true, C.TIME_UNSET)));
    }

    @Test
    public void oldOneSecondGateRemainsAvailableForSameBuildComparison() {
        ShadowSystemProperties.override(START_PROPERTY, "1000");
        DefaultLoadControl control = initializer.createLoadControl();
        assertFalse(control.shouldStartPlayback(parameters(999_000, 1f, false, C.TIME_UNSET)));
        assertTrue(control.shouldStartPlayback(parameters(1_000_000, 1f, false, C.TIME_UNSET)));
    }

    @Test
    public void malformedDebugValueFallsBackToProductionReadiness() {
        ShadowSystemProperties.override(START_PROPERTY, "invalid");
        DefaultLoadControl control = initializer.createLoadControl();
        assertFalse(control.shouldStartPlayback(parameters(499_000, 1f, false, C.TIME_UNSET)));
        assertTrue(control.shouldStartPlayback(parameters(500_000, 1f, false, C.TIME_UNSET)));
    }

    private void assertForwardBufferBand(int preset, long minUs, long maxUs) {
        preferences.setVideoBufferType(preset);
        DefaultLoadControl control = initializer.createLoadControl();
        control.onPrepared(PLAYER);
        try {
            assertTrue(control.shouldContinueLoading(parameters(minUs - 1, 1f, false, C.TIME_UNSET)));
            assertTrue(control.shouldContinueLoading(parameters(maxUs - 1, 1f, false, C.TIME_UNSET)));
            assertFalse(control.shouldContinueLoading(parameters(maxUs, 1f, false, C.TIME_UNSET)));
            assertFalse(control.shouldContinueLoading(parameters(minUs, 1f, false, C.TIME_UNSET)));
            assertTrue(control.shouldContinueLoading(parameters(minUs - 1, 1f, false, C.TIME_UNSET)));
        } finally {
            control.onReleased(PLAYER);
        }
    }

    private LoadControl.Parameters parameters(long bufferedUs, float speed, boolean rebuffering,
            long targetLiveOffsetUs) {
        return new LoadControl.Parameters(PLAYER, timeline, periodId, 0, bufferedUs, speed, true,
                rebuffering, targetLiveOffsetUs, C.TIME_UNSET);
    }
}
