package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import androidx.media3.exoplayer.ExoPlayer;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;

import java.lang.ref.WeakReference;

/**
 * Hand-off state for the YouTube-style in-app mini-player.
 *
 * <p>The playback Activity OWNS the {@link ExoPlayer} (see {@link MobilePlaybackService} -
 * the service only wires the media session/notification, background audio already survives the
 * Activity being covered). Minimizing therefore does NOT move the player anywhere:
 * {@link MobilePlaybackActivity} stays alive behind {@code MobileBrowseActivity}, and the card
 * simply re-parents the player's session-long {@link SurfaceTexture} into its own TextureView
 * (see {@link #getSessionTexture()}) - the codec never notices the hand-off. This class is only
 * the bridge between those two activities: "is a mini session active?" plus lazy access to the
 * live player/video/texture (lazy so an engine restart inside the playback activity never
 * leaves the card holding released objects).</p>
 *
 * <p>All access is main-thread (activity lifecycle callbacks + view clicks), so plain statics
 * are safe. The activity is held weakly: if the system destroys the backgrounded player
 * activity, {@link #isActive()} turns false on its own and the bar simply hides.</p>
 */
public final class MiniPlayerBridge {
    /**
     * A screen that can dock the live mini card: Home, Search, and Channel all qualify. The
     * player minimizes onto whichever host was RESUMED most recently - that is exactly the
     * screen sitting underneath the translucent playback window, so a video opened from Search
     * minimizes back onto the search results instead of yanking Home to the front.
     */
    public interface MiniHost {
        /** @return true when the mini card was made visible and scheduled for drawing. */
        boolean prepareMiniPlayerForHandoff(Runnable onDrawn);

        /** The ViewManager view class that reorders this host's Activity to the front. */
        Class<?> getMiniHostViewClass();

        /**
         * Distance in px between the host's card bottom edge and the content bottom (Home's
         * 56dp bottom-nav row; 0 for overlay hosts). Lets the minimize morph land exactly on
         * THIS host's card instead of always assuming Home's geometry.
         */
        int getMiniCardBottomOffsetPx();
    }

    private static final long NAVIGATION_PENDING_MS = 30_000;

    private static WeakReference<MobilePlaybackActivity> sActivity = new WeakReference<>(null);
    private static WeakReference<MobilePlaybackActivity> sPendingNavigation = new WeakReference<>(null);
    private static WeakReference<MiniHost> sMiniHost = new WeakReference<>(null);
    private static long sPendingNavigationAtMs;
    private static boolean sActive;
    private static Bitmap sHandoffStill;
    private static Bitmap sMiniEntryStill;
    private static Rect sMiniBounds;

    private MiniPlayerBridge() {
    }

    /**
     * Track the host screen currently underneath (or about to be underneath) the player. Called
     * from each host's onResume - the last resumed host is the minimize destination. Hosts stay
     * registered while merely paused (the player on top of them must still find them).
     */
    public static void registerMiniHost(MiniHost host) {
        sMiniHost = new WeakReference<>(host);
    }

    public static void unregisterMiniHost(MiniHost host) {
        if (sMiniHost.get() == host) {
            sMiniHost = new WeakReference<>(null);
        }
    }

    /** The registered destination host, or null (deep-linked player with no screen beneath). */
    @Nullable
    static MiniHost getMiniHost() {
        return sMiniHost.get();
    }

    /**
     * Pre-render the destination card while the playback window still covers it. This closes
     * the one-frame gap between hiding the morphed playback Activity and the host's onResume
     * callback. Kept separate from {@link #activate(MobilePlaybackActivity)} because channel
     * navigation has a different mini-player host and must not let this host claim the shared
     * SurfaceTexture.
     */
    static boolean prepareMiniHostForHandoff(Runnable onDrawn) {
        MiniHost host = sMiniHost.get();
        return host != null && host.prepareMiniPlayerForHandoff(onDrawn);
    }

    /** Called by the playback activity right before it backgrounds itself into mini mode. */
    static void activate(MobilePlaybackActivity activity) {
        sActivity = new WeakReference<>(activity);
        sActive = true;
    }

    /**
     * Mark a channel route that should become an in-app mini session only if its destination
     * Activity is actually created. Channel-id resolution can be asynchronous, so activating here
     * would strand a detached/shrunk player when lookup fails.
     */
    static void prepareNavigation(MobilePlaybackActivity activity) {
        sPendingNavigation = new WeakReference<>(activity);
        sPendingNavigationAtMs = SystemClock.uptimeMillis();
    }

    /** Called by the channel Activity once the pending route has succeeded. */
    public static boolean completePendingNavigation() {
        MobilePlaybackActivity activity = sPendingNavigation.get();
        boolean fresh = activity != null
                && SystemClock.uptimeMillis() - sPendingNavigationAtMs <= NAVIGATION_PENDING_MS;
        sPendingNavigation = new WeakReference<>(null);
        sPendingNavigationAtMs = 0;

        return fresh && !activity.isFinishing() && !activity.isDestroyed()
                && activity.minimizeForNavigation();
    }

    /** Called when the playback activity takes its surface back (expand / new video / destroy). */
    public static void deactivate() {
        sActive = false;
        sActivity = new WeakReference<>(null);
        sPendingNavigation = new WeakReference<>(null);
        sPendingNavigationAtMs = 0;
        sHandoffStill = null;
        sMiniEntryStill = null;
        sMiniBounds = null;
    }

    /**
     * The session-long video {@link SurfaceTexture} (see MobilePlaybackActivity's persistent
     * surface docs). The Browse card re-parents this into its own TextureView while the mini
     * session is active - the codec's output surface never changes, so playback never stalls.
     */
    @Nullable
    public static SurfaceTexture getSessionTexture() {
        return isActive() ? sActivity.get().getSessionTexture() : null;
    }

    /** Last card frame, captured by Browse when it detaches; shown by the expanding player. */
    public static void setHandoffStill(@Nullable Bitmap frame) {
        sHandoffStill = frame;
    }

    /** Initial full-size frame shown while a newly-created mini TextureView adopts the session. */
    static void setMiniEntryStill(@Nullable Bitmap frame) {
        sMiniEntryStill = frame;
    }

    /** Consume the initial mini frame (single use by the destination screen). */
    @Nullable
    public static Bitmap takeMiniEntryStill() {
        Bitmap frame = sMiniEntryStill;
        sMiniEntryStill = null;
        return frame;
    }

    /** Exact on-screen mini rectangle, used to reverse the morph without a geometry jump. */
    public static void setMiniBounds(@Nullable Rect bounds) {
        sMiniBounds = bounds != null ? new Rect(bounds) : null;
    }

    @Nullable
    static Rect takeMiniBounds() {
        Rect bounds = sMiniBounds;
        sMiniBounds = null;
        return bounds;
    }

    /** Consume the captured card frame (single use). */
    @Nullable
    static Bitmap takeHandoffStill() {
        Bitmap frame = sHandoffStill;
        sHandoffStill = null;
        return frame;
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
    public static ExoPlayer getPlayer() {
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
