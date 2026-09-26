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
    /** Stateful experimental sources own their failures; generic recovery must not change route/quality. */
    default boolean allowsAutomaticSourceRecovery() { return true; }
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
    /**
     * NEWTUBE(resume-seek): the automatic history ("continue watching") position of a new open.
     * An engine may start at the keyframe at or before it instead of decoding up to the exact
     * frame (the mobile media3 engine does). Every other seek - user scrubs, SponsorBlock, chapters,
     * link timestamps - goes through {@link #setPositionMs} and stays exact. Default: exact.
     */
    default void setResumePositionMs(long positionMs) { setPositionMs(positionMs); }
    /**
     * NEWTUBE(resume-seek): the position to store as history/resume state. Equal to
     * {@link #getPositionMs()} except right after a snapped resume: until playback has passed the
     * original resume target again, the target itself (leaving at once must not lose progress).
     */
    default long getHistoryPositionMs() { return getPositionMs(); }
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
