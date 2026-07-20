package com.newtube.mobile.casting;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.playback.MobilePlaybackActivity;

/**
 * Foreground service for an active cast session: while the phone is only a remote (Route B) the
 * process must survive backgrounding so the Lounge status subscription keeps flowing. Holds a
 * WifiLock + partial wakelock and shows a media-style notification with play/pause + disconnect.
 * Route A ("Direct cast") raised the stakes on those locks: every media byte the TV plays relays
 * phone&rarr;TV through the on-phone proxy, so a dozing radio doesn't just stall a remote - it
 * stops TV playback outright.
 *
 * <p>FGS pattern mirrors {@link com.newtube.mobile.ui.playback.MobilePlaybackService}: type
 * {@code mediaPlayback} (declared in the stmobile manifest), the first {@code startForeground} is
 * always reached from the foreground (the picker UI starts the service on connect), and the call
 * is guarded against the API 31+ {@code ForegroundServiceStartNotAllowedException} anyway - an
 * un-promoted service still works while the app lives, it just loses kill-immunity.</p>
 */
public class CastSessionService extends Service implements CastSessionManager.Listener {

    private static final String TAG = CastSessionService.class.getSimpleName();

    public static final String CHANNEL_ID = "newtube_cast_channel";
    private static final int NOTIFICATION_ID = 41338; // next to MobilePlaybackService's 41337

    private static final String ACTION_PLAY_PAUSE = "com.newtube.mobile.casting.action.PLAY_PAUSE";
    private static final String ACTION_DISCONNECT = "com.newtube.mobile.casting.action.DISCONNECT";

    private WifiManager.WifiLock mWifiLock;
    private PowerManager.WakeLock mWakeLock;
    private boolean mIsForeground;

    /** Start (or refresh) the session service. Call from the foreground (session connect). */
    public static void start(Context context) {
        CastSessionManager.startSessionService(context, new Intent(context, CastSessionService.class));
    }

    /** Stop the session service (session ended). */
    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, CastSessionService.class));
        } catch (Exception e) {
            // ignore
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        acquireLocks();
        CastSessionManager.instance(this).addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        CastSessionManager manager = CastSessionManager.instance(this);

        if (ACTION_PLAY_PAUSE.equals(action)) {
            manager.togglePlayPauseFromNotification();
        } else if (ACTION_DISCONNECT.equals(action)) {
            manager.disconnect(); // triggers onCastSessionEnded -> stop(context) -> onDestroy
            return START_NOT_STICKY;
        }

        promoteToForeground(buildNotification());
        // Not sticky: a session can't be resurrected by the system restarting an empty service.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        CastSessionManager.instance(this).removeListener(this);
        if (mIsForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            mIsForeground = false;
        }
        releaseLocks();
        super.onDestroy();
    }

    // ---------------------------------------------------------------------------------
    // CastSessionManager.Listener - keep the notification in sync with the TV
    // ---------------------------------------------------------------------------------

    @Override
    public void onCastSessionStarted(CastTarget target) {
        updateNotification();
    }

    @Override
    public void onCastSessionState(@Nullable String videoId, long positionMs, long durationMs, boolean playing) {
        updateNotification();
    }

    @Override
    public void onCastSessionEnded(@Nullable String reason) {
        // The manager also calls stop(context); stopSelf covers the race where the manager's
        // stopService lands before this listener ran.
        stopSelf();
    }

    // ---------------------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------------------

    private void promoteToForeground(Notification notification) {
        try {
            startForeground(NOTIFICATION_ID, notification);
            mIsForeground = true;
        } catch (IllegalStateException e) {
            // API 31+ ForegroundServiceStartNotAllowedException (ISE subclass): continue
            // un-promoted - the session keeps working while the app is alive (same recovery
            // stance as MobilePlaybackService).
            Log.e(TAG, "startForeground rejected, continuing un-promoted: " + e);
            mIsForeground = false;
        }
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            try {
                manager.notify(NOTIFICATION_ID, buildNotification());
            } catch (Exception e) {
                // POST_NOTIFICATIONS revoked: the FGS itself continues, just quietly.
            }
        }
    }

    private Notification buildNotification() {
        CastSessionManager manager = CastSessionManager.instance(this);
        CastTarget target = manager.getTarget();
        String targetName = target != null ? target.getName() : "";
        boolean playing = manager.isPlayingOnTv();

        // Content tap re-surfaces the existing player instance (same flags as the playback
        // notification's content intent - never stack a duplicate Activity).
        Intent contentIntent = new Intent(this, MobilePlaybackActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent content = PendingIntent.getActivity(this, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mobile_cast)
                .setContentTitle(getString(R.string.mobile_cast_playing_on, targetName))
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .addAction(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play,
                        getString(playing ? R.string.mobile_player_pause : R.string.mobile_player_play),
                        servicePendingIntent(ACTION_PLAY_PAUSE, 1))
                .addAction(R.drawable.ic_sheet_close,
                        getString(R.string.mobile_cast_disconnect),
                        servicePendingIntent(ACTION_DISCONNECT, 2))
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(0, 1));

        return builder.build();
    }

    private PendingIntent servicePendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, CastSessionService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.mobile_cast_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription(getString(R.string.mobile_cast_channel_desc));
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Locks: the Lounge session is long-poll network traffic with the screen likely off
    // ---------------------------------------------------------------------------------

    private void acquireLocks() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                mWifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NewTube:CastWifi");
                mWifiLock.setReferenceCounted(false);
                mWifiLock.acquire();
            }
        } catch (Exception e) {
            Log.e(TAG, "WifiLock acquire failed: " + e);
        }
        try {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power != null) {
                mWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NewTube:CastSession");
                mWakeLock.setReferenceCounted(false);
                mWakeLock.acquire();
            }
        } catch (Exception e) {
            Log.e(TAG, "WakeLock acquire failed: " + e);
        }
    }

    private void releaseLocks() {
        try {
            if (mWifiLock != null && mWifiLock.isHeld()) {
                mWifiLock.release();
            }
        } catch (Exception e) {
            // ignore
        }
        mWifiLock = null;
        try {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        } catch (Exception e) {
            // ignore
        }
        mWakeLock = null;
    }
}
