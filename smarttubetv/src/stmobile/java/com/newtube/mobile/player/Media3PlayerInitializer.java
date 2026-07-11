package com.newtube.mobile.player;

import android.content.Context;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import com.liskovsoft.sharedutils.helpers.DeviceHelpers;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

/**
 * Media3 counterpart of {@code ExoPlayerInitializer} for the touch player. Because this class is
 * mobile-only, the flavor's tuning (which the legacy initializer received through static overrides
 * from {@code MobileMainApplication}) is baked in directly:
 *
 * <ul>
 *   <li>steady-state buffer 50s min / 75s max (BUFFER_HIGH preset + the forward-seek widening),</li>
 *   <li>start gate 1s first frame / 2.5s after rebuffer (TTFF fix),</li>
 *   <li>back-buffer 120s from keyframe with a RAM-clamped byte budget (backward-seek fix),</li>
 *   <li>ABR up-switch after 5s of stable buffer (the mobile tuning from the legacy round).</li>
 * </ul>
 */
public class Media3PlayerInitializer {

    private static final int MIN_BUFFER_MS = 50_000;
    private static final int MAX_BUFFER_MS = 75_000;
    private static final int START_BUFFER_MS = 1_000;
    private static final int START_BUFFER_AFTER_REBUFFER_MS = 2_500;
    private static final int BACK_BUFFER_MS = 120_000;
    private static final int TARGET_BUFFER_BYTES = 192 * 1024 * 1024;
    private static final int ABR_UP_SWITCH_MS = 5_000;

    private final Context mContext;
    private final int mMaxBufferBytes;

    public Media3PlayerInitializer(Context context) {
        mContext = context.getApplicationContext();

        long deviceRam = DeviceHelpers.getDeviceRam(mContext);
        // Same RAM clamp as the legacy initializer (and its negative-overflow guard).
        mMaxBufferBytes = deviceRam <= 0 ? 196_000_000 : (int) (deviceRam / 18);
    }

    public DefaultTrackSelector createTrackSelector() {
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(
                mContext,
                new AdaptiveTrackSelection.Factory(
                        ABR_UP_SWITCH_MS,
                        AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
                        AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
                        AdaptiveTrackSelection.DEFAULT_BANDWIDTH_FRACTION));

        // 1080p Auto ceiling, mobile default; an explicit user pick overrides it (the track
        // adapter lifts the constraints when pinning a track).
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setMaxVideoSize(1920, 1080)
                .setExceedVideoConstraintsIfNecessary(true));

        return trackSelector;
    }

    public ExoPlayer createPlayer(DefaultTrackSelector trackSelector, DefaultBandwidthMeter bandwidthMeter) {
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        MIN_BUFFER_MS,
                        MAX_BUFFER_MS,
                        START_BUFFER_MS,
                        START_BUFFER_AFTER_REBUFFER_MS)
                .setBackBuffer(BACK_BUFFER_MS, /* retainBackBufferFromKeyframe= */ true)
                .setTargetBufferBytes(Math.min(TARGET_BUFFER_BYTES, mMaxBufferBytes))
                .build();

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(mContext)
                // A blacklisted/failed primary decoder falls back to another instead of erroring
                // (replaces the legacy BlacklistMediaCodecSelector's job for the common cases).
                .setEnableDecoderFallback(true);

        ExoPlayer player = new ExoPlayer.Builder(mContext)
                .setRenderersFactory(renderersFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setBandwidthMeter(bandwidthMeter)
                .build();

        setupAudio(player);

        return player;
    }

    private void setupAudio(ExoPlayer player) {
        if (PlayerTweaksData.instance(mContext).isAudioFocusEnabled()) {
            try {
                player.setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                                .build(),
                        /* handleAudioFocus= */ true);
            } catch (SecurityException e) { // uid not allowed to perform TAKE_AUDIO_FOCUS
                e.printStackTrace();
            }
        }

        // Pause when headphones unplug / bluetooth drops - standard phone behavior.
        player.setHandleAudioBecomingNoisy(true);
    }
}
