package com.newtube.mobile.ui.common;

import android.app.Dialog;
import android.content.Context;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

/**
 * Shared behaviour for the Material bottom sheets that open at a fixed fraction of the screen
 * (comments, live chat) rather than wrapping their content.
 *
 * <p>The sheet's SURFACE is not handled here: it comes from {@code bottomSheetDialogTheme} in
 * {@code styles_mobile.xml}, because Material re-applies its own {@code MaterialShapeDrawable}
 * to the sheet frame on every layout and quietly wins over any background assigned in code.</p>
 */
public final class MobileSheets {
    private MobileSheets() {}

    /**
     * Opens {@code dialog} expanded at {@code heightFraction} of the screen.
     *
     * <p>Deliberately NOT {@code getResources().getDisplayMetrics()}: {@code MotherActivity}
     * replaces the process's DisplayMetrics with a single cached instance built at the first
     * Activity's onCreate, so its {@code heightPixels} is frozen at whatever orientation the app
     * happened to start in. Launch the app in landscape and the comments sheet opened 918px tall
     * (85% of 1080) in portrait forever after. The live display is the right measure.</p>
     */
    public static void expandTo(Dialog dialog, float heightFraction) {
        if (dialog == null) {
            return;
        }

        View frame = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (frame == null) {
            return;
        }

        int height = Math.round(screenHeightPx(dialog.getContext()) * heightFraction);
        frame.getLayoutParams().height = height;
        frame.requestLayout();

        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(frame);
        behavior.setPeekHeight(height);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    private static int screenHeightPx(Context context) {
        WindowManager windowManager =
                (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return context.getResources().getDisplayMetrics().heightPixels;
        }

        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return metrics.heightPixels;
    }
}
