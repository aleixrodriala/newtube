package com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;

import java.io.InputStream;
import java.util.List;

public interface PlayerEngine extends PlayerConstants {
    /**
     * NEWTUBE(prepare-stash): hint that {@code formatInfo} is the likely NEXT video (autoplay
     * prefetch already fetched it) so the engine may pre-build the same MediaSource that
     * {@link #openDash(MediaItemFormatInfo)} would build and stash it for the advance - skipping
     * the MPD XML generation+parse from the open path. Best-effort; never live videos (their
     * manifest must stay URL-loaded so it can refresh). No-op default -&gt; TV engines unchanged.
     */
    default void prebuildNextSource(MediaItemFormatInfo formatInfo) {}
    void openSabr(MediaItemFormatInfo formatInfo);
    void openDash(MediaItemFormatInfo formatInfo);
    void openDash(InputStream dashManifest);
    void openDashUrl(String dashManifestUrl);
    void openHlsUrl(String hlsPlaylistUrl);
    void openUrlList(List<String> urlList);
    void openMerged(MediaItemFormatInfo formatInfo, String hlsPlaylistUrl);
    void openMerged(InputStream dashManifest, String hlsPlaylistUrl);
    long getPositionMs();
    void setPositionMs(long positionMs);
    long getDurationMs();
    void setPlayWhenReady(boolean play);
    boolean getPlayWhenReady();
    boolean isPlaying();
    boolean isLoading();
    List<FormatItem> getVideoFormats();
    List<FormatItem> getAudioFormats();
    List<FormatItem> getSubtitleFormats();
    void setFormat(FormatItem option);
    FormatItem getVideoFormat();
    FormatItem getAudioFormat();
    FormatItem getSubtitleFormat();
    boolean isEngineInitialized();
    void restartEngine();
    void reloadPlayback();
    void blockEngine(boolean block);
    boolean isEngineBlocked();
    boolean isInPIPMode();
    boolean containsMedia();
    void setSpeed(float speed);
    float getSpeed();
    void setPitch(float pitch);
    float getPitch();
    void setVolume(float volume);
    float getVolume();
    void setResizeMode(int mode);
    int getResizeMode();
    void setZoomPercents(int percents);
    void setAspectRatio(float ratio);
    void setRotationAngle(int angle);
    void setVideoFlipEnabled(boolean enabled);
    void setVideoGravity(int gravity);
}
