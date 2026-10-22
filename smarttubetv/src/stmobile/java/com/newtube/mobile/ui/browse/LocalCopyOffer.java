package com.newtube.mobile.ui.browse;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.LocalPlaylists;
import com.liskovsoft.smartyoutubetv2.common.misc.LocalPlaylistsCopy;
import com.liskovsoft.smartyoutubetv2.common.misc.LocalWatchLater;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;
import com.newtube.mobile.ui.common.MobileSnackbar;

import java.lang.ref.WeakReference;

/**
 * NEWTUBE(playlists): after signing in, one question about what was saved on the phone while signed
 * out and isn't on this account yet: copy it, or leave it on the phone. Nothing on the phone is
 * deleted either way. Asked once per batch: saving more while signed out asks again at the next
 * sign-in, about the new things only.
 */
final class LocalCopyOffer {
    /** The dialog on screen, if any; weak, so a screen recreated under it isn't kept alive. */
    @Nullable private static WeakReference<AlertDialog> sShowing;
    /** True while a copy runs: one at a time. */
    private static boolean sCopying;

    private LocalCopyOffer() {
    }

    static void maybeShow(@NonNull Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed() || isShowing() || sCopying
                || !YouTubeSignInService.instance().isSigned()) {
            return;
        }
        LocalPlaylists playlists = LocalPlaylists.instance(activity);
        LocalWatchLater watchLater = LocalWatchLater.instance(activity);
        if (!playlists.isCopyOfferPending() && !watchLater.isCopyOfferPending()) {
            return;
        }
        int[] counts = LocalPlaylistsCopy.countNew(activity);
        int playlistCount = counts[0];
        int videoCount = counts[1];
        if (playlistCount == 0 && videoCount == 0) {
            clearPending(activity); // all of it is on this account already
            return;
        }

        String what;
        if (playlistCount > 0 && videoCount > 0) {
            what = activity.getString(R.string.mobile_copy_offer_both,
                    activity.getResources().getQuantityString(R.plurals.mobile_copy_offer_playlists, playlistCount, playlistCount),
                    activity.getResources().getQuantityString(R.plurals.mobile_copy_offer_videos, videoCount, videoCount));
        } else if (playlistCount > 0) {
            what = activity.getResources().getQuantityString(R.plurals.mobile_copy_offer_playlists, playlistCount, playlistCount);
        } else {
            what = activity.getResources().getQuantityString(R.plurals.mobile_copy_offer_videos, videoCount, videoCount);
        }

        Context app = activity.getApplicationContext();
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity, R.style.MobileAlertDialog)
                .setTitle(R.string.mobile_copy_offer_title)
                .setMessage(activity.getString(R.string.mobile_copy_offer_body, what))
                .setPositiveButton(R.string.mobile_copy_offer_copy, (d, which) -> copy(app))
                .setNegativeButton(R.string.mobile_copy_offer_keep, (d, which) -> clearPending(app))
                // Back or a tap outside is "not now": it asks again next time.
                .setOnDismissListener(d -> sShowing = null)
                .show();
        sShowing = new WeakReference<>(dialog);
    }

    private static boolean isShowing() {
        AlertDialog dialog = sShowing != null ? sShowing.get() : null;
        return dialog != null && dialog.isShowing();
    }

    /** With the app's context: the copy outlives the screen that asked, and needs none of it. */
    private static void copy(@NonNull Context app) {
        clearPending(app);
        sCopying = true;
        MobileSnackbar.show(app, R.string.mobile_copy_working);
        LocalPlaylistsCopy.copyToAccount(app, (playlists, videos, failed) -> {
            sCopying = false;
            BrowsePresenter.instance(app).onPlaylistEdited();
            MobileSnackbar.show(app, failed > 0
                    ? app.getResources().getQuantityString(R.plurals.mobile_copy_done_failed, failed, failed)
                    : app.getString(R.string.mobile_copy_done), null, null);
        });
    }

    private static void clearPending(@NonNull Context context) {
        LocalPlaylists.instance(context).setCopyOfferPending(false);
        LocalWatchLater.instance(context).setCopyOfferPending(false);
    }
}
