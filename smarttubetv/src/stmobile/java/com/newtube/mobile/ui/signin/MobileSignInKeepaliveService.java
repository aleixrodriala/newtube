package com.newtube.mobile.ui.signin;

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

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Short-lived foreground service that keeps the sign-in token poll alive while the user is away
 * approving the device code in the browser Custom Tab (or wherever the hand-off ended).
 *
 * <p>Why it exists: the moment another activity fully covers the app, Android puts the process in
 * the cached tier and suspends its network (verified on the Pixel: the 3-second poll produced
 * nothing for 40+ s behind the tab, then succeeded within 1 s of the tab closing). Without
 * network the poll cannot observe the approval, so the success auto-return
 * ({@link MobileSignInActivity#showSuccess}) never fires while the tab is up - the exact moment
 * it is for. A foreground service exempts the process from freezing/network suspension, so the
 * poll lands behind the tab and the CLEAR_TOP relaunch pops the user straight back.
 *
 * <p>Lifecycle: started when the user opens the approval page, stopped on success/error/screen
 * exit. Declared {@code shortService} (API 34+ semantics: ~3 min budget, then {@link #onTimeout})
 * - the dance is far shorter; on timeout we just stop and sign-in falls back to completing on
 * the user's return, the pre-service behavior.
 */
public class MobileSignInKeepaliveService extends Service {
    private static final String CHANNEL_ID = "newtube_signin";
    private static final int NOTIFICATION_ID = 7802;

    public static void start(Context context) {
        try {
            ContextCompat.startForegroundService(
                    context, new Intent(context, MobileSignInKeepaliveService.class));
        } catch (Exception e) {
            // Not being allowed to start (weird OEM state) only costs the auto-return nicety.
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, MobileSignInKeepaliveService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.mobile_signin_title),
                    NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }

        Intent tapIntent = new Intent(this, MobileSignInActivity.class);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, tapIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mobile_account)
                .setContentTitle(getString(R.string.mobile_signin_notification))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    /** shortService budget exhausted (~3 min, API 34+): sign-in completes on return instead. */
    @Override
    public void onTimeout(int startId) {
        stopSelf();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
