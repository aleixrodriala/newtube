package com.newtube.mobile.downloads;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Picks what the downloader can turn into a plain playable file, from the format lists the
 * player already resolved. Pure Java (no Android types) so the selection rules are unit-tested.
 *
 * <p>Only H.264 video and AAC audio are offered: {@code MediaMuxer} writes MP4, every phone
 * decodes them, and YouTube serves both for every video up to 1080p. VP9/AV1 rungs (1440p+)
 * would need a WebM muxer and are left out. When a video has no separate H.264 track at all
 * (rare), the progressive muxed streams (itag 18/22) are offered instead - those need no muxing.
 */
public final class DownloadOptions {
    private DownloadOptions() {
    }

    public static List<DownloadOption> build(@Nullable List<MediaFormat> adaptiveFormats,
                                             @Nullable List<MediaFormat> urlFormats) {
        List<DownloadOption> result = new ArrayList<>();

        MediaFormat audio = pickAudio(adaptiveFormats);
        Map<Integer, MediaFormat> videoByHeight = pickVideoRungs(adaptiveFormats);

        if (audio != null && !videoByHeight.isEmpty()) {
            for (Map.Entry<Integer, MediaFormat> entry : videoByHeight.entrySet()) {
                MediaFormat video = entry.getValue();
                result.add(new DownloadOption(DownloadOption.KIND_VIDEO, labelOf(video, entry.getKey()),
                        entry.getKey(), video, audio, sumLengths(video, audio)));
            }
        } else {
            for (MediaFormat progressive : pickProgressive(urlFormats)) {
                int height = progressive.getHeight();
                result.add(new DownloadOption(DownloadOption.KIND_VIDEO, labelOf(progressive, height),
                        height, progressive, null, sumLengths(progressive, null)));
            }
        }

        if (audio != null) {
            result.add(new DownloadOption(DownloadOption.KIND_AUDIO, null, 0, null, audio,
                    sumLengths(null, audio)));
        }

        return result;
    }

    /**
     * Best AAC track of the original language. The format info carries no "default" flag, so the
     * original is recognized the way YouTube tags it: no track id at all (single-audio videos),
     * else the {@code .4} track type. A dubbed track is only taken when nothing else exists.
     */
    @Nullable
    static MediaFormat pickAudio(@Nullable List<MediaFormat> formats) {
        if (formats == null) {
            return null;
        }

        MediaFormat best = null;
        int bestRank = -1;

        for (MediaFormat format : formats) {
            if (!isAacAudio(format) || format.isDrc()) {
                continue;
            }

            int rank = audioTrackRank(format.getAudioTrackId()) * 100_000_000 + bitrateOf(format);
            if (rank > bestRank) {
                best = format;
                bestRank = rank;
            }
        }

        return best;
    }

    /** Highest-bitrate H.264 track per height, tallest first. */
    static Map<Integer, MediaFormat> pickVideoRungs(@Nullable List<MediaFormat> formats) {
        TreeMap<Integer, MediaFormat> byHeight = new TreeMap<>(Collections.reverseOrder());

        if (formats == null) {
            return byHeight;
        }

        for (MediaFormat format : formats) {
            if (!isAvcVideo(format) || format.getHeight() <= 0 || format.isOtf()) {
                continue;
            }

            MediaFormat current = byHeight.get(format.getHeight());
            if (current == null || bitrateOf(format) > bitrateOf(current)) {
                byHeight.put(format.getHeight(), format);
            }
        }

        return byHeight;
    }

    static List<MediaFormat> pickProgressive(@Nullable List<MediaFormat> formats) {
        List<MediaFormat> result = new ArrayList<>();

        if (formats == null) {
            return result;
        }

        TreeMap<Integer, MediaFormat> byHeight = new TreeMap<>(Collections.reverseOrder());
        for (MediaFormat format : formats) {
            if (format.getUrl() == null || !mimeStartsWith(format, "video/mp4") || format.getHeight() <= 0) {
                continue;
            }
            MediaFormat current = byHeight.get(format.getHeight());
            if (current == null || bitrateOf(format) > bitrateOf(current)) {
                byHeight.put(format.getHeight(), format);
            }
        }

        result.addAll(byHeight.values());
        return result;
    }

    static boolean isAvcVideo(MediaFormat format) {
        return format.getUrl() != null && mimeStartsWith(format, "video/mp4")
                && mimeContains(format, "avc1");
    }

    static boolean isAacAudio(MediaFormat format) {
        return format.getUrl() != null && mimeStartsWith(format, "audio/mp4")
                && mimeContains(format, "mp4a");
    }

    /** 2 = original track (no id, or type 4), 1 = anything else, 0 = unknown. */
    static int audioTrackRank(@Nullable String audioTrackId) {
        if (audioTrackId == null || audioTrackId.isEmpty()) {
            return 2;
        }
        if (audioTrackId.endsWith(".4")) {
            return 2;
        }
        return 1;
    }

    static int bitrateOf(MediaFormat format) {
        try {
            return format.getBitrate() != null ? Integer.parseInt(format.getBitrate()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static long lengthOf(@Nullable MediaFormat format) {
        if (format == null) {
            return 0;
        }
        try {
            return format.getClen() != null ? Long.parseLong(format.getClen()) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static long sumLengths(@Nullable MediaFormat video, @Nullable MediaFormat audio) {
        long v = lengthOf(video);
        long a = lengthOf(audio);
        if (v < 0 || a < 0) {
            return -1;
        }
        return v + a;
    }

    private static String labelOf(MediaFormat format, int height) {
        String label = format.getQualityLabel();
        if (label != null && !label.isEmpty()) {
            return label;
        }
        return height + "p";
    }

    private static boolean mimeStartsWith(MediaFormat format, String prefix) {
        String mime = format.getMimeType();
        return mime != null && mime.toLowerCase(Locale.US).startsWith(prefix);
    }

    private static boolean mimeContains(MediaFormat format, String codec) {
        String mime = format.getMimeType();
        return mime != null && mime.toLowerCase(Locale.US).contains(codec);
    }

    /** "245 MB" style, decimal units like the system file manager. */
    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "";
        }
        if (bytes < 1000) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"kB", "MB", "GB"};
        int unit = -1;
        while (value >= 1000 && unit < units.length - 1) {
            value /= 1000;
            unit++;
        }
        if (unit < 0) {
            return bytes + " B";
        }
        return String.format(Locale.US, value >= 100 || unit == 0 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    /** "12:34" / "1:02:03" like the card duration badge. */
    public static String formatDuration(long durationMs) {
        long totalSec = durationMs / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        return h > 0
                ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.US, "%d:%02d", m, s);
    }

    /** A file name the media store and every file system accept; falls back to the video id. */
    public static String fileNameFor(@Nullable String title, String videoId, String extension) {
        String base = title == null ? "" : title;
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (base.isEmpty()) {
            base = videoId;
        }
        if (base.length() > 120) {
            base = base.substring(0, 120).trim();
        }
        return base + "." + extension;
    }
}
