package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;

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
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService;

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
    // NEWTUBE(pin-rescue): one-shot-per-open guard. media3 pins an EXPLICIT video-quality rung
    // with a TrackSelectionOverride, which disables per-track exclusion - so a rung whose
    // googlevideo URL persistently 403s can never be dropped, and the reload loop just re-pins the
    // dead format until the cap surfaces an error (the legacy engine had TrackErrorFixer for this).
    // A first SOURCE 403 remints URLs and rotates the /player client with every pin intact. Only a
    // repeat after that fresh-route attempt can implicate a concrete pin; then fall it back to Auto
    // for THIS video, session-scoped through tempVideoFormat so the persisted preference is never
    // overwritten. Armed on that error, re-asserted on our reload's onNewVideo, disarmed together
    // with the auto-fix cap (healthy playback or a user-initiated open).
    private boolean mPinRescueArmed;
    private String mPinRescueVideoId;
    // NEWTUBE(pin-rescue): audio twin of the video pin rescue above. A persisted audio-language
    // pin maps to ONE concrete rendition (itag), and if that rendition's URL persistently 403s,
    // every reload re-pins it - observed on-device as an infinite loop replaying the same 41s
    // (the manifest's alternative audio codec of the same language was never tried). One rescue
    // per failing cycle: the video pin goes first, the audio pin on the next failing cycle.
    private boolean mAudioPinRescueArmed;
    private String mAudioPinRescueVideoId;
    // NEWTUBE(same-position cap): errors recurring at (nearly) the SAME media position count in
    // their own window that onPlay does NOT reset. After a reload most of the replayed span plays
    // from the disk cache, so reaching READY+playing proves nothing about the chunk that killed
    // the previous cycle - the plain consecutive cap resets on that false-healthy signal and the
    // reload loop runs forever (observed: 8+ identical 41s cycles). Only a user-initiated open,
    // a manual retry, or an error at a genuinely different position resets this window.
    private static final long SAME_POSITION_WINDOW_MS = 5_000;
    private long mLastErrorPositionMs = -1;
    private int mSamePositionErrorCount;
    // Dead state: the cap tripped on a connectivity-class error and auto-fixing stopped. A play tap
    // now means "retry" (onPlayClicked/onPauseClicked), a timer retries on the escalating backoff
    // below, and a default-network callback retries as soon as connectivity provably returns.
    private boolean mErrorCapped;
    /**
     * Spacing of the TIMER-driven automatic retries, indexed by attempt: an outage is only ever
     * inferred from our own failures (Android may not have noticed yet - see
     * {@link #armConnectivityRetry}), so this is weak evidence and the budget is finite. The whole
     * schedule covers ~8 min of outage with 5 retries; after that only a user action (play tap /
     * reopen), a proven connectivity edge, or genuinely recovered playback ({@link #onTickle})
     * resumes automatic recovery. This is what keeps a slow-but-alive link from being hammered -
     * an unbounded reload loop provoked server-side anti-abuse (see {@link #MAX_CONSECUTIVE_AUTO_FIXES}).
     */
    private static final long[] AUTO_RETRY_BACKOFF_MS = {5_000, 15_000, 45_000, 120_000, 300_000};
    private int mAutoRetryAttempt;
    /** Gate shared by the timer and the network callback ({@link SystemClock#elapsedRealtime()}). */
    private long mNextAutoRetryAtMs;
    private ConnectivityManager mConnectivityManager;
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    // Both retry triggers are posted to the main thread and funnel through the same gate. Distinct
    // Runnable instances on purpose: Utils.post/postDelayed dedupe per instance, so a network event
    // must not silently cancel a pending timer (or vice versa).
    private final Runnable mConnectivityRetry = () -> requestAutoRetry("network", true);
    private final Runnable mScheduledRetry = () -> requestAutoRetry("timer", false);

    @Override
    public void onInit() {
        mVideoLoaderController = getController(VideoLoaderController.class);
    }

    @Override
    public void onEngineError(int type, int rendererIndex, Throwable error) {
        Log.e(TAG, "Player error occurred: %s. Trying to fix…", type);
        long positionMs = getPlayer() != null ? getPlayer().getPositionMs() : -1;
        long durationMs = getPlayer() != null ? getPlayer().getDurationMs() : -1;
        NetPath.log(NetPath.context() + " recovery-error type=" + type
                + " renderer=" + rendererIndex + " pos=" + positionMs
                + " duration=" + durationMs + ' ' + NetPath.networkSnapshot(getContext())
                + " causes=" + NetPath.throwableSummary(error));

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
        if (mConsecutiveAutoFixCount > 0 || mSamePositionErrorCount > 0) {
            NetPath.log(NetPath.context() + " recovery-healthy pos="
                    + (getPlayer() != null ? getPlayer().getPositionMs() : -1)
                    + " clearedAttempts=" + mConsecutiveAutoFixCount
                    + " samePos=" + mSamePositionErrorCount);
        }
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
            NetPath.log(NetPath.context() + " recovery-reload-landed video=" + videoId
                    + " attempt=" + mConsecutiveAutoFixCount);
            // NEWTUBE(pin-rescue): VideoStateController (registered before us) has just cleared
            // tempVideoFormat in its own onNewVideo; re-assert Auto here so the reopened source's
            // restoreVideoFormat reads it back instead of the failing persisted/temp pin.
            reassertPinRescueIfArmed(videoId);
        } else {
            // Genuine user-initiated open (new video or manual retry): fresh window, and leave the
            // dead state - the connectivity listener belonged to the previous attempt.
            resetAutoFixCap();
            resetSamePositionWindow();
            clearErrorCapped();
        }
    }

    @Override
    public void onPlayClicked() {
        // Dead state: the engine is idle after the capped error, so a play tap can't resume playback -
        // treat it as a user-initiated retry (reset the window, reload). Harmless no-op otherwise.
        // Reached from the in-player button AND from the notification / lock screen / headset
        // transport controls, which is the only recovery a backgrounded audio session ever gets.
        if (mErrorCapped) {
            retryNow(true);
        }
    }

    @Override
    public void onPauseClicked() {
        // Same dead-state retry: the play/pause toggle may dispatch pause first (playWhenReady was
        // still set when the error hit), so the FIRST tap on the only visible affordance lands here.
        if (mErrorCapped) {
            retryNow(true);
        }
    }

    @Override
    public void onTickle() {
        // Playback has passed the position that kept dying, so the outage/poison chunk is genuinely
        // behind us: refill the automatic-retry budget and the same-position window. onPlay can't
        // say this - after a reload most of the replayed span comes from the disk cache, so
        // READY+playing proves nothing about the chunk that killed the previous cycle (that false
        // signal is exactly what mSamePositionErrorCount exists to survive).
        if (mAutoRetryAttempt == 0 && mSamePositionErrorCount == 0) {
            return;
        }
        // Still dead (a retry is pending / in flight): whatever the player reports, this is not
        // recovered playback.
        if (mErrorCapped || getPlayer() == null || !getPlayer().isPlaying()) {
            return;
        }

        long positionMs = getPlayer().getPositionMs();
        if (mLastErrorPositionMs >= 0 && positionMs <= mLastErrorPositionMs + SAME_POSITION_WINDOW_MS) {
            return;
        }

        NetPath.log(NetPath.context() + " recovery-recovered pos=" + positionMs
                + " clearedRetries=" + mAutoRetryAttempt + " samePos=" + mSamePositionErrorCount);
        mAutoRetryAttempt = 0;
        mNextAutoRetryAtMs = 0;
        resetSamePositionWindow();
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
        boolean freshUrlsRequested = false;
        String errorContent = error != null ? error.getMessage() : null;
        String errorTitle = getErrorTitle(type, rendererIndex);

        // 4th consecutive error without healthy playback in between: stop auto-fixing entirely
        // (no config mutation, no reload/restart) and leave a state the user can act on.
        if (registerAutoFixAndCheckCap(error)) {
            surfaceCappedError(errorTitle, error);
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
        } else if (type == PlayerEventListener.ERROR_TYPE_SOURCE) {
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
            maybeRescuePinnedFormat(errorContent, rendererIndex);

            boolean isGeneralError = Helpers.startsWithAny(errorContent, "Response code: 429", "Response code: 500");
            boolean gvsForbidden = hasHttpStatus(error, 403);
            if (isGeneralError && isSubtitlesEnabled()) {
                disableSubtitles(); // Response code: 429
            } else if (isGeneralError && getPlayerTweaksData().isHighBitrateFormatsEnabled()) {
                getPlayerTweaksData().setHighBitrateFormatsEnabled(false); // Response code: 429
            } else {
                // A proven GVS 403 from an authenticated TV route is remembered against this
                // default network. Recovery still remints immediately; later opens avoid selecting
                // the same doomed carrier/client route first for a short self-healing window.
                if (gvsForbidden) {
                    VideoInfoService.instance().markCurrentPlaybackRouteForbidden();
                }
                YouTubeServiceManager.instance().applyNoPlaybackFix(); // Response code: 403
                freshUrlsRequested = true;
            }
            NetPath.log(NetPath.context() + " recovery-source http403="
                    + (gvsForbidden ? "y" : "n")
                    + " freshUrls=" + (freshUrlsRequested ? "y" : "n")
                    + " subtitles=" + (isSubtitlesEnabled() ? "on" : "off"));

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

        // The error surface is the player itself (title + overlay), never a toast: the raw
        // "Response code: 403"/Cronet dumps that used to be thrown on screen were unreadable,
        // covered the video, and outlived the automatic fix that was already recovering behind
        // them. Everything they carried is in the NetPath log lines above.
        if (showMessage) {
            if (getPlayer() != null) {
                // Connectivity failures get a friendly, actionable title; everything else gets the
                // localized error title rather than a raw Cronet/socket/HTTP dump (which lives in
                // the NetPath lines above, where debugging actually happens). This title is
                // transient on this path - the restart/reload below re-sets the real video title.
                getPlayer().setTitle(isConnectivityError(error)
                        ? getContext().getString(R.string.msg_player_no_connection_retry)
                        : errorTitle);
            }
        }

        if (restartEngine) {
            // NOTE: no onNewVideo fires on this path (engine re-init loads the video directly),
            // so no pending marker is needed - the consecutive count survives the restart.
            mVideoLoaderController.restartEngine();
        } else {
            // Need at least to reload the video because the player becomes idle after error
            scheduleAutoReload(freshUrlsRequested);
        }
        NetPath.log(NetPath.context() + " recovery-action action="
                + (restartEngine ? "restart-engine"
                : freshUrlsRequested ? "remint-reload" : "reload")
                + " attempt=" + mConsecutiveAutoFixCount
                + " samePos=" + mSamePositionErrorCount);
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

        // No toast here either (see applyEngineErrorAction): this one threw a whole stack trace on
        // screen for a failure the very next line usually reloads away.

        // Format(metadata)-fetch errors reload just like engine errors - same consecutive cap.
        if (registerAutoFixAndCheckCap(error)) {
            surfaceCappedError(getContext().getString(R.string.unknown_source_error), error);
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
            resetSamePositionWindow();
        }

        mConsecutiveAutoFixCount++;

        // Same-position tracking (see the field doc): position <= 0 means unknown (player gone or
        // error before the restore seek) - leave the window untouched, the plain cap covers those.
        long positionMs = getPlayer() != null ? getPlayer().getPositionMs() : -1;
        if (positionMs > 0) {
            if (mLastErrorPositionMs >= 0 && Math.abs(positionMs - mLastErrorPositionMs) < SAME_POSITION_WINDOW_MS) {
                mSamePositionErrorCount++;
            } else {
                mSamePositionErrorCount = 1;
            }
            mLastErrorPositionMs = positionMs;
        }

        NetPath.log(NetPath.context() + " recovery-count consecutive="
                + mConsecutiveAutoFixCount + " samePos=" + mSamePositionErrorCount
                + " pos=" + positionMs + ' ' + NetPath.networkSnapshot(getContext())
                + " causes=" + NetPath.throwableSummary(error));

        if (mConsecutiveAutoFixCount > MAX_CONSECUTIVE_AUTO_FIXES || mSamePositionErrorCount > MAX_CONSECUTIVE_AUTO_FIXES) {
            android.util.Log.w("NetPath", "auto-reload cap hit (consecutive=" + mConsecutiveAutoFixCount
                    + " samePos=" + mSamePositionErrorCount + " at " + mLastErrorPositionMs + "ms) for " + videoId
                    + " — stopping; last error: " + error);
            return true;
        }

        return false;
    }

    private void resetSamePositionWindow() {
        mLastErrorPositionMs = -1;
        mSamePositionErrorCount = 0;
    }

    /**
     * Cap reached: surface the error in the player itself - a readable title over a stopped,
     * user-actionable state (manual retry / back out), never an endless spinner and never a toast.
     * Enters the dead state: a play tap becomes a manual retry, and for a connectivity-class error
     * the escalating auto-retry plus a default-network callback take over.
     *
     * @param errorTitle localized, user-facing. The raw exception text belongs in the NetPath log.
     */
    private void surfaceCappedError(String errorTitle, Throwable error) {
        boolean connectivity = isConnectivityError(error);

        if (getPlayer() != null) {
            getPlayer().setTitle(connectivity
                    ? getContext().getString(R.string.msg_player_no_connection_retry)
                    : errorTitle);
            getPlayer().showProgressBar(false);
            getPlayer().showOverlay(true);
        }

        mErrorCapped = true;

        // Only connectivity errors arm the auto-retry: reconnecting can't fix a server/content error,
        // and re-hammering it is exactly the anti-abuse behavior the cap exists to prevent.
        if (connectivity) {
            scheduleAutoRetry();
            armConnectivityRetry();
        }
        NetPath.log(NetPath.context() + " recovery-capped connectivity="
                + (connectivity ? "y" : "n") + ' ' + NetPath.networkSnapshot(getContext()));
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
     * Schedules the next timer-driven automatic retry on the {@link #AUTO_RETRY_BACKOFF_MS}
     * schedule. This is the recovery path for the common mobile outage - a tunnel, a lift, a
     * platform, a Wi-Fi->cellular handover - where the link stops delivering packets but Android
     * keeps reporting the network as connected and VALIDATED, so {@link #armConnectivityRetry}
     * never observes an edge to fire on (measured: data-stall detection took ~12 min to invalidate
     * a wedged LTE network, while the player gives up within seconds).
     */
    private void scheduleAutoRetry() {
        if (mAutoRetryAttempt >= AUTO_RETRY_BACKOFF_MS.length) {
            NetPath.log(NetPath.context() + " recovery-auto-retry-exhausted attempts=" + mAutoRetryAttempt);
            return;
        }

        long delayMs = AUTO_RETRY_BACKOFF_MS[mAutoRetryAttempt];
        mNextAutoRetryAtMs = SystemClock.elapsedRealtime() + delayMs;
        Utils.postDelayed(mScheduledRetry, delayMs);
        NetPath.log(NetPath.context() + " recovery-auto-retry-scheduled in=" + delayMs
                + " attempt=" + mAutoRetryAttempt);
    }

    /**
     * The one gate every automatic retry passes through, on the main thread.
     *
     * @param connectivityEdge the trigger was a PROVEN disconnected->validated transition, not our
     *                         own inference. That's strong evidence the outage is over, so it
     *                         refills the weak-evidence backoff budget (and revives a spent one)
     *                         and retries immediately.
     */
    private void requestAutoRetry(String trigger, boolean connectivityEdge) {
        // The dead state may have been left in the meantime (new video, engine release, manual
        // retry) - a stale timer or callback must not reload into a player that moved on.
        if (!mErrorCapped) {
            return;
        }

        if (connectivityEdge) {
            mAutoRetryAttempt = 0;
            mNextAutoRetryAtMs = 0;
        } else if (mAutoRetryAttempt >= AUTO_RETRY_BACKOFF_MS.length) {
            return;
        }

        long waitMs = mNextAutoRetryAtMs - SystemClock.elapsedRealtime();
        if (waitMs > 0) {
            // Too soon. Typically the connectivity callback replaying the state of a network that
            // is already up at registration time; re-arm for the remainder instead of firing.
            Utils.postDelayed(mScheduledRetry, waitMs);
            return;
        }

        NetPath.log(NetPath.context() + " recovery-auto-retry trigger=" + trigger
                + " attempt=" + mAutoRetryAttempt + ' ' + NetPath.networkSnapshot(getContext()));
        retryNow(false);
    }

    /**
     * Arms an automatic retry for when connectivity provably RETURNS. Strictly edge-triggered:
     * registerDefaultNetworkCallback immediately replays onAvailable/onCapabilitiesChanged for the
     * network that's ALREADY up, so a level-triggered "validated = retry" would fire instantly when
     * the cap trips on a slow-but-alive link (SocketTimeoutException/ERR_TIMED_OUT) - an unbounded
     * cap->arm->fire->cap loop against googlevideo, exactly what {@link #MAX_CONSECUTIVE_AUTO_FIXES}
     * exists to prevent. That's why this path stays edge-triggered even though the edge often never
     * comes; {@link #scheduleAutoRetry} owns the no-edge case on a bounded budget. Registered on the
     * APPLICATION context (never the Activity - a backgrounded dead player would otherwise leak it).
     * Idempotent: one live registration per dead-state episode.
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
        NetPath.log(NetPath.context() + " recovery-network-arm seedDisconnected="
                + (seedDisconnected ? "y" : "n") + ' ' + NetPath.networkSnapshot(context));

        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            // Confined to this callback: all events of one registration are serialized on a single
            // ConnectivityManager handler thread, so a plain boolean is safe.
            private boolean mSeenDisconnected = seedDisconnected;

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    if (mSeenDisconnected) {
                        mSeenDisconnected = false; // one fire per observed disconnect (disarm lands on the main thread)
                        NetPath.log(NetPath.context() + " recovery-network-restored "
                                + NetPath.networkSnapshot(context, network));
                        onConnectivityRestored();
                    }
                } else {
                    mSeenDisconnected = true;
                }
            }

            @Override
            public void onLost(Network network) {
                mSeenDisconnected = true;
                NetPath.log(NetPath.context() + " recovery-network-lost net="
                        + network.hashCode());
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
     * Recovery from the dead state: forget the cap window and reload the current video as if the user
     * had reopened it, so the anti-abuse counter starts over. Both triggers disarm the dead state
     * first, so a retry can never be entered twice for the same episode.
     *
     * @param userInitiated a play tap or a genuine reopen. Only a user says "this is worth trying
     *                      again from scratch": an automatic retry instead advances the escalating
     *                      backoff, which is what stops a failure that merely LOOKS like a
     *                      connectivity outage (a poisoned chunk timing out at the same position)
     *                      from being replayed forever. Only recovered playback ({@link #onTickle})
     *                      or a proven connectivity edge refills that budget on their own.
     */
    private void retryNow(boolean userInitiated) {
        NetPath.log(NetPath.context() + " recovery-retry-now user=" + (userInitiated ? "y" : "n")
                + " pos=" + (getPlayer() != null ? getPlayer().getPositionMs() : -1)
                + ' ' + NetPath.networkSnapshot(getContext()));
        if (userInitiated) {
            clearErrorCapped(); // also refills the automatic-retry budget
        } else {
            mAutoRetryAttempt++; // the next episode of this outage waits longer
            mErrorCapped = false;
            disarmAutoRetry();
        }
        resetAutoFixCap();
        resetSamePositionWindow();
        // Tag the reload below as OUR OWN so onNewVideo doesn't read it as a fresh user-initiated
        // open and wipe what was just set - in particular the retry budget, which has to survive
        // the reload it pays for. Measured before this line existed: every automatic retry reset
        // itself to attempt=0, so the backoff never escalated and offline playback re-attempted
        // forever on a fixed ~16s period (the exact hammering the budget exists to bound).
        mAutoReloadPending = true;
        mAutoFixVideoId = getVideo() != null ? getVideo().videoId : null;
        // The dead state was reached through repeated URL failures - and on the connectivity-restore
        // path a network reattach may sit behind a new public IP that no longer matches the URLs'
        // ip= binding. Without this, reloadVideo() rides the still-actual positive format-info cache
        // and replays exactly the URLs that just died (observed on-device: the manual retry burned a
        // full error cycle on the stale manifest before the automatic path re-fetched). Invalidate
        // like the automatic 403 path does, so the retry mints fresh URLs.
        YouTubeServiceManager.instance().applyNoPlaybackFix();
        if (mVideoLoaderController != null) {
            mVideoLoaderController.reloadVideo();
        }
    }

    private void clearErrorCapped() {
        mErrorCapped = false;
        // A user action / a new playback session ends the outage episode: automatic recovery starts
        // over at the shortest backoff next time.
        mAutoRetryAttempt = 0;
        mNextAutoRetryAtMs = 0;
        disarmAutoRetry();
    }

    private void disarmAutoRetry() {
        Utils.removeCallbacks(mConnectivityRetry, mScheduledRetry);
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
        scheduleAutoReload(false);
    }

    private void scheduleAutoReload(boolean freshUrlsRequested) {
        mAutoReloadPending = true;
        NetPath.log(NetPath.context() + " recovery-schedule mode="
                + (freshUrlsRequested ? "url-remint" : "normal")
                + " attempt=" + mConsecutiveAutoFixCount
                + " pos=" + (getPlayer() != null ? getPlayer().getPositionMs() : -1));
        if (freshUrlsRequested) {
            mVideoLoaderController.reloadVideoAfterUrlRemint();
        } else {
            mVideoLoaderController.reloadVideo();
        }
    }

    /** Matches the status in any nested transport exception without depending on media3 classes. */
    private static boolean hasHttpStatus(Throwable error, int statusCode) {
        String code = Integer.toString(statusCode);
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 12; cause = cause.getCause(), depth++) {
            String message = cause.getMessage();
            if (message != null && (message.contains("Response code: " + code)
                    || message.contains("responseCode=" + code)
                    || message.contains("status=" + code)
                    || message.contains("HTTP " + code))) {
                return true;
            }
        }
        return false;
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
        mAudioPinRescueArmed = false;
        mAudioPinRescueVideoId = null;
    }

    /**
     * NEWTUBE(pin-rescue): relax an EXPLICIT track pin only after a fresh URL/client remint failed
     * too. An initial 403 commonly rejects every URL minted by one /player client; blaming whichever
     * audio/video loader happened to surface first produced a false "audio track failing" toast and
     * needlessly discarded the user's choice. A repeat at startup (consecutive count) or at the same
     * media position (the counter that survives READY playback) identifies a track-specific failure.
     * When media3 can identify the source renderer, only that pin is eligible; legacy/unknown errors
     * preserve the prior video-then-audio fallback. The actual session-only switch is applied by
     * {@link #reassertPinRescueIfArmed} on the reload; persisted preferences are never overwritten.
     */
    private void maybeRescuePinnedFormat(String reason, int rendererIndex) {
        if (getPlayer() == null || getVideo() == null) {
            return;
        }

        // Only server rejections of the pinned URL ("Response code: 403" etc). A transient
        // network blip (UnknownHost, connection loss) fails EVERY rung equally - downgrading
        // the pin there would just cost quality once the network recovers.
        if (reason == null || !reason.contains("Response code:")) {
            return;
        }

        if (mConsecutiveAutoFixCount < 2 && mSamePositionErrorCount < 2) {
            android.util.Log.d("NetPath", "rescue pins deferred for fresh-route retry renderer="
                    + rendererIndex + " reason=" + reason);
            return;
        }

        String videoId = getVideo().videoId;
        boolean videoRescued = mPinRescueArmed && Helpers.equals(videoId, mPinRescueVideoId);
        boolean audioRescued = mAudioPinRescueArmed && Helpers.equals(videoId, mAudioPinRescueVideoId);
        boolean videoCandidate = rendererIndex == PlayerEventListener.RENDERER_INDEX_UNKNOWN
                || rendererIndex == PlayerEventListener.RENDERER_INDEX_VIDEO;
        boolean audioCandidate = rendererIndex == PlayerEventListener.RENDERER_INDEX_UNKNOWN
                || rendererIndex == PlayerEventListener.RENDERER_INDEX_AUDIO;

        // Unknown-renderer legacy errors keep the historical one-rescue-per-cycle order: video pin
        // first, observe the next cycle, then audio. Media3's inferred renderer targets one directly.
        FormatItem pinnedVideo = getEffectiveVideoFormat();
        if (videoCandidate && !videoRescued && !isAutoFormat(pinnedVideo)) {
            mPinRescueArmed = true;
            mPinRescueVideoId = videoId;

            android.util.Log.d("NetPath", "rescue pin->auto reason=" + reason);
            MessageHelpers.showLongMessage(getContext(),
                    getContext().getString(R.string.msg_quality_pin_fallback, qualityLabel(pinnedVideo)));
            return;
        }

        if (audioCandidate && !audioRescued && !isAutoFormat(getEffectiveAudioFormat())) {
            mAudioPinRescueArmed = true;
            mAudioPinRescueVideoId = videoId;

            android.util.Log.d("NetPath", "rescue audio-pin->default reason=" + reason);
            MessageHelpers.showLongMessage(getContext(),
                    getContext().getString(R.string.msg_audio_pin_fallback));
        }
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
        if (mAudioPinRescueArmed && Helpers.equals(videoId, mAudioPinRescueVideoId)) {
            // The default is a codec-preference preset (= adaptive, language-aware selection),
            // so the selector is free to pick the same-language alternative codec rendition
            // instead of the one concrete itag the persisted pin kept re-selecting.
            getPlayerData().setTempAudioFormat(getPlayerData().getDefaultAudioFormat());
        }
    }

    /** The video format that {@code restoreVideoFormat} would apply: per-session pin, else persisted. */
    private FormatItem getEffectiveVideoFormat() {
        FormatItem temp = getPlayerData().getTempVideoFormat();
        return temp != null ? temp : getPlayerData().getFormat(FormatItem.TYPE_VIDEO);
    }

    /** The audio format that {@code restoreAudioFormat} would apply: per-session pin, else persisted. */
    private FormatItem getEffectiveAudioFormat() {
        FormatItem temp = getPlayerData().getTempAudioFormat();
        return temp != null ? temp : getPlayerData().getFormat(FormatItem.TYPE_AUDIO);
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
