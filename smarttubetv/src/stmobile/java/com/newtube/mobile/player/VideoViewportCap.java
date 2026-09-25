package com.newtube.mobile.player;

import android.content.res.Configuration;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;

import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

import java.util.List;
import java.util.function.BooleanSupplier;

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
 * <p><b>Inline box while saving data.</b> The portrait watch page shows the video in a 16:9 box as
 * wide as the screen - 1080x608 on a Pixel 9 - where ABR otherwise climbs to 1080p (~2.4 Mbps vs
 * ~1.76 for the 720p that already covers it). That box is recorded as a second, lower-priority
 * window ({@link #setInline}) which caps selection ONLY while
 * {@link MeteredNetworkMonitor#shouldSaveData} holds - a metered network AND Android Data Saver
 * restricting this app (metered alone is not enough: unlimited LTE reports metered too, and there
 * quality wins). The check is made on every selection, so a handover to Wi-Fi or Data Saver being
 * switched off lifts it for the very next chunk without anybody having to tell the selector.
 * Fullscreen (landscape) removes the box ({@link #clearInline}). A PiP/mini window, while set,
 * always wins over it and caps on any network. Same rung rule, same "buffered chunks play out"
 * switch semantics as above; a zoom/fill resize mode ({@link Viewport#cover}) crops the video to
 * the box, so the rung must cover the CROPPED size instead of the letterboxed one.</p>
 *
 * <p>Process-wide single instance: there is one foreground player, and the state must survive an
 * engine restart (fresh track selector and factory) while the window is small. Written on main,
 * read on the playback thread - one volatile immutable snapshot per window.</p>
 */
final class VideoViewportCap {
    /** media3's {@code DefaultTrackSelector.FRACTION_TO_CONSIDER_FULLSCREEN}. */
    private static final float FRACTION_TO_CONSIDER_FULLSCREEN = 0.98f;

    static final String MODE_INLINE = "inline";

    private static final VideoViewportCap SHARED = new VideoViewportCap();

    /** PiP / mini card: capped whatever the network. */
    @Nullable private volatile Viewport mViewport;
    /** The portrait watch-page box: capped only while {@link #mSaveData} says so. */
    @Nullable private volatile Viewport mInline;
    private final BooleanSupplier mSaveData;

    VideoViewportCap() {
        this(MeteredNetworkMonitor::shouldSaveData);
    }

    /** Test seam: the data-saving gate (production: the process-wide monitor's volatile flags). */
    VideoViewportCap(BooleanSupplier saveData) {
        mSaveData = saveData;
    }

    static VideoViewportCap shared() {
        return SHARED;
    }

    /** An immutable window snapshot; identity changes on every set, which invalidates caches. */
    static final class Viewport {
        final String mode;
        final int width;
        final int height;
        /** The video COVERS the window and is cropped (zoom/fill resize modes), not letterboxed. */
        final boolean cover;

        Viewport(String mode, int width, int height) {
            this(mode, width, height, false);
        }

        Viewport(String mode, int width, int height, boolean cover) {
            this.mode = mode;
            this.width = width;
            this.height = height;
            this.cover = cover;
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

    /**
     * Back to the full-size player: no small-window cap (the selector's own physical-display rule
     * applies) - unless the player came back to the portrait inline box while saving data, in
     * which case that box's cap takes over (and the log says so instead of "cap off").
     */
    void clear(String reason) {
        if (mViewport == null) {
            return;
        }
        mViewport = null;
        Viewport inline = mInline;
        if (inline != null) {
            logInline(inline, mSaveData.getAsBoolean(), reason);
        } else {
            NetPath.log("viewport full reason=" + reason + " -> cap off");
        }
    }

    /**
     * The portrait watch-page video box is {@code width x height} px ({@code cover}: a zoom/fill
     * resize mode crops the video to it). Caps NEW chunks only while saving data (metered + Data
     * Saver).
     * Repeats of the same box (every layout pass reports it) are free and silent.
     */
    void setInline(int width, int height, boolean cover) {
        if (width <= 0 || height <= 0) {
            NetPath.log("viewport " + MODE_INLINE + " ignored size=" + width + "x" + height);
            return;
        }
        Viewport current = mInline;
        if (current != null && current.width == width && current.height == height
                && current.cover == cover) {
            return;
        }
        Viewport inline = new Viewport(MODE_INLINE, width, height, cover);
        mInline = inline;
        logInline(inline, mSaveData.getAsBoolean(), null);
    }

    /** Fullscreen (landscape) or a new playback screen: the inline box no longer exists. */
    void clearInline(String reason) {
        Viewport inline = mInline;
        if (inline == null) {
            return;
        }
        mInline = null;
        NetPath.log("viewport " + MODE_INLINE + " size=" + inline.width + "x" + inline.height
                + " -> cap off reason=" + reason);
    }

    /**
     * {@link MeteredNetworkMonitor}: the data-saving gate flipped (network or Data Saver). Logging
     * only - {@link #current()} reads the gate itself on every selection, so the cap follows it
     * even without this call.
     */
    void onSavingChanged(boolean saving, String event) {
        Viewport inline = mInline;
        if (inline != null) {
            logInline(inline, saving, event);
        }
    }

    /**
     * The window that caps selection right now, or null: a PiP/mini window always; otherwise the
     * inline box while saving data. Called per rung check on the playback thread - two volatile
     * reads and the monitor's volatile flags.
     */
    @Nullable
    Viewport current() {
        Viewport small = mViewport;
        if (small != null) {
            return small;
        }
        Viewport inline = mInline;
        return inline != null && mSaveData.getAsBoolean() ? inline : null;
    }

    private void logInline(Viewport inline, boolean saving, @Nullable String from) {
        Viewport small = mViewport;
        String outcome = small != null ? "deferred behind=" + small.mode
                : saving ? "cap on" : "cap off reason=not-saving";
        NetPath.log("viewport " + MODE_INLINE + " size=" + inline.width + "x" + inline.height
                + (inline.cover ? " fill=y" : "") + " " + MeteredNetworkMonitor.describe()
                + (from != null ? " from=" + from : "") + " -> " + outcome);
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
        return maxPixelsToRetain(formats, viewportWidth, viewportHeight, false);
    }

    /**
     * Same, and {@code cover}: the video is scaled to COVER the window and cropped (zoom/fill
     * resize modes), so the rung must match the covering size - for a 16:9 video in a 16:9 box
     * that is the same answer, for a vertical video in the 16:9 inline box it is the full width.
     * Never smaller than the letterboxed answer, so a crop mode can only make the cap looser.
     */
    static int maxPixelsToRetain(List<Format> formats, int viewportWidth, int viewportHeight,
            boolean cover) {
        int maxPixels = Integer.MAX_VALUE;
        for (Format format : formats) {
            if (format.width <= 0 || format.height <= 0) {
                continue;
            }
            int[] fitted = maxVideoSizeInViewport(viewportWidth, viewportHeight,
                    format.width, format.height, cover);
            int pixels = format.width * format.height;
            if (format.width >= (int) (fitted[0] * FRACTION_TO_CONSIDER_FULLSCREEN)
                    && format.height >= (int) (fitted[1] * FRACTION_TO_CONSIDER_FULLSCREEN)
                    && pixels < maxPixels) {
                maxPixels = pixels;
            }
        }
        return maxPixels;
    }

    /**
     * media3 {@code TrackSelectionUtil.getMaxVideoSizeInViewport} with orientationMayChange=false;
     * {@code cover} flips which dimension binds (the video fills the window and overflows the other).
     */
    private static int[] maxVideoSizeInViewport(int viewportWidth, int viewportHeight,
            int videoWidth, int videoHeight, boolean cover) {
        boolean widerThanViewport =
                (long) videoWidth * viewportHeight >= (long) videoHeight * viewportWidth;
        if (widerThanViewport != cover) {
            // Fit: horizontal letterboxing along the bottom and top. Cover: a taller video fills
            // the width and is cropped top and bottom.
            return new int[] {viewportWidth, ceilDivide((long) viewportWidth * videoHeight, videoWidth)};
        }
        // Fit: vertical letterboxing along the edges. Cover: a wider video fills the height and
        // is cropped at the edges.
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
