package com.newtube.mobile.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import com.liskovsoft.sharedutils.helpers.DeviceHelpers;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

/**
 * Media3 counterpart of {@code ExoPlayerInitializer} for the touch player. Because this class is
 * mobile-only, the flavor's tuning (which the legacy initializer received through static overrides
 * from {@code MobileMainApplication}) is baked in directly:
 *
 * <ul>
 *   <li>steady-state buffer sized by the "Video buffer" setting (see {@link #createPlayer};
 *       default = the BUFFER_HIGH preset that used to be hardcoded: 50s min / 75s max),</li>
 *   <li>start gate 1s first frame / 2.5s after rebuffer (TTFF fix),</li>
 *   <li>back-buffer 120s from keyframe with a RAM-clamped byte budget (backward-seek fix),</li>
 *   <li>ABR up-switch after 5s of stable buffer (the mobile tuning from the legacy round).</li>
 * </ul>
 */
public class Media3PlayerInitializer {

    // Video-buffer knob (Settings > Player > "Video buffer", PlayerData.getVideoBufferType()),
    // read at player CREATION - an engine restart applies a change, same semantics as the legacy
    // engine. ramCap = RAM/18 (196MB fallback), the pre-knob memory backstop.
    //
    //   preset    minBuf  maxBuf   byte target
    //   LOW        20s     30s     min( 48MB, ramCap/4)
    //   MEDIUM     50s     50s     min( 96MB, ramCap/2)   (min==max: stock ExoPlayer pattern)
    //   HIGH       50s     75s     min(192MB, ramCap)     <- pre-knob baked values = the default
    //   HIGHEST    50s    120s     min(288MB, ramCap*1.5 = RAM/12)
    //
    // LOW's 30s max sits below the standing 50s min, so its min scales down by the same 2/3
    // min:max ratio the HIGH pair uses (50/75). Start gate, back-buffer and the time-over-size
    // priority are shared by all presets.
    private static final int MIN_BUFFER_MS = 50_000;
    private static final int MAX_BUFFER_MS = 75_000;
    private static final int LOW_MIN_BUFFER_MS = 20_000;
    private static final int LOW_MAX_BUFFER_MS = 30_000;
    private static final int MEDIUM_MAX_BUFFER_MS = 50_000;
    private static final int HIGHEST_MAX_BUFFER_MS = 120_000;
    private static final int START_BUFFER_MS = 1_000;
    // 1500 beat the old 2500 in every pair of a 5-pair interleaved starve/refill A/B on the
    // Pixel 9 (pinned 1080p vp9, 800->2400kbps shaping): median stall 3.21s -> 1.71s. The gain
    // tracks the theoretical refill time of the removed 1000ms of media, so it generalizes.
    private static final int START_BUFFER_AFTER_REBUFFER_MS = 1_500;
    private static final int BACK_BUFFER_MS = 120_000;
    private static final int MB = 1024 * 1024;
    private static final int TARGET_BUFFER_BYTES = 192 * MB;
    private static final int LOW_TARGET_BUFFER_BYTES = 48 * MB;
    private static final int MEDIUM_TARGET_BUFFER_BYTES = 96 * MB;
    private static final int HIGHEST_TARGET_BUFFER_BYTES = 288 * MB;
    private static final int ABR_UP_SWITCH_MS = 5_000;

    // NEWTUBE(buffer-knob): one-time default alignment. Every pre-knob mobile build ran the baked
    // BUFFER_HIGH preset no matter what the stored pref said, and PlayerData's parse default is
    // MEDIUM - so an untouched install carries MEDIUM while having always experienced HIGH.
    // Promote that untouched/dead MEDIUM to HIGH exactly once, so wiring the knob changes nothing
    // for existing installs and the Settings radio finally reflects reality. Explicit picks made
    // after this build are never touched (the flag is already set).
    private static final String PLAYER_PREFS_NAME = "newtube_player";
    private static final String KEY_BUFFER_DEFAULT_ALIGNED = "buffer_default_aligned";

    private final Context mContext;
    private final int mMaxBufferBytes;

    public Media3PlayerInitializer(Context context) {
        mContext = context.getApplicationContext();

        long deviceRam = DeviceHelpers.getDeviceRam(mContext);
        // Same RAM clamp as the legacy initializer (and its negative-overflow guard).
        mMaxBufferBytes = deviceRam <= 0 ? 196_000_000 : (int) (deviceRam / 18);

        alignBufferDefaultOnce();
    }

    private void alignBufferDefaultOnce() {
        SharedPreferences prefs = mContext.getSharedPreferences(PLAYER_PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_BUFFER_DEFAULT_ALIGNED, false)) {
            PlayerData playerData = PlayerData.instance(mContext);
            if (playerData.getVideoBufferType() == PlayerData.BUFFER_MEDIUM) {
                playerData.setVideoBufferType(PlayerData.BUFFER_HIGH);
            }
            prefs.edit().putBoolean(KEY_BUFFER_DEFAULT_ALIGNED, true).apply();
        }
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
        // See the preset table above. Read afresh on every creation so an engine restart (error
        // fix, in-player buffer pick, next activity open) picks up the current setting.
        int bufferType = PlayerData.instance(mContext).getVideoBufferType();
        int minBufferMs;
        int maxBufferMs;
        int targetBufferBytes;
        String bufferName;
        switch (bufferType) {
            case PlayerData.BUFFER_LOW:
                minBufferMs = LOW_MIN_BUFFER_MS;
                maxBufferMs = LOW_MAX_BUFFER_MS;
                targetBufferBytes = Math.min(LOW_TARGET_BUFFER_BYTES, mMaxBufferBytes / 4);
                bufferName = "LOW";
                break;
            case PlayerData.BUFFER_MEDIUM:
                minBufferMs = MEDIUM_MAX_BUFFER_MS; // min==max, stock ExoPlayer pattern
                maxBufferMs = MEDIUM_MAX_BUFFER_MS;
                targetBufferBytes = Math.min(MEDIUM_TARGET_BUFFER_BYTES, mMaxBufferBytes / 2);
                bufferName = "MEDIUM";
                break;
            case PlayerData.BUFFER_HIGHEST:
                minBufferMs = MIN_BUFFER_MS;
                maxBufferMs = HIGHEST_MAX_BUFFER_MS;
                // Long math: RAM/12 can exceed Integer.MAX_VALUE on huge-RAM devices; min() first.
                targetBufferBytes = (int) Math.min(HIGHEST_TARGET_BUFFER_BYTES, mMaxBufferBytes * 3L / 2);
                bufferName = "HIGHEST";
                break;
            case PlayerData.BUFFER_HIGH:
            default: // unknown value -> today's behavior
                minBufferMs = MIN_BUFFER_MS;
                maxBufferMs = MAX_BUFFER_MS;
                targetBufferBytes = Math.min(TARGET_BUFFER_BYTES, mMaxBufferBytes);
                bufferName = "HIGH";
                break;
        }
        // NEWTUBE(loop-experiment): the post-rebuffer resume gate is runtime-flippable in debug
        // builds (adb shell setprop debug.arc.rebuffer_gate_ms 1500; engine restart applies it)
        // so the 2500-vs-1500 A/B runs on ONE build with everything else identical. Release
        // builds always use the constant.
        int startAfterRebufferMs = START_BUFFER_AFTER_REBUFFER_MS;
        if (com.liskovsoft.smartyoutubetv2.tv.BuildConfig.DEBUG) {
            startAfterRebufferMs =
                    DebugMediaShaper.propInt("debug.arc.rebuffer_gate_ms", startAfterRebufferMs);
        }

        android.util.Log.d("NetPath", "buffer=" + bufferName + " max=" + (maxBufferMs / 1000) + "s"
                + " min=" + (minBufferMs / 1000) + "s bytes=" + (targetBufferBytes / MB) + "MB"
                + (startAfterRebufferMs != START_BUFFER_AFTER_REBUFFER_MS
                        ? " rebuffer-gate=" + startAfterRebufferMs + "ms" : ""));

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        minBufferMs,
                        maxBufferMs,
                        START_BUFFER_MS,
                        startAfterRebufferMs)
                .setBackBuffer(BACK_BUFFER_MS, /* retainBackBufferFromKeyframe= */ true)
                .setTargetBufferBytes(targetBufferBytes)
                // The byte cap above is a memory BACKSTOP only: without this flag the loader stops
                // at the byte target even below the preset's min (on 2-3GB devices the RAM clamp
                // binds before the time target -> shorter real buffer -> more rebuffers). With it,
                // the time thresholds always win; the byte cap only guards pathological memory use.
                .setPrioritizeTimeOverSizeThresholds(true)
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
