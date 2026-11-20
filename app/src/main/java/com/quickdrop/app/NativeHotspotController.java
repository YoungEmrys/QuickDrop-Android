package com.quickdrop.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.net.Uri;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native Android-to-Android file transfer over a QuickDrop local-only hotspot. */
public final class NativeHotspotController {
    public interface Listener {
        void onStatus(String message);
        void onHostDetails(String ssid, String password, String hostAddress, int port);
        void onConnected(boolean connected, String message);
        void onFileReceived(File file);
    }

    private static final int PORT = 8989;
    private static final int MAX_FILES = 100;
    private static final long MAX_FILE_SIZE = 2L * 1024L * 1024L * 1024L;
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Object writeLock = new Object();
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ServerSocket serverSocket;
    private volatile Socket peerSocket;
    private volatile DataInputStream peerIn;
    private volatile DataOutputStream peerOut;
    private volatile boolean closed;
    private volatile boolean connected;
    private volatile boolean hosting;
    private String hostAddress = "";

    public NativeHotspotController(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        this.connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void createConnection() {
        if (Build.VERSION.SDK_INT < 26) { status("Native hotspot sharing requires Android 8.0 or later."); return; }
        if (reservation != null) { status("Connection hotspot is already active."); return; }
        closed = false; hosting = true;
        startConnectionService();
        status("Requesting a private QuickDrop hotspot…");
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation value) {
                    reservation = value;
                    try {
                        android.net.wifi.WifiConfiguration config = reservation.getWifiConfiguration();
                        String ssid = clean(config == null ? "QuickDrop" : config.SSID);
                        String password = clean(config == null ? "" : config.preSharedKey);
                        hostAddress = findHotspotAddress();
                        if (TextUtils.isEmpty(hostAddress)) throw new Exception("Android did not expose the hotspot address. Stop and retry.");
                        startHostSocket();
                        final String finalSsid = TextUtils.isEmpty(ssid) ? "QuickDrop" : ssid;
                        final String finalPassword = password;
                        main.post(() -> { listener.onHostDetails(finalSsid, finalPassword, hostAddress, PORT); listener.onConnected(false, "Hotspot ready · Waiting for another QuickDrop phone"); });
                    } catch (Exception e) { status("Hotspot started, but setup failed: " + e.getMessage()); }
                }
                @Override public void onStopped() { stop(); status("QuickDrop hotspot stopped by Android."); }
                @Override public void onFailed(int reason) { status("Android could not start the QuickDrop hotspot (code " + reason + "). Turn off any conflicting hotspot and try again."); }
            }, main);
        } catch (SecurityException e) { status("Nearby Wi-Fi permission is required to create the hotspot."); }
          catch (Exception e) { status("Could not create hotspot: " + e.getMessage()); }
    }

    private String clean(String value) {
        if (value == null) return "";
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) return value.substring(1, value.length()-1);
        return value;
    }

    private void startHostSocket() {
        io.execute(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                status("Hotspot ready · Waiting for receiver");
                while (!closed && !serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    socket.setSoTimeout(20000); socket.setKeepAlive(true);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    String hello = in.readUTF();
                    if (!"QDHOTSPOT:2".equals(hello)) { socket.close(); continue; }
                    out.writeUTF("QDHOTSPOT-OK:2"); out.flush();
                    attachPeer(socket, in, out);
                    readPeerLoop(socket, in);
                }
            } catch (Exception e) { if (!closed) status("Connection listener stopped: " + e.getMessage()); }
        });
    }

    public void connectToSender(String ssid, String password) {
        connectToSender(ssid, password, "", PORT);
    }

    public void connectToSender(String ssid, String password, String senderAddress, int senderPort) {
        if (Build.VERSION.SDK_INT < 29) { status("Automatic native connection requires Android 10 or later. Use Browser Sharing on this phone."); return; }
        if (TextUtils.isEmpty(ssid)) { status("Enter the sender's hotspot name (SSID)."); return; }
        if (networkCallback != null) { status("A sender connection request is already active."); return; }
        closed = false; hosting = false; startConnectionService(); status("Requesting Android permission to join " + ssid + "…");
        WifiNetworkSpecifier.Builder spec = new WifiNetworkSpecifier.Builder().setSsid(ssid);
        if (!TextUtils.isEmpty(password)) spec.setWpa2Passphrase(password);
        NetworkRequest request = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec.build()).build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                connectivity.bindProcessToNetwork(network);
                String gateway = TextUtils.isEmpty(senderAddress) ? gatewayFor(network) : senderAddress;
                if (TextUtils.isEmpty(gateway)) { status("Joined hotspot, but could not identify the sender address."); return; }
                status("Connected to hotspot · Verifying QuickDrop on the other phone…");
                io.execute(() -> verifyPeer(gateway, senderPort > 0 ? senderPort : PORT));
            }
            @Override public void onUnavailable() { status("Android could not join that hotspot. Check the name/password and try again."); clearNetworkRequest(false); }
            @Override public void onLost(Network network) { connected = false; closePeer(); clearNetworkRequest(true); hosting = false; stopConnectionService(); main.post(() -> listener.onConnected(false, "Sender hotspot connection was lost. Scan the QR code again to reconnect.")); }
        };
        try { connectivity.requestNetwork(request, networkCallback); }
        catch (Exception e) { status("Could not request hotspot connection: " + e.getMessage()); clearNetworkRequest(false); }
    }

    private void verifyPeer(String gateway, int port) {
        try {
            Socket socket = new Socket(); socket.connect(new java.net.InetSocketAddress(gateway, port), 10000); socket.setSoTimeout(20000); socket.setKeepAlive(true);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream()); DataInputStream in = new DataInputStream(socket.getInputStream());
            out.writeUTF("QDHOTSPOT:2"); out.flush();
            if (!"QDHOTSPOT-OK:2".equals(in.readUTF())) throw new Exception("The device did not respond as a compatible QuickDrop sender.");
            attachPeer(socket, in, out); readPeerLoop(socket, in);
        } catch (Exception e) { status("QuickDrop verification failed: " + e.getMessage()); }
    }

    private void attachPeer(Socket socket, DataInputStream in, DataOutputStream out) {
        peerSocket = socket; peerIn = in; peerOut = out; connected = true;
        main.post(() -> listener.onConnected(true, "Connected · Ready to send and receive files."));
        status("QuickDrop phone connected · File transfer ready");
    }

    private void readPeerLoop(Socket socket, DataInputStream in) {
        int missedHeartbeats = 0;
        try {
            while (!closed && !socket.isClosed()) {
                final String command;
                try { command = in.readUTF(); }
                catch (SocketTimeoutException timeout) {
                    if (++missedHeartbeats >= 3) throw new Exception("Peer stopped responding. The other phone may have closed QuickDrop or left the connection.");
                    synchronized (writeLock) { DataOutputStream out = peerOut; if (out != null && peerSocket == socket) { out.writeUTF("PING"); out.flush(); } }
                    continue;
                }
                if ("FILES".equals(command)) { missedHeartbeats = 0; receiveFiles(in); }
                else if ("PING".equals(command)) { synchronized (writeLock) { if (peerOut != null && peerSocket == socket) { peerOut.writeUTF("PONG"); peerOut.flush(); } } }
                else if ("PONG".equals(command)) missedHeartbeats = 0;
                else throw new Exception("Unknown transfer command.");
            }
        } catch (Exception e) { if (!closed) status("Peer connection ended: " + e.getMessage()); }
        finally {
            if (peerSocket == socket) { connected = false; closePeer(); clearNetworkRequest(true); if (!hosting) stopConnectionService(); main.post(() -> listener.onConnected(false, "Peer disconnected · Hotspot is still available. Reconnect to continue.")); }
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void receiveFiles(DataInputStream in) throws Exception {
        int count = in.readInt(); if (count < 1 || count > MAX_FILES) throw new Exception("Invalid file batch.");
        File folder = new File(context.getFilesDir(), "QuickDrop"); if (!folder.exists()) folder.mkdirs();
        for (int i = 0; i < count; i++) {
            String name = safeName(in.readUTF()); long size = in.readLong();
            if (size < 0 || size > MAX_FILE_SIZE) throw new Exception("File size is outside the supported limit.");
            File target = uniqueFile(folder, name);
            try (FileOutputStream fileOut = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024]; long remaining = size;
                while (remaining > 0) { int n = in.read(buffer, 0, (int)Math.min(buffer.length, remaining)); if (n < 0) throw new Exception("Transfer ended before the file was complete."); fileOut.write(buffer, 0, n); remaining -= n; }
            }
            StorageBridge.exportFile(folder.getAbsolutePath(), target.getName());
            final File received = target; main.post(() -> listener.onFileReceived(received));
            status("Received " + target.getName());
        }
        status("File transfer completed successfully.");
    }

    public void sendFiles(List<Uri> files) {
        if (!connected || peerOut == null || peerSocket == null || peerSocket.isClosed()) { status("Connect to another QuickDrop phone before sending files."); return; }
        if (files == null || files.isEmpty()) return;
        ArrayList<Uri> copy = new ArrayList<>(files);
        io.execute(() -> {
            try {
                synchronized (writeLock) {
                    DataOutputStream out = peerOut;
                    ArrayList<Long> sizes = new ArrayList<>();
                    for (Uri uri : copy) {
                        long size = getSize(uri);
                        if (size < 0 || size > MAX_FILE_SIZE) throw new Exception("Cannot determine a supported file size for " + safeName(displayName(uri)));
                        sizes.add(size);
                    }
                    out.writeUTF("FILES"); out.writeInt(copy.size()); out.flush();
                    byte[] buffer = new byte[64 * 1024];
                    for (int index = 0; index < copy.size(); index++) {
                        Uri uri = copy.get(index);
                        String name = safeName(displayName(uri));
                        long size = sizes.get(index);
                        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                            if (input == null) throw new Exception("Cannot open " + name);
                            out.writeUTF(name); out.writeLong(size); out.flush();
                            long sent = 0; int n;
                            while ((n = input.read(buffer)) != -1) { out.write(buffer, 0, n); sent += n; }
                            if (sent != size) throw new Exception("File changed while sending: " + name);
                        }
                        out.flush(); status("Sent " + name);
                    }
                }
                status("All selected files sent successfully.");
            } catch (Exception e) { status("File transfer failed: " + e.getMessage()); }
        });
    }

    private long getSize(Uri uri) throws Exception {
        try (android.content.res.AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) { return afd == null ? -1 : afd.getLength(); }
    }
    private String displayName(Uri uri) {
        try (android.database.Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (i >= 0) return c.getString(i); }
        } catch (Exception ignored) {}
        return uri.getLastPathSegment() == null ? "shared-file" : uri.getLastPathSegment();
    }
    private String safeName(String name) {
        String v = name == null ? "shared-file" : name.replace('\\','/'); v = v.substring(v.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}\\/:*?\"<>|]", "_").trim();
        return v.isEmpty() ? "shared-file" : v;
    }
    private File uniqueFile(File folder, String name) {
        File f = new File(folder, name); if (!f.exists()) return f;
        String base = name, ext = ""; int dot = name.lastIndexOf('.'); if (dot > 0) { base = name.substring(0,dot); ext = name.substring(dot); }
        for (int i=1;i<10000;i++) { f = new File(folder, base + " (" + i + ")" + ext); if (!f.exists()) return f; }
        return new File(folder, System.currentTimeMillis()+"-"+name);
    }

    private String gatewayFor(Network network) {
        try { LinkProperties lp = connectivity.getLinkProperties(network); if (lp != null) for (RouteInfo route : lp.getRoutes()) { InetAddress g = route.getGateway(); if (g instanceof Inet4Address && !g.isAnyLocalAddress()) return g.getHostAddress(); } } catch (Exception ignored) {}
        return "";
    }
    private String findHotspotAddress() {
        try { for (Network n : connectivity.getAllNetworks()) { NetworkCapabilities caps = connectivity.getNetworkCapabilities(n); if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue; LinkProperties lp = connectivity.getLinkProperties(n); if (lp != null) for (android.net.LinkAddress la : lp.getLinkAddresses()) { InetAddress a = la.getAddress(); if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) return a.getHostAddress(); } } } catch (Exception ignored) {}
        try { Enumeration<NetworkInterface> it = NetworkInterface.getNetworkInterfaces(); while (it.hasMoreElements()) { NetworkInterface ni = it.nextElement(); String n = ni.getName().toLowerCase(); if (!(n.contains("ap") || n.contains("swlan") || n.equals("wlan0"))) continue; for (InetAddress a : Collections.list(ni.getInetAddresses())) if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) return a.getHostAddress(); } } catch (Exception ignored) {}
        return "";
    }
    public boolean isConnected() { return connected; }
    public void disconnect() { stop(); status("Native QuickDrop connection disconnected."); }
    private void closePeer() { try { if (peerSocket != null) peerSocket.close(); } catch (Exception ignored) {} peerSocket=null; peerIn=null; peerOut=null; }
    public void stop() {
        closed=true; connected=false; closePeer();
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (reservation != null) { try { reservation.close(); } catch (Exception ignored) {} reservation=null; }
        clearNetworkRequest(true); hosting = false; stopConnectionService(); main.post(() -> listener.onConnected(false,"Not connected"));
    }
    private void startConnectionService() {
        try { context.startForegroundService(new android.content.Intent(context, NativeConnectionService.class).setAction(NativeConnectionService.ACTION_START)); }
        catch (Exception e) { status("Could not start the background connection service: " + e.getMessage()); }
    }
    private void stopConnectionService() {
        try { context.startService(new android.content.Intent(context, NativeConnectionService.class).setAction(NativeConnectionService.ACTION_STOP)); }
        catch (Exception ignored) {}
    }
    private void clearNetworkRequest(boolean unbind) {
        if (networkCallback != null) { try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {} networkCallback=null; }
        if (unbind) { try { connectivity.bindProcessToNetwork(null); } catch (Exception ignored) {} }
    }
    private void status(String message) { main.post(() -> listener.onStatus(message)); }
}
