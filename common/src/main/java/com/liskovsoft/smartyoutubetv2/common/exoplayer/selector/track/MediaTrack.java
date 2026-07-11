package com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager;

/**
 * Engine-neutral track DTO shell. The legacy findBestMatch/compare machinery that ran on the
 * vendored exoplayer2 engine was deleted with it in the phone-only port; what remains is the
 * data carrier ({@link TrackFormat} + selection flags) shared by {@code ExoFormatItem},
 * {@code PlayerData} persistence and the media3 adapter.
 */
public abstract class MediaTrack {
    private static final int VP9_WEIGHT = 31;
    private static final int AVC_WEIGHT = 28;
    private static final int AV1_WEIGHT = 14;
    public TrackFormat format;
    public boolean isSelected;
    public boolean isSaved;
    public boolean isPreset;
    public int rendererIndex;

    public MediaTrack(int rendererIndex) {
        this.rendererIndex = rendererIndex;
    }

    public static MediaTrack forRendererIndex(int rendererIndex) {
        switch (rendererIndex) {
            case TrackSelectorManager.RENDERER_INDEX_VIDEO:
                return new VideoTrack(rendererIndex);
            case TrackSelectorManager.RENDERER_INDEX_AUDIO:
                return new AudioTrack(rendererIndex);
            case TrackSelectorManager.RENDERER_INDEX_SUBTITLE:
                return new SubtitleTrack(rendererIndex);
        }

        return null;
    }

    public static int getCodecWeight(MediaTrack track) {
        if (track == null || track.format == null) {
            return 0;
        }

        return getCodecWeight(track.format.codecs);
    }

    public static int getCodecWeight(String codec) {
        return isVP9Codec(codec) ? VP9_WEIGHT : isAVCCodec(codec) ? AVC_WEIGHT : isAV1Codec(codec) ? AV1_WEIGHT : 0;
    }

    public boolean isVP9Codec() {
        return format != null && isVP9Codec(format.codecs);
    }

    public boolean isAV1Codec() {
        return format != null && isAV1Codec(format.codecs);
    }

    private static boolean isVP9Codec(String codec) {
        if (codec == null) {
            return false;
        }

        codec = codec.toLowerCase();

        return Helpers.containsAny(codec, "vp9", "vp09");
    }

    private static boolean isAVCCodec(String codec) {
        if (codec == null) {
            return false;
        }

        codec = codec.toLowerCase();

        return codec.contains("avc");
    }

    private static boolean isAV1Codec(String codec) {
        if (codec == null) {
            return false;
        }

        codec = codec.toLowerCase();

        return codec.contains("av01");
    }
}
