package com.newtube.mobile.ui.playback;

/**
 * NEWTUBE(mini-park): the foreground hold {@link MobilePlaybackService} keeps for a parked mini
 * session (see MobilePlaybackService#setKeepForegroundWhilePaused for why a parked session needs
 * it), free of Android types so its rules are unit-testable. Main-thread only, like the service.
 *
 * <p>The hold has to survive the two moments the service sees its notification go away without
 * the session ending:</p>
 * <ul>
 *   <li>a video switch while parked (Next/Previous on the notification or lock screen): the media
 *       reset empties the player's timeline, PlayerNotificationManager cancels its notification,
 *       and the old cancel handler dropped the foreground and stopped the service - leaving the
 *       parked session unheld while the next video resolved (or failed), exposed to the freezer
 *       again;</li>
 *   <li>an engine restart (error recovery): the player is detached and re-attached, and the old
 *       detach was the real-teardown path, which also cleared the hold.</li>
 * </ul>
 */
final class ParkedForegroundHold {
    private boolean mHeld;

    boolean isHeld() {
        return mHeld;
    }

    /** @return true when the hold changed (the caller then re-posts the notification). */
    boolean set(boolean held) {
        if (mHeld == held) {
            return false;
        }
        mHeld = held;
        return true;
    }

    /** A posted notification promotes the service when the player is ongoing OR the hold is on. */
    boolean promotesOnPost(boolean ongoing) {
        return ongoing || mHeld;
    }

    /**
     * The notification went away. Held and not the user's own dismissal means a parked video
     * switch: keep the foreground (the platform ignores an app's cancel of its foreground
     * notification, so the parked notification stays until the next item re-posts it) and keep
     * the service running. A user dismissal always takes the normal path, so it can end the park.
     */
    boolean keepsServiceOnCancel(boolean dismissedByUser) {
        return mHeld && !dismissedByUser;
    }

    /**
     * The player is being detached. Only the real teardown (the playback Activity finishing) ends
     * the hold, and it must end BEFORE the notification is cancelled so that cancel takes the normal
     * stop-foreground path. An engine restart re-attaches the same parked session and keeps it.
     */
    void onDetach(boolean realTeardown) {
        if (realTeardown) {
            mHeld = false;
        }
    }
}
