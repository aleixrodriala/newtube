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
    /**
     * NEWTUBE(readiness): how much longer the open answer's media may legitimately be held back for
     * its pre-roll ads, ms (0 = not waiting). A spinner inside it is not a stall.
     */
    default long getMediaReadinessHoldMs() { return 0; }
    void openSabr(MediaItemFormatInfo formatInfo);
    void openDash(MediaItemFormatInfo formatInfo);
    void openDash(InputStream dashManifest);
    void openDashUrl(String dashManifestUrl);
    void openHlsUrl(String hlsPlaylistUrl);
    void openUrlList(List<String> urlList);
    /**
     * NEWTUBE(delivery): a VOD answer with no usable adaptive formats, over its HLS manifest
     * ({@link MediaItemFormatInfo#isHlsVodSelected()}). Engines that do not know it open the URL.
     */
    default void openHlsVod(MediaItemFormatInfo formatInfo) { openHlsUrl(formatInfo.getHlsManifestUrl()); }
    /** NEWTUBE(readiness): the answer's progressive formats; an engine may gate them on the answer. */
    default void openProgressive(MediaItemFormatInfo formatInfo) { openUrlList(formatInfo.createUrlList()); }
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
    /**
     * NEWTUBE(wall-memory): this open's media requests, for the one-minute wall's signature
     * (VideoInfoService.notePlaybackMedia403): the stream start (ms) of the last one refused with
     * HTTP 403, and the lowest and highest start of those served; -1 when none or unknown.
     */
    default long getForbiddenMediaStartMs() { return -1; }
    default long getLowestServedMediaStartMs() { return -1; }
    default long getHighestServedMediaStartMs() { return -1; }
    /**
     * NEWTUBE(wall-swap): continue the watched open on {@code formatInfo}'s URLs for the same media
     * files, without a reload (see PlayerEngineEventListener.onMediaRefusedAhead).
     * @return false when the engine waits for no answer or the answer lacks a refused file
     */
    default boolean repointMedia(MediaItemFormatInfo formatInfo) { return false; }
    /** NEWTUBE(wall-swap): no answer is coming: the held refusal fails as before. */
    default void cancelRepoint() {}
    /** NEWTUBE(wall-swap): a held refusal still waits for an answer. */
    default boolean isRepointPending() { return false; }
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
    /**
     * NEWTUBE(hold-speed): the speed playback runs at right now - {@link #getSpeed()} (the chosen
     * speed, what is saved and shown) except during a temporary boost such as the phone's
     * press-and-hold 2x. For whatever times itself against the playback.
     */
    default float getEffectiveSpeed() { return getSpeed(); }
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
