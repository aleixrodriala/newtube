package com.newtube.mobile.player;

import androidx.media3.common.C;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;

import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * NEWTUBE(metered): the preset's {@code DefaultLoadControl} behind one extra "stop loading" rule
 * that only exists while the user is saving data: a metered default network AND Android Data Saver
 * restricting this app ({@link MeteredNetworkMonitor#shouldSaveData}). Anything else - Wi-Fi, and
 * cellular without Data Saver (unlimited plans report metered too; there stability wins over
 * megabytes) - sees the delegate bit-for-bit.
 *
 * <p>Why: the HIGH preset keeps 50-75 s loaded ahead (~25 MB of 1080p). On a capped plan that is
 * paid for even when the video is abandoned after 10 s, and it keeps filling while paused. While
 * saving data the forward target becomes
 * <pre>
 *   playing:  min(presetMax, 30 s x max(1, speed) + time actually played in this playback)
 *   paused:   min(presetMax, 20 s)
 * </pre>
 * so an abandoned open costs ~30 s of media instead of 75 s, and the cushion grows back to the
 * preset as the viewer demonstrates they are watching (after 45 s of playback HIGH is fully back).</p>
 *
 * <p>The cost, stated plainly: a smaller cushion rides out a shorter outage. While the ceiling
 * binds, a total link loss longer than the buffered 30-75 s (or 20 s after a pause) rebuffers
 * where the preset alone might not have. A bandwidth DIP below the video bitrate is covered either
 * way (30 s is 20x the 1.5 s rebuffer gate); a tunnel-length blackout is the case this trades.</p>
 *
 * <p>What it never touches (product rule: bytes must not cost startup or rebuffers): the start and
 * rebuffer gates ({@link #shouldStartPlayback} is pure delegation), the back buffer, the allocator
 * and byte budget, the preload player ({@link PlayerId#PRELOAD}), and any decision while
 * rebuffering. It can only turn the delegate's "continue" into "hold" - never the reverse - and
 * the delegate still sees every call so its own min/max hysteresis state stays exactly what it
 * would have been without the wrapper.</p>
 *
 * <p>"Played time" is media time the playhead actually advanced while playWhenReady, credited
 * between consecutive loading decisions and bounded by the wall clock that elapsed at the playback
 * speed - so a forward seek is not "watching", and a long pause is not either. It resets whenever
 * the player is prepared, stopped or released (each open in this app is a stop + prepare).</p>
 */
final class MeteredBufferLoadControl implements LoadControl {
    /** Floor while playing, in wall-clock seconds of cushion (scaled to media time above 1x). */
    static final long PLAYING_FLOOR_US = 30_000_000L;
    /** Target while paused: enough for an instant resume, not a paused 75 s download. */
    static final long PAUSED_TARGET_US = 20_000_000L;
    /** A decision line is logged again only when the target moved by at least this much. */
    private static final long LOG_TARGET_STEP_US = 10_000_000L;

    private final LoadControl mDelegate;
    private final long mPresetMaxUs;
    private final BooleanSupplier mSaveData;
    private final LongSupplier mRealtimeMs;
    /** Per foreground player (PlayerId has identity equality). Touched only on its playback thread. */
    private final Map<PlayerId, PlaybackTally> mTallies = new ConcurrentHashMap<>();

    MeteredBufferLoadControl(LoadControl delegate, long presetMaxUs, BooleanSupplier saveData,
            LongSupplier realtimeMs) {
        mDelegate = delegate;
        mPresetMaxUs = presetMaxUs;
        mSaveData = saveData;
        mRealtimeMs = realtimeMs;
    }

    // ---------------------------------------------------------------------------------
    // Pure policy
    // ---------------------------------------------------------------------------------

    /**
     * The forward-buffer ceiling this policy imposes, in media microseconds, or
     * {@link C#TIME_UNSET} for "no ceiling beyond the preset" (not saving data, or rebuffering).
     * {@code saveData} is the whole gate (metered AND Data Saver) as the monitor reports it.
     */
    static long targetBufferUs(boolean saveData, boolean playWhenReady, boolean rebuffering,
            long playedUs, float speed, long presetMaxUs) {
        if (!saveData || rebuffering) {
            return C.TIME_UNSET;
        }
        long target;
        if (playWhenReady) {
            // The floor is a wall-clock cushion: at 2x the same 30 s of safety is 60 s of media.
            long floorUs = speed > 1f ? (long) (PLAYING_FLOOR_US * (double) speed) : PLAYING_FLOOR_US;
            target = floorUs + Math.max(0, playedUs);
        } else {
            target = PAUSED_TARGET_US;
        }
        return Math.min(presetMaxUs, target);
    }

    /** True when the policy stops loading that the preset alone would have continued. */
    static boolean shouldHold(boolean saveData, boolean playWhenReady, boolean rebuffering,
            long playedUs, float speed, long presetMaxUs, long bufferedUs) {
        long target = targetBufferUs(saveData, playWhenReady, rebuffering, playedUs, speed, presetMaxUs);
        return target != C.TIME_UNSET && bufferedUs >= target;
    }

    // ---------------------------------------------------------------------------------
    // The one changed decision
    // ---------------------------------------------------------------------------------

    @Override
    public boolean shouldContinueLoading(Parameters parameters) {
        boolean delegateContinues = mDelegate.shouldContinueLoading(parameters);
        if (parameters.playerId == PlayerId.PRELOAD) {
            return delegateContinues; // the preload sample target is 2 s; not this policy's business
        }

        PlaybackTally tally = tallyFor(parameters.playerId);
        tally.advance(parameters, mRealtimeMs.getAsLong());
        if (!delegateContinues) {
            tally.holding = false;
            return false; // the preset already stops here; the wrapper never overrides a stop
        }

        boolean saveData = mSaveData.getAsBoolean();
        long target = targetBufferUs(saveData, parameters.playWhenReady, parameters.rebuffering,
                tally.playedUs, parameters.playbackSpeed, mPresetMaxUs);
        boolean hold = target != C.TIME_UNSET && parameters.bufferedDurationUs >= target;
        if (hold) {
            maybeLogHold(tally, parameters, target);
        }
        tally.holding = hold;
        return !hold;
    }

    /**
     * One line per regime, not per decision: the loader re-asks every ~10 ms while held and the
     * target creeps up with played time, so log only a new hold whose mode changed or whose target
     * moved a full {@link #LOG_TARGET_STEP_US} since the last line.
     */
    private void maybeLogHold(PlaybackTally tally, Parameters parameters, long target) {
        if (tally.holding) {
            return;
        }
        boolean modeChanged = tally.loggedPlayWhenReady == null
                || tally.loggedPlayWhenReady != parameters.playWhenReady;
        if (!modeChanged && Math.abs(target - tally.loggedTargetUs) < LOG_TARGET_STEP_US) {
            return;
        }
        tally.loggedPlayWhenReady = parameters.playWhenReady;
        tally.loggedTargetUs = target;
        NetPath.log("buffer-cap metered=y dataSaver=y mode="
                + (parameters.playWhenReady ? "playing" : "paused")
                + " target=" + seconds(target) + "s buffered=" + seconds(parameters.bufferedDurationUs)
                + "s played=" + seconds(tally.playedUs) + "s preset-max=" + seconds(mPresetMaxUs)
                + "s speed=" + parameters.playbackSpeed + " -> hold");
    }

    private static String seconds(long us) {
        return String.valueOf(Math.round(us / 100_000.0) / 10.0);
    }

    private PlaybackTally tallyFor(PlayerId playerId) {
        PlaybackTally tally = mTallies.get(playerId);
        if (tally == null) {
            tally = new PlaybackTally();
            mTallies.put(playerId, tally);
        }
        return tally;
    }

    /** Played-time accounting for one playback (prepare -> stop). Playback-thread confined. */
    static final class PlaybackTally {
        /** Wall-clock slack so sampling jitter never under-credits real playback. */
        private static final double WALL_CLOCK_SLACK = 1.25;

        long playedUs;
        boolean holding;
        Boolean loggedPlayWhenReady;
        long loggedTargetUs;
        private long lastPositionUs = C.TIME_UNSET;
        private long lastRealtimeMs;
        private boolean lastPlaying;
        private Object lastPeriodUid;
        private long lastWindowSequence;

        void advance(Parameters parameters, long nowMs) {
            Object periodUid = parameters.mediaPeriodId.periodUid;
            long windowSequence = parameters.mediaPeriodId.windowSequenceNumber;
            boolean samePeriod = lastPositionUs != C.TIME_UNSET
                    && Objects.equals(periodUid, lastPeriodUid) && windowSequence == lastWindowSequence;
            if (samePeriod && lastPlaying) {
                long positionDeltaUs = parameters.playbackPositionUs - lastPositionUs;
                double speed = Math.max(parameters.playbackSpeed, 0.1f);
                long wallBoundUs = (long) (Math.max(0, nowMs - lastRealtimeMs) * 1000L * speed
                        * WALL_CLOCK_SLACK);
                playedUs += Math.max(0, Math.min(positionDeltaUs, wallBoundUs));
            }
            lastPositionUs = parameters.playbackPositionUs;
            lastRealtimeMs = nowMs;
            lastPlaying = parameters.playWhenReady && !parameters.rebuffering;
            lastPeriodUid = periodUid;
            lastWindowSequence = windowSequence;
        }
    }

    // ---------------------------------------------------------------------------------
    // Everything else: untouched delegation (every method ExoPlayerImplInternal and
    // PreloadMediaSource call in 1.10.1 - verified against the bytecode - is forwarded; the
    // interface defaults would otherwise route to deprecated overloads or throw).
    // NOTE: while this wrapper holds, the delegate still believes it is loading, and
    // DefaultLoadControl.shouldContinuePreloading refuses while any player is loading - so
    // ExoPlayer's own playlist preloading (setPreloadConfiguration, unused in this app) would stay
    // off while saving data. The PreloadMediaSource path is unaffected (PRELOAD is exempt above).
    // ---------------------------------------------------------------------------------

    @Override
    public void onPrepared(PlayerId playerId) {
        mTallies.remove(playerId);
        mDelegate.onPrepared(playerId);
    }

    @Override
    public void onTracksSelected(Parameters parameters, TrackGroupArray trackGroups,
            ExoTrackSelection[] trackSelections) {
        mDelegate.onTracksSelected(parameters, trackGroups, trackSelections);
    }

    @Override
    public void onStopped(PlayerId playerId) {
        mTallies.remove(playerId);
        mDelegate.onStopped(playerId);
    }

    @Override
    public void onReleased(PlayerId playerId) {
        mTallies.remove(playerId);
        mDelegate.onReleased(playerId);
    }

    @Override
    public Allocator getAllocator(PlayerId playerId) {
        return mDelegate.getAllocator(playerId);
    }

    @Override
    public long getBackBufferDurationUs(PlayerId playerId) {
        return mDelegate.getBackBufferDurationUs(playerId);
    }

    @Override
    public boolean retainBackBufferFromKeyframe(PlayerId playerId) {
        return mDelegate.retainBackBufferFromKeyframe(playerId);
    }

    @Override
    public boolean shouldStartPlayback(Parameters parameters) {
        return mDelegate.shouldStartPlayback(parameters);
    }

    @Override
    public boolean shouldContinuePreloading(PlayerId playerId, Timeline timeline,
            MediaSource.MediaPeriodId mediaPeriodId, long bufferedDurationUs) {
        return mDelegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId,
                bufferedDurationUs);
    }
}
