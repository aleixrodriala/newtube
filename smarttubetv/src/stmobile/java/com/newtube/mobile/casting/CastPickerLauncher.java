package com.newtube.mobile.casting;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * The one entry point for opening the cast picker from any screen (player top bar, Browse top
 * bar). Owns the Android 16+ ACCESS_LOCAL_NETWORK runtime-permission gate so the flow isn't
 * duplicated per activity: LAN multicast/unicast (SSDP/DIAL/mDNS) dies with EPERM without the
 * permission, so ask first, then open the picker - on denial too, because manual "Link with TV
 * code" pairing is a plain internet call and still works.
 *
 * <p>Each host activity routes its {@code onRequestPermissionsResult} through
 * {@link #handlePermissionResult}; presentation stays per-screen via
 * {@link CastPickerSheet.SheetPresenter} (the player passes its immersive-safe
 * {@code showPlayerSheet}, plain screens pass {@link #presentPlain}).</p>
 */
public final class CastPickerLauncher {

    /**
     * Not in {@code Manifest.permission} yet with compileSdk 36's stubs; exists on Android 16+.
     */
    private static final String PERM_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";
    /** Shared across host activities; neither uses this code for anything else. */
    public static final int REQUEST_CODE_LOCAL_NETWORK = 112;

    private CastPickerLauncher() {
    }

    /**
     * Open the cast picker, first passing the local-network permission gate. When the permission
     * dialog has to be shown, the picker opens from the activity's
     * {@code onRequestPermissionsResult} -> {@link #handlePermissionResult} instead.
     */
    public static void open(Activity activity, CastPickerSheet.SheetPresenter presenter) {
        if (Build.VERSION.SDK_INT >= 36
                && activity.checkSelfPermission(PERM_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{PERM_LOCAL_NETWORK}, REQUEST_CODE_LOCAL_NETWORK);
            return;
        }
        // Discovery runs while the sheet is open and stops on dismiss (CastPickerSheet).
        new CastPickerSheet(activity).show(presenter);
    }

    /**
     * Companion to {@link #open}: call from the activity's {@code onRequestPermissionsResult}.
     *
     * @return true when the request was ours (the picker was opened), false to let the activity
     *         handle an unrelated request code
     */
    public static boolean handlePermissionResult(Activity activity, int requestCode,
                                                 CastPickerSheet.SheetPresenter presenter) {
        if (requestCode != REQUEST_CODE_LOCAL_NETWORK) {
            return false;
        }
        // Open the picker on denial too: discovery will find nothing, but manual
        // "Link with TV code" pairing is a plain internet call and still works.
        new CastPickerSheet(activity).show(presenter);
        return true;
    }

    /**
     * Default presenter for non-player screens (Browse): same expanded-state + transparent-frame
     * treatment as the player's {@code showPlayerSheet}, minus the immersive-window handling that
     * only the landscape player needs.
     */
    public static void presentPlain(BottomSheetDialog dialog) {
        dialog.setOnShowListener(d -> {
            View sheetView = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheetView != null) {
                // The frame's own white background would poke out around bg_mobile_sheet's corners.
                sheetView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheetView);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });
        dialog.show();
    }
}
