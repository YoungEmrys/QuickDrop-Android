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
import android.os.PowerManager;
import android.net.wifi.WifiManager;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.json.JSONObject;
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
    private PowerManager.WakeLock cpuWakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override public void onCreate() {
        super.onCreate();
        StorageBridge.initialize(this);
        createChannel();
        // Keep the CPU and Wi-Fi radio awake while QuickDrop is actively serving transfers.
        // Locks are released when the user stops sharing or Android destroys the service.
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuickDrop::TransferService");
            cpuWakeLock.setReferenceCounted(false);
            cpuWakeLock.acquire();
        } catch (Throwable e) {
            android.util.Log.w("QuickDrop", "Could not acquire CPU wake lock", e);
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "QuickDrop::WifiTransfer");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Throwable e) {
            android.util.Log.w("QuickDrop", "Could not acquire Wi-Fi lock", e);
        }
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
            // Use a JSON string across the Chaquopy boundary. This avoids null
            // PyObject values from dict lookups and gives Java a stable contract.
            PyObject result = module.callAttr("start_server_json");
            if (result == null) throw new IllegalStateException("Python start_server_json() returned null.");
            String payload = result.toJava(String.class);
            if (payload == null || payload.trim().isEmpty())
                throw new IllegalStateException("Python returned an empty server response.");
            JSONObject info = new JSONObject(payload);
            if (!info.optBoolean("running", false))
                throw new IllegalStateException("Python returned a response but the server is not running.");
            if (stopping) { module.callAttr("stop_server"); return; }

            String share = info.optString("share_url", "");
            String admin = info.optString("admin_url", "");
            String ip = info.optString("ip", "unknown address");
            if (share.isEmpty() || admin.isEmpty())
                throw new IllegalStateException("Server started but did not provide its sharing and control-panel URLs. Response: " + payload);

            SharedPreferences prefs = getSharedPreferences("quickdrop_settings", MODE_PRIVATE);
            prefs.edit().putBoolean("server_running", true).remove("server_error")
                    .putString("share_url", share).putString("admin_url", admin)
                    .putString("folder_path", folder.getAbsolutePath()).apply();
            updateNotification("Sharing is active · " + ip);
            sendBroadcast(new Intent("com.quickdrop.app.SERVER_STARTED"));
        } catch (Throwable e) {
            StringWriter trace = new StringWriter();
            e.printStackTrace(new PrintWriter(trace));
            getSharedPreferences("quickdrop_settings", MODE_PRIVATE).edit()
                    .putBoolean("server_running", false)
                    .remove("share_url").remove("admin_url")
                    .putString("server_error", e.toString() + "\n\n" + trace).apply();
            updateNotification("Could not start · Open QuickDrop for details");
            sendBroadcast(new Intent("com.quickdrop.app.SERVER_FAILED"));
            started = false;
            try {
                if (Python.isStarted()) Python.getInstance().getModule("quickdrop").callAttr("stop_server");
            } catch (Throwable cleanupError) {
                android.util.Log.e("QuickDrop", "Server cleanup after startup failure failed", cleanupError);
            }
            android.util.Log.e("QuickDrop", "Python server startup failed", e);
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
        try { if (cpuWakeLock != null && cpuWakeLock.isHeld()) cpuWakeLock.release(); } catch (Exception ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
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
