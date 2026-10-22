package com.liskovsoft.smartyoutubetv2.common.utils;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.VideoMenuPresenter.VideoMenuCallback;
import com.liskovsoft.smartyoutubetv2.common.misc.LocalPlaylists;
import com.liskovsoft.smartyoutubetv2.common.misc.LocalWatchLater;
import com.liskovsoft.smartyoutubetv2.common.misc.PhoneUi;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;

import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(playlists): the "Save to playlist" sheet for a signed-out phone: New playlist, then Watch
 * later and the phone's playlists as checkboxes, each tick saving or removing at once - YouTube's
 * own sheet, kept on the phone ({@link LocalWatchLater}, {@link LocalPlaylists}).
 */
public final class LocalPlaylistSheet {
    private LocalPlaylistSheet() {
    }

    /** The phone's sheet stands in for the account's: a phone that isn't signed in. */
    public static boolean applies() {
        return PhoneUi.isEnabled() && !YouTubeSignInService.instance().isSigned();
    }

    public static void show(@NonNull Context context, @Nullable Video video, @Nullable VideoMenuCallback callback,
                            @Nullable Runnable onFinish) {
        if (!LocalWatchLater.canSave(video)) {
            return;
        }

        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        LocalWatchLater watchLater = LocalWatchLater.instance(context);
        LocalPlaylists playlists = LocalPlaylists.instance(context);

        dialog.appendSingleButton(UiOptionItem.from(
                context.getString(R.string.add_video_to_new_playlist),
                optionItem -> {
                    dialog.closeDialog();
                    showCreateDialog(context, video);
                }));

        List<OptionItem> options = new ArrayList<>();
        options.add(UiOptionItem.from(
                context.getString(R.string.header_watch_later),
                item -> {
                    if (item.isSelected()) {
                        watchLater.add(video);
                    } else {
                        watchLater.remove(video.videoId);
                        onRemoved(video, null, callback);
                    }
                    BrowsePresenter.instance(context).onPlaylistEdited();
                },
                watchLater.contains(video.videoId)));
        for (LocalPlaylists.LocalPlaylist playlist : playlists.playlists()) {
            options.add(UiOptionItem.from(
                    playlist.title,
                    item -> {
                        if (item.isSelected()) {
                            playlists.add(playlist.id, video);
                        } else {
                            playlists.remove(playlist.id, video.videoId);
                            onRemoved(video, playlist.id, callback);
                        }
                        BrowsePresenter.instance(context).onPlaylistEdited();
                    },
                    playlists.contains(playlist.id, video.videoId)));
        }
        // No category title: it would repeat the sheet's own title word for word.
        dialog.appendCheckedCategory(null, options);
        dialog.showDialog(context.getString(R.string.dialog_add_to_playlist), onFinish);
    }

    /** Asks for a name, makes a phone playlist holding {@code video} (none: an empty playlist). */
    public static void showCreateDialog(@NonNull Context context, @Nullable Video video) {
        SimpleEditDialog.show(
                context,
                context.getString(R.string.create_playlist),
                context.getString(R.string.playlist_name_hint),
                null,
                newValue -> {
                    if (newValue == null || newValue.trim().isEmpty()) {
                        return false;
                    }
                    LocalPlaylists.instance(context).create(newValue, video);
                    BrowsePresenter.instance(context).onPlaylistEdited();
                    MessageHelpers.showMessage(context, context.getString(R.string.saved_to_playlist_fmt, newValue.trim()));
                    return true;
                });
    }

    /** Asks for a new name for a phone playlist. */
    public static void showRenameDialog(@NonNull Context context, @NonNull Video playlistCard, @Nullable Runnable onDone) {
        LocalPlaylists.LocalPlaylist playlist = LocalPlaylists.instance(context).get(playlistCard.playlistId);
        if (playlist == null) {
            return;
        }
        SimpleEditDialog.show(
                context,
                context.getString(R.string.rename_playlist),
                context.getString(R.string.playlist_name_hint),
                playlist.title,
                newValue -> {
                    if (newValue == null || newValue.trim().isEmpty()) {
                        return false;
                    }
                    LocalPlaylists.instance(context).rename(playlist.id, newValue);
                    playlistCard.title = newValue.trim();
                    BrowsePresenter.instance(context).onPlaylistEdited();
                    if (onDone != null) {
                        onDone.run();
                    }
                    return true;
                });
    }

    /** The video's card leaves the list it was removed from, when that list is the one on screen. */
    private static void onRemoved(@NonNull Video video, @Nullable String playlistId, @Nullable VideoMenuCallback callback) {
        boolean shownThere = playlistId != null
                ? playlistId.equals(video.localPlaylistId)
                : video.sectionId == LocalWatchLater.SECTION_ID;
        if (callback != null && shownThere) {
            callback.onItemAction(video, VideoMenuCallback.ACTION_REMOVE_FROM_PLAYLIST);
        }
    }
}
