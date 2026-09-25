package com.newtube.mobile.player;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.Clock;
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
 * full-resolution chunks and only NEW chunks are fetched at the window's rung. The same holds for
 * the data-saving inline-box cap, whose window comes and goes with the network / Data Saver:
 * nothing here caches that answer beyond the window snapshot {@link VideoViewportCap#current()}
 * returns.</p>
 *
 * <p>Explicit quality picks are untouched by construction: a {@code TrackSelectionOverride} of
 * one track becomes a {@code FixedTrackSelection}, never this class. Audio formats (no
 * dimensions) are always eligible.</p>
 */
final class ViewportCappedTrackSelection extends AdaptiveTrackSelection {
    private final VideoViewportCap mCap;
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
                    viewport.height, viewport.cover);
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
                + (viewport.cover ? " fill=y" : "")
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
