package com.newtube.mobile.downloads;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.tv.R;

/** The one-line status every surface shows for an item: card meta, notification, menus. */
final class DownloadProgressText {
    private DownloadProgressText() {
    }

    static String of(Context context, DownloadItem item) {
        switch (item.state) {
            case DownloadItem.STATE_QUEUED:
                return context.getString(R.string.mobile_download_state_queued);
            case DownloadItem.STATE_DOWNLOADING: {
                int percent = item.progressPercent();
                String done = DownloadOptions.formatBytes(item.bytesDone);
                if (percent < 0) {
                    return context.getString(R.string.mobile_download_state_downloading_bytes, done);
                }
                return context.getString(R.string.mobile_download_state_downloading, percent,
                        done, DownloadOptions.formatBytes(item.bytesTotal));
            }
            case DownloadItem.STATE_PROCESSING:
                return context.getString(R.string.mobile_download_state_processing);
            case DownloadItem.STATE_FAILED:
                return item.error != null ? item.error : context.getString(R.string.mobile_download_error_generic);
            case DownloadItem.STATE_DONE:
            default: {
                String quality = item.isAudioOnly()
                        ? context.getString(R.string.mobile_download_audio_only)
                        : item.qualityLabel != null ? item.qualityLabel : "";
                String size = DownloadOptions.formatBytes(item.bytesTotal);
                return size.isEmpty() ? quality : quality + " · " + size;
            }
        }
    }
}
