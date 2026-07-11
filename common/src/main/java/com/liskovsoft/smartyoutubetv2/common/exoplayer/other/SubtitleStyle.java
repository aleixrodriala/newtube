package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

/**
 * Subtitle style preset (colors + caption edge type). Formerly nested in the legacy
 * {@code SubtitleManager} (deleted with the vendored exoplayer2 engine); the phone port's
 * {@code Media3SubtitleManager} consumes it unchanged. {@code PlayerData} persists only the
 * preset's LIST INDEX, so relocating the class has no persistence impact.
 *
 * <p>{@link #captionStyle} carries a {@code CaptionStyleCompat.EDGE_TYPE_*} int - the exoplayer2
 * and media3 constants use identical values.</p>
 */
public class SubtitleStyle {
    public final int nameResId;
    public final int subsColorResId;
    public final int backgroundColorResId;
    public final int captionStyle;

    public SubtitleStyle(int nameResId) {
        this(nameResId, -1, -1, -1);
    }

    public SubtitleStyle(int nameResId, int subsColorResId, int backgroundColorResId, int captionStyle) {
        this.nameResId = nameResId;
        this.subsColorResId = subsColorResId;
        this.backgroundColorResId = backgroundColorResId;
        this.captionStyle = captionStyle;
    }

    public boolean isSystem() {
        return subsColorResId == -1 && backgroundColorResId == -1 && captionStyle == -1;
    }
}
