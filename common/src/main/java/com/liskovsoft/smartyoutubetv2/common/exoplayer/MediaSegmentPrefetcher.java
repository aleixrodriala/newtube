package com.liskovsoft.smartyoutubetv2.common.exoplayer;

import android.net.Uri;

import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.cache.Cache;
import com.google.android.exoplayer2.upstream.cache.CacheUtil;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NEWTUBE(mobile-ttff): head-of-stream prefetch into the shared media disk cache.
 *
 * When the tap-time format-info prefetch completes (while the player Activity is still inflating),
 * start downloading the first bytes of the PREDICTED video + audio streams into the same
 * {@link Cache} the player reads through ({@link ExoMediaSourceFactory}'s CacheDataSource keys
 * purely by URI). By the time ExoPlayer issues its first segment requests, they are local disk
 * reads instead of network round-trips.
 *
 * Prediction mirrors the mobile default selection (1080p-capped VP9 + its opus audio) with plain
 * heuristics; a mis-prediction costs nothing on the critical path - the player just fetches its own
 * choice over the (already preconnected) warm connection, and the wasted bytes are bounded and LRU-
 * evicted. Contention with a starting player is safe by construction: playback's CacheDataSource is
 * non-blocking (no FLAG_BLOCK_ON_CACHE), so a span the prefetcher still holds is simply bypassed
 * straight to network.
 *
 * Only useful where there IS dead time between format info and prepare() (the Home-tap path);
 * same-player loads (related taps) go straight from format info to prepare, so they're not hooked.
 *
 * Off by default -> TV byte-for-byte unchanged.
 */
public class MediaSegmentPrefetcher {
    private static final String TAG = MediaSegmentPrefetcher.class.getSimpleName();
    // Video head: init + index + roughly the first second-and-a-bit of a 1080p vp9 stream.
    private static final long VIDEO_HEAD_BYTES = 1_536 * 1024;
    // Audio head: init + first seconds of opus.
    private static final long AUDIO_HEAD_BYTES = 256 * 1024;
    private static final int MAX_PREDICTED_HEIGHT = 1080;

    private static volatile boolean sEnabled;
    private static ExecutorService sExecutor;
    private static volatile AtomicBoolean sCancelPrevious;

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    /** Fire-and-forget. Safe to call from any thread. */
    public static void prefetch(MediaItemFormatInfo formatInfo) {
        if (!sEnabled || formatInfo == null) {
            return;
        }

        Cache cache = ExoMediaSourceFactory.getMediaCache();
        if (cache == null || formatInfo.isLive() || !formatInfo.containsDashFormats()) {
            return; // no cache installed, or live/SABR-ish content the cache can't help
        }

        List<MediaFormat> formats = formatInfo.getAdaptiveFormats();
        if (formats == null || formats.isEmpty()) {
            return;
        }

        MediaFormat video = predictVideoFormat(formats);
        MediaFormat audio = predictAudioFormat(formats);
        if (video == null && audio == null) {
            return;
        }

        synchronized (MediaSegmentPrefetcher.class) {
            if (sCancelPrevious != null) {
                sCancelPrevious.set(true); // a newer tap supersedes any still-running prefetch
            }
            AtomicBoolean cancel = new AtomicBoolean(false);
            sCancelPrevious = cancel;

            if (sExecutor == null) {
                sExecutor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "MediaSegmentPrefetch");
                    t.setDaemon(true);
                    return t;
                });
            }

            sExecutor.execute(() -> {
                cacheHead(cache, audio, AUDIO_HEAD_BYTES, cancel); // audio first: tiny, unblocks combined start
                cacheHead(cache, video, VIDEO_HEAD_BYTES, cancel);
            });
        }
    }

    private static void cacheHead(Cache cache, MediaFormat format, long bytes, AtomicBoolean cancel) {
        if (format == null || format.getUrl() == null || cancel.get()) {
            return;
        }

        try {
            DataSpec dataSpec = new DataSpec(Uri.parse(format.getUrl()), 0, bytes, null);
            DefaultHttpDataSource upstream = new DefaultHttpDataSource(ExoMediaSourceFactory.getMediaUserAgent());
            CacheUtil.cache(dataSpec, cache, /* cacheKeyFactory */ null, upstream, /* progressListener */ null, cancel);
            Log.d(TAG, "prefetched %s bytes of itag %s (%s)", bytes, format.getITag(), format.getMimeType());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // cancelled by a newer prefetch - expected
        } catch (Throwable e) {
            Log.d(TAG, "prefetch skipped: %s", e.getMessage()); // best-effort
        }
    }

    /** Mirror of the mobile default: highest rung <= 1080p, VP9 preferred (matches VIDEO_FHD_VP9_60). */
    private static MediaFormat predictVideoFormat(List<MediaFormat> formats) {
        MediaFormat best = null;
        boolean bestVp9 = false;
        for (MediaFormat format : formats) {
            String mime = format.getMimeType();
            if (mime == null || !mime.startsWith("video/")) {
                continue;
            }
            int height = format.getHeight();
            if (height <= 0 || height > MAX_PREDICTED_HEIGHT || format.getUrl() == null) {
                continue;
            }
            boolean vp9 = mime.contains("vp9") || mime.contains("vp09");
            if (best == null
                    || (vp9 && !bestVp9)
                    || (vp9 == bestVp9 && height > best.getHeight())
                    || (vp9 == bestVp9 && height == best.getHeight() && parseInt(format.getFps()) > parseInt(best.getFps()))) {
                best = format;
                bestVp9 = vp9;
            }
        }
        return best;
    }

    /** Opus/webm preferred (pairs with VP9), else highest-bitrate audio. */
    private static MediaFormat predictAudioFormat(List<MediaFormat> formats) {
        MediaFormat best = null;
        boolean bestWebm = false;
        for (MediaFormat format : formats) {
            String mime = format.getMimeType();
            if (mime == null || !mime.startsWith("audio/") || format.getUrl() == null) {
                continue;
            }
            // Xtags carry DRC/multi-language variants; skip the exotic ones for prediction.
            if (format.getXtags() != null || format.isDrc()) {
                continue;
            }
            boolean webm = mime.contains("webm") || mime.contains("opus");
            if (best == null
                    || (webm && !bestWebm)
                    || (webm == bestWebm && parseInt(format.getBitrate()) > parseInt(best.getBitrate()))) {
                best = format;
                bestWebm = webm;
            }
        }
        return best;
    }

    private static int parseInt(String value) {
        return Helpers.parseInt(value, 0);
    }
}
