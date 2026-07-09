package com.newtube.mobile.ui.playback;

import android.content.Context;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.SimpleExoPlayer;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;

import java.lang.ref.WeakReference;

/**
 * Hand-off state for the YouTube-style in-app mini-player.
 *
 * <p>The player is a {@code singleInstance} Activity that OWNS the {@link SimpleExoPlayer}
 * (see {@link MobilePlaybackService} - the service only wires the media session/notification,
 * background audio already survives the Activity being covered). Minimizing therefore does NOT
 * move the player anywhere: {@link MobilePlaybackActivity} releases its video surface, stays
 * alive behind {@code MobileBrowseActivity}, and Browse renders the SAME live player into a
 * docked bar through its own small {@code PlayerView}. This class is only the bridge between
 * those two activities: "is a mini session active?" plus lazy access to the live player/video
 * (lazy so an engine restart inside the playback activity never leaves the bar holding a
 * released player).</p>
 *
 * <p>All access is main-thread (activity lifecycle callbacks + view clicks), so plain statics
 * are safe. The activity is held weakly: if the system destroys the backgrounded player
 * activity, {@link #isActive()} turns false on its own and the bar simply hides.</p>
 */
public final class MiniPlayerBridge {

    private static WeakReference<MobilePlaybackActivity> sActivity = new WeakReference<>(null);
    private static boolean sActive;

    private MiniPlayerBridge() {
    }

    /** Called by the playback activity right before it backgrounds itself into mini mode. */
    static void activate(MobilePlaybackActivity activity) {
        sActivity = new WeakReference<>(activity);
        sActive = true;
    }

    /** Called when the playback activity takes its surface back (expand / new video / destroy). */
    public static void deactivate() {
        sActive = false;
        sActivity = new WeakReference<>(null);
    }

    /** True while a live, still-alive player session is docked in the mini bar. */
    public static boolean isActive() {
        if (!sActive) {
            return false;
        }
        MobilePlaybackActivity activity = sActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || activity.getSharedPlayer() == null) {
            deactivate(); // the session died behind our back - self-heal
            return false;
        }
        return true;
    }

    /** The live player to render in the bar, or null when no mini session is active. */
    @Nullable
    public static SimpleExoPlayer getPlayer() {
        return isActive() ? sActivity.get().getSharedPlayer() : null;
    }

    /** Metadata of the playing video (title/author for the bar), or null. */
    @Nullable
    public static Video getVideo() {
        return isActive() ? sActivity.get().getVideo() : null;
    }

    /**
     * Expand: bring the (still alive) playback activity back to the front. Its onResume
     * re-claims the video surface and deactivates this bridge. The caller must detach the
     * mini bar's PlayerView first so the surface is free.
     */
    public static void expand(Context context) {
        ViewManager.instance(context).startView(PlaybackView.class);
    }

    /** Close from the bar's X: stop playback and finish the hidden playback activity. */
    public static void close() {
        MobilePlaybackActivity activity = sActivity.get();
        deactivate();
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            activity.closeFromMiniPlayer();
        }
    }
}
