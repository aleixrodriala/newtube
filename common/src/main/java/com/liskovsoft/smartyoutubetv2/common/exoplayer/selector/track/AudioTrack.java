package com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track;

/**
 * Thin audio-track shell: keeps {@link MediaTrack#forRendererIndex(int)} returning the right
 * subtype. The legacy inBounds/compare matchers were deleted with the vendored exoplayer2 engine.
 */
public class AudioTrack extends MediaTrack {
    public AudioTrack(int rendererIndex) {
        super(rendererIndex);
    }
}
