package com.newtube.mobile.downloads;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.MobileBrowseActivity;
import com.newtube.mobile.ui.common.MetaSeparator;
import com.newtube.mobile.ui.common.MobileSnackbar;

import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * "Download" tapped: resolve the formats (the same single-flight /player fetch the player uses,
 * so from the watch page it is instant), then offer the rungs as one app dialog - the same
 * bottom sheet every other picker in the app is - and hand the choice to the service.
 */
public final class DownloadPicker {
    private static final long SLOW_FETCH_NOTICE_MS = 700;
    /** A little over the action Snackbar's 4 s on screen. */
    private static final long REWORD_WINDOW_MS = 6_000;

    @Nullable private static Disposable sFetch;

    private DownloadPicker() {
    }

    public static void show(Context context, Video video) {
        if (context == null || video == null || video.videoId == null) {
            return;
        }
        if (video.isLive || video.isUpcoming) {
            MobileSnackbar.show(context, R.string.mobile_download_unavailable_live);
            return;
        }

        RxHelper.disposeActions(sFetch);

        Handler main = new Handler(Looper.getMainLooper());
        Runnable slowNotice = () -> MobileSnackbar.show(context, R.string.mobile_download_preparing);
        main.postDelayed(slowNotice, SLOW_FETCH_NOTICE_MS);

        sFetch = YouTubeMediaItemService.instance()
                .getFormatInfoObserve(video.videoId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(info -> {
                    main.removeCallbacks(slowNotice);
                    present(context, video, info);
                }, error -> {
                    main.removeCallbacks(slowNotice);
                    MobileSnackbar.show(context, R.string.mobile_download_unavailable);
                });
    }

    private static void present(Context context, Video video, @Nullable MediaItemFormatInfo info) {
        if (info == null || info.isUnplayable()) {
            String reason = info != null ? info.getPlayabilityReason() : null;
            MobileSnackbar.show(context, reason != null ? reason : context.getString(R.string.mobile_download_unavailable), null, null);
            return;
        }

        List<DownloadOption> options = DownloadOptions.build(info.getAdaptiveFormats(), info.getUrlFormats());
        if (options.isEmpty()) {
            MobileSnackbar.show(context, R.string.mobile_download_unavailable);
            return;
        }

        // The format info carries the canonical title/author; the card may only have a partial one.
        String title = video.getTitle() != null ? video.getTitle() : info.getTitle();
        String author = video.getAuthor() != null ? video.getAuthor() : info.getAuthor();
        // A card built from a download carries its local file:// thumbnail, which a new job cannot
        // fetch (the file goes with the deleted copy): only http(s) card images are used as is.
        String cardImage = video.getCardImageUrl();
        String thumb = cardImage != null && cardImage.startsWith("http") ? cardImage
                : "https://i.ytimg.com/vi/" + video.videoId + "/hqdefault.jpg";

        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        DownloadRegistry registry = DownloadRegistry.instance(context);

        for (DownloadOption option : options) {
            String label = option.isAudioOnly()
                    ? context.getString(R.string.mobile_download_audio_only)
                    : option.qualityLabel;
            String format = option.isAudioOnly() ? "M4A" : "MP4";
            String size = DownloadOptions.formatBytes(option.totalBytes);
            String description = size.isEmpty() ? format : format + MetaSeparator.DOT + size;

            DownloadItem existing = registry.findDone(video.videoId, option.kind);
            boolean alreadyHere = existing != null && (option.isAudioOnly()
                    || (existing.qualityLabel != null && existing.qualityLabel.equals(option.qualityLabel)));
            if (alreadyHere) {
                description += MetaSeparator.DOT + context.getString(R.string.mobile_download_already);
            }

            dialog.appendSingleButton(UiOptionItem.from(label, description, optionItem -> {
                dialog.closeDialog();
                if (alreadyHere) {
                    // NEWTUBE(snackbar): the same file is already on the device - say so rather
                    // than queue a second copy under a "Download started".
                    MobileSnackbar.show(context, context.getString(R.string.mobile_download_already_here),
                            context.getString(R.string.mobile_download_view), () -> openDownloads(context));
                    return;
                }
                DownloadItem item = DownloadItem.create(video.videoId, title != null ? title : video.videoId,
                        author, thumb, option);
                MobileDownloadService.enqueue(context, item);
                // NEWTUBE(snackbar): confirm with the size, and a way to the Downloads tab.
                String started = size.isEmpty()
                        ? context.getString(R.string.mobile_download_started)
                        : context.getString(R.string.mobile_download_started_size, size);
                MobileSnackbar.show(context, started,
                        context.getString(R.string.mobile_download_view), () -> openDownloads(context));
                rewordWhenDone(context, item, started, size);
            }));
        }

        dialog.showDialog(context.getString(R.string.dialog_download));
    }

    /**
     * A small file can finish while its "Download started" message is still up (next to a pill that
     * already says "Downloaded"); reword the message when that happens. The listener lets go after
     * the item ends or after a few seconds, whichever comes first.
     */
    private static void rewordWhenDone(Context context, DownloadItem item, String started, String size) {
        DownloadRegistry registry = DownloadRegistry.instance(context);
        Handler main = new Handler(Looper.getMainLooper());
        DownloadRegistry.Listener[] holder = new DownloadRegistry.Listener[1];
        Runnable release = () -> registry.removeListener(holder[0]);
        holder[0] = () -> {
            if (item.isDone()) {
                MobileSnackbar.replaceText(started, size.isEmpty()
                        ? context.getString(R.string.mobile_download_done)
                        : context.getString(R.string.mobile_download_done_size, size));
                release.run();
            } else if (item.isFailed()) {
                release.run();
            }
        };
        registry.addListener(holder[0]);
        main.postDelayed(release, REWORD_WINDOW_MS);
    }

    /** The Downloads tab, as the download notification opens it. */
    private static void openDownloads(Context context) {
        context.startActivity(new Intent(context, MobileBrowseActivity.class)
                .setAction(MobileBrowseActivity.ACTION_OPEN_DOWNLOADS)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }
}
