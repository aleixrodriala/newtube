package com.newtube.mobile.downloads;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;

/**
 * One row of the download picker: a video rung (H.264 video track + AAC audio track, muxed on
 * the device) or the audio track alone. Built by {@link DownloadOptions} from the same format
 * info the player uses, so every URL here is already deciphered and ready to fetch.
 */
public final class DownloadOption {
    public static final int KIND_VIDEO = 0;
    public static final int KIND_AUDIO = 1;

    public final int kind;
    /** "1080p", "720p60" ... for video; null for audio. */
    @Nullable public final String qualityLabel;
    public final int height;
    /** Video-only track (DASH) or a muxed progressive stream when {@link #audio} is null. */
    @Nullable public final MediaFormat video;
    /** Audio-only track (DASH); null when {@link #video} is a progressive stream that has audio. */
    @Nullable public final MediaFormat audio;
    /** Sum of the known content lengths, or -1 when any part has no length. */
    public final long totalBytes;

    DownloadOption(int kind, @Nullable String qualityLabel, int height,
                   @Nullable MediaFormat video, @Nullable MediaFormat audio, long totalBytes) {
        this.kind = kind;
        this.qualityLabel = qualityLabel;
        this.height = height;
        this.video = video;
        this.audio = audio;
        this.totalBytes = totalBytes;
    }

    public boolean isAudioOnly() {
        return kind == KIND_AUDIO;
    }

    /** A progressive stream: one file that already carries both tracks. */
    public boolean isProgressive() {
        return kind == KIND_VIDEO && audio == null;
    }
}
