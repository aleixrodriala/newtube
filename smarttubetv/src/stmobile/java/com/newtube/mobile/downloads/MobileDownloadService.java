package com.newtube.mobile.downloads;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.MobileBrowseActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that drains the download queue one item at a time.
 *
 * <p>Same lifecycle shape as the cast and playback services: started from the foreground when
 * a download is enqueued, promoted with a progress notification (type dataSync, the one
 * Android 14 accepts for transfers), stopped when the queue is empty. A partial wake lock keeps
 * the CPU up between chunks with the screen off; Wi-Fi is left to the system, a paused radio
 * just makes a chunk retry.
 */
public final class MobileDownloadService extends Service {
    private static final String TAG = MobileDownloadService.class.getSimpleName();
    private static final String CHANNEL_ID = "newtube_downloads";
    private static final int NOTIFICATION_ID = 0x4E54_0010;
    private static final int DONE_NOTIFICATION_BASE = 0x4E54_0100;

    private static final String ACTION_START = "com.newtube.mobile.downloads.START";
    private static final String ACTION_CANCEL = "com.newtube.mobile.downloads.CANCEL";
    private static final String EXTRA_ITEM_ID = "item_id";

    private final ExecutorService mWorker = Executors.newSingleThreadExecutor(r -> new Thread(r, "newtube-download"));
    private DownloadRegistry mRegistry;
    private NotificationManager mNotifications;
    @Nullable private PowerManager.WakeLock mWakeLock;
    private volatile boolean mDraining;
    private volatile boolean mForeground;

    /** Adds the item to the registry and makes sure the queue is being drained. */
    public static void enqueue(Context context, DownloadItem item) {
        DownloadRegistry.instance(context).add(item);
        start(context);
    }

    /** Re-runs a failed item (part files, if any, resume where they stopped). */
    public static void retry(Context context, DownloadItem item) {
        item.state = DownloadItem.STATE_QUEUED;
        item.error = null;
        item.cancelRequested = false;
        DownloadRegistry.instance(context).notifyChanged();
        start(context);
    }

    /**
     * Stops an active item and removes it. The running job polls the flag at its next progress
     * tick and drops the part files itself; a queued item simply leaves the registry.
     */
    public static void cancel(Context context, DownloadItem item) {
        item.cancelRequested = true;
        if (item.state == DownloadItem.STATE_QUEUED) {
            DownloadRegistry.instance(context).remove(item);
        }
    }

    private static void start(Context context) {
        Intent intent = new Intent(context, MobileDownloadService.class).setAction(ACTION_START);
        ContextCompat.startForegroundService(context, intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mRegistry = DownloadRegistry.instance(this);
        mNotifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        // Every start must promote promptly: a startForegroundService() that never reaches
        // startForeground() is an ANR-class crash on API 26+. Cancel arrives the same way (the
        // notification action), so it promotes too and steps back down when nothing is running.
        promote(buildProgressNotification(mRegistry.nextQueued()));

        if (ACTION_CANCEL.equals(action)) {
            DownloadItem item = mRegistry.find(intent.getStringExtra(EXTRA_ITEM_ID));
            if (item != null) {
                cancel(this, item);
            }
            synchronized (mDrainLock) {
                if (!mDraining) {
                    stopForegroundCompat();
                    stopSelf();
                }
            }
            return START_NOT_STICKY;
        }

        drainIfIdle();
        return START_NOT_STICKY;
    }

    private final Object mDrainLock = new Object();

    private void drainIfIdle() {
        synchronized (mDrainLock) {
            if (mDraining) {
                return;
            }
            mDraining = true;
        }
        acquireWakeLock();
        mWorker.execute(this::drain);
    }

    private void drain() {
        try {
            while (true) {
                DownloadItem running = mRegistry.nextQueued();
                if (running == null) {
                    synchronized (mDrainLock) {
                        // Re-check under the lock: an enqueue that raced the empty check must not be
                        // stranded behind a mDraining flag nobody resets.
                        if (mRegistry.nextQueued() == null) {
                            mDraining = false;
                            break;
                        }
                    }
                    continue;
                }
                updateProgressNotification(running);
                new DownloadJob(this, running, mRegistry, this::updateProgressNotification).run();
                if (running.isDone()) {
                    postDoneNotification(running);
                }
            }
        } finally {
            synchronized (mDrainLock) {
                mDraining = false;
            }
            releaseWakeLock();
            stopForegroundCompat();
            stopSelf();
        }
    }

    // ---------------------------------------------------------------------------------
    // Notifications
    // ---------------------------------------------------------------------------------

    private void promote(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            mForeground = true;
        } catch (RuntimeException e) {
            // API 31+ refuses a background promotion; the queue still drains while the process lives.
            Log.w(TAG, "foreground promotion refused: " + e);
        }
    }

    private void stopForegroundCompat() {
        if (!mForeground) {
            return;
        }
        mForeground = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void updateProgressNotification(DownloadItem item) {
        if (!mForeground) {
            return;
        }
        mNotifications.notify(NOTIFICATION_ID, buildProgressNotification(item));
    }

    private Notification buildProgressNotification(@Nullable DownloadItem item) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_nav_downloads_fill)
                .setContentIntent(openDownloadsIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS);

        if (item == null) {
            builder.setContentTitle(getString(R.string.header_downloads))
                    .setProgress(0, 0, true);
            return builder.build();
        }

        builder.setContentTitle(item.title);
        if (item.state == DownloadItem.STATE_PROCESSING) {
            builder.setContentText(getString(R.string.mobile_download_state_processing))
                    .setProgress(0, 0, true);
        } else {
            int percent = item.progressPercent();
            builder.setContentText(DownloadProgressText.of(this, item))
                    .setProgress(100, Math.max(0, percent), percent < 0);
            builder.addAction(R.drawable.ic_sheet_close, getString(R.string.cancel),
                    cancelIntent(item));
        }
        return builder.build();
    }

    private void postDoneNotification(DownloadItem item) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_watch_downloaded)
                .setContentTitle(getString(R.string.mobile_download_done_title))
                .setContentText(item.title)
                .setContentIntent(openDownloadsIntent())
                .setAutoCancel(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
        mNotifications.notify(DONE_NOTIFICATION_BASE + (item.id.hashCode() & 0xFF), notification);
    }

    private PendingIntent openDownloadsIntent() {
        Intent intent = new Intent(this, MobileBrowseActivity.class)
                .setAction(MobileBrowseActivity.ACTION_OPEN_DOWNLOADS)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent cancelIntent(DownloadItem item) {
        Intent intent = new Intent(this, MobileDownloadService.class)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_ITEM_ID, item.id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? PendingIntent.getForegroundService(this, item.id.hashCode(), intent, flags)
                : PendingIntent.getService(this, item.id.hashCode(), intent, flags);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mNotifications != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.mobile_download_channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.mobile_download_channel_desc));
            channel.setShowBadge(false);
            mNotifications.createNotificationChannel(channel);
        }
    }

    // ---------------------------------------------------------------------------------
    // Locks
    // ---------------------------------------------------------------------------------

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && mWakeLock == null) {
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NewTube:downloads");
                mWakeLock.setReferenceCounted(false);
                mWakeLock.acquire(6 * 60 * 60 * 1000L);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "wake lock unavailable: " + e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        } catch (RuntimeException ignored) {
        }
        mWakeLock = null;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        mWorker.shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
