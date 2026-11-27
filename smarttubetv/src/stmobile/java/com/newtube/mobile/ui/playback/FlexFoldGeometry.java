package com.newtube.mobile.ui.playback;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.window.layout.DisplayFeature;
import androidx.window.layout.FoldingFeature;
import androidx.window.layout.WindowLayoutInfo;

/**
 * Computes tabletop / Flex mode layout geometry from Jetpack WindowManager folding features.
 *
 * <p>One job: translate a horizontal {@link FoldingFeature} into exact local pixel split bounds
 * inside the player root view, or {@code null} when the window is flat, vertical-folded, or too
 * compact for split-panel Flex controls.</p>
 */
final class FlexFoldGeometry {

    static final class FlexSplit {
        final int topPanelHeightPx;
        final int hingeSpacerHeightPx;

        FlexSplit(int topPanelHeightPx, int hingeSpacerHeightPx) {
            this.topPanelHeightPx = topPanelHeightPx;
            this.hingeSpacerHeightPx = hingeSpacerHeightPx;
        }
    }

    private FlexFoldGeometry() {
    }

    /** Returns true when {@code feature} describes a horizontal half-opened foldable hinge. */
    static boolean isTabletopFold(@Nullable FoldingFeature feature) {
        return feature != null
                && feature.getState() == FoldingFeature.State.HALF_OPENED
                && feature.getOrientation() == FoldingFeature.Orientation.HORIZONTAL;
    }

    /** Finds the active tabletop folding feature in {@code info} and maps it to {@code rootView}. */
    @Nullable
    static FlexSplit computeFromLayoutInfo(@Nullable WindowLayoutInfo info,
            @Nullable View rootView, int minHalfPx) {
        if (info == null || rootView == null || rootView.getHeight() <= 0) {
            return null;
        }
        int[] rootLoc = new int[2];
        rootView.getLocationInWindow(rootLoc);
        for (DisplayFeature feature : info.getDisplayFeatures()) {
            if (feature instanceof FoldingFeature) {
                FoldingFeature fold = (FoldingFeature) feature;
                if (!isTabletopFold(fold)) {
                    continue;
                }
                Rect bounds = fold.getBounds();
                return computeSplit(rootLoc[1], rootView.getHeight(),
                        bounds.top, bounds.bottom, minHalfPx);
            }
        }
        return null;
    }

    /**
     * Maps window-relative fold bounds into local top height + hinge spacer inside a root view
     * whose height is {@code rootHeight}. Returns {@code null} if either half is smaller than
     * {@code minHalfPx}.
     */
    @Nullable
    static FlexSplit computeSplit(int rootTopInWindow, int rootHeight,
            int foldTopInWindow, int foldBottomInWindow, int minHalfPx) {
        if (rootHeight <= 0 || minHalfPx <= 0 || foldBottomInWindow < foldTopInWindow) {
            return null;
        }
        int localTop = foldTopInWindow - rootTopInWindow;
        int localBottom = foldBottomInWindow - rootTopInWindow;
        int bottomRemaining = rootHeight - localBottom;
        if (localTop < minHalfPx || bottomRemaining < minHalfPx) {
            return null;
        }
        return new FlexSplit(localTop, Math.max(0, localBottom - localTop));
    }
}
