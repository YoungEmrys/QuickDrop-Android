package com.quickdrop.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.WpsInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Native Wi-Fi Direct discovery, connection and file transfer. */
public class WifiDirectController {
    public interface Listener {
        void onStatus(String message);
        void onPeers(List<WifiP2pDevice> peers);
        void onConnected(boolean connected);
    }

    private static final int PORT = 8988;
    private static final int HANDSHAKE_PORT = 8989;
    private static final int MAGIC = 0x51444631; // QDF1
    private static final int HANDSHAKE_MAGIC = 0x51444431; // QDD1
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024;
    private final Activity activity;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final IntentFilter filter = new IntentFilter();
    private final ArrayList<WifiP2pDevice> peers = new ArrayList<>();
    private BroadcastReceiver receiver;
    private boolean registered = false;
    private volatile boolean connected = false;
    private volatile boolean groupOwner = false;
    private volatile String hostAddress;
    private volatile String peerMacAddress;
    private volatile ServerSocket serverSocket;
    private volatile ServerSocket handshakeServerSocket;
    private volatile boolean linkFormed = false;
    private volatile boolean handshakeComplete = false;
    private volatile boolean handshakeRunning = false;
    private volatile boolean closed = false;

    public WifiDirectController(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        manager = (WifiP2pManager) activity.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager == null ? null : manager.initialize(activity, activity.getMainLooper(), null);
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    status(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED ? "Wi-Fi Direct is enabled." : "Enable Wi-Fi to use Wi-Fi Direct.");
                } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    requestPeers();
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    if (manager != null && channel != null) manager.requestConnectionInfo(channel, info -> handleConnection(info));
                }
            }
        };
    }

    public boolean isConnected() { return connected; }
    public boolean canSend() { return connected; }
    public List<WifiP2pDevice> getPeers() { return new ArrayList<>(peers); }

    public void register() {
        if (!registered && receiver != null) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                else activity.registerReceiver(receiver, filter);
                registered = true;
            } catch (Exception e) { status("Wi-Fi Direct receiver could not start: " + e.getMessage()); }
        }
    }

    public void unregister() {
        if (registered) { try { activity.unregisterReceiver(receiver); } catch (Exception ignored) { } registered = false; }
    }

    public void discover() { discover(false); }

    public void discover(boolean receiveMode) {
        if (manager == null || channel == null) { status("This device does not provide Android Wi-Fi Direct."); return; }
        try {
            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    status("Searching for nearby Android devices… Discovery can take several seconds. Keep both phones unlocked with QuickDrop open.");
                    main.postDelayed(WifiDirectController.this::requestPeers, 1800);
                }
                @Override public void onFailure(int reason) { status("Discovery failed: " + reasonText(reason) + ". Check Wi-Fi and Nearby devices permission."); }
            });
        } catch (SecurityException e) { status("Allow Nearby devices permission to discover phones."); }
    }

    public void connect(WifiP2pDevice device) { connect(device, false); }

    public void connect(WifiP2pDevice device, boolean preferGroupOwner) {
        if (device == null) return;
        peerMacAddress = device.deviceAddress;
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        // Receiver mode strongly prefers this phone as group owner; sender mode
        // yields group-owner priority so the selected receiving phone can host
        // the receive socket. Android still makes the final negotiation decision.
        config.wps.setup = WpsInfo.PBC;
        config.groupOwnerIntent = preferGroupOwner ? 15 : 0;
        try {
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { status("Invitation sent to " + device.deviceName + ". Android may show a system connection approval on the other phone; keep both screens unlocked while they connect."); }
                @Override public void onFailure(int reason) { status("Connection request was rejected by Android: " + reasonText(reason) + ". Wait a moment and try again on both phones."); }
            });
        } catch (SecurityException e) { status("Nearby devices permission is required."); }
    }

    private void requestPeers() {
        try { manager.requestPeers(channel, list -> {
            peers.clear(); peers.addAll(list.getDeviceList());
            List<WifiP2pDevice> copy = new ArrayList<>(peers);
            main.post(() -> listener.onPeers(copy));
            if (copy.isEmpty()) status("No devices found yet. Keep the other phone on the Wi-Fi Direct screen and scan again.");
        }); } catch (SecurityException e) { status("Nearby devices permission is required to list devices."); }
    }

    private void handleConnection(WifiP2pInfo info) {
        if (info == null || !info.groupFormed) {
            linkFormed = false; handshakeComplete = false; connected = false;
            hostAddress = null; closeServer(); closeHandshakeServer();
            main.post(() -> listener.onConnected(false));
            status("Not connected. Scan for a nearby device to begin.");
            return;
        }
        linkFormed = true;
        groupOwner = info.isGroupOwner;
        hostAddress = info.groupOwnerAddress == null ? null : info.groupOwnerAddress.getHostAddress();
        connected = false;
        handshakeComplete = false;
        main.post(() -> listener.onConnected(false));
        status("Wi-Fi Direct link formed. Verifying QuickDrop identity and receiver readiness…");
        startReceiveServer();
        startHandshake();
    }

    /** QDD step 1: verify both apps can exchange a versioned handshake over the P2P link. */
    private synchronized void startHandshake() {
        if (!linkFormed || handshakeRunning || handshakeComplete || closed) return;
        handshakeRunning = true;
        if (groupOwner) {
            new Thread(this::runHandshakeServer, "QuickDrop-QDD-Handshake-Server").start();
        } else {
            new Thread(this::runHandshakeClient, "QuickDrop-QDD-Handshake-Client").start();
        }
    }

    private void runHandshakeServer() {
        try (ServerSocket server = new ServerSocket(HANDSHAKE_PORT)) {
            handshakeServerSocket = server;
            server.setSoTimeout(30000);
            status("Wi-Fi Direct link formed. Waiting for the other QuickDrop phone to identify itself…");
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(10000);
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                int magic = in.readInt();
                String protocol = in.readUTF();
                String remoteId = in.readUTF();
                String remoteName = in.readUTF();
                String remoteMode = in.readUTF();
                if (magic != HANDSHAKE_MAGIC || !"QDD".equals(protocol) || remoteId.isEmpty())
                    throw new IOException("The nearby app did not send a valid QuickDrop handshake.");
                out.writeInt(HANDSHAKE_MAGIC); out.writeUTF("QDD"); out.writeUTF("ACCEPTED");
                out.writeUTF(android.os.Build.MODEL == null ? "Android" : android.os.Build.MODEL);
                out.flush();
                completeHandshake("Connected securely to " + remoteName + ". QuickDrop handshake verified.");
            }
        } catch (Exception e) {
            if (linkFormed && !closed) failHandshake("QuickDrop handshake failed: " + e.getMessage());
        } finally {
            handshakeServerSocket = null;
            handshakeRunning = false;
        }
    }

    private void runHandshakeClient() {
        try {
            if (hostAddress == null || hostAddress.isEmpty()) throw new IOException("Android did not provide the Wi-Fi Direct group address.");
            IOException last = null;
            for (int attempt = 1; attempt <= 12 && linkFormed && !closed; attempt++) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(hostAddress, HANDSHAKE_PORT), 2500);
                    socket.setSoTimeout(8000);
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                    DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                    String id = android.provider.Settings.Secure.getString(activity.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                    if (id == null) id = "android-device";
                    out.writeInt(HANDSHAKE_MAGIC); out.writeUTF("QDD"); out.writeUTF(id);
                    out.writeUTF(android.os.Build.MODEL == null ? "Android" : android.os.Build.MODEL);
                    out.writeUTF("peer"); out.flush();
                    int magic = in.readInt(); String protocol = in.readUTF();
                    String result = in.readUTF(); String remoteName = in.readUTF();
                    if (magic != HANDSHAKE_MAGIC || !"QDD".equals(protocol) || !"ACCEPTED".equals(result))
                        throw new IOException("The other phone rejected the QuickDrop handshake.");
                    completeHandshake("Connected to " + remoteName + ". QuickDrop handshake verified.");
                    return;
                } catch (IOException e) {
                    last = e;
                    try { Thread.sleep(650); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
            }
            if (last != null) throw last;
        } catch (Exception e) {
            if (linkFormed && !closed) failHandshake("Could not verify the other QuickDrop phone: " + e.getMessage());
        } finally { handshakeRunning = false; }
    }

    private void completeHandshake(String message) {
        if (!linkFormed || closed) return;
        handshakeComplete = true; connected = true;
        status(message);
        main.post(() -> listener.onConnected(true));
    }

    private void failHandshake(String message) {
        handshakeComplete = false; connected = false;
        status(message + " Disconnect and retry with both phones open and unlocked.");
        main.post(() -> listener.onConnected(false));
    }

    public void sendFiles(List<Uri> uris) {
        if (!connected || uris == null || uris.isEmpty()) { status("Connect to a QuickDrop device first and select at least one file."); return; }
        new Thread(() -> {
            try (Socket socket = new Socket()) {
                String destination = groupOwner ? resolvePeerAddress() : hostAddress;
                if (destination == null || destination.isEmpty()) throw new IllegalStateException("Could not locate the other phone on the Wi-Fi Direct link. Disconnect and reconnect both phones, then try again.");
                IOException lastConnectError = null;
                for (int attempt = 1; attempt <= 6; attempt++) {
                    try { socket.connect(new InetSocketAddress(destination, PORT), 4000); lastConnectError = null; break; }
                    catch (IOException e) { lastConnectError = e; try { Thread.sleep(700); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Connection attempt interrupted", interrupted); } }
                }
                if (lastConnectError != null) throw lastConnectError;
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                out.writeInt(MAGIC); out.writeInt(uris.size());
                byte[] buffer = new byte[64 * 1024];
                for (Uri uri : uris) {
                    String name = displayName(uri);
                    long size = getSize(uri);
                    if (size < 0 || size > MAX_FILE_BYTES) throw new IllegalArgumentException("Unsupported file size: " + name);
                    out.writeUTF(name); out.writeLong(size);
                    try (InputStream in = new BufferedInputStream(activity.getContentResolver().openInputStream(uri))) {
                        if (in == null) throw new IllegalStateException("Cannot read " + name);
                        int n; long sent = 0;
                        while ((n = in.read(buffer)) != -1) { out.write(buffer, 0, n); sent += n; }
                        if (sent != size) throw new IllegalStateException("File changed while sending: " + name);
                    }
                    status("Sent " + name);
                }
                out.flush(); status("Transfer completed successfully.");
            } catch (Exception e) { status("Wi-Fi Direct send failed: " + e.getMessage()); }
        }, "QuickDrop-WiFiDirect-Send").start();
        status("Sending files over Wi-Fi Direct…");
    }

    /** Resolve the connected P2P client when this phone is the group owner. */
    private String resolvePeerAddress() {
        // Android normally exposes the peer's P2P interface address in its ARP table
        // after group formation. Match the MAC reported by WifiP2pDevice first.
        String wanted = peerMacAddress == null ? "" : peerMacAddress.replace(":", "").toLowerCase(Locale.US);
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader("/proc/net/arp"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cols = line.trim().split("\\s+");
                if (cols.length >= 4) {
                    String mac = cols[3].replace(":", "").toLowerCase(Locale.US);
                    if (!wanted.isEmpty() && mac.equals(wanted) && !cols[0].equals(hostAddress)) return cols[0];
                }
            }
        } catch (Exception ignored) { }
        // Fallback: scan the Wi-Fi Direct /24 subnet for the QuickDrop receive port.
        // This is used only when Android has not populated the ARP entry.
        if (hostAddress != null && hostAddress.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            int dot = hostAddress.lastIndexOf('.');
            String prefix = hostAddress.substring(0, dot + 1);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(32);
            java.util.concurrent.CompletionService<String> results = new java.util.concurrent.ExecutorCompletionService<>(pool);
            int submitted = 0;
            for (int i = 2; i <= 254; i++) {
                final String ip = prefix + i;
                if (ip.equals(hostAddress)) continue;
                results.submit(() -> {
                    try (Socket probe = new Socket()) {
                        probe.connect(new InetSocketAddress(ip, PORT), 180);
                        return ip;
                    } catch (Exception e) { return null; }
                });
                submitted++;
            }
            try {
                for (int i = 0; i < submitted; i++) {
                    String found = results.take().get();
                    if (found != null) { pool.shutdownNow(); return found; }
                }
            } catch (Exception ignored) { }
            pool.shutdownNow();
        }
        return null;
    }

    private void startReceiveServer() {
        if (serverSocket != null && !serverSocket.isClosed()) return;
        new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(PORT);
                serverSocket = server;
                while (!closed && connected && !server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(120000);
                        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                        if (in.readInt() != MAGIC) throw new IllegalArgumentException("Unrecognized QuickDrop transfer.");
                        int count = in.readInt();
                        if (count < 1 || count > 100) throw new IllegalArgumentException("Invalid file count.");
                        File folder = new File(activity.getFilesDir(), "QuickDrop");
                        if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Cannot create receive folder.");
                        for (int i = 0; i < count; i++) {
                            String name = safeName(in.readUTF());
                            long size = in.readLong();
                            if (size < 0 || size > MAX_FILE_BYTES) throw new IllegalArgumentException("Invalid file size.");
                            File target = uniqueFile(folder, name);
                            try (FileOutputStream file = new FileOutputStream(target)) {
                                byte[] buffer = new byte[64 * 1024]; long remaining = size;
                                while (remaining > 0) {
                                    int n = in.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                                    if (n < 0) throw new IllegalStateException("Transfer ended unexpectedly.");
                                    file.write(buffer, 0, n); remaining -= n;
                                }
                                file.getFD().sync();
                            }
                            boolean mirrored = StorageBridge.exportFile(folder.getAbsolutePath(), target.getName());
                            status("Received " + target.getName() + (mirrored ? " and copied to your selected shared folder." : " in QuickDrop storage."));
                        }
                        status("Incoming Wi-Fi Direct transfer completed.");
                    } catch (java.io.EOFException ignored) {
                        // Port probe from the peer-address fallback; no transfer was started.
                    } catch (Exception e) { status("Receive error: " + e.getMessage()); }
                }
            } catch (Exception e) { if (connected && !closed) status("Could not start receiving: " + e.getMessage()); }
        }, "QuickDrop-WiFiDirect-Receive").start();
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = activity.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (ix >= 0) return c.getString(ix); }
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment(); return last == null ? "shared-file" : last;
    }
    private long getSize(Uri uri) {
        try (android.database.Cursor c = activity.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int ix = c.getColumnIndex(OpenableColumns.SIZE); if (ix >= 0 && !c.isNull(ix)) return c.getLong(ix); }
        } catch (Exception ignored) { }
        return -1;
    }
    private String safeName(String name) {
        String cleaned = name == null ? "received-file" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) return "received-file";
        return cleaned.length() > 180 ? cleaned.substring(cleaned.length() - 180) : cleaned;
    }
    private File uniqueFile(File folder, String name) {
        File f = new File(folder, name); if (!f.exists()) return f;
        String base = name, ext = ""; int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        for (int i = 1; i < 10000; i++) { f = new File(folder, base + " (" + i + ")" + ext); if (!f.exists()) return f; }
        return new File(folder, System.currentTimeMillis() + "-" + name);
    }
    private String reasonText(int r) {
        switch (r) { case WifiP2pManager.P2P_UNSUPPORTED: return "Wi-Fi Direct is not supported"; case WifiP2pManager.BUSY: return "Wi-Fi is busy; try again"; case WifiP2pManager.ERROR: return "system error"; default: return "error code " + r; }
    }
    private void status(String message) { main.post(() -> listener.onStatus(message)); }
    private void closeServer() { try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) { } serverSocket = null; }
    private void closeHandshakeServer() { try { if (handshakeServerSocket != null) handshakeServerSocket.close(); } catch (Exception ignored) { } handshakeServerSocket = null; }
    public void close() { closed = true; linkFormed = false; handshakeComplete = false; connected = false; closeServer(); closeHandshakeServer(); unregister(); }
}
