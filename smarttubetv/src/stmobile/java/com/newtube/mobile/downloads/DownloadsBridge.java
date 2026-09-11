package com.newtube.mobile.downloads;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.misc.VideoDownloads;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone's implementation of the shared {@link VideoDownloads.Handler} seam, and the one
 * place a {@link DownloadItem} is turned into the {@link Video} the rest of the app understands.
 *
 * <p>Cards are cached per download id and mutated in place on progress, so the Downloads grid
 * can be refreshed with {@code ACTION_SYNC} (a payloaded partial rebind) instead of a full
 * replace - the same trick that keeps the watched-progress bar from blinking the card.
 */
public final class DownloadsBridge implements VideoDownloads.Handler {
    private static volatile DownloadsBridge sInstance;

    private final Context mContext;
    private final Map<String, Video> mCards = new HashMap<>();

    public static DownloadsBridge instance(Context context) {
        if (sInstance == null) {
            synchronized (DownloadsBridge.class) {
                if (sInstance == null) {
                    sInstance = new DownloadsBridge(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private DownloadsBridge(Context context) {
        mContext = context;
    }

    public static void install(Context context) {
        VideoDownloads.setHandler(instance(context));
    }

    @Override
    public void requestDownload(Context context, Video video) {
        DownloadPicker.show(context, video);
    }

    @Override
    public List<Video> listSectionVideos() {
        List<Video> result = new ArrayList<>();
        synchronized (mCards) {
            List<DownloadItem> items = DownloadRegistry.instance(mContext).itemsNewestFirst();
            Map<String, Video> alive = new HashMap<>();
            for (DownloadItem item : items) {
                Video card = videoFor(item);
                alive.put(item.id, card);
                result.add(card);
            }
            mCards.clear();
            mCards.putAll(alive);
        }
        return result;
    }

    @Override
    @Nullable
    public String localUriFor(String videoId) {
        DownloadItem item = DownloadRegistry.instance(mContext).findDone(videoId, DownloadOption.KIND_VIDEO);
        if (item == null || item.outputUri == null) {
            return null;
        }
        return new DownloadStorage(mContext).exists(item.outputUri) ? item.outputUri : null;
    }

    /** The registry entry behind a Downloads card, or null for any other card. */
    @Nullable
    public DownloadItem itemFor(@Nullable Video video) {
        if (video == null || video.downloadId == null) {
            return null;
        }
        return DownloadRegistry.instance(mContext).find(video.downloadId);
    }

    /** The card for an item - the same instance as long as the item lives, refreshed in place. */
    public Video videoFor(DownloadItem item) {
        Video card;
        synchronized (mCards) {
            card = mCards.get(item.id);
            if (card == null) {
                card = new Video();
                card.videoId = item.videoId;
                card.downloadId = item.id;
                card.title = item.title;
                card.author = item.author;
                mCards.put(item.id, card);
            }
        }
        sync(card, item);
        return card;
    }

    /** Refreshes every cached card from its item; returns the ones that exist. */
    public List<Video> syncCards() {
        List<Video> result = new ArrayList<>();
        DownloadRegistry registry = DownloadRegistry.instance(mContext);
        synchronized (mCards) {
            for (Map.Entry<String, Video> entry : mCards.entrySet()) {
                DownloadItem item = registry.find(entry.getKey());
                if (item != null) {
                    sync(entry.getValue(), item);
                    result.add(entry.getValue());
                }
            }
        }
        return result;
    }

    private void sync(Video card, DownloadItem item) {
        card.localUri = item.isDone() ? item.outputUri : null;
        card.cardImageUrl = item.thumbPath != null && new File(item.thumbPath).exists()
                ? Uri.fromFile(new File(item.thumbPath)).toString()
                : item.thumbUrl;

        String status = DownloadProgressText.of(mContext, item);
        card.secondTitle = item.author != null && !item.author.isEmpty()
                ? item.author + " · " + status
                : status;

        // Every not-ready state carries a badge (and the thumbnail is dimmed by the card), so a
        // download never looks like a video that can be played yet.
        switch (item.state) {
            case DownloadItem.STATE_DONE:
                card.badge = item.durationMs > 0 ? DownloadOptions.formatDuration(item.durationMs) : null;
                card.percentWatched = -1;
                break;
            case DownloadItem.STATE_DOWNLOADING: {
                int percent = item.progressPercent();
                card.badge = percent >= 0 ? percent + "%" : mContext.getString(R.string.mobile_download_badge_downloading);
                card.percentWatched = Math.max(1, percent);
                break;
            }
            case DownloadItem.STATE_PROCESSING:
                card.badge = mContext.getString(R.string.mobile_download_state_processing);
                card.percentWatched = 100;
                break;
            case DownloadItem.STATE_FAILED:
                card.badge = mContext.getString(R.string.mobile_download_badge_failed);
                card.percentWatched = -1;
                break;
            case DownloadItem.STATE_QUEUED:
            default:
                card.badge = mContext.getString(R.string.mobile_download_badge_queued);
                card.percentWatched = -1;
                break;
        }
    }

}
