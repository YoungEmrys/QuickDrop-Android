package com.quickdrop.app;

import android.Manifest;
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Separate native Android-to-Android local-hotspot connection. Does not use Wi-Fi Direct. */
public final class NativeHotspotController {
    public interface Listener {
        void onStatus(String message);
        void onHostDetails(String ssid, String password, String hostAddress, int port);
        void onConnected(boolean connected, String message);
    }

    private static final int PORT = 8989;
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ServerSocket serverSocket;
    private Socket peerSocket;
    private volatile boolean closed;
    private volatile boolean connected;
    private String hostAddress = "";

    public NativeHotspotController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void createConnection() {
        if (Build.VERSION.SDK_INT < 26) { status("Native hotspot sharing requires Android 8.0 or later."); return; }
        if (reservation != null) { status("Connection hotspot is already active."); return; }
        closed = false;
        status("Requesting a private QuickDrop hotspot…");
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation value) {
                    reservation = value;
                    try {
                        WifiManager.LocalOnlyHotspotReservation r = reservation;
                        android.net.wifi.WifiConfiguration config = r.getWifiConfiguration();
                        String ssid = config != null ? config.SSID : "QuickDrop";
                        if (ssid != null && ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) ssid = ssid.substring(1, ssid.length()-1);
                        String password = config != null ? config.preSharedKey : "";
                        if (password != null && password.length() >= 2 && password.startsWith("\"") && password.endsWith("\"")) password = password.substring(1, password.length()-1);
                        hostAddress = findHotspotAddress();
                        if (TextUtils.isEmpty(hostAddress)) hostAddress = "192.168.43.1";
                        startHostSocket();
                        final String finalSsid = ssid == null ? "QuickDrop" : ssid;
                        final String finalPassword = password == null ? "" : password;
                        main.post(() -> { listener.onHostDetails(finalSsid, finalPassword, hostAddress, PORT); listener.onConnected(false, "Hotspot ready · Waiting for another QuickDrop phone"); });
                    } catch (Exception e) { status("Hotspot started, but setup failed: " + e.getMessage()); }
                }
                @Override public void onStopped() { stop(); status("QuickDrop hotspot stopped by Android."); }
                @Override public void onFailed(int reason) { status("Android could not start the QuickDrop hotspot (code " + reason + "). Turn off any conflicting hotspot and try again."); }
            }, main);
        } catch (SecurityException e) { status("Nearby Wi-Fi permission is required to create the hotspot."); }
          catch (Exception e) { status("Could not create hotspot: " + e.getMessage()); }
    }

    private void startHostSocket() {
        io.execute(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                status("Hotspot ready · Waiting for receiver");
                while (!closed && !serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    socket.setSoTimeout(12000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    String hello = in.readLine();
                    if ("QDHOTSPOT:1".equals(hello)) {
                        out.println("QDHOTSPOT-OK:1");
                        peerSocket = socket; connected = true;
                        main.post(() -> listener.onConnected(true, "Connected securely to another QuickDrop phone. Ready for native file sharing."));
                        status("QuickDrop phone connected");
                        while (!closed && !socket.isClosed() && in.readLine() != null) { /* heartbeat */ }
                        connected = false; try { socket.close(); } catch (Exception ignored) {}
                        main.post(() -> listener.onConnected(false, "Peer disconnected · Hotspot is still ready"));
                    } else socket.close();
                }
            } catch (Exception e) { if (!closed) status("Connection listener stopped: " + e.getMessage()); }
        });
    }

    public void connectToSender(String ssid, String password) {
        if (Build.VERSION.SDK_INT < 29) { status("Automatic native connection requires Android 10 or later. Use Browser Sharing on this phone."); return; }
        if (TextUtils.isEmpty(ssid)) { status("Enter the sender's hotspot name (SSID)."); return; }
        if (networkCallback != null) { status("A sender connection request is already active."); return; }
        closed = false;
        status("Requesting Android permission to join " + ssid + "…");
        WifiNetworkSpecifier.Builder spec = new WifiNetworkSpecifier.Builder().setSsid(ssid);
        if (!TextUtils.isEmpty(password)) spec.setWpa2Passphrase(password);
        NetworkRequest request = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec.build()).build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                connectivity.bindProcessToNetwork(network);
                String gateway = gatewayFor(network);
                if (TextUtils.isEmpty(gateway)) { status("Joined hotspot, but could not identify the sender address."); return; }
                status("Connected to hotspot · Verifying QuickDrop on the other phone…");
                io.execute(() -> verifyPeer(gateway));
            }
            @Override public void onUnavailable() { status("Android could not join that hotspot. Check the name/password and try again."); clearNetworkRequest(false); }
            @Override public void onLost(Network network) { connected = false; main.post(() -> listener.onConnected(false, "Sender hotspot connection was lost.")); }
        };
        try { connectivity.requestNetwork(request, networkCallback); }
        catch (Exception e) { status("Could not request hotspot connection: " + e.getMessage()); clearNetworkRequest(false); }
    }

    private void verifyPeer(String gateway) {
        try {
            Socket socket = new Socket(); socket.connect(new java.net.InetSocketAddress(gateway, PORT), 8000); socket.setSoTimeout(8000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out.println("QDHOTSPOT:1");
            if (!"QDHOTSPOT-OK:1".equals(in.readLine())) throw new Exception("The device did not respond as a QuickDrop sender.");
            peerSocket = socket; connected = true;
            main.post(() -> listener.onConnected(true, "Connected securely to the sender. Ready for native file sharing."));
            String line; while (!closed && (line = in.readLine()) != null) { /* heartbeat */ }
        } catch (Exception e) { status("QuickDrop verification failed: " + e.getMessage()); }
    }

    private String gatewayFor(Network network) {
        try { LinkProperties lp = connectivity.getLinkProperties(network); if (lp != null) for (RouteInfo route : lp.getRoutes()) { InetAddress g = route.getGateway(); if (g instanceof Inet4Address && !g.isAnyLocalAddress()) return g.getHostAddress(); } } catch (Exception ignored) {}
        return "";
    }

    private String findHotspotAddress() {
        // Prefer the local-only Wi-Fi network's own interface address. This avoids
        // accidentally showing the phone's ordinary Wi-Fi/LAN address.
        try {
            for (Network n : connectivity.getAllNetworks()) {
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(n);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                LinkProperties lp = connectivity.getLinkProperties(n);
                if (lp != null) for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement(); String n = ni.getName().toLowerCase();
                if (!(n.contains("ap") || n.contains("swlan") || n.equals("wlan0"))) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) {}
        return "";
    }

    public boolean isConnected() { return connected; }
    public void disconnect() { stop(); status("Native QuickDrop connection disconnected."); }
    public void stop() {
        closed = true; connected = false;
        try { if (peerSocket != null) peerSocket.close(); } catch (Exception ignored) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (reservation != null) { try { reservation.close(); } catch (Exception ignored) {} reservation = null; }
        clearNetworkRequest(true);
        main.post(() -> listener.onConnected(false, "Not connected"));
    }
    private void clearNetworkRequest(boolean unbind) {
        if (networkCallback != null) { try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {} networkCallback = null; }
        if (unbind) { try { connectivity.bindProcessToNetwork(null); } catch (Exception ignored) {} }
    }
    private void status(String message) { main.post(() -> listener.onStatus(message)); }
}
