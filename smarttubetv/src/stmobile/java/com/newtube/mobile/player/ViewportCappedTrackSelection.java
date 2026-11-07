package com.newtube.mobile.player;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.Clock;
import androidx.media3.exoplayer.source.chunk.MediaChunk;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.upstream.BandwidthMeter;

import com.google.common.collect.ImmutableList;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(viewport): stock {@link AdaptiveTrackSelection} whose rung eligibility additionally
 * respects {@link VideoViewportCap} (see there for why the cap cannot be a selector parameter).
 *
 * <p>Only {@link #canSelectFormat} changes, and only while a cap is set: media3 calls it from
 * {@code determineIdealSelectedIndex}, highest rung first, and falls back to the lowest
 * non-excluded rung when nothing qualifies - so a cap can never leave the selection empty. All
 * of media3's own switching rules still apply on top: a down-switch is refused while the buffer
 * is above the preset's down-switch window, so entering PiP keeps playing the already-buffered
 * full-resolution chunks and only NEW chunks are fetched at the window's rung. Nothing here caches
 * the window beyond the snapshot {@link VideoViewportCap#current()} returns.</p>
 *
 * <p>Explicit quality picks are untouched by construction: a {@code TrackSelectionOverride} of
 * one track becomes a {@code FixedTrackSelection}, never this class. Audio formats (no
 * dimensions) are always eligible.</p>
 *
 * <p><b>Startup chunks are replaced once the quality goes up</b> ({@link #evaluateQueueSize},
 * NEWTUBE(quality-start)). The first chunks of a playback are fetched before any real transfer
 * has been measured, so they are often 480p; a second later the estimate allows 1080p, but stock
 * media3 only drops buffered chunks that start more than 25 s ahead and are at most 1279x719. The
 * rest played out: on a 50 Mbps Wi-Fi, 1080p was fetched 1.5 s after the open and seen only 18 s
 * in. Chunks loaded before this selection's first switch (selection reason INITIAL) that are
 * below the rung now selected are dropped from {@link #STARTUP_RETAIN_US} ahead and fetched again
 * at that rung. Later down-switches (ADAPTIVE chunks: a dip mid-video) keep the stock rule, so a
 * recovering link never throws a long buffer away. Guards (a Codex review's findings): the
 * selection must have switched UP by its own decision (ADAPTIVE - a selection just re-created by
 * an audio or captions change starts UNKNOWN with its top rung "selected"), that rung must still
 * fit the current estimate and the window cap (the selection is only refreshed per chunk request,
 * so it can be stale after a collapse), and only within {@link #STARTUP_WINDOW_MS} of the
 * selection's creation - the startup it exists for; never a paused live stream whose window moved
 * past the buffer.</p>
 */
final class ViewportCappedTrackSelection extends AdaptiveTrackSelection {
    /** Startup chunks starting closer than this to the playback position always play out. */
    static final long STARTUP_RETAIN_US = 5_000_000;
    /** Startup chunks are replaced only this long after the selection was created. */
    static final long STARTUP_WINDOW_MS = 30_000;

    private final VideoViewportCap mCap;
    private final Clock mClock;
    private final BandwidthMeter mBandwidthMeter;
    private final float mBandwidthFraction;
    private final long mCreatedMs;
    private float mPlaybackSpeed = 1f;
    /** Playback-thread cache of the cap resolved against THIS selection's rungs. */
    @Nullable private VideoViewportCap.Viewport mResolvedFor;
    private int mResolvedMaxPixels = Integer.MAX_VALUE;

    private ViewportCappedTrackSelection(TrackGroup group, int[] tracks, int type,
            BandwidthMeter bandwidthMeter, long minDurationForQualityIncreaseMs,
            long maxDurationForQualityDecreaseMs, long minDurationToRetainAfterDiscardMs,
            int maxWidthToDiscard, int maxHeightToDiscard, float bandwidthFraction,
            float bufferedFractionToLiveEdgeForQualityIncrease,
            List<AdaptationCheckpoint> adaptationCheckpoints, Clock clock, VideoViewportCap cap) {
        super(group, tracks, type, bandwidthMeter, minDurationForQualityIncreaseMs,
                maxDurationForQualityDecreaseMs, minDurationToRetainAfterDiscardMs, maxWidthToDiscard,
                maxHeightToDiscard, bandwidthFraction, bufferedFractionToLiveEdgeForQualityIncrease,
                adaptationCheckpoints, clock);
        mCap = cap;
        mClock = clock;
        mBandwidthMeter = bandwidthMeter;
        mBandwidthFraction = bandwidthFraction;
        mCreatedMs = clock.elapsedRealtime();
    }

    @Override
    public void onPlaybackSpeed(float playbackSpeed) {
        super.onPlaybackSpeed(playbackSpeed);
        mPlaybackSpeed = playbackSpeed;
    }

    @Override
    public int evaluateQueueSize(long playbackPositionUs, List<? extends MediaChunk> queue) {
        // Same once-a-second (or new chunk) pace as the stock rule; asked before super stamps it.
        long nowMs = mClock.elapsedRealtime();
        boolean evaluate = shouldEvaluateQueueSize(nowMs, queue);
        int size = super.evaluateQueueSize(playbackPositionUs, queue);
        if (!evaluate || size < queue.size() || nowMs - mCreatedMs > STARTUP_WINDOW_MS) {
            return size;
        }
        Format selected = getSelectedFormat();
        long affordable = (long) (mBandwidthMeter.getBitrateEstimate() * mBandwidthFraction);
        if (selected.bitrate == Format.NO_VALUE || selected.bitrate > affordable
                || !VideoViewportCap.allows(selected, resolveMaxPixels())) {
            return size; // the up-switch no longer holds: keep what is buffered
        }
        int keep = startupDiscardIndex(playbackPositionUs, queue, getSelectionReason(),
                selected, mPlaybackSpeed);
        if (keep < queue.size()) {
            MediaChunk first = queue.get(keep);
            NetPath.log(NetPath.context() + " rung replace " + VideoRungLog.describe(first.trackFormat)
                    + " chunks=" + (queue.size() - keep) + " from=" + first.startTimeUs / 1000
                    + " pos=" + playbackPositionUs / 1000
                    + " to=" + VideoRungLog.describe(getSelectedFormat()));
        }
        return keep;
    }

    /**
     * The queue index from which startup chunks are dropped, or {@code queue.size()} to keep
     * them all: the first chunk at least {@link #STARTUP_RETAIN_US} of playout ahead, loaded
     * before the first switch (INITIAL), below the selected rung in both class and bitrate. Only
     * once this selection has switched by its own decision (ADAPTIVE): INITIAL has nothing better
     * yet, and UNKNOWN has not decided at all.
     */
    static int startupDiscardIndex(long playbackPositionUs, List<? extends MediaChunk> queue,
            int selectionReason, Format selected, float playbackSpeed) {
        if (selectionReason != C.SELECTION_REASON_ADAPTIVE || selected == null) {
            return queue.size();
        }
        int selectedRung = VideoRung.classHeight(selected.width, selected.height);
        long retainMediaUs = (long) (STARTUP_RETAIN_US * Math.max(playbackSpeed, 1f));
        for (int i = 0; i < queue.size(); i++) {
            MediaChunk chunk = queue.get(i);
            Format format = chunk.trackFormat;
            if (chunk.startTimeUs - playbackPositionUs < retainMediaUs
                    || chunk.trackSelectionReason != C.SELECTION_REASON_INITIAL) {
                continue;
            }
            if (format.bitrate != Format.NO_VALUE && selected.bitrate != Format.NO_VALUE
                    && format.bitrate < selected.bitrate
                    && VideoRung.classHeight(format.width, format.height) < selectedRung) {
                return i;
            }
        }
        return queue.size();
    }

    @Override
    protected boolean canSelectFormat(Format format, int trackBitrate, long effectiveBitrate) {
        return super.canSelectFormat(format, trackBitrate, effectiveBitrate)
                && VideoViewportCap.allows(format, resolveMaxPixels());
    }

    /** Integer.MAX_VALUE when no cap is set. Recomputed only when the window snapshot changes. */
    int resolveMaxPixels() {
        VideoViewportCap cap = mCap;
        VideoViewportCap.Viewport viewport = cap != null ? cap.current() : null;
        if (viewport == null) {
            mResolvedFor = null;
            mResolvedMaxPixels = Integer.MAX_VALUE;
            return Integer.MAX_VALUE;
        }
        if (viewport != mResolvedFor) {
            List<Format> formats = new ArrayList<>(length());
            for (int i = 0; i < length(); i++) {
                formats.add(getFormat(i));
            }
            mResolvedFor = viewport;
            mResolvedMaxPixels = VideoViewportCap.maxPixelsToRetain(formats, viewport.width,
                    viewport.height);
            logResolved(viewport, formats, mResolvedMaxPixels);
        }
        return mResolvedMaxPixels;
    }

    private static void logResolved(VideoViewportCap.Viewport viewport, List<Format> formats,
            int maxPixels) {
        Format top = null;
        int eligible = 0;
        int video = 0;
        for (Format format : formats) {
            if (format.width <= 0 || format.height <= 0) {
                continue;
            }
            video++;
            if (VideoViewportCap.allows(format, maxPixels)) {
                eligible++;
                if (top == null || format.width * format.height > top.width * top.height) {
                    top = format;
                }
            }
        }
        if (video == 0) {
            return; // an audio selection: nothing to report
        }
        NetPath.log("viewport " + viewport.mode + " window=" + viewport.width + "x" + viewport.height
                + " top-rung=" + (top != null ? top.width + "x" + top.height : "none")
                + " eligible=" + eligible + "/" + video);
    }

    /**
     * Same knobs as {@link AdaptiveTrackSelection.Factory}'s 4-argument constructor (whose
     * remaining defaults - discard 1279x719, live-edge fraction 0.75, {@link Clock#DEFAULT} - are
     * repeated here because the parent keeps them private; a unit test pins the parity).
     */
    static final class Factory extends AdaptiveTrackSelection.Factory {
        private final int mMinDurationForQualityIncreaseMs;
        private final int mMaxDurationForQualityDecreaseMs;
        private final int mMinDurationToRetainAfterDiscardMs;
        private final float mBandwidthFraction;
        private final VideoViewportCap mCap;

        Factory(int minDurationForQualityIncreaseMs, int maxDurationForQualityDecreaseMs,
                int minDurationToRetainAfterDiscardMs, float bandwidthFraction, VideoViewportCap cap) {
            super(minDurationForQualityIncreaseMs, maxDurationForQualityDecreaseMs,
                    minDurationToRetainAfterDiscardMs, bandwidthFraction);
            mMinDurationForQualityIncreaseMs = minDurationForQualityIncreaseMs;
            mMaxDurationForQualityDecreaseMs = maxDurationForQualityDecreaseMs;
            mMinDurationToRetainAfterDiscardMs = minDurationToRetainAfterDiscardMs;
            mBandwidthFraction = bandwidthFraction;
            mCap = cap;
        }

        @Override
        protected AdaptiveTrackSelection createAdaptiveTrackSelection(TrackGroup group, int[] tracks,
                int type, BandwidthMeter bandwidthMeter,
                ImmutableList<AdaptationCheckpoint> adaptationCheckpoints) {
            return new ViewportCappedTrackSelection(group, tracks, type, bandwidthMeter,
                    mMinDurationForQualityIncreaseMs, mMaxDurationForQualityDecreaseMs,
                    mMinDurationToRetainAfterDiscardMs,
                    AdaptiveTrackSelection.DEFAULT_MAX_WIDTH_TO_DISCARD,
                    AdaptiveTrackSelection.DEFAULT_MAX_HEIGHT_TO_DISCARD, mBandwidthFraction,
                    AdaptiveTrackSelection.DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
                    adaptationCheckpoints, Clock.DEFAULT, mCap);
        }
    }
}
