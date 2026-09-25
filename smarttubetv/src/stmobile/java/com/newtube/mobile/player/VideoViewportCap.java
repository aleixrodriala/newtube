package com.newtube.mobile.player;

import android.content.res.Configuration;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;

import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

import java.util.List;

/**
 * NEWTUBE(viewport): "the video is currently shown in a small window" - system PiP or the in-app
 * Browse mini card - so fetching rungs far above that window's pixels is wasted bytes.
 *
 * <p><b>Why this is not {@code TrackSelectionParameters.setViewportSize}.</b> In media3 1.10.1 a
 * viewport change mid-playback shrinks the ADAPTIVE track set; the new selection is then not
 * {@code TrackSelectorResult.isEquivalent} to the playing one, {@code DashMediaPeriod} releases the
 * video {@code ChunkSampleStream} and {@code RendererHolder.maybeDisableOrResetPosition} disables
 * the video renderer (all bytecode-verified). That throws away every buffered video chunk (up to
 * 75 s of already-paid 1080p) and rebuffers - once on PiP entry and again on exit, i.e. a visible
 * stall exactly when the user expands the video. Instead the cap lives INSIDE adaptive selection
 * ({@link ViewportCappedTrackSelection#canSelectFormat}): the track set, the sample stream and the
 * buffer are untouched. The cap only changes media3's IDEAL rung, so it is subject to media3's own
 * switching rules: a down-switch waits until the buffer is below the preset's down-switch window
 * (entering PiP with a full buffer keeps fetching the current rung for a while - by design, no
 * visible drop), and an excluded (e.g. 403'd) rung can push the choice elsewhere.</p>
 *
 * <p>Lifting the cap is an ordinary ABR up-switch. media3's {@code evaluateQueueSize} then drops
 * and refetches buffered chunks more than 25 s ahead ONLY if they are at most 1279x719 - so the
 * 360p/480p chunks of a normal PiP window are replaced, but 720p chunks from an enlarged PiP play
 * out, and the SABR source (empty {@code reevaluateBuffer}) never discards: its queue drains.</p>
 *
 * <p>The rung chosen mirrors media3's own viewport rule
 * ({@code DefaultTrackSelector.getMaxVideoPixelsToRetainForViewport}, orientationMayChange=false):
 * keep up to the smallest rung that still covers 98% of the window in both dimensions. A ~600x340
 * PiP window keeps 360p; an enlarged ~1000 px one keeps 720p.</p>
 *
 * <p>Process-wide single instance: there is one foreground player, and the state must survive an
 * engine restart (fresh track selector and factory) while the window is small. Written on main,
 * read on the playback thread - one volatile immutable snapshot.</p>
 */
final class VideoViewportCap {
    /** media3's {@code DefaultTrackSelector.FRACTION_TO_CONSIDER_FULLSCREEN}. */
    private static final float FRACTION_TO_CONSIDER_FULLSCREEN = 0.98f;

    private static final VideoViewportCap SHARED = new VideoViewportCap();

    @Nullable private volatile Viewport mViewport;

    static VideoViewportCap shared() {
        return SHARED;
    }

    /** An immutable window snapshot; identity changes on every set, which invalidates caches. */
    static final class Viewport {
        final String mode;
        final int width;
        final int height;

        Viewport(String mode, int width, int height) {
            this.mode = mode;
            this.width = width;
            this.height = height;
        }
    }

    /** Cap subsequent chunk selection to a {@code width x height} px window. Non-positive = ignored. */
    void set(String mode, int width, int height) {
        if (width <= 0 || height <= 0) {
            NetPath.log("viewport " + mode + " ignored size=" + width + "x" + height);
            return;
        }
        Viewport current = mViewport;
        if (current != null && current.width == width && current.height == height
                && current.mode.equals(mode)) {
            return; // the config change that follows PiP entry repeats the same window
        }
        mViewport = new Viewport(mode, width, height);
        NetPath.log("viewport " + mode + " size=" + width + "x" + height + " -> cap on");
    }

    /** Back to the full-screen player: no cap (the selector's own physical-display rule applies). */
    void clear(String reason) {
        if (mViewport == null) {
            return;
        }
        mViewport = null;
        NetPath.log("viewport full reason=" + reason + " -> cap off");
    }

    @Nullable
    Viewport current() {
        return mViewport;
    }

    /**
     * Real pixels of a window from its configuration: {@code screen*Dp} and {@code densityDpi} are
     * both system values. {@code getResources().getDisplayMetrics()} must NOT be used here - the
     * app swaps in a TV-derived density (see MotherActivity.initDpi).
     */
    @Nullable
    static int[] windowPixels(@Nullable Configuration config) {
        if (config == null || config.screenWidthDp <= 0 || config.screenHeightDp <= 0
                || config.densityDpi <= 0) {
            return null;
        }
        float scale = config.densityDpi / 160f;
        return new int[] {Math.round(config.screenWidthDp * scale),
                Math.round(config.screenHeightDp * scale)};
    }

    /**
     * Pure: the largest pixel count worth fetching for {@code formats} shown in a
     * {@code viewportWidth x viewportHeight} window, or {@link Integer#MAX_VALUE} when no rung is
     * large enough to fill it (nothing to cap). Formats without dimensions are ignored.
     */
    static int maxPixelsToRetain(List<Format> formats, int viewportWidth, int viewportHeight) {
        int maxPixels = Integer.MAX_VALUE;
        for (Format format : formats) {
            if (format.width <= 0 || format.height <= 0) {
                continue;
            }
            int[] fitted = maxVideoSizeInViewport(viewportWidth, viewportHeight,
                    format.width, format.height);
            int pixels = format.width * format.height;
            if (format.width >= (int) (fitted[0] * FRACTION_TO_CONSIDER_FULLSCREEN)
                    && format.height >= (int) (fitted[1] * FRACTION_TO_CONSIDER_FULLSCREEN)
                    && pixels < maxPixels) {
                maxPixels = pixels;
            }
        }
        return maxPixels;
    }

    /** media3 {@code TrackSelectionUtil.getMaxVideoSizeInViewport} with orientationMayChange=false. */
    private static int[] maxVideoSizeInViewport(int viewportWidth, int viewportHeight,
            int videoWidth, int videoHeight) {
        if ((long) videoWidth * viewportHeight >= (long) videoHeight * viewportWidth) {
            // Horizontal letterboxing along the bottom and top.
            return new int[] {viewportWidth, ceilDivide((long) viewportWidth * videoHeight, videoWidth)};
        }
        // Vertical letterboxing along the edges.
        return new int[] {ceilDivide((long) viewportHeight * videoWidth, videoHeight), viewportHeight};
    }

    private static int ceilDivide(long numerator, long denominator) {
        return (int) ((numerator + denominator - 1) / denominator);
    }

    /** Pure: whether a format may be picked under {@code maxPixels}. Audio/unknown size always may. */
    static boolean allows(Format format, int maxPixels) {
        if (maxPixels == Integer.MAX_VALUE || format.width <= 0 || format.height <= 0) {
            return true;
        }
        return format.width * format.height <= maxPixels;
    }
}
