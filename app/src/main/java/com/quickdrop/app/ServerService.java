package com.quickdrop.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerService extends Service {
    public static final String ACTION_START = "com.quickdrop.app.START";
    public static final String ACTION_STOP = "com.quickdrop.app.STOP";
    private static final String CHANNEL = "quickdrop_server";
    private static final int NOTIFICATION_ID = 4102;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean started = false;
    private volatile boolean stopping = false;

    @Override public void onCreate() {
        super.onCreate();
        StorageBridge.initialize(this);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification("Starting QuickDrop…"));
        if (!started) {
            started = true;
            worker.execute(this::startPythonServer);
        }
        boolean keep = getSharedPreferences("quickdrop_settings", MODE_PRIVATE).getBoolean("keep_running", false);
        return keep ? START_STICKY : START_NOT_STICKY;
    }

    private void startPythonServer() {
        try {
            if (stopping) return;
            File folder = new File(getFilesDir(), "QuickDrop");
            if (!folder.exists()) folder.mkdirs();
            StorageBridge.importSelectedFolder(folder.getAbsolutePath());

            Python py = Python.getInstance();
            PyObject module = py.getModule("quickdrop");
            module.callAttr("configure_folder", folder.getAbsolutePath());
            if (stopping) return;
            PyObject info = module.callAttr("start_server");
            if (stopping) { module.callAttr("stop_server"); return; }
            String share = info.get("share_url").toString();
            String admin = info.get("admin_url").toString();
            SharedPreferences prefs = getSharedPreferences("quickdrop_settings", MODE_PRIVATE);
            prefs.edit().putBoolean("server_running", true)
                    .putString("share_url", share).putString("admin_url", admin)
                    .putString("folder_path", folder.getAbsolutePath()).apply();
            updateNotification("Sharing is active · " + info.get("ip").toString());
            sendBroadcast(new Intent("com.quickdrop.app.SERVER_STARTED"));
        } catch (Exception e) {
            getSharedPreferences("quickdrop_settings", MODE_PRIVATE).edit()
                    .putBoolean("server_running", false).putString("server_error", e.toString()).apply();
            updateNotification("Could not start · Open QuickDrop for details");
            sendBroadcast(new Intent("com.quickdrop.app.SERVER_FAILED"));
            stopSelf();
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        boolean keep = getSharedPreferences("quickdrop_settings", MODE_PRIVATE)
                .getBoolean("keep_running", false);
        if (!keep) stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        stopping = true;
        getSharedPreferences("quickdrop_settings", MODE_PRIVATE).edit()
                .putBoolean("server_running", false).remove("share_url").remove("admin_url").apply();
        try {
            if (Python.isStarted()) Python.getInstance().getModule("quickdrop").callAttr("stop_server");
        } catch (Exception ignored) { }
        try { worker.shutdownNow(); } catch (Exception ignored) { }
        stopForeground(STOP_FOREGROUND_REMOVE);
        sendBroadcast(new Intent("com.quickdrop.app.SERVER_STOPPED"));
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "QuickDrop sharing", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shown while QuickDrop is sharing files over your local network.");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ServerService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_quickdrop).setContentTitle("QuickDrop")
                .setContentText(text).setContentIntent(pi).setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi);
        return builder.build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
