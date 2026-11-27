package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.Nullable;

import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
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

    /**
     * NEWTUBE(motion): a host card left on screen, frozen on its last frame, while the player comes
     * back over it (see {@link #setPendingCardFold}).
     */
    public interface CardFold {
        /** Hide the card now; run {@code onFolded} once a frame without it is on screen. */
        void fold(@Nullable Runnable onFolded);
    }

    private static final long NAVIGATION_PENDING_MS = 30_000;
    @Nullable
    private static CardFold sPendingFold;

    private static WeakReference<MobilePlaybackActivity> sActivity = new WeakReference<>(null);
    private static WeakReference<MobilePlaybackActivity> sPendingNavigation = new WeakReference<>(null);
    private static WeakReference<MiniHost> sMiniHost = new WeakReference<>(null);
    @Nullable
    private static Class<?> sLastHostViewClass;
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
        if (host != null) {
            sLastHostViewClass = host.getMiniHostViewClass();
        }
    }

    public static void unregisterMiniHost(MiniHost host) {
        unregisterMiniHost(host, false);
    }

    /**
     * Detach a host when destroyed. Non-finishing teardown (background kill / recreation while
     * the player sits on top) keeps the last host view class so minimizing/exiting playback still
     * returns there instead of jumping back to Home.
     */
    public static void unregisterMiniHost(MiniHost host, boolean finishing) {
        if (sMiniHost.get() == host) {
            sMiniHost = new WeakReference<>(null);
            if (finishing && (host == null || sLastHostViewClass == host.getMiniHostViewClass())) {
                sLastHostViewClass = null;
            }
        }
    }

    /** The registered destination host, or null (deep-linked player or killed host). */
    @Nullable
    static MiniHost getMiniHost() {
        return sMiniHost.get();
    }

    /** View class to return to when exiting/minimizing the player, surviving non-finishing host kills. */
    public static Class<?> getMinimizeDestinationViewClass() {
        MiniHost host = sMiniHost.get();
        if (host != null) {
            return host.getMiniHostViewClass();
        }
        return sLastHostViewClass != null ? sLastHostViewClass : BrowseView.class;
    }

    public static void resetForTest() {
        sMiniHost = new WeakReference<>(null);
        sLastHostViewClass = null;
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

    /**
     * NEWTUBE(motion): a host pausing with a mini session docked detaches its texture (the player
     * may be about to take it back) but keeps the card showing that last frame instead of hiding
     * it. The expanding player's window draws ~50 ms after the host pauses (Pixel 9), and a card
     * hidden at pause read as a blink: 4 frames of no video before the morph. The player folds the
     * card once its own frame over the card is on screen ({@link #foldHostCard}); a host that
     * resumes or stops first clears or folds it itself. Deliberately not part of the session state
     * that {@link #deactivate()} clears: the player deactivates before it folds.
     */
    public static void setPendingCardFold(@Nullable CardFold fold) {
        sPendingFold = fold;
    }

    public static void clearPendingCardFold(CardFold fold) {
        if (sPendingFold == fold) {
            sPendingFold = null;
        }
    }

    /**
     * NEWTUBE(motion): a host's X is closing the session once its card has animated away; the
     * runnable aborts that (and puts the sound back). A new video delivered to this same live player
     * meanwhile (PlaybackPresenter re-uses it) must not be closed with it: the player calls
     * {@link #cancelClosing()} when it is handed a different video.
     */
    @Nullable
    private static Runnable sClosingAbort;

    public static void setClosing(@Nullable Runnable abort) {
        sClosingAbort = abort;
    }

    static void cancelClosing() {
        Runnable abort = sClosingAbort;
        sClosingAbort = null;
        if (abort != null) {
            abort.run();
        }
    }

    /** The player now covers the card: fold it, then run {@code onFolded} (at once if none waits). */
    static void foldHostCard(@Nullable Runnable onFolded) {
        CardFold fold = sPendingFold;
        sPendingFold = null;
        if (fold != null) {
            fold.fold(onFolded);
        } else if (onFolded != null) {
            onFolded.run();
        }
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

    /**
     * NEWTUBE(issue #9): give a host card's video frame (an {@link AspectRatioFrameLayout}, see
     * {@code mobile_mini_player_frame}) the playing video's shape. The card is a fixed 16:9 box and
     * a TextureView scales its buffer to its own bounds, so a 2.35:1 film, a 4:3 clip or a vertical
     * video filled the card stretched. Now it is letterboxed on the card's black background, like
     * the watch page's video box the minimize morph shrinks into it. Unknown size: unchanged.
     * Cheap and idempotent (setAspectRatio only relayouts on a change), so hosts call it from their
     * sync and their progress tick - the tick picks up an autoplayed video of another shape.
     */
    public static void fitToVideo(@Nullable View frame) {
        ExoPlayer player = getPlayer();
        if (!(frame instanceof AspectRatioFrameLayout) || player == null) {
            return;
        }
        VideoSize size = player.getVideoSize();
        if (size.width > 0 && size.height > 0) {
            ((AspectRatioFrameLayout) frame).setAspectRatio(
                    size.width * size.pixelWidthHeightRatio / size.height);
        }
    }

    /**
     * NEWTUBE(loudness): the session's volume as its player controller keeps it, apart from loudness
     * normalization (a direct ExoPlayer write would be undone by the next normalization update);
     * -1 when no mini session is active.
     */
    public static float getVolume() {
        return isActive() ? sActivity.get().getVolume() : -1;
    }

    /** NEWTUBE(loudness): see {@link #getVolume()}. No-op when no mini session is active. */
    public static void setVolume(float volume) {
        if (isActive()) {
            sActivity.get().setVolume(volume);
        }
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
