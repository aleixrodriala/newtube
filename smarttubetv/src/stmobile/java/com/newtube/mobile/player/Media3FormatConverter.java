package com.newtube.mobile.player;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack;

/**
 * Converts media3 {@link androidx.media3.common.Format}s into the legacy
 * {@code com.google.android.exoplayer2.Format} objects that the app's shared data model
 * ({@code MediaTrack} / {@code ExoFormatItem} / {@code PlayerData} persistence / the quality
 * sheet) is built on. The legacy Format is used purely as a DTO here - it is never handed to a
 * player - which lets the whole presenter/UI layer work unchanged while the actual engine is
 * media3. The vendored exoplayer2 library stays in the APK for the TV flavors anyway, so this
 * costs nothing.
 */
final class Media3FormatConverter {

    private Media3FormatConverter() {
    }

    /** media3 track type -> the app's renderer index vocabulary (video 0 / audio 1 / text 2). */
    static int rendererIndexForTrackType(int trackType) {
        switch (trackType) {
            case androidx.media3.common.C.TRACK_TYPE_VIDEO:
                return TrackSelectorManager.RENDERER_INDEX_VIDEO;
            case androidx.media3.common.C.TRACK_TYPE_AUDIO:
                return TrackSelectorManager.RENDERER_INDEX_AUDIO;
            case androidx.media3.common.C.TRACK_TYPE_TEXT:
                return TrackSelectorManager.RENDERER_INDEX_SUBTITLE;
            default:
                return -1;
        }
    }

    /** Builds a legacy-Format-backed {@link MediaTrack} DTO for one media3 track. */
    @Nullable
    static MediaTrack toMediaTrack(int rendererIndex, androidx.media3.common.Format format) {
        MediaTrack mediaTrack = MediaTrack.forRendererIndex(rendererIndex);

        if (mediaTrack == null) {
            return null;
        }

        mediaTrack.format = toLegacyFormat(rendererIndex, format);

        return mediaTrack;
    }

    /**
     * media3 Format -> legacy exoplayer2 Format DTO. Field names match 1:1; only the static
     * factory methods differ. DRC audio variants are recognized by the {@code xtags=drc} marker
     * YouTube puts in the stream URL, surfaced by the MPD builder as part of the format id
     * ("140-drc" style) - mirror the check the legacy parser did.
     */
    static com.google.android.exoplayer2.Format toLegacyFormat(int rendererIndex, androidx.media3.common.Format format) {
        String id = format.id;
        String codecs = format.codecs;
        String language = pickLanguage(format);

        switch (rendererIndex) {
            case TrackSelectorManager.RENDERER_INDEX_VIDEO:
                return com.google.android.exoplayer2.Format.createVideoSampleFormat(
                        id, format.sampleMimeType, codecs, format.bitrate, /* maxInputSize= */ -1,
                        format.width, format.height, format.frameRate,
                        /* initializationData= */ null, /* drmInitData= */ null);
            case TrackSelectorManager.RENDERER_INDEX_AUDIO:
                return com.google.android.exoplayer2.Format.createAudioSampleFormat(
                        id, format.sampleMimeType, codecs, format.bitrate, /* maxInputSize= */ -1,
                        format.channelCount > 0 ? format.channelCount : 0,
                        format.sampleRate > 0 ? format.sampleRate : 0,
                        /* initializationData= */ null, /* drmInitData= */ null,
                        /* selectionFlags= */ 0, language, isDrc(format));
            case TrackSelectorManager.RENDERER_INDEX_SUBTITLE:
            default:
                return com.google.android.exoplayer2.Format.createTextSampleFormat(
                        id, format.sampleMimeType, /* selectionFlags= */ 0, language);
        }
    }

    /**
     * The generated MPD carries the human-readable audio-variant name ("English original",
     * "Spanish dubbed") where a BCP-47 code would normally live; media3 normalizes unknown tags
     * but keeps the raw string in {@code label}. Prefer the label so the dub picker and the
     * "prefer original audio" match keep the exact wording the legacy selector saw.
     */
    @Nullable
    static String pickLanguage(androidx.media3.common.Format format) {
        if (format.label != null && !format.label.isEmpty()) {
            return format.label;
        }
        return format.language;
    }

    static boolean isDrc(androidx.media3.common.Format format) {
        return format.id != null && format.id.contains("drc");
    }
}
