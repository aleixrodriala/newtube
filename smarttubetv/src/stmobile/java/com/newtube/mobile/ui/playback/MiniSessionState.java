package com.newtube.mobile.ui.playback;

import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * NEWTUBE(mini-park): lifecycle of the in-app mini-player session, free of Android types so the
 * rules can be unit-tested (see {@link MiniPlayerBridge} for the Activity plumbing around it).
 *
 * <pre>
 *   NONE --dock()--&gt; DOCKED --park()--&gt; PARKED --resume()--&gt; DOCKED
 *     ^                  |                  |
 *     +----clear()-------+-----clear()------+   (expand, new video opened from a screen,
 *                                                 notification dismissed, task removed,
 *                                                 park timeout, player destroyed)
 * </pre>
 *
 * <ul>
 *   <li>DOCKED: the floating card shows the live player (the playback Activity sits behind the
 *       host screens and still owns the ExoPlayer).</li>
 *   <li>PARKED: the user closed the card. Playback is paused and the card is gone, but the player,
 *       its media session and the (paused, dismissible) media notification all stay, so play on the
 *       notification / system media controls / lock screen / headset resumes the same video at the
 *       same position. Nothing is in the foreground-service state while parked.</li>
 * </ul>
 */
final class MiniSessionState {
    // Every nowMs below is SystemClock.elapsedRealtime(): it keeps counting through deep sleep.
    // uptimeMillis (and Handler delays) stop while the CPU sleeps, so a video parked before the
    // phone went to sleep for the night used to stay parked until 10 minutes of AWAKE time.

    /**
     * How long a parked session waits to be resumed before it ends for real.
     *
     * <p>Matches SystemUI's paused-media timeout (MediaTimeoutListener.PAUSED_MEDIA_TIMEOUT, 10 min):
     * from Android 11 the only resume affordance - the media player in the quick-settings carousel
     * and on the lock screen - is hidden by the system after that long paused, and a user swipe on
     * that player hides it the same way WITHOUT telling the app (observed on API 36: SystemUI logs
     * "Media carousel swiped away" and marks the entry timed out, the notification is never
     * cancelled). Past this point nothing can resume the session, so keeping the paused player,
     * its buffers and the hidden watch page alive would only cost memory. Media3's own
     * MediaSessionService uses the same 10 minutes to drop a paused session out of the foreground.</p>
     */
    static final long PARK_TIMEOUT_MS = 10 * 60 * 1000L;

    enum Phase {
        NONE,
        DOCKED,
        PARKED
    }

    private Phase mPhase = Phase.NONE;
    private long mParkedAtMs;
    @Nullable
    private String mParkedVideoId;

    Phase getPhase() {
        return mPhase;
    }

    /** Minimize (or channel navigation) put the live player into a host's card. */
    void dock() {
        mPhase = Phase.DOCKED;
        mParkedAtMs = 0;
        mParkedVideoId = null;
    }

    /**
     * The card's X. Only a docked session with something to resume can park; anything else (ended,
     * failed, casting, never docked) is a real close.
     *
     * @return true when the session parked; false means the caller must close it for real.
     */
    boolean park(boolean resumable, long nowMs, @Nullable String videoId) {
        if (mPhase != Phase.DOCKED || !resumable) {
            return false;
        }
        mPhase = Phase.PARKED;
        mParkedAtMs = nowMs;
        mParkedVideoId = videoId;
        return true;
    }

    /**
     * A different video was loaded into the parked player - Next/Previous on the notification or
     * a headset. That is the user interacting, so its 10 minutes start now; the new video usually
     * starts playing right away, which leaves PARKED through {@link #resume()} anyway.
     *
     * @return true when the clock restarted.
     */
    boolean onVideoChanged(@Nullable String videoId, long nowMs) {
        if (mPhase != Phase.PARKED || Objects.equals(videoId, mParkedVideoId)) {
            return false;
        }
        mParkedVideoId = videoId;
        mParkedAtMs = nowMs;
        return true;
    }

    /**
     * Playback started again while parked - from ANY source: play on the notification / system
     * media controls / lock screen / headset, Next/Previous starting the following video, a cast
     * session handing playback back - or a cast session took over the paused player. The session
     * is a normal mini session again (the card comes back as soon as a host screen can show it).
     *
     * @return true when this call un-parked the session.
     */
    boolean resume() {
        if (mPhase != Phase.PARKED) {
            return false;
        }
        mPhase = Phase.DOCKED;
        mParkedAtMs = 0;
        mParkedVideoId = null;
        return true;
    }

    /** The paused notification was swiped away. Only a parked session ends on that. */
    boolean endsOnNotificationDismiss() {
        return mPhase == Phase.PARKED;
    }

    /** True once a parked session has waited {@link #PARK_TIMEOUT_MS} without being resumed. */
    boolean isParkExpired(long nowMs) {
        return mPhase == Phase.PARKED && nowMs - mParkedAtMs >= PARK_TIMEOUT_MS;
    }

    /**
     * The teardown decision. Never ends a player that is playing (or about to): whatever started
     * playback should have resumed the session, and killing audio the user is listening to would
     * be far worse than a parked session living a little longer.
     */
    boolean shouldEnd(long nowMs, boolean playWhenReady) {
        return !playWhenReady && isParkExpired(nowMs);
    }

    /** Time left before {@link #PARK_TIMEOUT_MS}, or -1 when not parked. */
    long remainingMs(long nowMs) {
        if (mPhase != Phase.PARKED) {
            return -1;
        }
        return Math.max(0, PARK_TIMEOUT_MS - (nowMs - mParkedAtMs));
    }

    /** Hosts draw the card only for a docked session; a parked one lives on in the notification. */
    boolean isCardVisible() {
        return mPhase == Phase.DOCKED;
    }

    /** The hidden playback Activity is still in the task under the host screens. */
    boolean isBehindHosts() {
        return mPhase != Phase.NONE;
    }

    boolean isParked() {
        return mPhase == Phase.PARKED;
    }

    void clear() {
        mPhase = Phase.NONE;
        mParkedAtMs = 0;
        mParkedVideoId = null;
    }

    /**
     * Whether closing the card should park instead of ending playback. Resuming needs a loaded
     * stream: READY or BUFFERING, not ended. An IDLE player (fatal error, nothing loaded) has
     * nothing to resume, and while casting - connected OR still connecting - the TV owns (or is
     * about to own) playback: a second, paused local session would only duplicate the cast
     * notification.
     */
    static boolean canPark(boolean hasPlayer, boolean readyOrBuffering, boolean ended,
            boolean castConnectedOrConnecting) {
        return hasPlayer && readyOrBuffering && !ended && !castConnectedOrConnecting;
    }
}
