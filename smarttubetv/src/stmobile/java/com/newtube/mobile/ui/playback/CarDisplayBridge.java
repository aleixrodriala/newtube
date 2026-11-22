package com.newtube.mobile.ui.playback;

import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.media3.exoplayer.ExoPlayer;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.lang.ref.WeakReference;

/**
 * Process-wide access to the live playback session for the Android Auto screen.
 *
 * <p>The car app runs in the same process as {@link MobilePlaybackActivity}, which owns the only
 * {@link ExoPlayer}. The car screen therefore does not create a second player: it hands the
 * host-provided {@link Surface} to the live one and gets the phone's session surface back when
 * the car screen goes away. The activity is held weakly so a destroyed player cannot be revived
 * through this class.</p>
 *
 * <p>The host's surface outlives the activity on purpose: a video started on the phone after the
 * car screen is already open must land on the car display as soon as the activity registers
 * itself here (see {@link #attach(MobilePlaybackActivity)}).</p>
 */
public final class CarDisplayBridge {
    private static WeakReference<MobilePlaybackActivity> sActivity = new WeakReference<>(null);
    private static Surface sCarSurface;

    private CarDisplayBridge() {
    }

    static void attach(MobilePlaybackActivity activity) {
        sActivity = new WeakReference<>(activity);
        if (sCarSurface != null && sCarSurface.isValid()) {
            activity.attachCarSurface(sCarSurface);
        }
    }

    static void detach(MobilePlaybackActivity activity) {
        if (sActivity.get() == activity) {
            sActivity = new WeakReference<>(null);
        }
    }

    public static void attachCarSurface(@Nullable Surface surface) {
        if (surface == null || !surface.isValid()) {
            return;
        }
        sCarSurface = surface;
        MobilePlaybackActivity activity = sActivity.get();
        if (activity != null) {
            activity.attachCarSurface(surface);
        }
    }

    public static void detachCarSurface(@Nullable Surface surface) {
        if (surface == null || sCarSurface == null || surface != sCarSurface) {
            return;
        }
        sCarSurface = null;
        MobilePlaybackActivity activity = sActivity.get();
        if (activity != null) {
            activity.detachCarSurface();
        }
    }

    @Nullable
    public static ExoPlayer getPlayer() {
        MobilePlaybackActivity activity = sActivity.get();
        return activity != null ? activity.getSharedPlayer() : null;
    }

    @Nullable
    public static Video getVideo() {
        MobilePlaybackActivity activity = sActivity.get();
        return activity != null ? activity.getVideo() : null;
    }

    public static void togglePlayPause() {
        MobilePlaybackActivity activity = sActivity.get();
        if (activity != null) {
            activity.togglePlayPauseFromCar();
        }
    }

    public static void seekBy(long deltaMs) {
        MobilePlaybackActivity activity = sActivity.get();
        if (activity != null) {
            activity.seekByFromCar(deltaMs);
        }
    }

    public static boolean playNext() {
        MobilePlaybackActivity activity = sActivity.get();
        return activity != null && activity.playNextFromCar();
    }

    public static boolean playPrevious() {
        MobilePlaybackActivity activity = sActivity.get();
        return activity != null && activity.playPreviousFromCar();
    }
}
