package com.newtube.mobile.downloads;

import android.content.Context;
import android.media.MediaMuxer;
import android.util.Log;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.player.Media3SourceFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Runs one {@link DownloadItem} to completion on the service's worker thread: fetch the parts,
 * fetch the thumbnail, mux, publish. Progress lands on the item and goes out through the
 * registry; the service turns it into the notification.
 */
final class DownloadJob {
    private static final String TAG = DownloadJob.class.getSimpleName();

    interface Listener {
        void onProgress(DownloadItem item);
    }

    private final Context mContext;
    private final DownloadItem mItem;
    private final DownloadRegistry mRegistry;
    private final DownloadStorage mStorage;
    private final StreamFetcher mFetcher;
    private final Listener mListener;
    private long mLastNotifyMs;

    DownloadJob(Context context, DownloadItem item, DownloadRegistry registry, Listener listener) {
        mContext = context.getApplicationContext();
        mItem = item;
        mRegistry = registry;
        mStorage = new DownloadStorage(mContext);
        mFetcher = new StreamFetcher(Media3SourceFactory.MEDIA_USER_AGENT);
        mListener = listener;
    }

    void run() {
        mItem.state = DownloadItem.STATE_DOWNLOADING;
        mItem.error = null;
        mRegistry.notifyChanged();

        File videoPart = mItem.isAudioOnly() ? null : new File(DownloadRegistry.partsDir(mContext), mItem.id + ".video");
        File audioPart = mItem.audioItag != null ? new File(DownloadRegistry.partsDir(mContext), mItem.id + ".audio") : null;

        try {
            fetchThumbnail();
            fetchParts(videoPart, audioPart);
            if (mItem.cancelRequested) {
                throw new StreamFetcher.CancelledException();
            }
            publish(videoPart, audioPart);
            mItem.state = DownloadItem.STATE_DONE;
            mItem.error = null;
            retirePreviousCopies();
            deleteParts(videoPart, audioPart);
        } catch (StreamFetcher.CancelledException e) {
            deleteParts(videoPart, audioPart);
            mRegistry.remove(mItem);
            return;
        } catch (StreamFetcher.UrlRefusedException e) {
            Log.w(TAG, "download refused: " + e);
            fail(mContext.getString(R.string.mobile_download_error_link));
        } catch (IOException e) {
            Log.w(TAG, "download failed: " + e);
            fail(mContext.getString(R.string.mobile_download_error_generic));
        } catch (RuntimeException e) {
            Log.e(TAG, "download crashed", e);
            fail(mContext.getString(R.string.mobile_download_error_generic));
        }

        mRegistry.notifyChanged();
    }

    private void fail(String message) {
        mItem.state = DownloadItem.STATE_FAILED;
        mItem.error = message;
    }

    // ---------------------------------------------------------------------------------
    // Fetch
    // ---------------------------------------------------------------------------------

    private void fetchThumbnail() {
        if (mItem.thumbPath != null && new File(mItem.thumbPath).exists()) {
            return;
        }
        if (mItem.thumbUrl == null) {
            return;
        }
        File thumb = new File(DownloadRegistry.dir(mContext), mItem.id + ".jpg");
        if (mFetcher.fetchSmall(mItem.thumbUrl, thumb) != null) {
            mItem.thumbPath = thumb.getAbsolutePath();
        }
    }

    /** Client routes to walk when googlevideo refuses the links, like the player's remint loop. */
    private static final int MAX_ROUTE_ATTEMPTS = 4;

    private void fetchParts(@Nullable File videoPart, @Nullable File audioPart) throws IOException {
        // The URLs from the enqueue moment serve a fresh job; a Retry (or a link the server
        // meanwhile refused) resolves the same itags again from a fresh format info.
        if (mItem.videoUrl == null && mItem.audioUrl == null) {
            resolveUrls();
        }

        for (int attempt = 1; ; attempt++) {
            try {
                fetchPartsOnce(videoPart, audioPart);
                return;
            } catch (StreamFetcher.UrlRefusedException refused) {
                if (attempt >= MAX_ROUTE_ATTEMPTS) {
                    throw refused;
                }
                // A media 403 is the ordinary fate of one /player client's links (the TV routes
                // without a PO token, measured on the Pixel: the player itself gets it and
                // recovers). Do exactly what ErrorFixerController does: quarantine the route,
                // rotate the client, mint fresh links for the same itags.
                Log.w(TAG, "links refused (route " + attempt + "/" + MAX_ROUTE_ATTEMPTS + "), rotating client: " + refused);
                NetPath.log("download route-refused attempt=" + attempt + " video=" + mItem.videoId);
                VideoInfoService.instance().markCurrentPlaybackRouteForbidden();
                YouTubeServiceManager.instance().applyNoPlaybackFix();
                resolveUrls();
            }
        }
    }

    private void fetchPartsOnce(@Nullable File videoPart, @Nullable File audioPart) throws IOException {
        long videoLen = mItem.videoLength;
        long audioLen = mItem.audioLength;
        long total = videoLen > 0 && (audioPart == null || audioLen > 0) ? videoLen + Math.max(0, audioLen) : -1;
        if (total > 0) {
            mItem.bytesTotal = total;
        }

        final long[] done = {0, 0};
        if (videoPart != null) {
            if (mItem.videoUrl == null) {
                throw new IOException("no video link");
            }
            mFetcher.fetch(mItem.videoUrl, videoPart, videoLen, (fileBytes, streamTotal) -> {
                done[0] = fileBytes;
                return report(done[0] + done[1]);
            });
            done[0] = videoPart.length();
        }
        if (audioPart != null) {
            if (mItem.audioUrl == null) {
                throw new IOException("no audio link");
            }
            mFetcher.fetch(mItem.audioUrl, audioPart, audioLen, (fileBytes, streamTotal) -> {
                done[1] = fileBytes;
                return report(done[0] + done[1]);
            });
            done[1] = audioPart.length();
        }
        report(done[0] + done[1]);
    }

    private boolean report(long bytesDone) {
        mItem.bytesDone = bytesDone;
        long now = System.currentTimeMillis();
        if (now - mLastNotifyMs >= 500) {
            mLastNotifyMs = now;
            mRegistry.notifyChanged();
            mListener.onProgress(mItem);
        }
        return !mItem.cancelRequested;
    }

    /** Fresh links for the itags this item was created with. */
    private void resolveUrls() throws IOException {
        MediaItemFormatInfo info;
        try {
            info = YouTubeMediaItemService.instance()
                    .getFormatInfoObserve(mItem.videoId).blockingFirst();
        } catch (RuntimeException e) {
            throw new IOException("format info unavailable: " + e.getMessage(), e);
        }
        if (info == null || info.isUnplayable()) {
            throw new IOException("video unavailable");
        }

        MediaFormat video = mItem.videoItag != null ? findByItag(info.getAdaptiveFormats(), mItem.videoItag) : null;
        if (video == null && mItem.videoItag != null) {
            video = findByItag(info.getUrlFormats(), mItem.videoItag);
        }
        MediaFormat audio = mItem.audioItag != null ? findByItag(info.getAdaptiveFormats(), mItem.audioItag) : null;

        if (mItem.videoItag != null && video == null) {
            throw new StreamFetcher.UrlRefusedException(0);
        }
        if (mItem.audioItag != null && audio == null) {
            throw new StreamFetcher.UrlRefusedException(0);
        }

        mItem.videoUrl = video != null ? video.getUrl() : null;
        mItem.audioUrl = audio != null ? audio.getUrl() : null;
        mItem.videoLength = DownloadOptions.lengthOf(video);
        mItem.audioLength = DownloadOptions.lengthOf(audio);
    }

    @Nullable
    private static MediaFormat findByItag(@Nullable List<MediaFormat> formats, String itag) {
        if (formats == null) {
            return null;
        }
        for (MediaFormat format : formats) {
            if (itag.equals(format.getITag()) && format.getUrl() != null) {
                return format;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------
    // Publish
    // ---------------------------------------------------------------------------------

    private void publish(@Nullable File videoPart, @Nullable File audioPart) throws IOException {
        mItem.state = DownloadItem.STATE_PROCESSING;
        mRegistry.notifyChanged();
        mListener.onProgress(mItem);

        String fileName = DownloadOptions.fileNameFor(mItem.title, mItem.videoId, mItem.isAudioOnly() ? "m4a" : "mp4");
        DownloadStorage.Target target = mStorage.create(fileName, mItem.isAudioOnly());
        MediaMuxer muxer = null;
        try {
            muxer = mStorage.openMuxer(target);
            long durationMs = MediaMuxHelper.mux(videoPart, audioPart, muxer,
                    (timeUs, durationUs) -> !mItem.cancelRequested);
            muxer.release();
            muxer = null;
            mStorage.commit(target);
            mItem.durationMs = durationMs;
            mItem.outputUri = target.uri.toString();
            mItem.fileName = fileName;
            long size = mStorage.sizeOf(mItem.outputUri);
            if (size > 0) {
                mItem.bytesTotal = size;
                mItem.bytesDone = size;
            }
        } catch (IOException | RuntimeException e) {
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (RuntimeException ignored) {
                }
            }
            mStorage.abandon(target);
            if (mItem.cancelRequested) {
                throw new StreamFetcher.CancelledException();
            }
            throw e instanceof IOException ? (IOException) e : new IOException("mux failed", e);
        }
    }

    /** One copy per video and kind: a re-download replaces the earlier file. */
    private void retirePreviousCopies() {
        for (DownloadItem other : mRegistry.items()) {
            if (other != mItem && other.isDone() && other.videoId.equals(mItem.videoId) && other.kind == mItem.kind) {
                mStorage.delete(other.outputUri);
                if (other.thumbPath != null) {
                    //noinspection ResultOfMethodCallIgnored
                    new File(other.thumbPath).delete();
                }
                mRegistry.remove(other);
            }
        }
    }

    private static void deleteParts(@Nullable File videoPart, @Nullable File audioPart) {
        if (videoPart != null) {
            //noinspection ResultOfMethodCallIgnored
            videoPart.delete();
        }
        if (audioPart != null) {
            //noinspection ResultOfMethodCallIgnored
            audioPart.delete();
        }
    }
}
