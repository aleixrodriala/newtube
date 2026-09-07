package com.newtube.mobile.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.BandwidthMeter;

import com.liskovsoft.sharedutils.helpers.DeviceHelpers;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
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
 *   <li>start gate 0.5s first frame / 1.5s after rebuffer (TTFF fix),</li>
 *   <li>back-buffer 120s from keyframe with a RAM-clamped byte budget (backward-seek fix),</li>
 *   <li>ABR up-switch after 5s of stable buffer (the mobile tuning from the legacy round), paired
 *       with a down-switch window scaled to the chosen buffer preset (see
 *       {@link #createTrackSelector()}).</li>
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
    // TTFF-first startup/seek readiness. Dense-resume ABBA on the Pixel at 1500kbps reduced
    // visible-picture delay by ~618ms versus 1000ms, with unchanged decoded-first-frame timing.
    // This spends 500ms of initial cushion; the forward buffer and rebuffer gate stay intact.
    // 250ms remains a debug comparison until its latency/stability tradeoff is measured.
    private static final int START_BUFFER_MS = 500;
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
    @Nullable private DefaultPreloadManager.Builder mPreloadManagerBuilder;
    @Nullable private DefaultTrackSelector mPreloadTrackSelector;

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
        // NEWTUBE(abr-window): the DOWN-switch window must scale with the buffer preset. media3
        // signature (verified against 1.10.1 bytecode - the 4-arg ctor delegates to the 8-arg one
        // storing minDurationForQualityIncreaseMs, maxDurationForQualityDecreaseMs,
        // minDurationToRetainAfterDiscardMs, ... in that order):
        //   Factory(minDurationForQualityIncreaseMs, maxDurationForQualityDecreaseMs,
        //           minDurationToRetainAfterDiscardMs, bandwidthFraction)
        // AdaptiveTrackSelection.updateSelectedTrack REFUSES a lower rung while
        // bufferedDurationUs >= maxDurationForQualityDecreaseUs, so that constant is the point at
        // which a collapse becomes visible to ABR at all. Leaving it at media3's 25s default while
        // the presets buffer 50-75s put the threshold BELOW the load control's min buffer: in
        // steady state (buffer oscillating between min and max) the selector could never consider
        // a down-switch, and after a bandwidth collapse it stayed blind while the buffer drained.
        //   HIGH preset, 1080p @2.7Mbps collapsing to a 300kbps link: media drains at
        //   1 - 0.3/2.7 = 0.89 s of buffer per second, so 75s -> 25s took 50/0.89 = ~56s of blind
        //   playback (while up-switches stayed eligible after just 5s of buffer).
        // Scaling it to the MIDPOINT of the preset's own min..max band fixes both ends: the
        // selector is free to react during normal operation, and the same collapse is seen after
        // 75s -> 62.5s = 12.5/0.89 = ~14s, with ~70s of buffer still in hand to actually complete
        // the switch (a down-switch only affects NEWLY loaded chunks - everything already buffered
        // still plays at the old rung, which is why reacting early matters).
        //   LOW 20/30s -> 25s (= media3's default), MEDIUM 50/50s -> 50s, HIGH 50/75s -> 62.5s,
        //   HIGHEST 50/120s -> 85s.
        BufferPreset preset = resolveBufferPreset();

        DefaultTrackSelector trackSelector = new DefaultTrackSelector(
                mContext,
                new AdaptiveTrackSelection.Factory(
                        ABR_UP_SWITCH_MS,
                        preset.abrDownSwitchWindowMs(),
                        AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
                        AdaptiveTrackSelection.DEFAULT_BANDWIDTH_FRACTION));

        // 1080p Auto ceiling, mobile default; an explicit user pick overrides it (the track
        // adapter lifts the constraints when pinning a track).
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setMaxVideoSize(1920, 1080)
                .setExceedVideoConstraintsIfNecessary(true));

        return trackSelector;
    }

    /**
     * See the preset table above. Read afresh on every creation so an engine restart (error fix,
     * in-player buffer pick, next activity open) picks up the current setting - and so the track
     * selector and the load control of one player are always built from the SAME preset.
     */
    private BufferPreset resolveBufferPreset() {
        int bufferType = PlayerData.instance(mContext).getVideoBufferType();
        switch (bufferType) {
            case PlayerData.BUFFER_LOW:
                return new BufferPreset("LOW", LOW_MIN_BUFFER_MS, LOW_MAX_BUFFER_MS,
                        Math.min(LOW_TARGET_BUFFER_BYTES, mMaxBufferBytes / 4));
            case PlayerData.BUFFER_MEDIUM:
                // min==max, stock ExoPlayer pattern
                return new BufferPreset("MEDIUM", MEDIUM_MAX_BUFFER_MS, MEDIUM_MAX_BUFFER_MS,
                        Math.min(MEDIUM_TARGET_BUFFER_BYTES, mMaxBufferBytes / 2));
            case PlayerData.BUFFER_HIGHEST:
                // Long math: RAM/12 can exceed Integer.MAX_VALUE on huge-RAM devices; min() first.
                return new BufferPreset("HIGHEST", MIN_BUFFER_MS, HIGHEST_MAX_BUFFER_MS,
                        (int) Math.min(HIGHEST_TARGET_BUFFER_BYTES, mMaxBufferBytes * 3L / 2));
            case PlayerData.BUFFER_HIGH:
            default: // unknown value -> today's behavior
                return new BufferPreset("HIGH", MIN_BUFFER_MS, MAX_BUFFER_MS,
                        Math.min(TARGET_BUFFER_BYTES, mMaxBufferBytes));
        }
    }

    /** One row of the preset table: the load-control numbers plus the ABR window derived from them. */
    private static final class BufferPreset {
        final String name;
        final int minBufferMs;
        final int maxBufferMs;
        final int targetBufferBytes;

        BufferPreset(String name, int minBufferMs, int maxBufferMs, int targetBufferBytes) {
            this.name = name;
            this.minBufferMs = minBufferMs;
            this.maxBufferMs = maxBufferMs;
            this.targetBufferBytes = targetBufferBytes;
        }

        /** See {@link #createTrackSelector()} for why this is the midpoint of the buffer band. */
        int abrDownSwitchWindowMs() {
            return (minBufferMs + maxBufferMs) / 2;
        }
    }

    /** Build the actual policy separately so readiness/loading boundaries can be tested without a decoder. */
    DefaultLoadControl createLoadControl() {
        BufferPreset preset = resolveBufferPreset();
        int minBufferMs = preset.minBufferMs;
        int maxBufferMs = preset.maxBufferMs;
        int targetBufferBytes = preset.targetBufferBytes;
        String bufferName = preset.name;

        // NEWTUBE(loop-experiment): the post-rebuffer resume gate is runtime-flippable in debug
        // builds (adb shell setprop debug.arc.rebuffer_gate_ms 1500; engine restart applies it)
        // so the 2500-vs-1500 A/B runs on ONE build with everything else identical. Release
        // builds always use the constant.
        int startBufferMs = START_BUFFER_MS;
        int startAfterRebufferMs = START_BUFFER_AFTER_REBUFFER_MS;
        if (com.liskovsoft.smartyoutubetv2.tv.BuildConfig.DEBUG) {
            // First-frame gate experiments leave the steady-state/outage buffer intact. Bound
            // accidental property values so debug QA cannot create an invalid load control.
            startBufferMs = Math.max(250, Math.min(2_000,
                    DebugMediaShaper.propInt("debug.arc.start_buffer_ms", startBufferMs)));
            startAfterRebufferMs =
                    DebugMediaShaper.propInt("debug.arc.rebuffer_gate_ms", startAfterRebufferMs);
        }

        android.util.Log.d("NetPath", "buffer=" + bufferName + " max=" + (maxBufferMs / 1000) + "s"
                + " min=" + (minBufferMs / 1000) + "s bytes=" + (targetBufferBytes / MB) + "MB"
                + " abr-up=" + (ABR_UP_SWITCH_MS / 1000) + "s"
                + " abr-down=" + (preset.abrDownSwitchWindowMs() / 1000) + "s"
                + " start-gate=" + startBufferMs + "ms"
                + (startAfterRebufferMs != START_BUFFER_AFTER_REBUFFER_MS
                        ? " rebuffer-gate=" + startAfterRebufferMs + "ms" : ""));

        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        minBufferMs,
                        maxBufferMs,
                        startBufferMs,
                        startAfterRebufferMs)
                .setBackBuffer(BACK_BUFFER_MS, /* retainBackBufferFromKeyframe= */ true)
                .setTargetBufferBytes(targetBufferBytes)
                // Separate from the foreground budget. The sample target is only two seconds;
                // this is its allocation backstop (an in-flight segment can finish above it).
                .setPlayerTargetBufferBytes(PlayerId.PRELOAD.name, 4 * MB)
                // The byte cap above is a memory BACKSTOP only: without this flag the loader stops
                // at the byte target even below the preset's min (on 2-3GB devices the RAM clamp
                // binds before the time target -> shorter real buffer -> more rebuffers). With it,
                // the time thresholds always win; the byte cap only guards pathological memory use.
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
    }

    public ExoPlayer createPlayer(DefaultTrackSelector trackSelector, BandwidthMeter bandwidthMeter) {
        boolean enablePreloading = (com.liskovsoft.smartyoutubetv2.tv.BuildConfig.DEBUG
                || com.liskovsoft.smartyoutubetv2.tv.BuildConfig.BENCHMARK)
                && "1".equals(DebugMediaShaper.prop("debug.arc.next_media_preload"));
        return createPlayer(trackSelector, bandwidthMeter, enablePreloading);
    }

    /** The debug-only offline fixture opts in explicitly without altering any global property. */
    ExoPlayer createPlayer(DefaultTrackSelector trackSelector, BandwidthMeter bandwidthMeter,
            boolean enablePreloading) {
        DefaultLoadControl loadControl = createLoadControl();
        mPreloadManagerBuilder = null;
        mPreloadTrackSelector = null;

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(mContext)
                // A blacklisted/failed primary decoder falls back to another instead of erroring
                // (replaces the legacy BlacklistMediaCodecSelector's job for the common cases).
                .setEnableDecoderFallback(true);

        // Media3 1.10.1 leaves both parts of deadline-based playback scheduling disabled. The
        // renderer flag supplies video deadlines on async decoders (the default on API 31+);
        // the player flag consumes those deadlines instead of polling at a fixed interval.
        // Keep this a same-build CPU/drop-frame experiment until measured across the Pixel flows.
        boolean dynamicScheduling = com.liskovsoft.smartyoutubetv2.tv.BuildConfig.DEBUG
                && "1".equals(DebugMediaShaper.prop("debug.arc.dynamic_scheduling"));
        renderersFactory.setEnableMediaCodecVideoRendererDurationToProgressUs(dynamicScheduling);
        NetPath.log("player-scheduling dynamic=" + (dynamicScheduling ? "y" : "n")
                + " video-deadlines=" + (dynamicScheduling ? "y" : "n"));

        ExoPlayer.Builder playerBuilder = new ExoPlayer.Builder(mContext)
                .setRenderersFactory(renderersFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setBandwidthMeter(bandwidthMeter)
                .experimentalSetDynamicSchedulingEnabled(dynamicScheduling);
        if (!enablePreloading) {
            // A disabled prototype must not add a second selector or manager builder to TTFF.
            ExoPlayer player = playerBuilder.build();
            setupAudio(player);
            return player;
        }

        // A loaded PreloadMediaSource can transfer its sample queues only on the SAME playback
        // looper and allocator. Build both owners from one builder, while keeping their mutable
        // selectors independent: the first factory request creates foreground, the second preload.
        mPreloadTrackSelector = createTrackSelector();
        DefaultTrackSelector preloadTrackSelector = mPreloadTrackSelector;
        boolean[] foregroundSelectorReturned = {false};
        mPreloadManagerBuilder = new DefaultPreloadManager.Builder(mContext,
                rank -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(
                        0, Media3NextPreloader.TARGET_DURATION_MS))
                .setRenderersFactory(renderersFactory)
                .setTrackSelectorFactory(context -> {
                    if (!foregroundSelectorReturned[0]) {
                        foregroundSelectorReturned[0] = true;
                        return trackSelector;
                    }
                    return preloadTrackSelector;
                })
                .setLoadControl(loadControl)
                .setBandwidthMeter(bandwidthMeter);
        ExoPlayer player = mPreloadManagerBuilder.buildExoPlayer(playerBuilder);

        setupAudio(player);

        return player;
    }

    /** Valid after createPlayer; the controller owns and releases the built manager. */
    @Nullable
    public DefaultPreloadManager.Builder getPreloadManagerBuilder() {
        return mPreloadManagerBuilder;
    }

    /** Independent mutable parameters, using the same adaptive-selection factory as foreground. */
    @Nullable
    public DefaultTrackSelector getPreloadTrackSelector() {
        return mPreloadTrackSelector;
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
