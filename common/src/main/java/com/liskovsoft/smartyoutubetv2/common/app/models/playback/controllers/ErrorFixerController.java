package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.misc.BufferingDetector;
import com.liskovsoft.smartyoutubetv2.common.misc.BufferingDetector.OnLongBuffering;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
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
    // Dead state: the cap tripped on a connectivity-class error and auto-fixing stopped. A play tap
    // now means "retry" (onPlayClicked/onPauseClicked) and a default-network callback arms exactly
    // one automatic retry when the connection returns.
    private boolean mErrorCapped;
    private ConnectivityManager mConnectivityManager;
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    private final Runnable mConnectivityRetry = () -> {
        // Posted to the main thread from the binder-thread network callback. The dead state may have
        // been left in the meantime (new video, engine release, manual retry) - only act if still set.
        if (mErrorCapped) {
            retryNow();
        }
    };

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
        } else {
            // Genuine user-initiated open (new video or manual retry): fresh window, and leave the
            // dead state - the connectivity listener belonged to the previous attempt.
            resetAutoFixCap();
            clearErrorCapped();
        }
    }

    @Override
    public void onPlayClicked() {
        // Dead state: the engine is idle after the capped error, so a play tap can't resume playback -
        // treat it as a user-initiated retry (reset the window, reload). Harmless no-op otherwise.
        if (mErrorCapped) {
            retryNow();
        }
    }

    @Override
    public void onPauseClicked() {
        // Same dead-state retry: the play/pause toggle may dispatch pause first (playWhenReady was
        // still set when the error hit), so the FIRST tap on the only visible affordance lands here.
        if (mErrorCapped) {
            retryNow();
        }
    }

    @Override
    public void onFinish() {
        mBufferingDetector.reset();
        // Playback session ended: drop the dead-state listener so it can't leak or fire into a gone player.
        clearErrorCapped();
    }

    @Override
    public void onEngineReleased() {
        mBufferingDetector.reset();
        // A scheduled reload can't land after release (VideoLoaderController disposes its
        // callbacks), so drop the marker: a later re-open of the same video is a user action.
        // The count itself survives on purpose - restartEngine() fix cycles release+recreate the
        // engine mid-cycle and must still hit the cap.
        mAutoReloadPending = false;
        // Player torn down - nothing to retry into, so unregister the connectivity callback too.
        clearErrorCapped();
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
            surfaceCappedError(errorMessage, errorContent, error);
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
                // Connectivity failures get a friendly, actionable title (a raw Cronet/socket dump
                // helps no one here); genuine server/content errors keep the raw content. This title
                // is transient on this path - the restart/reload below re-sets the real video title.
                getPlayer().setTitle(isConnectivityError(error)
                        ? getContext().getString(R.string.msg_player_no_connection_retry)
                        : errorContent);
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
            surfaceCappedError(fullMsg, message, error);
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
     * Enters the dead state: a play tap becomes a manual retry, and for a connectivity-class error
     * a default-network callback arms one automatic retry for when the connection returns.
     */
    private void surfaceCappedError(String errorMessage, String errorContent, Throwable error) {
        boolean connectivity = isConnectivityError(error);

        MessageHelpers.showLongMessage(getContext(), errorMessage);
        if (getPlayer() != null) {
            getPlayer().setTitle(connectivity
                    ? getContext().getString(R.string.msg_player_no_connection_retry)
                    : errorContent);
            getPlayer().showProgressBar(false);
            getPlayer().showOverlay(true);
        }

        mErrorCapped = true;

        // Only connectivity errors arm the auto-retry: reconnecting can't fix a server/content error,
        // and re-hammering it is exactly the anti-abuse behavior the cap exists to prevent.
        if (connectivity) {
            armConnectivityRetry();
        }
    }

    /**
     * A lost/absent network connection (vs. a server- or content-side error, where the raw message
     * is genuinely useful). Walks the cause chain: socket exception types plus the Cronet / data
     * source connectivity message markers seen in the field.
     */
    private static boolean isConnectivityError(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 12; cause = cause.getCause(), depth++) {
            if (cause instanceof UnknownHostException || cause instanceof SocketTimeoutException
                    || cause instanceof ConnectException || cause instanceof SocketException) {
                return true;
            }
            if (Helpers.containsAny(cause.getMessage(),
                    "Unable to connect to",
                    "ERR_INTERNET_DISCONNECTED", "ERR_NAME_NOT_RESOLVED", "ERR_ADDRESS_UNREACHABLE",
                    "ERR_CONNECTION_", "ERR_TIMED_OUT", "ERR_NETWORK_CHANGED", "ERR_PROXY_CONNECTION_FAILED",
                    "Exception in CronetUrlRequest")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Arms a single automatic retry for when connectivity RETURNS. Strictly edge-triggered:
     * registerDefaultNetworkCallback immediately replays onAvailable/onCapabilitiesChanged for the
     * network that's ALREADY up, so a level-triggered "validated = retry" would fire instantly when
     * the cap trips on a slow-but-alive link (SocketTimeoutException/ERR_TIMED_OUT) - an unbounded
     * cap->arm->fire->cap loop against googlevideo, exactly what {@link #MAX_CONSECUTIVE_AUTO_FIXES}
     * exists to prevent. Registered on the APPLICATION context (never the Activity - a backgrounded
     * dead player would otherwise leak it). Idempotent: one live registration per dead-state episode.
     */
    private void armConnectivityRetry() {
        if (mNetworkCallback != null) {
            return;
        }

        Context context = getContext() != null ? getContext().getApplicationContext() : null;
        ConnectivityManager cm = context != null
                ? (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE) : null;
        if (cm == null) {
            return;
        }

        // Seed the edge detector from the CURRENT state: mid-outage (no default network, or one
        // that isn't VALIDATED) the disconnect edge already happened - fire on the next validation.
        // If the network is validated right now, stay quiet until a real disconnect is observed;
        // the play-tap manual retry covers the network-is-fine-but-slow case.
        Network active = cm.getActiveNetwork();
        NetworkCapabilities activeCaps = active != null ? cm.getNetworkCapabilities(active) : null;
        boolean seedDisconnected = activeCaps == null || !activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);

        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            // Confined to this callback: all events of one registration are serialized on a single
            // ConnectivityManager handler thread, so a plain boolean is safe.
            private boolean mSeenDisconnected = seedDisconnected;

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    if (mSeenDisconnected) {
                        mSeenDisconnected = false; // one fire per observed disconnect (disarm lands on the main thread)
                        onConnectivityRestored();
                    }
                } else {
                    mSeenDisconnected = true;
                }
            }

            @Override
            public void onLost(Network network) {
                mSeenDisconnected = true;
            }
        };

        try {
            cm.registerDefaultNetworkCallback(callback);
        } catch (RuntimeException e) { // e.g. TOO_MANY_REQUESTS or a restricted OEM build
            Log.e(TAG, "Failed to register connectivity retry: %s", e.getMessage());
            return;
        }

        mConnectivityManager = cm;
        mNetworkCallback = callback;
    }

    private void onConnectivityRestored() {
        // Fires on a binder thread - hop to the main thread before touching player/controller state.
        // The stable Runnable dedupes repeated callbacks (Utils.post drops any pending copy first).
        Utils.post(mConnectivityRetry);
    }

    /**
     * User- or connectivity-initiated recovery from the dead state: forget the cap window and reload
     * the current video as if the user had reopened it, so the anti-abuse counter starts over. Strictly
     * one automatic retry per episode - the listener is disarmed here before the reload is posted.
     */
    private void retryNow() {
        clearErrorCapped();
        resetAutoFixCap();
        if (mVideoLoaderController != null) {
            mVideoLoaderController.reloadVideo();
        }
    }

    private void clearErrorCapped() {
        mErrorCapped = false;
        disarmConnectivityRetry();
    }

    private void disarmConnectivityRetry() {
        Utils.removeCallbacks(mConnectivityRetry);
        if (mConnectivityManager != null && mNetworkCallback != null) {
            try {
                mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
            } catch (RuntimeException e) { // never registered / already unregistered
                Log.e(TAG, "Failed to unregister connectivity retry: %s", e.getMessage());
            }
        }
        mConnectivityManager = null;
        mNetworkCallback = null;
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
