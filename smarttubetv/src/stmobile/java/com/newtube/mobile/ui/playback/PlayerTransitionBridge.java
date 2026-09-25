package com.newtube.mobile.ui.playback;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * One-shot visual hand-off from a tapped feed thumbnail to the playback Activity.
 *
 * <p>The source Activity stays rendered underneath the translucent player window. Right before it
 * opens, the card captures its thumbnail and screen bounds here; the player consumes that snapshot
 * and grows the exact image from the exact rectangle into its video box. A short expiry prevents a
 * non-video route (playlist/channel) from accidentally animating a later unrelated player launch.</p>
 */
public final class PlayerTransitionBridge {
    private static final long MAX_AGE_MS = 3_000;
    @Nullable
    private static LaunchSnapshot sPending;

    private PlayerTransitionBridge() {
    }

    /** Capture a fully-visible thumbnail immediately before its click opens playback. */
    public static void prepare(@Nullable View source) {
        prepare(source, (View[]) null);
    }

    /**
     * NEWTUBE(open-snapshot): as {@link #prepare(View)}, with {@code hideWhileCapturing} (the
     * card's duration badge and red watch-progress bar) left out of the captured frame. The
     * snapshot covers the video box until the first frame; baked into it, the card's "27:16"
     * sat next to the player's own "27:15" and the progress bar floated inside the player.
     * Hidden only for the synchronous draw below, so the card never renders without them.
     */
    public static void prepare(@Nullable View source, @Nullable View... hideWhileCapturing) {
        clear();
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0
                || !source.isShown() || source.getWindowToken() == null) {
            return;
        }

        int[] location = new int[2];
        source.getLocationOnScreen(location);
        Rect bounds = new Rect(location[0], location[1],
                location[0] + source.getWidth(), location[1] + source.getHeight());
        Rect visible = new Rect();
        if (!source.getGlobalVisibleRect(visible) || !visible.equals(bounds)) {
            // A clipped card would expand across the toolbar/nav area that clipped it. A plain
            // fade is cleaner for this uncommon edge case than morphing mismatched geometry.
            return;
        }

        int[] savedVisibility = hideForCapture(hideWhileCapturing);
        try {
            Bitmap frame = Bitmap.createBitmap(
                    source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
            source.draw(new Canvas(frame));
            sPending = new LaunchSnapshot(bounds, frame, SystemClock.uptimeMillis());
        } catch (RuntimeException ignored) {
            // Layout changed between the bounds check and draw; fall back to the normal open.
        } finally {
            restoreAfterCapture(hideWhileCapturing, savedVisibility);
        }
    }

    @Nullable
    private static int[] hideForCapture(@Nullable View[] views) {
        if (views == null) {
            return null;
        }
        int[] saved = new int[views.length];
        for (int i = 0; i < views.length; i++) {
            if (views[i] != null) {
                saved[i] = views[i].getVisibility();
                if (saved[i] == View.VISIBLE) {
                    views[i].setVisibility(View.INVISIBLE); // INVISIBLE: no relayout, only this draw
                }
            }
        }
        return saved;
    }

    private static void restoreAfterCapture(@Nullable View[] views, @Nullable int[] saved) {
        if (views == null || saved == null) {
            return;
        }
        for (int i = 0; i < views.length; i++) {
            if (views[i] != null && views[i].getVisibility() != saved[i]) {
                views[i].setVisibility(saved[i]);
            }
        }
    }

    /** Drop any pending thumbnail when the tapped card routes somewhere other than playback. */
    public static void clear() {
        if (sPending != null && !sPending.frame.isRecycled()) {
            sPending.frame.recycle();
        }
        sPending = null;
    }

    public static boolean hasPending() {
        return sPending != null
                && SystemClock.uptimeMillis() - sPending.createdAtMs <= MAX_AGE_MS;
    }

    /** Consume the snapshot once. Package-private because only the player renders it. */
    @Nullable
    static LaunchSnapshot take() {
        LaunchSnapshot snapshot = sPending;
        sPending = null;
        if (snapshot == null) {
            return null;
        }
        if (SystemClock.uptimeMillis() - snapshot.createdAtMs > MAX_AGE_MS) {
            if (!snapshot.frame.isRecycled()) {
                snapshot.frame.recycle();
            }
            return null;
        }
        return snapshot;
    }

    static final class LaunchSnapshot {
        final Rect sourceBounds;
        final Bitmap frame;
        final long createdAtMs;

        LaunchSnapshot(Rect sourceBounds, Bitmap frame, long createdAtMs) {
            this.sourceBounds = new Rect(sourceBounds);
            this.frame = frame;
            this.createdAtMs = createdAtMs;
        }
    }
}
