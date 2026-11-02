package com.newtube.mobile.ui.browse;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.LocalPlaylistsCopy;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;
import com.newtube.mobile.ui.common.MobileSnackbar;

import java.lang.ref.WeakReference;

import io.reactivex.rxjava3.disposables.Disposable;

/**
 * NEWTUBE(playlists): after signing in, one question about what was saved on the phone while signed
 * out and isn't on this account yet: copy it, or leave it on the phone. Nothing on the phone is
 * deleted either way. Each account is asked about what it hasn't got
 * ({@link LocalPlaylistsCopy#whatToOffer}): "Keep on phone" holds until something more is saved,
 * Back until the next sign-in here (or the app's next start), and a copy that didn't get everything
 * is offered again then.
 */
final class LocalCopyOffer {
    /** The dialog on screen, if any; weak, so a screen recreated under it isn't kept alive. */
    @Nullable private static WeakReference<AlertDialog> sShowing;
    /** The copy running, if any: one at a time. It stops itself if the account changes. */
    @Nullable private static Disposable sCopy;
    /** The account already asked in this process, whatever the answer: once, not on every return to Browse. */
    @Nullable private static String sAskedFor;

    private LocalCopyOffer() {
    }

    static void maybeShow(@NonNull Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed() || isShowing() || sCopy != null
                || !YouTubeSignInService.instance().isSigned()) {
            return;
        }
        String account = LocalPlaylistsCopy.accountKey();
        if (account.equals(sAskedFor)) {
            return;
        }
        int[] counts = LocalPlaylistsCopy.whatToOffer(activity);
        if (counts == null) {
            return;
        }
        int playlistCount = counts[0];
        int videoCount = counts[1];

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
                .setNegativeButton(R.string.mobile_copy_offer_keep, (d, which) -> LocalPlaylistsCopy.keepOnPhone(app))
                // Back or a tap outside is "not now": asked again at the next sign-in.
                .setOnDismissListener(d -> sShowing = null)
                .show();
        sAskedFor = account;
        sShowing = new WeakReference<>(dialog);
    }

    /** A sign-in (or a switch) while Browse is in front: whoever is signed in now may be asked. */
    static void onAccountChanged(@NonNull Activity activity) {
        sAskedFor = null;
        maybeShow(activity);
    }

    private static boolean isShowing() {
        AlertDialog dialog = sShowing != null ? sShowing.get() : null;
        return dialog != null && dialog.isShowing();
    }

    /** With the app's context: the copy outlives the screen that asked, and needs none of it. */
    private static void copy(@NonNull Context app) {
        MobileSnackbar.show(app, R.string.mobile_copy_working);
        sCopy = LocalPlaylistsCopy.copyToAccount(app, outcome -> {
            sCopy = null;
            BrowsePresenter.instance(app).onPlaylistEdited();
            MobileSnackbar.show(app, doneMessage(app, outcome), null, null);
        });
    }

    /** Copied; copied but for some playlists or videos (by name); nothing copied; or stopped. */
    @NonNull
    private static String doneMessage(@NonNull Context app, @NonNull LocalPlaylistsCopy.Outcome outcome) {
        if (outcome.stopped()) {
            return app.getString(R.string.mobile_copy_stopped);
        }
        int failedPlaylists = outcome.failedPlaylists();
        int failedVideos = outcome.failedVideos();
        if (failedPlaylists == 0 && failedVideos == 0) {
            return app.getString(R.string.mobile_copy_done);
        }
        if (outcome.playlists() == 0 && outcome.videos() == 0) {
            return app.getString(R.string.mobile_copy_failed);
        }
        String playlists = app.getResources().getQuantityString(
                R.plurals.mobile_copy_offer_playlists, failedPlaylists, failedPlaylists);
        String videos = app.getResources().getQuantityString(R.plurals.local_playlist_videos, failedVideos, failedVideos);
        String what = failedVideos == 0 ? playlists
                : failedPlaylists == 0 ? videos
                : app.getString(R.string.mobile_copy_offer_both, playlists, videos);
        return app.getString(R.string.mobile_copy_done_partly, what);
    }
}
