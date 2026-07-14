package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;

import java.lang.ref.WeakReference;

/**
 * Process-local route back to the one live player while Android has re-parented it into a pinned
 * PiP task. The launcher normally resumes the separate Browse task; routing from the player
 * instance itself makes Android expand that exact task and cannot create a duplicate player.
 */
public final class SystemPipBridge {
    private static final String ACTION_RESTORE_FROM_PIP =
            "com.newtube.mobile.action.RESTORE_FROM_PIP";
    private static WeakReference<MobilePlaybackActivity> sActivity = new WeakReference<>(null);

    private SystemPipBridge() {
    }

    static void attach(MobilePlaybackActivity activity) {
        sActivity = new WeakReference<>(activity);
    }

    static void detach(MobilePlaybackActivity activity) {
        if (sActivity.get() == activity) {
            sActivity = new WeakReference<>(null);
        }
    }

    /** Returns true only when a live pinned player accepted the launcher restore. */
    public static boolean restoreFromLauncher(Activity launcher) {
        MobilePlaybackActivity player = sActivity.get();
        if (player == null || player.isFinishing() || player.isDestroyed()
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.N
                || !player.isInPictureInPictureMode()) {
            return false;
        }

        Intent restore = new Intent(player, MobilePlaybackActivity.class)
                .setAction(ACTION_RESTORE_FROM_PIP)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log(
                "pip-restore pinnedTask=" + player.getTaskId() + " foregroundTask=" + launcher.getTaskId());
        player.startActivity(restore);
        return true;
    }
}
