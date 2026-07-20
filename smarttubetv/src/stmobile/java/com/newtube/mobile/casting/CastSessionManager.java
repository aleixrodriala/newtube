package com.newtube.mobile.casting;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.liskovsoft.mediaserviceinterfaces.CastSenderService;
import com.liskovsoft.mediaserviceinterfaces.RemoteControlService;
import com.liskovsoft.mediaserviceinterfaces.data.CastEvent;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * App-side owner of the one active cast session (CASTING.md "Shared architecture").
 *
 * <p>Owns the {@link CastSenderService#connectObserve} subscription (io thread; events observed on
 * main), tracks session state (target, videoId, position/duration/play-state), fans it out to
 * {@link Listener}s and routes transport commands back to the sender. Also starts/stops
 * {@link CastSessionService}, the foreground service that keeps the session alive (wifi lock +
 * notification) while the app is backgrounded.</p>
 *
 * <p>The sender implementation lives in the MediaServiceCore fork and may not have landed yet:
 * {@code getCastSenderService()} defaults to null. Everything here null-checks it and degrades
 * gracefully ({@link #isSenderAvailable()} gates the UI affordances).</p>
 */
public class CastSessionManager {

    public interface Listener {
        /** A session was established (main thread). */
        void onCastSessionStarted(CastTarget target);

        /** Playback state on the TV changed (main thread). Values are normalized to ms. */
        void onCastSessionState(@Nullable String videoId, long positionMs, long durationMs, boolean playing);

        /** The session ended - user disconnect, TV-side stop or error (main thread). */
        void onCastSessionEnded(@Nullable String reason);
    }

    private static final String TAG = CastSessionManager.class.getSimpleName();

    @SuppressWarnings("StaticFieldLeak") // holds the application context only
    private static CastSessionManager sInstance;

    private final Context mContext;
    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();

    private Disposable mConnectAction;
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
    // Sender availability
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
     * Open a session on the target. Any previous session is torn down first.
     *
     * @return false when the sender isn't available or the target can't be connected yet
     */
    public boolean connect(CastTarget target) {
        CastSenderService sender = getSender();
        if (sender == null || target == null || !target.isConnectable()) {
            return false;
        }

        teardown(); // one session at a time (immediate - the async disconnect() would race the new session)

        mTarget = target;
        resetPlaybackState();

        // Long-lived stream; disposing it tears the Lounge session down (interface contract).
        mConnectAction = sender.connectObserve(target.getScreen())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleEvent,
                        error -> {
                            Log.e(TAG, "Cast session error: " + error);
                            endSession(error.getMessage());
                        },
                        () -> endSession(null));
        return true;
    }

    /**
     * User-initiated disconnect (overlay button / notification action). Stops playback on the TV
     * FIRST, then tears the session down: the phone resumes local playback on session end, so
     * without the remote stop both screens play at once. Ordering matters - disposing the session
     * sets the sender's stopped flag, after which it refuses commands, so the teardown waits for
     * the stop POST (bounded by a timeout in case the TV is gone).
     *
     * <p>The Route B "phone can leave" property is untouched: it covers walking away or killing
     * the app (no disconnect runs, the TV keeps playing), not an explicit Disconnect tap.</p>
     */
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
                        .doFinally(this::teardown),
                error -> Log.e(TAG, "Stop-on-disconnect failed (tearing down anyway): " + error));
    }

    /** Immediate session teardown - no remote stop. Also called defensively before a new connect. */
    private void teardown() {
        if (mConnectAction != null && !mConnectAction.isDisposed()) {
            mConnectAction.dispose(); // tears the session down sender-side
        }
        mConnectAction = null;
        if (mConnected || mTarget != null) {
            endSession(null);
        }
    }

    private void handleEvent(CastEvent event) {
        switch (event.getType()) {
            case CastEvent.TYPE_CONNECTED:
                mConnected = true;
                CastSessionService.start(mContext);
                CastTarget target = mTarget;
                for (Listener listener : mListeners) {
                    listener.onCastSessionStarted(target);
                }
                break;
            case CastEvent.TYPE_NOW_PLAYING:
                if (!TextUtils.isEmpty(event.getVideoId())) {
                    mVideoId = event.getVideoId();
                }
                applyTiming(event);
                notifyState();
                break;
            case CastEvent.TYPE_STATE_CHANGE:
                applyTiming(event);
                notifyState();
                break;
            case CastEvent.TYPE_VOLUME_CHANGE:
                // Volume routing is not surfaced in the scaffolding UI yet.
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

    private void endSession(@Nullable String reason) {
        boolean wasActive = mConnected || mTarget != null;
        if (mConnectAction != null && !mConnectAction.isDisposed()) {
            mConnectAction.dispose();
        }
        mConnectAction = null;
        mConnected = false;
        mTarget = null;
        CastSessionService.stop(mContext);
        if (wasActive) {
            for (Listener listener : mListeners) {
                listener.onCastSessionEnded(reason);
            }
        }
        resetPlaybackState();
    }

    private void resetPlaybackState() {
        mConnected = false;
        mVideoId = null;
        mPositionMs = -1;
        mDurationMs = -1;
        mState = RemoteControlService.STATE_IDLE;
        mPositionTimestamp = 0;
    }

    private void notifyState() {
        for (Listener listener : mListeners) {
            listener.onCastSessionState(mVideoId, getPositionMs(), mDurationMs, isPlayingOnTv());
        }
    }

    // ---------------------------------------------------------------------------------
    // State accessors
    // ---------------------------------------------------------------------------------

    public boolean isConnected() {
        return mConnected;
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

    // ---------------------------------------------------------------------------------
    // Transport commands (valid only while connected; all fire-and-forget on io)
    // ---------------------------------------------------------------------------------

    public void loadVideo(String videoId, long positionMs) {
        if (TextUtils.isEmpty(videoId)) {
            return;
        }
        CastSenderService sender = getSender();
        if (sender == null || !mConnected) {
            return;
        }
        // Optimistic: remember what the TV is (about to be) playing so a locally initiated load
        // isn't re-routed again when setVideo() sees it (MobilePlaybackActivity hook).
        mVideoId = videoId;
        mPositionMs = Math.max(positionMs, 0);
        mPositionTimestamp = SystemClock.elapsedRealtime();
        runCommand(sender.loadVideoObserve(videoId, positionMs), "loadVideo");
    }

    public void play() {
        CastSenderService sender = getSender();
        if (sender != null && mConnected) {
            mState = RemoteControlService.STATE_PLAYING;
            runCommand(sender.playObserve(), "play");
            notifyState();
        }
    }

    public void pause() {
        CastSenderService sender = getSender();
        if (sender != null && mConnected) {
            // Fold the interpolated head start into the stored position before freezing it.
            mPositionMs = getPositionMs();
            mPositionTimestamp = SystemClock.elapsedRealtime();
            mState = RemoteControlService.STATE_PAUSED;
            runCommand(sender.pauseObserve(), "pause");
            notifyState();
        }
    }

    public void seekTo(long positionMs) {
        CastSenderService sender = getSender();
        if (sender != null && mConnected) {
            mPositionMs = Math.max(positionMs, 0);
            mPositionTimestamp = SystemClock.elapsedRealtime();
            runCommand(sender.seekToObserve(positionMs), "seekTo");
            notifyState();
        }
    }

    public void stopVideo() {
        CastSenderService sender = getSender();
        if (sender != null && mConnected) {
            runCommand(sender.stopVideoObserve(), "stopVideo");
        }
    }

    private void runCommand(Observable<Void> command, String name) {
        // Fire-and-forget off the main thread; RxHelper supplies the error-swallowing subscriber.
        RxHelper.execute(
                command.subscribeOn(Schedulers.io()),
                error -> Log.e(TAG, "Cast command '" + name + "' failed: " + error));
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
