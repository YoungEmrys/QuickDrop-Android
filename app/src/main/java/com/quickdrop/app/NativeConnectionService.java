package com.quickdrop.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** Keeps the native peer session visible to Android while the user switches apps. */
public final class NativeConnectionService extends Service {
    public static final String ACTION_START = "com.quickdrop.app.NATIVE_START";
    public static final String ACTION_STOP = "com.quickdrop.app.NATIVE_STOP";
    private static final String CHANNEL = "quickdrop_native_connection";
    private static final int NOTIFICATION_ID = 4103;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "QuickDrop device connection", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps a native QuickDrop phone-to-phone connection active.");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 3, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification notification = builder.setSmallIcon(R.drawable.ic_quickdrop)
                .setContentTitle("QuickDrop connection active")
                .setContentText("Phone-to-phone sharing is running. You can switch apps.")
                .setContentIntent(pending).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION_ID, notification);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() { stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
