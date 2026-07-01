package com.newtube.mobile.ui.playback;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.exoplayer2.ControlDispatcher;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector;
import com.google.android.exoplayer2.ui.PlayerNotificationManager;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.other.BackboneQueueNavigator;

/**
 * Lightweight foreground service that keeps audio playing when {@link MobilePlaybackActivity} is
 * backgrounded or the screen turns off, and drives the lock-screen / media notification.
 *
 * <p>It does NOT own a player - it reuses the single {@link SimpleExoPlayer} instance created by
 * {@link MobilePlaybackActivity} (handed over through {@link #attachPlayer}). It wires that live
 * player to:
 * <ul>
 *   <li>a {@link MediaSessionCompat} + {@link MediaSessionConnector} (lock-screen transport,
 *       metadata, prev/next routed to the reused {@link PlaybackPresenter}); mirrors the TV
 *       {@code PlaybackFragment.createMediaSession()} concept (ARCHITECTURE.md section 6), and</li>
 *   <li>a {@link PlayerNotificationManager} whose {@link PlayerNotificationManager.NotificationListener}
 *       promotes/demotes this service to/from the foreground so audio survives on API 34+.</li>
 * </ul>
 *
 * <p>The service is bound + started by the Activity while it is in the foreground (the player is
 * created on a visible screen), so {@code startForeground} is always reached from the foreground -
 * no {@code ForegroundServiceStartNotAllowedException}. On real finish the Activity calls
 * {@link #detachPlayer()} which clears the player (cancelling the notification -> stopForeground)
 * before the player itself is released.
 */
public class MobilePlaybackService extends Service {

    public static final String CHANNEL_ID = "newtube_playback_channel";
    private static final int NOTIFICATION_ID = 41337;

    private final IBinder mBinder = new LocalBinder();

    private PlayerNotificationManager mNotificationManager;
    private MediaSessionCompat mMediaSession;
    private MediaSessionConnector mMediaSessionConnector;
    private PlaybackPresenter mPresenter;
    private boolean mIsForeground;

    // Simple large-icon (album art) cache so the adapter can answer synchronously on repeat calls.
    private String mArtUrl;
    private Bitmap mArtBitmap;

    public class LocalBinder extends Binder {
        public MobilePlaybackService getService() {
            return MobilePlaybackService.this;
        }
    }

    /**
     * Context that forces {@link Context#RECEIVER_NOT_EXPORTED} on the 2-arg
     * {@code registerReceiver} the vendored {@link PlayerNotificationManager} uses internally, so it
     * doesn't crash on API 34+ (targetSdk 34). All other calls delegate to the base Service context.
     *
     * <p>Crucially, {@link PlayerNotificationManager}'s constructor immediately does
     * {@code context = context.getApplicationContext()} (exoplayer-ui PlayerNotificationManager.java),
     * then later calls {@code registerReceiver} on THAT stored context. A plain wrapper is therefore
     * discarded before the receiver is ever registered - which is why the flag was being lost and the
     * spurious {@code SecurityException: RECEIVER_EXPORTED...} surfaced on every video start (caught by
     * ErrorFixerController and shown as a false "no internet" toast). So {@link #getApplicationContext()}
     * is overridden to keep returning a flag-forcing wrapper, ensuring the override survives the unwrap.
     */
    private static class NotificationContextWrapper extends ContextWrapper {
        NotificationContextWrapper(Context base) {
            super(base);
        }

        @Override
        public Context getApplicationContext() {
            Context appContext = super.getApplicationContext();
            // Keep the registerReceiver override alive even after PlayerNotificationManager unwraps
            // us via getApplicationContext(). If the base already IS the app context, reuse this.
            return appContext == getBaseContext() ? this : new NotificationContextWrapper(appContext);
        }

        @Override
        public Intent registerReceiver(@Nullable BroadcastReceiver receiver, IntentFilter filter) {
            // Register against the base context (not this wrapper) to avoid recursion. ContextCompat
            // uses the flag-aware 4-arg registerReceiver on API 33+, which bypasses this override.
            return ContextCompat.registerReceiver(
                    getBaseContext(), receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The Activity keeps the service bound; nothing to do on plain start.
        return START_NOT_STICKY;
    }

    /**
     * Attach the reused player. Sets up the media session + notification. Safe to call once per
     * player instance; a second call re-attaches (used when the engine is restarted).
     */
    public void attachPlayer(SimpleExoPlayer player, PlaybackPresenter presenter, PendingIntent contentIntent) {
        if (player == null) {
            return;
        }

        // Re-attach cleanly if something was already wired.
        releaseInternal(false);

        mPresenter = presenter;

        mMediaSession = new MediaSessionCompat(getApplicationContext(), getPackageName());
        mMediaSession.setActive(true);

        mMediaSessionConnector = new MediaSessionConnector(mMediaSession);
        try {
            mMediaSessionConnector.setPlayer(player);
        } catch (NoSuchMethodError e) {
            // Some OEM ROMs (e.g. Android 9, Sony) lack a PlaybackState.Builder method used here.
            mMediaSessionConnector = null;
        }

        if (mMediaSessionConnector != null) {
            mMediaSessionConnector.setMediaMetadataProvider(p -> buildMetadata());
            mMediaSessionConnector.setQueueNavigator(new BackboneQueueNavigator() {
                @Override
                public long getSupportedQueueNavigatorActions(Player p) {
                    return PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
                }

                @Override
                public void onSkipToPrevious(Player p, ControlDispatcher controlDispatcher) {
                    if (mPresenter != null) {
                        Utils.post(() -> mPresenter.onPreviousClicked());
                    }
                }

                @Override
                public void onSkipToNext(Player p, ControlDispatcher controlDispatcher) {
                    if (mPresenter != null) {
                        Utils.post(() -> mPresenter.onNextClicked());
                    }
                }
            });
        }

        mNotificationManager = PlayerNotificationManager.createWithNotificationChannel(
                // Wrap the Service context so the manager's internal registerReceiver() call gets the
                // RECEIVER_NOT_EXPORTED flag mandated for apps targeting API 34+ (the vendored
                // exoplayer-ui PlayerNotificationManager still uses the 2-arg registerReceiver, which
                // otherwise throws SecurityException on Android 14). The action broadcasts are already
                // package-scoped (internal), so NOT_EXPORTED is correct.
                new NotificationContextWrapper(this),
                CHANNEL_ID,
                R.string.mobile_playback_channel_name,
                R.string.mobile_playback_channel_desc,
                NOTIFICATION_ID,
                new PlayerNotificationManager.MediaDescriptionAdapter() {
                    @Override
                    public String getCurrentContentTitle(Player player) {
                        Video video = mPresenter != null ? mPresenter.getVideo() : null;
                        return video != null ? Helpers.toString(video.getTitleFull()) : "";
                    }

                    @Nullable
                    @Override
                    public PendingIntent createCurrentContentIntent(Player player) {
                        return contentIntent;
                    }

                    @Nullable
                    @Override
                    public String getCurrentContentText(Player player) {
                        Video video = mPresenter != null ? mPresenter.getVideo() : null;
                        return video != null ? video.getAuthor() : null;
                    }

                    @Nullable
                    @Override
                    public Bitmap getCurrentLargeIcon(Player player, PlayerNotificationManager.BitmapCallback callback) {
                        return loadArt(callback);
                    }
                },
                new PlayerNotificationManager.NotificationListener() {
                    @Override
                    public void onNotificationPosted(int notificationId, Notification notification, boolean ongoing) {
                        if (ongoing) {
                            // Promote to foreground so audio keeps playing when backgrounded / screen off.
                            startForeground(notificationId, notification);
                            mIsForeground = true;
                        } else {
                            // Paused: keep the notification but drop foreground state (dismissible).
                            ServiceCompat.stopForeground(MobilePlaybackService.this, ServiceCompat.STOP_FOREGROUND_DETACH);
                            mIsForeground = false;
                        }
                    }

                    @Override
                    public void onNotificationCancelled(int notificationId, boolean dismissedByUser) {
                        ServiceCompat.stopForeground(MobilePlaybackService.this, ServiceCompat.STOP_FOREGROUND_REMOVE);
                        mIsForeground = false;
                        stopSelf();
                    }
                });

        mNotificationManager.setUseNavigationActions(true); // prev / next
        mNotificationManager.setUsePlayPauseActions(true);
        mNotificationManager.setUseStopAction(false);
        mNotificationManager.setSmallIcon(R.drawable.ic_notification_play);
        if (mMediaSession != null) {
            mNotificationManager.setMediaSessionToken(mMediaSession.getSessionToken());
        }

        // Posts the notification now (if media already loaded) and on every subsequent player event.
        mNotificationManager.setPlayer(player);
    }

    /** Detach + tear down media session and notification. Called before the player is released. */
    public void detachPlayer() {
        releaseInternal(true);
    }

    private void releaseInternal(boolean removeNotification) {
        if (mNotificationManager != null) {
            mNotificationManager.setPlayer(null); // triggers onNotificationCancelled -> stopForeground
            mNotificationManager = null;
        }
        if (mMediaSessionConnector != null) {
            mMediaSessionConnector.setPlayer(null);
            mMediaSessionConnector.setMediaMetadataProvider(null);
            mMediaSessionConnector.setQueueNavigator(null);
            mMediaSessionConnector = null;
        }
        if (mMediaSession != null) {
            mMediaSession.setActive(false);
            mMediaSession.release();
            mMediaSession = null;
        }
        if (removeNotification && mIsForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            mIsForeground = false;
        }
        mPresenter = null;
        mArtUrl = null;
        mArtBitmap = null;
    }

    @Override
    public void onDestroy() {
        releaseInternal(true);
        super.onDestroy();
    }

    private MediaMetadataCompat buildMetadata() {
        Video video = mPresenter != null ? mPresenter.getVideo() : null;
        if (video == null) {
            return null;
        }

        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder();
        builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, video.getTitleFull());
        builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, video.getTitleFull());
        builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, video.getAuthor());
        builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                Helpers.toString(video.getSecondTitleFull()));
        builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, video.getCardImageUrl());
        if (mArtBitmap != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, mArtBitmap);
        }
        return builder.build();
    }

    /** Load the current video thumbnail as the notification/lock-screen large icon (async, cached). */
    @Nullable
    private Bitmap loadArt(PlayerNotificationManager.BitmapCallback callback) {
        Video video = mPresenter != null ? mPresenter.getVideo() : null;
        String url = video != null ? video.getCardImageUrl() : null;

        if (TextUtils.isEmpty(url)) {
            return null;
        }

        // Serve the cached bitmap synchronously for the same video.
        if (Helpers.equals(url, mArtUrl) && mArtBitmap != null) {
            return mArtBitmap;
        }

        final String requestedUrl = url;
        Glide.with(getApplicationContext())
                .asBitmap()
                .load(url)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                        mArtUrl = requestedUrl;
                        mArtBitmap = resource;
                        callback.onBitmap(resource);
                    }

                    @Override
                    public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                        // no-op
                    }
                });

        return null;
    }
}
