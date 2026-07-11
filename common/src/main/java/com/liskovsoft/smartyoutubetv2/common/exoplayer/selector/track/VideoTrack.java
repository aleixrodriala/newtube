package com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track;

/**
 * Thin video-track shell: keeps {@link MediaTrack#forRendererIndex(int)} returning the right
 * subtype. The legacy inBounds/compare matchers were deleted with the vendored exoplayer2 engine.
 */
public class VideoTrack extends MediaTrack {
    public VideoTrack(int rendererIndex) {
        super(rendererIndex);
    }
}
