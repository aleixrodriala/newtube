package com.newtube.mobile.ui.dialog;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A {@link RecyclerView} that can be capped at a maximum height.
 *
 * <p>Used by the bottom-sheet presentation of {@link MobileAppDialogActivity}: in sheet mode the
 * sheet is {@code wrap_content} so short menus (context menu, Quality/Speed/Subtitles pickers) size
 * to their content, but a long list (e.g. a settings screen shown as a sheet) must stop growing and
 * start scrolling instead of running off the top of the screen. Setting a max height (via
 * {@link #setMaxHeight}) makes {@code wrap_content} measure "content height, but no taller than N".
 * In full-screen mode the max is cleared ({@code 0}) so the list fills the window as normal.</p>
 */
public class MaxHeightRecyclerView extends RecyclerView {
    /** 0 = unlimited (full-screen mode). */
    private int mMaxHeight;

    public MaxHeightRecyclerView(@NonNull Context context) {
        super(context);
    }

    public MaxHeightRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public MaxHeightRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setMaxHeight(int maxHeightPx) {
        if (mMaxHeight != maxHeightPx) {
            mMaxHeight = maxHeightPx;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, capHeightSpec(heightSpec, mMaxHeight));
    }

    /**
     * The tighter of the cap and the parent's own limit. Replacing the parent's spec outright (the
     * old behaviour) let the list measure taller than the window it sits in whenever the cap was
     * computed from stale metrics - the landscape sheet whose last rows could not be scrolled to.
     */
    static int capHeightSpec(int heightSpec, int maxHeight) {
        if (maxHeight <= 0) {
            return heightSpec;
        }
        int mode = MeasureSpec.getMode(heightSpec);
        int size = MeasureSpec.getSize(heightSpec);
        int limit = mode == MeasureSpec.UNSPECIFIED ? maxHeight : Math.min(size, maxHeight);
        return MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST);
    }
}
