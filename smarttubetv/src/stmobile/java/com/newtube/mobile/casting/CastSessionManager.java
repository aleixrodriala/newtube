package com.newtube.mobile.casting;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.liskovsoft.mediaserviceinterfaces.CastSenderService;
import com.liskovsoft.mediaserviceinterfaces.RemoteControlService;
import com.liskovsoft.mediaserviceinterfaces.data.CastEvent;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.newtube.mobile.casting.castv2.CastV2Session;
import com.newtube.mobile.casting.castv2.MdxScreenIdReader;
import com.newtube.mobile.casting.proxy.CastProxyServer;
import com.newtube.mobile.casting.proxy.MpdRewriter;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * App-side owner of the one active cast session (CASTING.md "Shared architecture").
 *
 * <p>Public API + {@link Listener} contract are route-agnostic: the player overlay and
 * {@link CastSessionService} talk only to this class. Internally a session is exactly one
 * {@link Connection} - {@link LoungeConnection} (Route B: Lounge sender in the MediaServiceCore
 * fork) or {@link CastV2Connection} (Route A: Cast v2 Default Media Receiver fed by the on-phone
 * {@link CastProxyServer}). The manager tracks shared session state (target, videoId,
 * position/duration/play-state), fans it out to {@link Listener}s on the main thread and routes
 * transport commands to the connection. It also starts/stops {@link CastSessionService}, the
 * foreground service that keeps the session alive (wifi lock + notification) while the app is
 * backgrounded.</p>
 *
 * <p><b>One-session invariant + dead-connection guarding</b> (the CLAUDE.md eviction-timeline
 * rule): {@code connect()} tears the previous session down IMMEDIATELY (the async
 * {@code disconnect()} would race the new session), and {@link #endSession} nulls
 * {@link #mConnection} BEFORE destroying it, so every connection callback - all of which hop to
 * the main thread first - re-checks {@link #isCurrent} and becomes a no-op once its session is
 * dead. The Lounge path additionally relies on Rx disposal (disposing the connect stream stops
 * its callbacks at the source).</p>
 *
 * <p>The Lounge sender implementation lives in the MediaServiceCore fork and may not have landed
 * yet: {@code getCastSenderService()} defaults to null. Lounge paths null-check it and degrade
 * gracefully ({@link #isSenderAvailable()} gates the UI affordances); Route A never needs it.</p>
 */
public class CastSessionManager {

    public interface Listener {
        /** A session was established (main thread). */
        void onCastSessionStarted(CastTarget target);

        /** Playback state on the TV changed (main thread). Values are normalized to ms. */
        void onCastSessionState(@Nullable String videoId, long positionMs, long durationMs, boolean playing);

        /** The session ended - user disconnect, TV-side stop or error (main thread). */
        void onCastSessionEnded(@Nullable String reason);

        /**
         * The manager entered/left the "connecting" window (main thread): a session is being
         * established (or an auto-fallback is resolving) but isn't usable yet. UI hint only -
         * the browse/player cast icons show a spinner. Fired on CHANGE, deduped. Default no-op
         * keeps existing listeners source-compatible.
         */
        default void onCastConnectingChanged(boolean connecting) {
        }
    }

    /**
     * One route's session implementation. Lifecycle: {@code start()} once; then either the user's
     * {@code disconnect()} (route-specific semantics, must end in {@link #endSession}) or the
     * manager's {@link #endSession} calling {@code destroy()} (immediate, no remote stop, never
     * calls back into the manager). Transport commands are only routed while {@link #mConnected}.
     */
    private interface Connection {
        void start();

        void disconnect();

        void destroy();

        void loadVideo(String videoId, long positionMs);

        void play();

        void pause();

        void seekTo(long positionMs);

        void stopVideo();

        /** Absolute receiver volume, already clamped to 0-100 by the manager. */
        void setVolume(int volumePercent);
    }

    private static final String TAG = CastSessionManager.class.getSimpleName();

    @SuppressWarnings("StaticFieldLeak") // holds the application context only
    private static CastSessionManager sInstance;

    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();

    @Nullable
    private Connection mConnection;
    @Nullable
    private CastTarget mTarget;
    private boolean mConnected;
    @Nullable
    private String mVideoId;
    private long mPositionMs = -1;
    private long mDurationMs = -1;
    private int mState = RemoteControlService.STATE_IDLE;
    /** elapsedRealtime of the last position-bearing event, for playing-state interpolation. */
    private long mPositionTimestamp;
    /**
     * TV volume as a 0-100 percentage; -1 until either route reports it (Lounge
     * TYPE_VOLUME_CHANGE / Cast v2 RECEIVER_STATUS) or a local set establishes a baseline.
     */
    private int mVolumePercent = -1;

    private CastSessionManager(Context context) {
        mContext = context.getApplicationContext();
    }

    public static synchronized CastSessionManager instance(Context context) {
        if (sInstance == null) {
            sInstance = new CastSessionManager(context);
        }
        return sInstance;
    }

    // ---------------------------------------------------------------------------------
    // Sender availability (Lounge routes only - Route A is self-contained)
    // ---------------------------------------------------------------------------------

    /** The Lounge sender (MediaServiceCore fork). Null until the submodule implementation lands. */
    @Nullable
    public CastSenderService getSender() {
        CastSenderService sender = YouTubeServiceManager.instance().getCastSenderService();
        if (sender == null) {
            Log.d(TAG, "CastSenderService not available yet (submodule implementation pending)");
        }
        return sender;
    }

    public boolean isSenderAvailable() {
        return YouTubeServiceManager.instance().getCastSenderService() != null;
    }

    // ---------------------------------------------------------------------------------
    // Session lifecycle
    // ---------------------------------------------------------------------------------

    /**
     * Open a session on the target. Any previous session (either route) is torn down first.
     *
     * @return false when the target can't be connected yet, or (Lounge routes) the sender isn't
     *         available
     */
    public boolean connect(CastTarget target) {
        // Any connect supersedes an armed/pending auto-fallback (the fallback's own connect
        // included - the switch is one-shot by construction).
        mFallbackArmed = false;
        mFallbackSessionEnabled = false;
        mFallbackToken = null;
        if (target == null || !target.isConnectable()) {
            notifyConnectingState(); // the token clear above may have ended a pending fallback
            return false;
        }
        boolean castV2 = target.getRoute() == CastTarget.Route.CAST_V2;
        if (!castV2 && getSender() == null) {
            notifyConnectingState();
            return false;
        }

        teardown(); // one session at a time (immediate - the async disconnect() would race the new session)

        mTarget = target;
        resetPlaybackState();
        mConnection = castV2 ? new CastV2Connection(target) : new LoungeConnection(target);
        mConnection.start();
        notifyConnectingState(); // spinner on: session in flight
        return true;
    }

    /**
     * One-tap connect for a picker device row: open the recommended Direct-cast session, and if
     * the session fails before playback is proven OR a later video can't be loaded directly,
     * automatically switch to the device's TV app instead of stranding the user with an error
     * toast (the picker's "one click, it just works" contract). Non-CAST_V2 targets simply
     * {@link #connect} - they have exactly one mode.
     */
    public boolean connectWithFallback(CastTarget target) {
        boolean started = connect(target);
        if (started && target.getRoute() == CastTarget.Route.CAST_V2) {
            mFallbackArmed = true; // after connect() - which always disarms first
            mFallbackSessionEnabled = true;
        }
        return started;
    }

    /**
     * User-initiated disconnect (overlay button / notification action). Route-specific:
     * <ul>
     *   <li>Lounge stops playback on the TV FIRST, then tears down - the phone resumes local
     *       playback on session end, so without the remote stop both screens play at once.</li>
     *   <li>Cast v2 just closes the session ({@link CastV2Session#close()} sends the receiver
     *       STOP, so the TV app closes - double playback is impossible by construction) and stops
     *       the proxy. No stop-then-wait dance; it stays snappy.</li>
     * </ul>
     */
    public void disconnect() {
        // Explicit user disconnect also cancels any armed/pending auto-fallback.
        mFallbackArmed = false;
        mFallbackSessionEnabled = false;
        mFallbackToken = null;
        Connection connection = mConnection;
        if (connection == null) {
            teardown();
            return;
        }
        connection.disconnect();
    }

    /** Immediate session teardown - no remote stop. Also called defensively before a new connect. */
    private void teardown() {
        if (mConnection != null || mConnected || mTarget != null) {
            endSession(null);
        }
    }

    /**
     * The one place a session dies. Order matters for the dead-callback guard: {@link #mConnection}
     * is nulled BEFORE {@code destroy()}, so any callback the dying connection still emits fails
     * its {@link #isCurrent} check instead of touching the (possibly brand-new) session state.
     */
    private void endSession(@Nullable String reason) {
        CastTarget endedTarget = mTarget;
        boolean fallback = shouldAutoFallback(mFallbackArmed, reason,
                endedTarget != null ? endedTarget.getRoute() : null);
        mFallbackArmed = false; // one-shot: a failing fallback session must not re-fallback
        mFallbackSessionEnabled = false;
        boolean wasActive = mConnection != null || mConnected || mTarget != null;
        Connection connection = mConnection;
        mConnection = null;
        if (connection != null) {
            connection.destroy();
        }
        mConnected = false;
        mTarget = null;
        CastSessionService.stop(mContext);
        if (wasActive) {
            for (Listener listener : mListeners) {
                listener.onCastSessionEnded(reason);
            }
        }
        resetPlaybackState();
        if (fallback) {
            // After the ended/reset fanout, so the fallback's connect() starts from clean state.
            startFallback(endedTarget, reason);
        }
        // Once the fallback decision ran: a started fallback keeps the spinner up (new session
        // or pending mdx read), a plain end turns it off.
        notifyConnectingState();
    }

    /** Dead-session guard: callbacks from a torn-down connection must never touch manager state. */
    private boolean isCurrent(Connection connection) {
        return mConnection == connection;
    }

    private void resetPlaybackState() {
        mConnected = false;
        mVideoId = null;
        mPositionMs = -1;
        mDurationMs = -1;
        mState = RemoteControlService.STATE_IDLE;
        mPositionTimestamp = 0;
        mVolumePercent = -1;
    }

    private void notifyState() {
        for (Listener listener : mListeners) {
            listener.onCastSessionState(mVideoId, getPositionMs(), mDurationMs, isPlayingOnTv());
        }
    }

    /** Shared TYPE_CONNECTED flow: mark connected, start the FGS, tell the UI (main thread). */
    private void handleConnected() {
        mConnected = true;
        notifyConnectingState(); // spinner off before the started fanout repaints the icons
        CastSessionService.start(mContext);
        CastTarget target = mTarget;
        for (Listener listener : mListeners) {
            listener.onCastSessionStarted(target);
        }
    }

    // ---------------------------------------------------------------------------------
    // State accessors
    // ---------------------------------------------------------------------------------

    public boolean isConnected() {
        return mConnected;
    }

    /**
     * A connect attempt is in flight but not usable yet: session starting (picker tap ->
     * onConnected takes the DMR ~4s, a Lounge bind ~1-2s) or an auto-fallback resolving its
     * screenId over the mdx shim (up to 15s). Drives the cast-icon spinner.
     */
    public boolean isConnecting() {
        return (mConnection != null && !mConnected) || mFallbackToken != null;
    }

    /** Last value handed to {@link Listener#onCastConnectingChanged} (dedupe on change). */
    private boolean mNotifiedConnecting;

    /** Recompute + fan out the connecting hint; call after every state transition. */
    private void notifyConnectingState() {
        boolean connecting = isConnecting();
        if (connecting == mNotifiedConnecting) {
            return;
        }
        mNotifiedConnecting = connecting;
        for (Listener listener : mListeners) {
            listener.onCastConnectingChanged(connecting);
        }
    }

    @Nullable
    public CastTarget getTarget() {
        return mTarget;
    }

    @Nullable
    public String getVideoId() {
        return mVideoId;
    }

    /** Last reported TV position, interpolated forward while the TV reports "playing". */
    public long getPositionMs() {
        if (mPositionMs < 0) {
            return -1;
        }
        long position = mPositionMs;
        if (mState == RemoteControlService.STATE_PLAYING && mPositionTimestamp > 0) {
            position += SystemClock.elapsedRealtime() - mPositionTimestamp;
        }
        return mDurationMs > 0 ? Math.min(position, mDurationMs) : position;
    }

    public long getDurationMs() {
        return mDurationMs;
    }

    public boolean isPlayingOnTv() {
        return mState == RemoteControlService.STATE_PLAYING;
    }

    /** True when the phone is feeding the Default Media Receiver through the local proxy. */
    public boolean isDirectRoute() {
        return mTarget != null && mTarget.getRoute() == CastTarget.Route.CAST_V2;
    }

    /** Selected Direct-cast quality ceiling; {@code 0} means Auto (universal 1080p cap). */
    public int getDirectQualityHeight() {
        Connection connection = mConnection;
        return connection instanceof CastV2Connection
                ? ((CastV2Connection) connection).getVideoHeightCap() : 0;
    }

    // ---------------------------------------------------------------------------------
    // Transport commands (valid only while connected; optimistic local state, real work async)
    // ---------------------------------------------------------------------------------

    public void loadVideo(String videoId, long positionMs) {
        if (TextUtils.isEmpty(videoId)) {
            return;
        }
        Connection connection = mConnection;
        if (connection == null || !mConnected) {
            return;
        }
        // Optimistic: remember what the TV is (about to be) playing so a locally initiated load
        // isn't re-routed again when setVideo() sees it (MobilePlaybackActivity hook).
        mVideoId = videoId;
        mPositionMs = Math.max(positionMs, 0);
        mPositionTimestamp = SystemClock.elapsedRealtime();
        connection.loadVideo(videoId, positionMs);
    }

    public void play() {
        Connection connection = mConnection;
        if (connection != null && mConnected) {
            mState = RemoteControlService.STATE_PLAYING;
            connection.play();
            notifyState();
        }
    }

    public void pause() {
        Connection connection = mConnection;
        if (connection != null && mConnected) {
            // Fold the interpolated head start into the stored position before freezing it.
            mPositionMs = getPositionMs();
            mPositionTimestamp = SystemClock.elapsedRealtime();
            mState = RemoteControlService.STATE_PAUSED;
            connection.pause();
            notifyState();
        }
    }

    public void seekTo(long positionMs) {
        Connection connection = mConnection;
        if (connection != null && mConnected) {
            mPositionMs = Math.max(positionMs, 0);
            mPositionTimestamp = SystemClock.elapsedRealtime();
            connection.seekTo(positionMs);
            notifyState();
        }
    }

    public void stopVideo() {
        Connection connection = mConnection;
        if (connection != null && mConnected) {
            connection.stopVideo();
        }
    }

    /**
     * Change Direct-cast quality without reconnecting. The proxy rebuilds its DASH manifest with
     * only compatible rungs at or below the requested height, then resumes at the TV's current
     * position. {@code 0} restores Auto (up to 1080p).
     */
    public boolean setDirectQualityHeight(int height) {
        Connection connection = mConnection;
        if (!(connection instanceof CastV2Connection) || !mConnected || TextUtils.isEmpty(mVideoId)) {
            return false;
        }
        ((CastV2Connection) connection).setVideoHeightCap(height);
        connection.loadVideo(mVideoId, Math.max(getPositionMs(), 0));
        return true;
    }

    /** Select/disable receiver-side captions on a YouTube or SmartTube Lounge session. */
    public boolean setReceiverSubtitle(@Nullable String vssId, @Nullable String languageCode) {
        Connection connection = mConnection;
        if (!(connection instanceof LoungeConnection) || !mConnected || TextUtils.isEmpty(mVideoId)) {
            return false;
        }
        ((LoungeConnection) connection).setSubtitle(mVideoId, vssId, languageCode);
        return true;
    }

    /**
     * Explicitly leave Direct cast and open the TV's YouTube receiver for its native controls.
     * The mdx read is always repeated because launching the app is the route switch itself.
     */
    public boolean switchDirectSessionToTvApp() {
        CastTarget target = mTarget;
        if (!mConnected || target == null || target.getRoute() != CastTarget.Route.CAST_V2
                || !isSenderAvailable() || TextUtils.isEmpty(target.getCastHost())) {
            return false;
        }
        mFallbackArmed = false;
        mFallbackSessionEnabled = false;
        startTvAppRoute(target, null);
        return true;
    }

    // ---------------------------------------------------------------------------------
    // TV volume (hardware volume keys route here while a session is connected)
    // ---------------------------------------------------------------------------------

    /** Baseline when a volume key arrives before any route reported the TV's real volume. */
    static final int VOLUME_BASELINE_PERCENT = 50;
    /** Per-volume-key-press step. */
    public static final int VOLUME_STEP_PERCENT = 5;

    /** Last known TV volume, 0-100; -1 while unknown (no volume event / local set yet). */
    public int getVolumePercent() {
        return mVolumePercent;
    }

    /** Set absolute TV volume (clamped to 0-100). Tracked optimistically like the transport state. */
    public void setVolumePercent(int volumePercent) {
        Connection connection = mConnection;
        if (connection == null || !mConnected) {
            return;
        }
        // Optimistic local tracking (mirrors play/pause/seek): both routes echo the real value
        // back asynchronously (Lounge TYPE_VOLUME_CHANGE / Cast v2 RECEIVER_STATUS) and correct us.
        mVolumePercent = clampVolumePercent(volumePercent);
        connection.setVolume(mVolumePercent);
    }

    /**
     * Relative TV volume change (volume keys: +/-{@link #VOLUME_STEP_PERCENT}).
     *
     * <p>Known limitation: both routes take ABSOLUTE volume on the wire, so when no volume event
     * has arrived yet the first press jumps to {@link #VOLUME_BASELINE_PERCENT} +/- delta rather
     * than nudging the TV's actual level. The very next status/volume event re-syncs; not worth a
     * query round-trip on the key path.</p>
     *
     * @return the volume percent just sent, or -1 when not connected (caller shows no indicator)
     */
    public int adjustVolume(int deltaPercent) {
        if (mConnection == null || !mConnected) {
            return -1;
        }
        setVolumePercent(applyVolumeDelta(mVolumePercent, deltaPercent));
        return mVolumePercent;
    }

    /** Pure clamp to the 0-100 wire range (static for JVM tests). */
    static int clampVolumePercent(int volumePercent) {
        return Math.max(0, Math.min(100, volumePercent));
    }

    /** Pure delta application with the unknown-state baseline (static for JVM tests). */
    static int applyVolumeDelta(int currentPercent, int deltaPercent) {
        int base = currentPercent >= 0 ? currentPercent : VOLUME_BASELINE_PERCENT;
        return clampVolumePercent(base + deltaPercent);
    }

    // ---------------------------------------------------------------------------------
    // Auto-fallback: Direct cast first, the device's YouTube app when it fails
    // ---------------------------------------------------------------------------------

    /** mdx shim budget for launching the YouTube receiver during fallback (mirrors CastPickerSheet). */
    private static final long FALLBACK_MDX_TIMEOUT_MS = 15_000;

    /**
     * Armed by {@link #connectWithFallback} until direct playback is proven (first raw "PLAYING"
     * media status - deliberately not BUFFERING, which can precede a LOAD_FAILED). While armed, a
     * session-level failure or a refused/rejected load switches routes instead of just toasting.
     */
    private boolean mFallbackArmed;
    /**
     * The direct session came from the picker's recommended one-tap path. Unlike
     * {@link #mFallbackArmed}, this survives the first successful video: a later video may be live
     * or otherwise incompatible with the Default Media Receiver and should still fall back to the
     * TV app. Explicit "Cast without ads" chooser sessions leave this false and keep honoring the
     * user's route choice.
     */
    private boolean mFallbackSessionEnabled;
    /**
     * Identity of the mdx read a pending fallback is waiting on. Any explicit connect() or
     * disconnect() nulls it, so a user action always supersedes the automatic switch.
     */
    @Nullable
    private Object mFallbackToken;

    /** Pure decision core (static for JVM tests): switch only for a FAILED armed direct session. */
    static boolean shouldAutoFallback(boolean armed, @Nullable String endReason,
            @Nullable CastTarget.Route route) {
        // reason == null covers every deliberate teardown: user disconnect, the teardown before a
        // new connect, and graceful remote closes.
        return armed && endReason != null && route == CastTarget.Route.CAST_V2;
    }

    /**
     * Load failures are eligible for the whole recommended one-tap direct session. This is
     * deliberately broader than {@link #shouldAutoFallback}: once a VOD has played successfully,
     * a later live/incompatible selection is a load-level route limitation, not a dead session.
     */
    static boolean shouldAutoFallbackForLoad(boolean armed, boolean sessionEnabled,
            @Nullable CastTarget.Route route) {
        return (armed || sessionEnabled) && route == CastTarget.Route.CAST_V2;
    }

    /** A Lounge receiver has loaded our requested item but kept its previous paused state. */
    static boolean shouldAutoPlayLoungeLoad(@Nullable String pendingVideoId,
            @Nullable String reportedVideoId, int reportedState) {
        return pendingVideoId != null && pendingVideoId.equals(reportedVideoId)
                && reportedState != RemoteControlService.STATE_PLAYING;
    }

    /** Load-level fallback hook (session still alive). True = switch started, suppress the toast. */
    private boolean maybeStartFallbackForLoad(String reason) {
        CastTarget target = mTarget;
        if (!shouldAutoFallbackForLoad(mFallbackArmed, mFallbackSessionEnabled,
                target != null ? target.getRoute() : null)) {
            return false;
        }
        mFallbackArmed = false;
        mFallbackSessionEnabled = false;
        startFallback(target, reason);
        return true;
    }

    /**
     * The automatic Direct-cast -> YouTube-app switch. Always run the mdx shim first so it launches
     * the YouTube receiver app and replaces the Default Media Receiver that Direct cast was using.
     * A saved Lounge screenId alone is insufficient: the backend can accept commands for that
     * screen while the TV is still visibly sitting in the old Cast receiver. The fresh screenId is
     * persisted exactly like the picker's explicit flow.
     */
    private void startFallback(CastTarget device, String reason) {
        if (!isSenderAvailable() || TextUtils.isEmpty(device.getCastHost())) {
            MessageHelpers.showMessage(mContext, reason); // no fallback possible - honest error
            return;
        }
        startTvAppRoute(device, reason);
    }

    /** Shared mdx launch for automatic fallback and the user's explicit “TV app controls” tap. */
    private void startTvAppRoute(CastTarget device, @Nullable String fallbackReason) {
        Log.d(TAG, (fallbackReason != null
                ? "Direct cast failed (" + fallbackReason + ") - falling back"
                : "Switching explicit Direct session") + " to the YouTube app on " + device.getName());
        MessageHelpers.showMessage(mContext, fallbackReason != null
                ? R.string.mobile_cast_fallback_switching : R.string.mobile_cast_switching_tv_app);

        Object token = new Object();
        mFallbackToken = token;
        notifyConnectingState(); // spinner stays up through the mdx resolve
        MdxScreenIdReader.readScreenId(device.getCastHost(), device.getCastPort(),
                FALLBACK_MDX_TIMEOUT_MS, new MdxScreenIdReader.Callback() {
                    @Override
                    public void onScreenId(String screenId) {
                        // Reader's internal thread - hop to main before touching manager state.
                        mMainHandler.post(() -> {
                            CastTarget resolved = youTubeAppTarget(device, screenId);
                            // The pairing is real regardless of what the user did meanwhile, and
                            // persisting it under the device name powers the picker-row merge.
                            CastPrefs.addPairedScreen(mContext, resolved.getScreen());
                            if (mFallbackToken != token) {
                                return; // a user connect/disconnect superseded the fallback
                            }
                            mFallbackToken = null;
                            connect(resolved);
                        });
                    }

                    @Override
                    public void onError(String mdxReason) {
                        Log.e(TAG, "Fallback mdx read failed: " + mdxReason);
                        mMainHandler.post(() -> {
                            if (mFallbackToken != token) {
                                return;
                            }
                            mFallbackToken = null;
                            notifyConnectingState();
                            // Automatic fallback surfaces the original load error. An explicit
                            // mode switch names the receiver launch failure instead.
                            if (fallbackReason != null) {
                                MessageHelpers.showMessage(mContext, fallbackReason);
                            } else {
                                MessageHelpers.showMessage(mContext, R.string.mobile_cast_launch_failed);
                            }
                        });
                    }
                });
    }

    /** The Lounge target for a Cast device's YouTube-app mode (mirrors CastPickerSheet's helper). */
    private static CastTarget youTubeAppTarget(CastTarget device, String screenId) {
        return CastTarget.fromCastDeviceYouTubeApp(
                device.getName(), device.getCastHost(), device.getCastPort()).withScreenId(screenId);
    }

    // ---------------------------------------------------------------------------------
    // Route B: Lounge connection (behavior preserved verbatim from the pre-refactor manager)
    // ---------------------------------------------------------------------------------

    private class LoungeConnection implements Connection {
        private final CastTarget mLoungeTarget;
        private Disposable mConnectAction;
        /** Cleared by the matching nowPlaying event; prevents old events starting a newer load. */
        @Nullable
        private String mPendingAutoPlayVideoId;

        LoungeConnection(CastTarget target) {
            mLoungeTarget = target;
        }

        @Override
        public void start() {
            CastSenderService sender = getSender();
            if (sender == null) {
                endSession("Lounge sender unavailable");
                return;
            }
            // Long-lived stream; disposing it tears the Lounge session down (interface contract).
            mConnectAction = sender.connectObserve(mLoungeTarget.getScreen())
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            this::handleEvent,
                            error -> {
                                Log.e(TAG, "Cast session error: " + error);
                                if (isCurrent(this)) {
                                    endSession(error.getMessage());
                                }
                            },
                            () -> {
                                if (isCurrent(this)) {
                                    endSession(null);
                                }
                            });
        }

        /**
         * Stops playback on the TV FIRST, then tears the session down. Ordering matters -
         * disposing the session sets the sender's stopped flag, after which it refuses commands,
         * so the teardown waits for the stop POST (bounded by a timeout in case the TV is gone).
         *
         * <p>The Route B "phone can leave" property is untouched: it covers walking away or
         * killing the app (no disconnect runs, the TV keeps playing), not an explicit
         * Disconnect tap.</p>
         */
        @Override
        public void disconnect() {
            CastSenderService sender = mConnected ? getSender() : null;
            if (sender == null) {
                teardown();
                return;
            }
            mConnected = false; // refuse further transport commands while the stop is in flight
            RxHelper.execute(
                    sender.stopVideoObserve()
                            .subscribeOn(Schedulers.io())
                            .timeout(3, java.util.concurrent.TimeUnit.SECONDS)
                            .observeOn(AndroidSchedulers.mainThread())
                            .doFinally(CastSessionManager.this::teardown),
                    error -> Log.e(TAG, "Stop-on-disconnect failed (tearing down anyway): " + error));
        }

        @Override
        public void destroy() {
            if (mConnectAction != null && !mConnectAction.isDisposed()) {
                mConnectAction.dispose(); // tears the session down sender-side
            }
            mConnectAction = null;
        }

        private void handleEvent(CastEvent event) {
            if (!isCurrent(this)) {
                return; // dead session (dispose raced an in-flight main-thread delivery)
            }
            switch (event.getType()) {
                case CastEvent.TYPE_CONNECTED:
                    handleConnected();
                    break;
                case CastEvent.TYPE_NOW_PLAYING:
                    boolean shouldAutoPlay = shouldAutoPlayLoungeLoad(mPendingAutoPlayVideoId,
                            event.getVideoId(), event.getState());
                    if (mPendingAutoPlayVideoId != null
                            && mPendingAutoPlayVideoId.equals(event.getVideoId())) {
                        mPendingAutoPlayVideoId = null;
                    }
                    if (!TextUtils.isEmpty(event.getVideoId())) {
                        mVideoId = event.getVideoId();
                    }
                    applyTiming(event);
                    notifyState();
                    // setPlaylist is not consistently autoplaying on the Philips receiver. A
                    // matching nowPlaying event is the reliable readiness signal: Play sent
                    // before it is silently ignored, while Play here starts immediately.
                    if (shouldAutoPlay) {
                        CastSessionManager.this.play();
                    }
                    break;
                case CastEvent.TYPE_STATE_CHANGE:
                    applyTiming(event);
                    notifyState();
                    break;
                case CastEvent.TYPE_VOLUME_CHANGE:
                    // Lounge wire volume is absolute 0-100 - same scale as ours.
                    if (event.getVolume() >= 0) {
                        mVolumePercent = clampVolumePercent(event.getVolume());
                    }
                    break;
                case CastEvent.TYPE_DISCONNECTED:
                    endSession(event.getReason());
                    break;
                default:
                    break;
            }
        }

        private void applyTiming(CastEvent event) {
            long position = event.getPositionMs();
            long duration = event.getDurationMs();
            if (duration > 0) {
                mDurationMs = duration;
            }
            if (position >= 0) {
                mPositionMs = position;
                mPositionTimestamp = SystemClock.elapsedRealtime();
            }
            if (event.getState() >= 0) {
                mState = event.getState();
            }
        }

        @Override
        public void loadVideo(String videoId, long positionMs) {
            CastSenderService sender = getSender();
            if (sender != null) {
                mPendingAutoPlayVideoId = videoId;
                // Some YouTube/SmartTube Lounge receivers honor setPlaylist but preserve the
                // previous PAUSED state. Sequence an explicit play AFTER the load POST so a
                // Direct -> TV-app fallback (especially VOD -> live) actually starts without an
                // extra tap. The matching nowPlaying event above is the primary readiness path;
                // this delayed command is a safety net for receivers that omit that event.
                runCommand(sender.loadVideoObserve(videoId, positionMs)
                        .concatWith(sender.playObserve().delaySubscription(8, TimeUnit.SECONDS)),
                        "loadVideo+play");
            }
        }

        @Override
        public void play() {
            CastSenderService sender = getSender();
            if (sender != null) {
                runCommand(sender.playObserve(), "play");
            }
        }

        @Override
        public void pause() {
            CastSenderService sender = getSender();
            if (sender != null) {
                runCommand(sender.pauseObserve(), "pause");
            }
        }

        @Override
        public void seekTo(long positionMs) {
            CastSenderService sender = getSender();
            if (sender != null) {
                runCommand(sender.seekToObserve(positionMs), "seekTo");
            }
        }

        @Override
        public void stopVideo() {
            CastSenderService sender = getSender();
            if (sender != null) {
                runCommand(sender.stopVideoObserve(), "stopVideo");
            }
        }

        @Override
        public void setVolume(int volumePercent) {
            CastSenderService sender = getSender();
            if (sender != null) {
                // setVolumeObserve takes absolute 0-100 (SenderCommand.setVolume javadoc).
                runCommand(sender.setVolumeObserve(volumePercent), "setVolume");
            }
        }

        void setSubtitle(String videoId, @Nullable String vssId, @Nullable String languageCode) {
            CastSenderService sender = getSender();
            if (sender != null) {
                runCommand(sender.setSubtitleObserve(videoId, vssId, languageCode),
                        "setSubtitlesTrack");
            }
        }

        private void runCommand(Observable<Void> command, String name) {
            // Fire-and-forget off the main thread; RxHelper supplies the error-swallowing subscriber.
            RxHelper.execute(
                    command.subscribeOn(Schedulers.io()),
                    error -> Log.e(TAG, "Cast command '" + name + "' failed: " + error));
        }
    }

    // ---------------------------------------------------------------------------------
    // Route A: Cast v2 connection (Default Media Receiver + phone-side proxy)
    // ---------------------------------------------------------------------------------

    /** A Route A load refused for an honest, user-readable reason (live stream, no avc1, ...). */
    private static final class DirectCastRefusedException extends RuntimeException {
        DirectCastRefusedException(String message) {
            super(message);
        }
    }

    /** What the io-thread prepare step hands the main thread: rewritten manifest + receiver metadata. */
    private static final class PreparedLoad {
        final byte[] mMpdBytes;
        @Nullable
        final String mTitle;

        PreparedLoad(byte[] mpdBytes, @Nullable String title) {
            mMpdBytes = mpdBytes;
            mTitle = title;
        }
    }

    /**
     * Map a Cast MEDIA_STATUS playerState onto the Lounge state model the overlay already speaks.
     *
     * <p>BUFFERING maps to STATE_PLAYING deliberately: the overlay's play/pause button reads
     * {@link #isPlayingOnTv()}, and a buffering TV showing a "play" button would invite a
     * double-toggle. Tradeoff: {@link #getPositionMs()} interpolates forward on STATE_PLAYING, so
     * the position drifts ahead during a stall - but {@link CastV2Session} polls MEDIA_STATUS
     * every 5s and each status rewrites the stored position, bounding the drift to &le;5s.</p>
     */
    static int mapCastV2PlayerState(@Nullable String playerState) {
        if ("PLAYING".equals(playerState) || "BUFFERING".equals(playerState)) {
            return RemoteControlService.STATE_PLAYING;
        }
        if ("PAUSED".equals(playerState)) {
            return RemoteControlService.STATE_PAUSED;
        }
        return RemoteControlService.STATE_IDLE; // "IDLE" + anything unknown
    }

    private class CastV2Connection implements Connection, CastV2Session.Listener {
        private final CastTarget mV2Target;
        private final CastProxyServer mProxy = new CastProxyServer();
        /** Guards session create-vs-destroy (start() runs on a worker, destroy() on main). */
        private final Object mSessionLock = new Object();
        @Nullable
        private CastV2Session mSession;
        private volatile boolean mDestroyed;
        /** Main-thread confined. Disposing it is the drop-stale half of the load serialization. */
        @Nullable
        private Disposable mLoadAction;
        /** videoId of the most recent load; a slower older chain's result is dropped by comparison. */
        @Nullable
        private volatile String mLoadVideoId;
        /** User's adaptive ceiling; 0 = Auto (MpdRewriter's universal 1080p ceiling). */
        private volatile int mVideoHeightCap;

        CastV2Connection(CastTarget target) {
            mV2Target = target;
        }

        @Override
        public void start() {
            // Off-main: proxy.start() binds a server socket. The CastV2Session itself is safe to
            // create/start from any thread (its channel does all network on its own threads).
            Thread starter = new Thread(() -> {
                String failure = null;
                try {
                    mProxy.start();
                } catch (IOException e) {
                    Log.e(TAG, "Cast proxy failed to start: " + e);
                    failure = mContext.getString(R.string.mobile_cast_direct_load_failed);
                }
                if (failure == null && mProxy.getBaseUrl() == null) {
                    // Bound but no LAN IPv4: the receiver could never reach us.
                    failure = mContext.getString(R.string.mobile_cast_direct_no_wifi);
                }
                if (failure != null) {
                    mProxy.stop();
                    String reason = failure;
                    mMainHandler.post(() -> {
                        if (isCurrent(this)) {
                            endSession(reason);
                        }
                    });
                    return;
                }
                synchronized (mSessionLock) {
                    if (mDestroyed) {
                        mProxy.stop(); // connect() raced us with a teardown - leave nothing running
                        return;
                    }
                    mSession = CastV2Session.forDefaultMediaReceiver(
                            mV2Target.getCastHost(), mV2Target.getCastPort(), this);
                    mSession.start();
                }
            }, "CastV2Connect");
            starter.setDaemon(true);
            starter.start();
        }

        /**
         * No stop-then-wait dance (contrast Lounge): {@link CastV2Session#close()} already sends
         * the receiver STOP, which closes the TV app outright - double playback is impossible.
         */
        @Override
        public void disconnect() {
            endSession(null); // endSession -> destroy() does the close+stop
        }

        @Override
        public void destroy() {
            mDestroyed = true;
            if (mLoadAction != null && !mLoadAction.isDisposed()) {
                mLoadAction.dispose();
            }
            mLoadAction = null;
            CastV2Session session;
            synchronized (mSessionLock) {
                session = mSession;
                mSession = null;
            }
            // Socket closes off-main; both calls are idempotent and safe on a dead peer.
            Thread closer = new Thread(() -> {
                if (session != null) {
                    session.close(); // receiver STOP + channel close - tears the TV app down
                }
                mProxy.stop();
            }, "CastV2Teardown");
            closer.setDaemon(true);
            closer.start();
        }

        // ---- CastV2Session.Listener (internal threads - hop to main, then guard) ----

        @Override
        public void onConnected() {
            mMainHandler.post(() -> {
                if (isCurrent(this)) {
                    // Same flow as Lounge TYPE_CONNECTED; the activity's onCastSessionStarted
                    // then calls loadVideo(videoId, positionMs) exactly like today.
                    handleConnected();
                }
            });
        }

        @Override
        public void onMediaStatus(String playerState, long positionMs, long durationMs) {
            mMainHandler.post(() -> {
                if (!isCurrent(this)) {
                    return;
                }
                if ("PLAYING".equals(playerState)) {
                    // Direct playback proven: a later CHANNEL/session failure is a real error,
                    // not fallback fodder. The separate session preference remains enabled for a
                    // later LOAD limitation (notably VOD -> live), where switching routes is the
                    // only way to honor the user's one-tap "just play it" choice.
                    mFallbackArmed = false;
                }
                mState = mapCastV2PlayerState(playerState);
                if (durationMs > 0) {
                    mDurationMs = durationMs;
                }
                if (positionMs >= 0) {
                    mPositionMs = positionMs;
                    mPositionTimestamp = SystemClock.elapsedRealtime();
                }
                notifyState();
            });
        }

        @Override
        public void onVolume(double level) {
            mMainHandler.post(() -> {
                if (isCurrent(this)) {
                    mVolumePercent = clampVolumePercent((int) Math.round(level * 100));
                }
            });
        }

        @Override
        public void onLaunchError(String reason) {
            postSessionEnd("Launch failed: " + reason);
        }

        @Override
        public void onLoadFailed(String type) {
            // Receiver rejected the LOAD (media error, session alive). Same handling as a local
            // load failure: toast - or, on a one-tap connect, the auto-fallback.
            mMainHandler.post(() -> {
                if (isCurrent(this)) {
                    failLoad(mContext.getString(R.string.mobile_cast_direct_load_failed));
                }
            });
        }

        @Override
        public void onChannelError(String reason) {
            // phoneFree=false is honest here: a dead channel IS a dead session (the TV has no
            // independent playback to keep going - it was streaming through the now-gone proxy).
            postSessionEnd(reason);
        }

        @Override
        public void onClosed() {
            postSessionEnd(null);
        }

        private void postSessionEnd(@Nullable String reason) {
            mMainHandler.post(() -> {
                if (isCurrent(this)) {
                    endSession(reason);
                }
            });
        }

        // ---- Load pipeline ----

        @Override
        public void loadVideo(String videoId, long positionMs) {
            // Serialize loads: dispose the previous chain (drop-stale) and remember which videoId
            // owns the slot. The proxy manifest swap happens on MAIN, after the staleness check -
            // doing it on io would let a slow old chain overwrite a newer video's manifest
            // (single-slot eviction rule, CLAUDE.md).
            if (mLoadAction != null && !mLoadAction.isDisposed()) {
                mLoadAction.dispose();
            }
            mLoadVideoId = videoId;
            mLoadAction = YouTubeServiceManager.instance().getMediaItemService()
                    .getFormatInfoObserve(videoId)
                    .subscribeOn(Schedulers.io())
                    .map(this::prepareLoad)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(prepared -> {
                        if (!isCurrent(this) || !videoId.equals(mLoadVideoId)) {
                            return; // session died or a newer load took the slot
                        }
                        CastV2Session session;
                        synchronized (mSessionLock) {
                            session = mSession;
                        }
                        String manifestUrl = mProxy.getManifestUrl();
                        if (session == null || manifestUrl == null) {
                            failLoad(mContext.getString(R.string.mobile_cast_direct_no_wifi));
                            return;
                        }
                        mProxy.loadVideo(prepared.mMpdBytes);
                        session.load(manifestUrl, MpdRewriter.MIME_TYPE, prepared.mTitle,
                                null /* no thumb getter on MediaItemFormatInfo */,
                                Math.max(positionMs, 0));
                    }, error -> {
                        if (!isCurrent(this) || !videoId.equals(mLoadVideoId)) {
                            return;
                        }
                        Log.e(TAG, "Direct-cast load failed: " + error);
                        failLoad(error instanceof DirectCastRefusedException
                                ? error.getMessage()
                                : mContext.getString(R.string.mobile_cast_direct_load_failed));
                    });
        }

        /** io thread: fetch-independent policy checks + manifest rewrite. Throws to refuse. */
        private PreparedLoad prepareLoad(MediaItemFormatInfo formatInfo) throws IOException {
            // LIVE IS NOT SUPPORTED on Route A v1: segment tokens wrap complete VOD URLs; live
            // needs proxied HLS (post-v1). isLive() = live RIGHT NOW - a finished stream
            // (isLiveContent) demuxes as normal VOD and casts fine, so only isLive() refuses.
            if (formatInfo.isLive()) {
                throw new DirectCastRefusedException(
                        mContext.getString(R.string.mobile_cast_direct_live_unsupported));
            }
            String baseUrl = mProxy.getBaseUrl();
            if (baseUrl == null) {
                throw new DirectCastRefusedException(
                        mContext.getString(R.string.mobile_cast_direct_no_wifi));
            }
            InputStream mpdStream = formatInfo.createMpdStream();
            if (mpdStream == null) {
                throw new DirectCastRefusedException(
                        mContext.getString(R.string.mobile_cast_direct_incompatible));
            }
            MpdRewriter.Result result = MpdRewriter.rewrite(mpdStream, baseUrl, mVideoHeightCap);
            if (result.isEmpty() || !result.hasCompatibleVideo()) {
                // Covers isVideoDroppedForCompatibility (had video, none avc1) AND audio-only:
                // silently casting sound with a black screen would read as a bug, not a feature.
                throw new DirectCastRefusedException(
                        mContext.getString(R.string.mobile_cast_direct_incompatible));
            }
            return new PreparedLoad(result.getMpdBytes(), formatInfo.getTitle());
        }

        /**
         * A load failed but the SESSION is fine - push a non-playing state so the overlay's
         * optimistic "loading" read doesn't spin forever, then either auto-fallback (one-tap
         * connects, pre-playback) or surface the reason. State goes FIRST: the fallback path may
         * connect() a new session, and this session's state must not leak into it.
         */
        private void failLoad(String reason) {
            mState = RemoteControlService.STATE_IDLE;
            notifyState();
            if (!maybeStartFallbackForLoad(reason)) {
                MessageHelpers.showMessage(mContext, reason);
            }
        }

        // ---- Transport (optimistic local updates already applied by the manager wrappers) ----

        @Override
        public void play() {
            CastV2Session session = currentSession();
            if (session != null) {
                session.play();
            }
        }

        @Override
        public void pause() {
            CastV2Session session = currentSession();
            if (session != null) {
                session.pause();
            }
        }

        @Override
        public void seekTo(long positionMs) {
            CastV2Session session = currentSession();
            if (session != null) {
                session.seekTo(positionMs);
            }
        }

        @Override
        public void stopVideo() {
            CastV2Session session = currentSession();
            if (session != null) {
                session.stop();
            }
        }

        @Override
        public void setVolume(int volumePercent) {
            CastV2Session session = currentSession();
            if (session != null) {
                session.setVolume(volumePercent / 100d); // Cast wire scale is 0.0-1.0
            }
        }

        int getVideoHeightCap() {
            return mVideoHeightCap;
        }

        void setVideoHeightCap(int height) {
            mVideoHeightCap = height > 0
                    ? Math.min(height, MpdRewriter.MAX_VIDEO_HEIGHT) : 0;
        }

        @Nullable
        private CastV2Session currentSession() {
            synchronized (mSessionLock) {
                return mSession;
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Listeners
    // ---------------------------------------------------------------------------------

    public void addListener(Listener listener) {
        if (listener != null && !mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    // ---------------------------------------------------------------------------------
    // Notification actions (from CastSessionService)
    // ---------------------------------------------------------------------------------

    /** Play/pause toggle for the cast notification. */
    void togglePlayPauseFromNotification() {
        if (isPlayingOnTv()) {
            pause();
        } else {
            play();
        }
    }

    /** Start helper kept here so the service-start policy lives in one place. */
    static void startSessionService(Context context, Intent intent) {
        // Reached from the foreground (connect happens from the picker UI), so the FGS grant is
        // available; ContextCompat routes to startForegroundService on O+.
        ContextCompat.startForegroundService(context, intent);
    }
}
