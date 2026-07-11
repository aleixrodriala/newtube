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
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerNotificationManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Lightweight foreground service that keeps audio playing when {@link MobilePlaybackActivity} is
 * backgrounded or the screen turns off, and drives the lock-screen / media notification.
 *
 * <p>It does NOT own a player - it reuses the single media3 {@link ExoPlayer} instance created by
 * {@link MobilePlaybackActivity} (handed over through {@link #attachPlayer}). Media3 has no
 * {@code MediaSessionConnector}, so this wires the live player to:
 * <ul>
 *   <li>a {@link MediaSessionCompat} synced by a small inline connector (transport callbacks
 *       routed to the player, prev/next to the reused {@link PlaybackPresenter} queue, playback
 *       state pushed on every player event), and</li>
 *   <li>a media3 {@link PlayerNotificationManager} whose notification listener promotes/demotes
 *       this service to/from the foreground so audio survives on API 34+. The player handed to it
 *       is wrapped in a {@link ForwardingPlayer} that always advertises prev/next and forwards
 *       them to the presenter's queue (our player only ever holds one MediaItem).</li>
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
    /** Cap the notification/lock-screen art at a sane size instead of decoding the full-res image. */
    private static final int ART_SIZE_PX = 512;

    private static final long SESSION_ACTIONS = PlaybackStateCompat.ACTION_PLAY
            | PlaybackStateCompat.ACTION_PAUSE
            | PlaybackStateCompat.ACTION_PLAY_PAUSE
            | PlaybackStateCompat.ACTION_SEEK_TO
            | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            | PlaybackStateCompat.ACTION_STOP;

    private final IBinder mBinder = new LocalBinder();

    private PlayerNotificationManager mNotificationManager;
    private MediaSessionCompat mMediaSession;
    private PlaybackPresenter mPresenter;
    // Reused player instance (owned by the Activity).
    private ExoPlayer mPlayer;
    private Player mNotificationPlayer;
    private Player.Listener mSessionSyncListener;
    private boolean mIsForeground;

    // Simple large-icon (album art) cache so the adapter can answer synchronously on repeat calls.
    private String mArtUrl;
    private Bitmap mArtBitmap;
    // Held so it can be cleared from Glide on release (avoids the leaked SIZE_ORIGINAL target).
    private CustomTarget<Bitmap> mArtTarget;

    public class LocalBinder extends Binder {
        public MobilePlaybackService getService() {
            return MobilePlaybackService.this;
        }
    }

    /**
     * Context that forces {@link Context#RECEIVER_NOT_EXPORTED} on any 2-arg
     * {@code registerReceiver} call {@link PlayerNotificationManager} makes internally. media3
     * 1.4 is targetSdk-34 aware on its own, but the wrapper is proven on this codebase and
     * harmless, so it stays as belt-and-braces (see the legacy service for the full history).
     */
    private static class NotificationContextWrapper extends ContextWrapper {
        NotificationContextWrapper(Context base) {
            super(base);
        }

        @Override
        public Context getApplicationContext() {
            Context appContext = super.getApplicationContext();
            return appContext == getBaseContext() ? this : new NotificationContextWrapper(appContext);
        }

        @Override
        public Intent registerReceiver(@Nullable BroadcastReceiver receiver, IntentFilter filter) {
            return ContextCompat.registerReceiver(
                    getBaseContext(), receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        }
    }

    /**
     * The single-MediaItem player can't advertise a queue, so prev/next would never show.
     * Advertise them unconditionally and forward to the shared suggestions queue.
     */
    private class QueueForwardingPlayer extends ForwardingPlayer {
        QueueForwardingPlayer(Player player) {
            super(player);
        }

        @Override
        public boolean isCommandAvailable(int command) {
            if (command == COMMAND_SEEK_TO_NEXT || command == COMMAND_SEEK_TO_PREVIOUS
                    || command == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM || command == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) {
                return true;
            }
            return super.isCommandAvailable(command);
        }

        @Override
        public Commands getAvailableCommands() {
            return super.getAvailableCommands().buildUpon()
                    .addAll(COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_PREVIOUS,
                            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build();
        }

        @Override
        public void seekToNext() {
            skipToNext();
        }

        @Override
        public void seekToNextMediaItem() {
            skipToNext();
        }

        @Override
        public void seekToPrevious() {
            skipToPrevious();
        }

        @Override
        public void seekToPreviousMediaItem() {
            skipToPrevious();
        }
    }

    private void skipToNext() {
        if (mPresenter != null) {
            Utils.post(() -> mPresenter.onNextClicked());
        }
    }

    private void skipToPrevious() {
        if (mPresenter != null) {
            Utils.post(() -> mPresenter.onPreviousClicked());
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
    public void attachPlayer(ExoPlayer player, PlaybackPresenter presenter, PendingIntent contentIntent) {
        if (player == null) {
            return;
        }

        // Re-attach cleanly if something was already wired.
        releaseInternal(false);

        mPresenter = presenter;
        mPlayer = player;
        mNotificationPlayer = new QueueForwardingPlayer(player);

        mMediaSession = new MediaSessionCompat(getApplicationContext(), getPackageName());
        mMediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                if (mPlayer != null) {
                    mPlayer.setPlayWhenReady(true);
                }
            }

            @Override
            public void onPause() {
                if (mPlayer != null) {
                    mPlayer.setPlayWhenReady(false);
                }
            }

            @Override
            public void onSeekTo(long pos) {
                if (mPlayer != null) {
                    mPlayer.seekTo(pos);
                }
            }

            @Override
            public void onSkipToNext() {
                skipToNext();
            }

            @Override
            public void onSkipToPrevious() {
                skipToPrevious();
            }

            @Override
            public void onStop() {
                if (mPlayer != null) {
                    mPlayer.setPlayWhenReady(false);
                }
            }
        });
        mMediaSession.setActive(true);

        // Inline connector: push state + metadata into the session on every relevant player event.
        mSessionSyncListener = new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                syncSession();
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                syncSession();
            }

            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                syncSession();
            }

            @Override
            public void onPlaybackParametersChanged(androidx.media3.common.PlaybackParameters playbackParameters) {
                syncSession();
            }

            @Override
            public void onTimelineChanged(androidx.media3.common.Timeline timeline, int reason) {
                syncSession(); // duration became known
            }
        };
        player.addListener(mSessionSyncListener);
        syncSession();

        mNotificationManager = new PlayerNotificationManager.Builder(
                new NotificationContextWrapper(this), NOTIFICATION_ID, CHANNEL_ID)
                .setChannelNameResourceId(R.string.mobile_playback_channel_name)
                .setChannelDescriptionResourceId(R.string.mobile_playback_channel_desc)
                .setSmallIconResourceId(R.drawable.ic_notification_play)
                .setMediaDescriptionAdapter(new PlayerNotificationManager.MediaDescriptionAdapter() {
                    @Override
                    public CharSequence getCurrentContentTitle(Player player) {
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
                    public CharSequence getCurrentContentText(Player player) {
                        Video video = mPresenter != null ? mPresenter.getVideo() : null;
                        return video != null ? video.getAuthor() : null;
                    }

                    @Nullable
                    @Override
                    public Bitmap getCurrentLargeIcon(Player player, PlayerNotificationManager.BitmapCallback callback) {
                        return loadArt(callback);
                    }
                })
                .setNotificationListener(new PlayerNotificationManager.NotificationListener() {
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
                })
                .build();

        mNotificationManager.setUseNextAction(true);
        mNotificationManager.setUsePreviousAction(true);
        mNotificationManager.setUsePlayPauseActions(true);
        mNotificationManager.setUseStopAction(false);
        // media3 1.5+ dropped the MediaSessionCompat.Token overload; unwrap the platform token
        // (MediaSessionCompat.Token.getToken() returns the framework MediaSession.Token).
        mNotificationManager.setMediaSessionToken(
                (android.media.session.MediaSession.Token) mMediaSession.getSessionToken().getToken());

        // Posts the notification now (if media already loaded) and on every subsequent player event.
        mNotificationManager.setPlayer(mNotificationPlayer);
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
        if (mPlayer != null && mSessionSyncListener != null) {
            mPlayer.removeListener(mSessionSyncListener);
        }
        mSessionSyncListener = null;
        if (mMediaSession != null) {
            mMediaSession.setActive(false);
            mMediaSession.release();
            mMediaSession = null;
        }
        if (removeNotification && mIsForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            mIsForeground = false;
        }
        // Release the Glide target so the loaded bitmap + its request can be collected.
        if (mArtTarget != null) {
            try {
                Glide.with(getApplicationContext()).clear(mArtTarget);
            } catch (Exception e) {
                // ignore
            }
            mArtTarget = null;
        }
        mPresenter = null;
        mPlayer = null;
        mNotificationPlayer = null;
        mArtUrl = null;
        mArtBitmap = null;
    }

    @Override
    public void onDestroy() {
        releaseInternal(true);
        super.onDestroy();
    }

    /** Push the current player state + metadata into the (lock-screen) media session. */
    private void syncSession() {
        if (mMediaSession == null || mPlayer == null) {
            return;
        }

        int sessionState;
        switch (mPlayer.getPlaybackState()) {
            case Player.STATE_BUFFERING:
                sessionState = PlaybackStateCompat.STATE_BUFFERING;
                break;
            case Player.STATE_READY:
                sessionState = mPlayer.getPlayWhenReady()
                        ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
                break;
            case Player.STATE_ENDED:
                sessionState = PlaybackStateCompat.STATE_STOPPED;
                break;
            case Player.STATE_IDLE:
            default:
                sessionState = PlaybackStateCompat.STATE_NONE;
                break;
        }

        mMediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(SESSION_ACTIONS)
                .setState(sessionState, mPlayer.getCurrentPosition(), mPlayer.getPlaybackParameters().speed)
                .setBufferedPosition(mPlayer.getBufferedPosition())
                .build());
        mMediaSession.setMetadata(buildMetadata());
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
        // Duration for the lock-screen / media-control scrubber. The player returns C.TIME_UNSET
        // (negative) until the timeline is known, so only publish a real positive duration.
        long durationMs = mPlayer != null ? mPlayer.getDuration() : 0;
        if (durationMs > 0) {
            builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
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

        // Cancel any in-flight art load before starting a new one (avoids leaking the target).
        if (mArtTarget != null) {
            Glide.with(getApplicationContext()).clear(mArtTarget);
            mArtTarget = null;
        }

        final String requestedUrl = url;
        // Bounded target size + .override() so Glide decodes a downscaled bitmap, not SIZE_ORIGINAL.
        mArtTarget = new CustomTarget<Bitmap>(ART_SIZE_PX, ART_SIZE_PX) {
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
        };

        Glide.with(getApplicationContext())
                .asBitmap()
                .load(url)
                .override(ART_SIZE_PX, ART_SIZE_PX)
                .into(mArtTarget);

        return null;
    }
}
