package com.liskovsoft.smartyoutubetv2.common.exoplayer.selector;

/**
 * Renderer-index vocabulary shared by the DTO layer ({@code FormatItem}/{@code MediaTrack}), the
 * presenter constants and the media3 adapter. The legacy DefaultTrackSelector machinery that lived
 * here ran on the vendored exoplayer2 engine and was deleted with it in the phone-only port
 * ({@code Media3TrackAdapter} is its media3 replacement).
 */
public final class TrackSelectorManager {
    public static final int RENDERER_INDEX_UNKNOWN = -1;
    public static final int RENDERER_INDEX_VIDEO = 0;
    public static final int RENDERER_INDEX_AUDIO = 1;
    public static final int RENDERER_INDEX_SUBTITLE = 2;

    private TrackSelectorManager() {
    }
}
