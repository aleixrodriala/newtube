package com.newtube.mobile.downloads;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.io.File;

/**
 * The context menu of a download: what the long-press on a Downloads card and the watch page's
 * "Downloaded" pill open. Same app dialog as every other card menu, so it looks native.
 */
public final class DownloadMenu {
    private DownloadMenu() {
    }

    public static void show(Context context, @Nullable DownloadItem item) {
        if (context == null || item == null) {
            return;
        }

        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        DownloadStorage storage = new DownloadStorage(context);

        if (item.isDone()) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.mobile_download_play),
                    optionItem -> {
                        dialog.closeDialog();
                        play(context, item);
                    }));
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.mobile_watch_share),
                    optionItem -> {
                        dialog.closeDialog();
                        share(context, item);
                    }));
        }
        if (item.isFailed()) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.mobile_download_retry),
                    optionItem -> {
                        dialog.closeDialog();
                        MobileDownloadService.retry(context, item);
                    }));
        }
        if (item.isActive()) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.mobile_download_cancel),
                    optionItem -> {
                        dialog.closeDialog();
                        MobileDownloadService.cancel(context, item);
                    }));
        } else {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.mobile_download_delete),
                    optionItem -> {
                        dialog.closeDialog();
                        delete(context, item);
                    }));
        }

        String subtitle = DownloadProgressText.of(context, item);
        dialog.showDialog(item.title + "\n" + subtitle);
    }

    /** Opens the file in the app's own player, exactly like tapping any other card. */
    public static void play(Context context, DownloadItem item) {
        if (!item.isDone() || item.outputUri == null) {
            return;
        }
        if (!new DownloadStorage(context).exists(item.outputUri)) {
            MessageHelpers.showMessage(context, R.string.mobile_download_file_missing);
            return;
        }
        PlaybackPresenter.instance(context).openVideo(DownloadsBridge.instance(context).videoFor(item));
    }

    public static void share(Context context, DownloadItem item) {
        Uri uri = new DownloadStorage(context).shareUri(item.outputUri);
        if (uri == null) {
            MessageHelpers.showMessage(context, R.string.mobile_download_file_missing);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType(item.isAudioOnly() ? "audio/mp4" : "video/mp4")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, item.title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(intent, context.getString(R.string.mobile_watch_share));
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(chooser);
    }

    public static void delete(Context context, DownloadItem item) {
        DownloadStorage storage = new DownloadStorage(context);
        storage.delete(item.outputUri);
        if (item.thumbPath != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(item.thumbPath).delete();
        }
        File parts = DownloadRegistry.partsDir(context);
        //noinspection ResultOfMethodCallIgnored
        new File(parts, item.id + ".video").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(parts, item.id + ".audio").delete();
        DownloadRegistry.instance(context).remove(item);
    }
}
