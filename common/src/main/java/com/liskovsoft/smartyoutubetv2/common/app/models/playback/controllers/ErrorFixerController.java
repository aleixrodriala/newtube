package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.annotation.SuppressLint;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack;
import com.liskovsoft.smartyoutubetv2.common.misc.BufferingDetector;
import com.liskovsoft.smartyoutubetv2.common.misc.BufferingDetector.OnLongBuffering;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.List;

public class ErrorFixerController extends BasePlayerController implements OnLongBuffering {
    private static final String TAG = ErrorFixerController.class.getSimpleName();
    private static final long STREAM_END_THRESHOLD_MS = 180_000;
    /**
     * At most this many CONSECUTIVE automatic error-driven reload/fix cycles per video without
     * playback reaching a healthy state in between. The next (4th) consecutive error stops
     * auto-fixing and surfaces the error - an unbounded ~1.7s reload loop was observed hammering
     * googlevideo for 9+ minutes, provoking server-side anti-abuse ("This video is unavailable").
     */
    private static final int MAX_CONSECUTIVE_AUTO_FIXES = 3;
    private final BufferingDetector mBufferingDetector = new BufferingDetector(this);
    private VideoLoaderController mVideoLoaderController;
    // Auto-fix cap state: per controller instance (= per playback session), deliberately not static.
    private int mConsecutiveAutoFixCount;
    private String mAutoFixVideoId;
    private boolean mAutoReloadPending;
    // NEWTUBE(pin-rescue): one-shot-per-open guard. media3 pins an EXPLICIT video-quality rung
    // with a TrackSelectionOverride, which disables per-track exclusion - so a rung whose
    // googlevideo URL persistently 403s can never be dropped, and the reload loop just re-pins the
    // dead format until the cap surfaces an error (the legacy engine had TrackErrorFixer for this).
    // On the first SOURCE error under an explicit pin we fall the pin back to Auto for THIS video
    // only, session-scoped through tempVideoFormat so the persisted quality preference is never
    // overwritten. Armed on that error, re-asserted on our reload's onNewVideo, disarmed together
    // with the auto-fix cap (healthy playback or a user-initiated open).
    private boolean mPinRescueArmed;
    private String mPinRescueVideoId;

    @Override
    public void onInit() {
        mVideoLoaderController = getController(VideoLoaderController.class);
    }

    @Override
    public void onEngineError(int type, int rendererIndex, Throwable error) {
        Log.e(TAG, "Player error occurred: %s. Trying to fix…", type);

        runEngineErrorAction(type, rendererIndex, error);
    }

    @Override
    public void onLongBuffering() {
        if (isStreamEnded()) {
            getMainController().onPlayEnd();
        } else if (isOfflineVideo() && isSubtitlesEnabled()) {
            // Long loading subtitles cause hangs
            disableSubtitles();
            scheduleAutoReload(); // buffering rescue: not counted against the cap, but machine-initiated
        } else if (!getPlayerTweaksData().isNetworkErrorFixingDisabled()) {
            //if (!isFasterDataSourceEnabled()) {
            //    enableFasterDataSource();
            //    restartEngine();
            //}

            //switchNextEngine();
            //restartEngine();

            lowerVideoQuality();
        }
    }

    @Override
    public void onBuffering() {
        mBufferingDetector.onStartBuffering();
    }

    @Override
    public void onSeekEnd() {
        mBufferingDetector.reset();
    }

    @Override
    public void onPlay() {
        mBufferingDetector.onStopBuffering();
        // Engine reached READY and is playing: playback is healthy again - reopen the fix window.
        resetAutoFixCap();
    }

    @Override
    public void onPause() {
        mBufferingDetector.onStopBuffering();
    }

    @Override
    public void onNewVideo(Video item) {
        mBufferingDetector.reset();

        // Our own scheduled reload re-enters here (VideoLoaderController.mReloadVideo dispatches
        // onNewVideo for the SAME video); only a genuinely user-initiated open - new video, or a
        // manual retry of the same one - resets the consecutive-fix window.
        String videoId = item != null ? item.videoId : null;
        if (mAutoReloadPending && Helpers.equals(videoId, mAutoFixVideoId)) {
            mAutoReloadPending = false; // our automatic reload landing - keep the count
            // NEWTUBE(pin-rescue): VideoStateController (registered before us) has just cleared
            // tempVideoFormat in its own onNewVideo; re-assert Auto here so the reopened source's
            // restoreVideoFormat reads it back instead of the failing persisted/temp pin.
            reassertPinRescueIfArmed(videoId);
        } else {
            resetAutoFixCap();
        }
    }

    @Override
    public void onFinish() {
        mBufferingDetector.reset();
    }

    @Override
    public void onEngineReleased() {
        mBufferingDetector.reset();
        // A scheduled reload can't land after release (VideoLoaderController disposes its
        // callbacks), so drop the marker: a later re-open of the same video is a user action.
        // The count itself survives on purpose - restartEngine() fix cycles release+recreate the
        // engine mid-cycle and must still hit the cap.
        mAutoReloadPending = false;
    }

    private void runEngineErrorAction(int type, int rendererIndex, Throwable error) {
        // Hide begin errors in embed mode (e.g. wrong date/time: unable to connect to...)
        if (isEmbedPlayer() && getPlayer() != null && getPlayer().getPositionMs() == 0) {
            getPlayer().finish();
            return;
        }

        if (isStreamEnded()) {
            // Url no longer works (e.g. live stream ended)
            getMainController().onPlayEnd();
            return;
        }

        applyEngineErrorAction(type, rendererIndex, error);
    }

    private void applyEngineErrorAction(int type, int rendererIndex, Throwable error) {
        boolean restartEngine = true;
        boolean showMessage = true;
        String errorContent = error != null ? error.getMessage() : null;
        String errorTitle = getErrorTitle(type, rendererIndex);
        String errorMessage = errorTitle + "\n" + errorContent;

        // 4th consecutive error without healthy playback in between: stop auto-fixing entirely
        // (no config mutation, no reload/restart) and leave a state the user can act on.
        if (registerAutoFixAndCheckCap(error)) {
            surfaceCappedError(errorMessage, errorContent);
            return;
        }

        if (Helpers.startsWithAny(errorContent, "Unable to connect to")) {
            // No internet connection or WRONG DATE on the device
            // Recently this message starting to show for other reasons
            YouTubeServiceManager.instance().applyNoPlaybackFix(); // ?
            //switchNextEngine(); // ?
            //restartEngine = false;
        } else if (error instanceof OutOfMemoryError || (error != null && error.getCause() instanceof OutOfMemoryError)) {
            if (getPlayerTweaksData().getPlayerDataSource() == PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP) {
                // OkHttp has memory leak problems
                enableFasterDataSource();
            } else if (getPlayerData().getVideoBufferType() == PlayerData.BUFFER_HIGH || getPlayerData().getVideoBufferType() == PlayerData.BUFFER_HIGHEST) {
                // Takes effect via the restartEngine below: the engine restart rebuilds the player
                // and its LoadControl, which reads this pref at creation (mobile: Media3PlayerInitializer).
                getPlayerData().setVideoBufferType(PlayerData.BUFFER_MEDIUM);
            } else {
                getPlayerTweaksData().setSectionPlaylistEnabled(false);
                restartEngine = false;
            }
        } else if (Helpers.containsAny(errorContent, "Exception in CronetUrlRequest") && !getPlayerTweaksData().isNetworkErrorFixingDisabled()) {
            if (getVideo() != null && !getVideo().isLive) { // Finished live stream may provoke errors in Cronet
                getPlayerTweaksData().setPlayerDataSource(PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT);
            } else {
                restartEngine = false;
            }
        } else if (type == PlayerEventListener.ERROR_TYPE_SOURCE && rendererIndex == PlayerEventListener.RENDERER_INDEX_UNKNOWN) {
            // NOTE: Starts with any (url deciphered incorrectly)
            // "Response code: 403" (poToken error, forbidden)
            // "Response code: 404" (not sure whether below helps)
            // "Response code: 503" (not sure whether below helps)
            // "Response code: 400" (not sure whether below helps)
            // "Response code: 429" (subtitle error, too many requests)
            // "Response code: 500" (subtitle error, generic server error)

            // NOTE: Fixing too many requests or network issues
            // NOTE: All these errors have unknown renderer (-1)
            // "Unable to connect to", "Invalid NAL length", "Response code: 421",
            // "Response code: 404", "Response code: 429", "Invalid integer size",
            // "Unexpected ArrayIndexOutOfBoundsException", "Unexpected IndexOutOfBoundsException"

            //if (Helpers.startsWithAny(errorContent, "Response code: 403")) {
            //    YouTubeServiceManager.instance().applyNoPlaybackFix();
            //} else if (isSubtitlesEnabled()) {
            //    disableSubtitles(); // Response code: 429
            //} else if (getPlayerTweaksData().isHighBitrateFormatsEnabled()) {
            //    getPlayerTweaksData().setHighBitrateFormatsEnabled(false); // Response code: 429
            //} else {
            //    YouTubeServiceManager.instance().applyNoPlaybackFix(); // Response code: 403
            //}

            // NEWTUBE(pin-rescue): an explicit video-quality pin can't be excluded on the media3
            // engine (see mPinRescueArmed) - fall it back to Auto for this video before the reload
            // below re-pins the dead format again. No-op when no explicit pin is active.
            maybeRescuePinnedFormat(errorContent);

            boolean isGeneralError = Helpers.startsWithAny(errorContent, "Response code: 429", "Response code: 500");
            if (isGeneralError && isSubtitlesEnabled()) {
                disableSubtitles(); // Response code: 429
            } else if (isGeneralError && getPlayerTweaksData().isHighBitrateFormatsEnabled()) {
                getPlayerTweaksData().setHighBitrateFormatsEnabled(false); // Response code: 429
            } else {
                YouTubeServiceManager.instance().applyNoPlaybackFix(); // Response code: 403
            }

            restartEngine = false;
            showMessage = false;
        } else if (type == PlayerEventListener.ERROR_TYPE_RENDERER && rendererIndex == PlayerEventListener.RENDERER_INDEX_SUBTITLE) {
            // "Response code: 429" (subtitle error)
            // "Response code: 500" (subtitle error)
            disableSubtitles();
            restartEngine = false;
        } else if (type == PlayerEventListener.ERROR_TYPE_RENDERER && rendererIndex == PlayerEventListener.RENDERER_INDEX_VIDEO) {
            getPlayerData().setFormat(FormatItem.VIDEO_FHD_AVC_30);
            if (getPlayerTweaksData().isSWDecoderForced()) {
                getPlayerTweaksData().setSWDecoderForced(false);
            } else {
                restartEngine = false;
            }
        } else if (type == PlayerEventListener.ERROR_TYPE_RENDERER && rendererIndex == PlayerEventListener.RENDERER_INDEX_AUDIO) {
            getPlayerData().setFormat(FormatItem.AUDIO_HQ_MP4A);
            restartEngine = false;
        } else if (type == PlayerEventListener.ERROR_TYPE_UNEXPECTED) {
            // IllegalStateException: Buffer too small (5242880 < 7208383)
            if (Helpers.startsWithAny(errorContent, "Buffer too small", "Invalid to call at Released state; only valid in executing state")) {
                lowerVideoQuality();
                //restartEngine = false;
            }
        }

        if (showMessage) {
            MessageHelpers.showLongMessage(getContext(), errorMessage);
            if (getPlayer() != null) {
                getPlayer().setTitle(errorContent);
            }
        }

        if (restartEngine) {
            // NOTE: no onNewVideo fires on this path (engine re-init loads the video directly),
            // so no pending marker is needed - the consecutive count survives the restart.
            mVideoLoaderController.restartEngine();
        } else {
            // Need at least to reload the video because the player becomes idle after error
            scheduleAutoReload();
        }
    }

    @SuppressLint("StringFormatMatches")
    private String getErrorTitle(int type, int rendererIndex) {
        String errorTitle;
        int msgResId;

        switch (type) {
            // Some ciphered data could be outdated.
            // Might happen when the app wasn't used quite a long time.
            case PlayerEventListener.ERROR_TYPE_SOURCE:
                switch (rendererIndex) {
                    case PlayerEventListener.RENDERER_INDEX_VIDEO:
                        msgResId = R.string.msg_player_error_video_source;
                        break;
                    case PlayerEventListener.RENDERER_INDEX_AUDIO:
                        msgResId = R.string.msg_player_error_audio_source;
                        break;
                    case PlayerEventListener.RENDERER_INDEX_SUBTITLE:
                        msgResId = R.string.msg_player_error_subtitle_source;
                        break;
                    default:
                        msgResId = R.string.unknown_source_error;
                }
                errorTitle = getContext().getString(msgResId);
                break;
            case PlayerEventListener.ERROR_TYPE_RENDERER:
                switch (rendererIndex) {
                    case PlayerEventListener.RENDERER_INDEX_VIDEO:
                        msgResId = R.string.msg_player_error_video_renderer;
                        break;
                    case PlayerEventListener.RENDERER_INDEX_AUDIO:
                        msgResId = R.string.msg_player_error_audio_renderer;
                        break;
                    case PlayerEventListener.RENDERER_INDEX_SUBTITLE:
                        msgResId = R.string.msg_player_error_subtitle_renderer;
                        break;
                    default:
                        msgResId = R.string.unknown_renderer_error;
                }
                errorTitle = getContext().getString(msgResId);
                break;
            case PlayerEventListener.ERROR_TYPE_UNEXPECTED:
                errorTitle = getContext().getString(R.string.player_unexpected_error);
                break;
            default:
                errorTitle = getContext().getString(R.string.msg_player_error, type);
                break;
        }

        return errorTitle;
    }

    public void runFormatErrorAction(Throwable error) {
        if (getPlayer() == null) {
            return;
        }

        if (isEmbedPlayer()) {
            getPlayer().finish();
            return;
        }

        String message = error.getMessage();
        String className = error.getClass().getSimpleName();
        String fullMsg = String.format("loadFormatInfo error: %s: %s", className, Utils.getStackTraceAsString(error));
        Log.e(TAG, fullMsg);

        if (!Helpers.containsAny(message, "fromNullable result is null")) {
            MessageHelpers.showLongMessage(getContext(), fullMsg);
        }

        // Format(metadata)-fetch errors reload just like engine errors - same consecutive cap.
        if (registerAutoFixAndCheckCap(error)) {
            surfaceCappedError(fullMsg, message);
            return;
        }

        if (Helpers.containsAny(message, "Unexpected token", "Syntax error", "invalid argument") || // temporal fix
                Helpers.equalsAny(className, "PoTokenException", "BadWebViewException")) {
            YouTubeServiceManager.instance().applyNoPlaybackFix();
            scheduleAutoReload();
        } else if (Helpers.containsAny(message, "is not defined")) {
            YouTubeServiceManager.instance().invalidateCache();
            scheduleAutoReload();
        } else {
            Log.e(TAG, "Probably no internet connection");
            scheduleAutoReload();
        }
    }

    /**
     * Counts a CONSECUTIVE automatic error-driven fix cycle for the current video and reports
     * whether the cap ({@link #MAX_CONSECUTIVE_AUTO_FIXES}) is exceeded - the caller must then
     * NOT schedule another automatic fix. The window resets on healthy playback ({@link #onPlay})
     * and on user-initiated opens ({@link #onNewVideo} not caused by our own scheduled reload).
     */
    private boolean registerAutoFixAndCheckCap(Throwable error) {
        String videoId = getVideo() != null ? getVideo().videoId : null;

        if (!Helpers.equals(videoId, mAutoFixVideoId)) {
            // Different video than the one being counted: fresh window.
            mAutoFixVideoId = videoId;
            mConsecutiveAutoFixCount = 0;
        }

        mConsecutiveAutoFixCount++;

        if (mConsecutiveAutoFixCount > MAX_CONSECUTIVE_AUTO_FIXES) {
            android.util.Log.w("NetPath", "auto-reload cap hit (" + mConsecutiveAutoFixCount + ") for " + videoId
                    + " — stopping; last error: " + error);
            return true;
        }

        return false;
    }

    /**
     * Cap reached: surface the error through the existing message mechanism and leave the player
     * in a stopped, user-actionable state (manual retry / back out) - never an endless spinner.
     */
    private void surfaceCappedError(String errorMessage, String errorContent) {
        MessageHelpers.showLongMessage(getContext(), errorMessage);
        if (getPlayer() != null) {
            getPlayer().setTitle(errorContent);
            getPlayer().showProgressBar(false);
            getPlayer().showOverlay(true);
        }
    }

    /**
     * Every automatic (machine-initiated) reload goes through here, so {@link #onNewVideo} can
     * tell our own reload of the same video apart from a user-initiated open/retry.
     */
    private void scheduleAutoReload() {
        mAutoReloadPending = true;
        mVideoLoaderController.reloadVideo();
    }

    private void resetAutoFixCap() {
        mConsecutiveAutoFixCount = 0;
        mAutoFixVideoId = null;
        mAutoReloadPending = false;
        // NEWTUBE(pin-rescue): healthy playback / a user-initiated open both disarm the rescue.
        // tempVideoFormat is left as-is (VideoStateController.onNewVideo clears it on the next
        // user open, which restores the untouched persisted pin) so recovered playback keeps Auto.
        mPinRescueArmed = false;
        mPinRescueVideoId = null;
    }

    /**
     * NEWTUBE(pin-rescue): first SOURCE error while an EXPLICIT video-quality pin is active. media3
     * pins an explicit rung with a {@code TrackSelectionOverride}, which disables per-track
     * exclusion, so a rung that persistently 403s can never be dropped - the reload loop re-pins
     * the dead format until the cap. Arm the fallback (the actual switch to Auto happens on the
     * reload's {@link #onNewVideo}, see {@link #reassertPinRescueIfArmed}), inform the user, and
     * leave a NetPath breadcrumb. One-shot per open: once Auto is in effect the pin is no longer
     * active, so this can't re-arm; the explicit id guard also blocks a same-open re-entry.
     * No-op (unchanged behavior) when the active video format is Auto/a preset ceiling.
     */
    private void maybeRescuePinnedFormat(String reason) {
        if (getPlayer() == null || getVideo() == null) {
            return;
        }

        // Only server rejections of the pinned URL ("Response code: 403" etc). A transient
        // network blip (UnknownHost, connection loss) fails EVERY rung equally - downgrading
        // the pin there would just cost quality once the network recovers.
        if (reason == null || !reason.contains("Response code:")) {
            return;
        }

        String videoId = getVideo().videoId;
        if (mPinRescueArmed && Helpers.equals(videoId, mPinRescueVideoId)) {
            return; // already rescued this open
        }

        FormatItem pinned = getEffectiveVideoFormat();
        if (isAutoFormat(pinned)) {
            return; // no explicit pin -> nothing to rescue
        }

        mPinRescueArmed = true;
        mPinRescueVideoId = videoId;

        android.util.Log.d("NetPath", "rescue pin->auto reason=" + reason);
        MessageHelpers.showLongMessage(getContext(),
                getContext().getString(R.string.msg_quality_pin_fallback, qualityLabel(pinned)));
    }

    /**
     * NEWTUBE(pin-rescue): re-assert Auto as the per-session {@code tempVideoFormat} on our own
     * reload. Runs after {@code VideoStateController.onNewVideo} (registered before us) has cleared
     * tempVideoFormat, and before the reopened source's {@code onSourceChanged -> restoreVideoFormat}
     * reads it, so the reloaded video selects Auto (the mobile-capped default ceiling, exactly the
     * quality sheet's "Auto" row) without ever overwriting the persisted pin.
     */
    private void reassertPinRescueIfArmed(String videoId) {
        if (mPinRescueArmed && Helpers.equals(videoId, mPinRescueVideoId)) {
            getPlayerData().setTempVideoFormat(getPlayerData().getDefaultVideoFormat());
        }
    }

    /** The video format that {@code restoreVideoFormat} would apply: per-session pin, else persisted. */
    private FormatItem getEffectiveVideoFormat() {
        FormatItem temp = getPlayerData().getTempVideoFormat();
        return temp != null ? temp : getPlayerData().getFormat(FormatItem.TYPE_VIDEO);
    }

    /**
     * "Auto" = a ceiling preset, not a concrete stream (mirrors VideoStateController.isAutoFormat /
     * the media3 adapter's isAutoTarget): the DEFAULT format constants ship with isPreset unset but
     * a null format id, so both must count.
     */
    private static boolean isAutoFormat(FormatItem item) {
        if (item == null || item.isPreset()) {
            return true;
        }
        MediaTrack track = item.getTrack();
        return track == null || track.format == null || track.format.id == null;
    }

    /** Distinct resolution rung of the pinned format: "1080p" / "1080p60" style (for the toast). */
    private static String qualityLabel(FormatItem item) {
        int height = item.getHeight();
        if (height <= 0) {
            return "Pinned quality";
        }
        return height + "p" + (item.getFrameRate() > 40 ? "60" : "");
    }

    /**
     * Bad idea. Faster source is different among devices
     */
    private void enableFasterDataSource() {
        if (isFasterDataSourceEnabled()) {
            return;
        }

        getPlayerTweaksData().setPlayerDataSource(getFasterDataSource());
    }

    private static int getFasterDataSource() {
        return Utils.skipCronet() ? PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT : PlayerTweaksData.PLAYER_DATA_SOURCE_CRONET;
    }

    /**
     * Bad idea. Faster source is different among devices
     */
    private boolean isFasterDataSourceEnabled() {
        int fasterDataSource = getFasterDataSource();
        return getPlayerTweaksData().getPlayerDataSource() == fasterDataSource;
    }

    private void switchNextEngine() {
        getPlayerTweaksData().setPlayerDataSource(getNextEngine());
    }

    private int getNextEngine() {
        int currentEngine = getPlayerTweaksData().getPlayerDataSource();
        Integer[] engineList = Utils.skipCronet() ?
                new Integer[] { PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP } :
                new Integer[] { PlayerTweaksData.PLAYER_DATA_SOURCE_CRONET, PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP };
        return Helpers.getNextValue(engineList, currentEngine);
    }

    private boolean isSubtitlesEnabled() {
        return getPlayer() != null && !FormatItem.SUBTITLE_NONE.equals(getPlayer().getSubtitleFormat());
    }

    private void disableSubtitles() {
        getPlayerData().setSubtitlesPerChannelEnabled(false); // Important!
        getPlayerData().setFormat(FormatItem.SUBTITLE_NONE);
    }

    private boolean isStreamEnded() {
        if (getPlayer() == null || getVideo() == null) {
            return false;
        }

        return getVideo().isLiveEnd && getPlayer().getDurationMs() > 0
                && getPlayer().getDurationMs() - getPlayer().getPositionMs() < STREAM_END_THRESHOLD_MS;
    }

    private boolean isOfflineVideo() {
        if (getPlayer() == null || getVideo() == null) {
            return false;
        }

        return !getVideo().isLive && !getVideo().isLiveEnd;
    }

    private void lowerVideoQuality() {
        if (getPlayer() == null) {
            return;
        }

        List<FormatItem> videoFormats = getPlayer().getVideoFormats();

        if (videoFormats == null) {
            return;
        }

        int idx = videoFormats.indexOf(getPlayer().getVideoFormat());
        int nextIdx = idx + 1;

        if (videoFormats.size() > nextIdx) {
            FormatItem formatItem = videoFormats.get(nextIdx);
            getPlayer().setFormat(formatItem);
            // This helps to persist the format between engine restart
            getPlayerData().setTempVideoFormat(formatItem);
        }
    }
}
