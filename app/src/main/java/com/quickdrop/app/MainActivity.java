package com.quickdrop.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.DownloadListener;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.FrameLayout;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;
import android.content.ClipData;
import android.database.Cursor;
import android.provider.OpenableColumns;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.widget.EditText;
import android.app.AlertDialog;

import androidx.documentfile.provider.DocumentFile;

public class MainActivity extends Activity {
    private static final int PICK_FOLDER = 501;
    private static final int NOTIFICATION_PERMISSION = 502;
    private static final int FILE_PICKER = 503;
    private static final int WIFI_DIRECT_PICKER = 505;
    private static final int WIFI_PERMISSION = 506;
    private static final int WIFI_FILE_PICKER = 507;
    private android.webkit.ValueCallback<Uri[]> fileCallback;
    private final Handler handler = new Handler();
    private SharedPreferences prefs;
    private LinearLayout root;
    private FrameLayout shell, drawer, drawerPanel;
    private View drawerScrim;
    private boolean drawerOpen = false;
    private TextView status, folderLabel, shareLink, errorText;
    private Button startStop, openPanel, wifiSendButton, wifiReceiveButton, menuButton;
    private TextView wifiCardTitle, wifiDirectStatus, selectedFilesLabel;
    private Button chooseWifiFilesButton;
    private ProgressBar wifiProgress;
    private final ArrayList<Uri> wifiSelectedFiles = new ArrayList<>();
    private LinearLayout wifiDeviceCard;
    private LogoDropView logoAnimation;
    private String wifiMode = "";
    private boolean wifiPickerOpened = false;
    private WifiDirectController wifiDirect;
    private CheckBox keepRunning;
    private WebView webView;
    private boolean panelVisible = false;
    private NativeHotspotController nativeHotspot;
    private TextView nativeStatus, nativeHostDetails;
    private Button createNativeButton, connectNativeButton, disconnectNativeButton, nativePickFilesButton, nativeQrButton;
    private static final int NATIVE_FILE_PICKER = 509;
    private static final int NATIVE_QR_SCAN = 510;
    private String nativeQrPayload = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("quickdrop_settings", MODE_PRIVATE);
        StorageBridge.initialize(this);
        buildUi();
        wifiDirect = new WifiDirectController(this, new WifiDirectController.Listener() {
            @Override public void onStatus(String message) {
                if (wifiMode.isEmpty()) return;
                if (message != null && (message.startsWith("Searching for nearby") || message.startsWith("No devices found yet"))) {
                    wifiDirectStatus.setText(wifiMode.equals("receive") ? "receiving ready" : "looking for devices");
                } else {
                    wifiDirectStatus.setText(message);
                }
                if (wifiProgress != null) {
                    boolean busy = message != null && (message.startsWith("Searching") || message.startsWith("Invitation") || message.startsWith("Connecting") || message.startsWith("Sending") || message.startsWith("Receive error") || message.startsWith("Could not start receiving"));
                    wifiProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
                }
                if (message != null && (message.startsWith("Received ") || message.startsWith("Incoming Wi-Fi Direct transfer completed") || message.startsWith("Transfer completed"))) logoAnimation.setAnimating(false);
                if (message != null && message.equals("Transfer completed successfully.")) {
                    wifiSelectedFiles.clear();
                    if (selectedFilesLabel != null) selectedFilesLabel.setText("No files selected");
                }
            }
            @Override public void onPeers(java.util.List<android.net.wifi.p2p.WifiP2pDevice> peers) { showWifiDirectPeers(peers); }
            @Override public void onConnected(boolean connected) {
                if (connected) {
                    logoAnimation.setAnimating(false);
                    wifiDirectStatus.setText(wifiMode.equals("receive") ? "Connected · receiving ready" : (wifiMode.equals("send") ? "Connected · Sending selected files" : "Connected · This phone is ready to receive"));
                    if (wifiMode.equals("send") && !wifiSelectedFiles.isEmpty()) wifiDirect.sendFiles(new ArrayList<>(wifiSelectedFiles));
                } else {
                    wifiPickerOpened = false;
                    if (!wifiMode.isEmpty()) logoAnimation.setAnimating(true);
                }
            }
        });
        nativeHotspot = new NativeHotspotController(this, new NativeHotspotController.Listener() {
            @Override public void onStatus(String message) { if (nativeStatus != null) nativeStatus.setText(message); }
            @Override public void onHostDetails(String ssid, String password, String address, int port) {
                nativeQrPayload = "QDHOTSPOT|2|" + ssid + "|" + (password == null ? "" : password) + "|" + address + "|" + port;
                if (nativeHostDetails != null) { nativeHostDetails.setText("Hotspot name: " + ssid + "\nPassword: " + (password == null || password.isEmpty() ? "Not required" : password) + "\nSender address: " + address + ":" + port + "\nShow the connection QR code to the receiving phone."); nativeHostDetails.setVisibility(View.VISIBLE); }
                if (nativeQrButton != null) nativeQrButton.setVisibility(View.VISIBLE);
            }
            @Override public void onConnected(boolean connected, String message) {
                if (nativeStatus != null) nativeStatus.setText(message);
                if (disconnectNativeButton != null) disconnectNativeButton.setVisibility(connected || nativeHostDetails != null && nativeHostDetails.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
                if (nativePickFilesButton != null) nativePickFilesButton.setEnabled(connected);
            }
            @Override public void onFileReceived(File file) {
                Toast.makeText(MainActivity.this, "Received: " + file.getName(), Toast.LENGTH_LONG).show();
            }
        });
        refreshUi();
        handleIncomingShare(getIntent());
        handler.post(poll);
    }

    private void buildUi() {
        shell = new FrameLayout(this);
        shell.setBackgroundColor(0xFFF4F7FB);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(24));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(this);
        title.setText("QuickDrop"); title.setTextSize(27); title.setTextColor(0xFF102A43); title.setTypeface(null, 1);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        menuButton = new Button(this); menuButton.setText("☰"); menuButton.setTextSize(22); menuButton.setAllCaps(false);
        menuButton.setTextColor(0xFF102A43); menuButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        menuButton.setBackgroundColor(android.graphics.Color.TRANSPARENT); menuButton.setMinWidth(0); menuButton.setMinHeight(0);
        toolbar.addView(menuButton, params(dp(52), dp(48)));
        root.addView(toolbar, params(-1, -2));
        menuButton.setOnClickListener(v -> toggleDrawer(true));

        TextView subtitle = new TextView(this);
        subtitle.setText("Share files between your phone and nearby devices.");
        subtitle.setTextSize(14); subtitle.setTextColor(0xFF526477);
        LinearLayout.LayoutParams subLp = params(-1, -2); subLp.topMargin = dp(4); root.addView(subtitle, subLp);

        status = new TextView(this);
        status.setText("Server is stopped"); status.setTextSize(16); status.setTextColor(0xFF8A3B12);
        status.setPadding(dp(14), dp(14), dp(14), dp(14)); status.setBackgroundColor(0xFFFFF0DF);
        LinearLayout.LayoutParams statusLp = params(-1, -2); statusLp.topMargin = dp(20); root.addView(status, statusLp);

        startStop = button("Start sharing");
        LinearLayout.LayoutParams startLp = params(-1, dp(54)); startLp.topMargin = dp(14); root.addView(startStop, startLp);
        startStop.setOnClickListener(v -> { if (prefs.getBoolean("server_running", false)) stopServer(); else startServer(); });

        keepRunning = new CheckBox(this);
        keepRunning.setText("Keep sharing after leaving QuickDrop"); keepRunning.setTextColor(0xFF263746); keepRunning.setTextSize(14);
        keepRunning.setChecked(prefs.getBoolean("keep_running", false));
        LinearLayout.LayoutParams keepLp = params(-1, -2); keepLp.topMargin = dp(15); root.addView(keepRunning, keepLp);
        keepRunning.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean("keep_running", checked).apply());
        TextView note = new TextView(this);
        note.setText("When this is off, QuickDrop stops if you swipe the app away from Recents. When it is on, sharing continues in the background until you stop it.");
        note.setTextColor(0xFF68798A); note.setTextSize(12);
        LinearLayout.LayoutParams noteLp = params(-1, -2); noteLp.topMargin = dp(2); root.addView(note, noteLp);

        shareLink = new TextView(this); shareLink.setTextSize(13); shareLink.setTextColor(0xFF1456A0); shareLink.setTextIsSelectable(true);
        LinearLayout.LayoutParams linkLp = params(-1, -2); linkLp.topMargin = dp(18); root.addView(shareLink, linkLp);

        TextView wifiTitle = sectionTitle("Wi-Fi Direct (Android to Android)");
        LinearLayout.LayoutParams wifiTitleLp = params(-1, -2); wifiTitleLp.topMargin = dp(24); root.addView(wifiTitle, wifiTitleLp);
        TextView wifiHint = new TextView(this);
        wifiHint.setText("Send directly to another Android phone, or keep this phone ready to receive.");
        wifiHint.setTextSize(13); wifiHint.setTextColor(0xFF526477);
        LinearLayout.LayoutParams wifiHintLp = params(-1, -2); wifiHintLp.topMargin = dp(5); root.addView(wifiHint, wifiHintLp);

        // File selection and live activity indicator sit above the Send/Receive controls.
        LinearLayout filePickerCard = new LinearLayout(this);
        filePickerCard.setOrientation(LinearLayout.VERTICAL);
        filePickerCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        android.graphics.drawable.GradientDrawable pickerBg = new android.graphics.drawable.GradientDrawable();
        pickerBg.setColor(0xFFFFFFFF); pickerBg.setCornerRadius(dp(14)); pickerBg.setStroke(dp(1), 0xFFE0E7EF);
        filePickerCard.setBackground(pickerBg);
        LinearLayout.LayoutParams pickerCardLp = params(-1, -2); pickerCardLp.topMargin = dp(12); root.addView(filePickerCard, pickerCardLp);
        TextView pickerTitle = sectionTitle("Choose files to send");
        filePickerCard.addView(pickerTitle, params(-1, -2));
        selectedFilesLabel = new TextView(this);
        selectedFilesLabel.setText("No files selected"); selectedFilesLabel.setTextSize(12); selectedFilesLabel.setTextColor(0xFF68798A);
        LinearLayout.LayoutParams selectedLp = params(-1, -2); selectedLp.topMargin = dp(4); filePickerCard.addView(selectedFilesLabel, selectedLp);
        chooseWifiFilesButton = button("Choose files");
        LinearLayout.LayoutParams chooseLp = params(-1, dp(44)); chooseLp.topMargin = dp(8); filePickerCard.addView(chooseWifiFilesButton, chooseLp);
        chooseWifiFilesButton.setOnClickListener(v -> chooseFilesForWifiDirect());
        wifiProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        wifiProgress.setIndeterminate(true); wifiProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = params(-1, dp(4)); progressLp.topMargin = dp(10); filePickerCard.addView(wifiProgress, progressLp);

        LinearLayout wifiActions = new LinearLayout(this);
        wifiActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = params(-1, dp(50)); actionsLp.topMargin = dp(12); root.addView(wifiActions, actionsLp);
        wifiSendButton = button("Send");
        wifiReceiveButton = button("Receive");
        LinearLayout.LayoutParams halfLeft = new LinearLayout.LayoutParams(0, -1, 1f); halfLeft.rightMargin = dp(5);
        LinearLayout.LayoutParams halfRight = new LinearLayout.LayoutParams(0, -1, 1f); halfRight.leftMargin = dp(5);
        wifiActions.addView(wifiSendButton, halfLeft); wifiActions.addView(wifiReceiveButton, halfRight);
        wifiSendButton.setOnClickListener(v -> {
            if (!wifiSelectedFiles.isEmpty() && wifiDirect != null && wifiDirect.isConnected()) wifiDirect.sendFiles(new ArrayList<>(wifiSelectedFiles));
            else startWifiMode("send");
        });
        wifiReceiveButton.setOnClickListener(v -> startWifiMode("receive"));

        wifiCardTitle = sectionTitle("Choose Send or Receive");
        LinearLayout.LayoutParams cardTitleLp = params(-1, -2); cardTitleLp.topMargin = dp(16); root.addView(wifiCardTitle, cardTitleLp);
        wifiDeviceCard = new LinearLayout(this);
        wifiDeviceCard.setOrientation(LinearLayout.VERTICAL);
        wifiDeviceCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(0xFFFFFFFF); cardBg.setCornerRadius(dp(14)); cardBg.setStroke(dp(1), 0xFFE0E7EF);
        wifiDeviceCard.setBackground(cardBg);
        LinearLayout.LayoutParams deviceCardLp = params(-1, -2); deviceCardLp.topMargin = dp(7); root.addView(wifiDeviceCard, deviceCardLp);
        wifiDirectStatus = new TextView(this);
        wifiDirectStatus.setText("Choose a sharing mode to begin"); wifiDirectStatus.setTextSize(13); wifiDirectStatus.setTextColor(0xFF526477);
        wifiDeviceCard.addView(wifiDirectStatus, params(-1, -2));
        logoAnimation = new LogoDropView(this);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(112), dp(112)); logoLp.gravity = Gravity.CENTER_HORIZONTAL; logoLp.topMargin = dp(8);
        wifiDeviceCard.addView(logoAnimation, logoLp);
        TextView wifiDirectFootnote = new TextView(this);
        wifiDirectFootnote.setText("Keep both phones unlocked with QuickDrop open while connecting."); wifiDirectFootnote.setTextSize(11); wifiDirectFootnote.setTextColor(0xFF8190A0);
        LinearLayout.LayoutParams footLp = params(-1, -2); footLp.topMargin = dp(4); wifiDeviceCard.addView(wifiDirectFootnote, footLp);

        // Separate native hotspot mode. Wi-Fi Direct above remains untouched.
        TextView nativeTitle = sectionTitle("Native Hotspot Sharing (QuickDrop to QuickDrop)");
        LinearLayout.LayoutParams nativeTitleLp = params(-1, -2); nativeTitleLp.topMargin = dp(26); root.addView(nativeTitle, nativeTitleLp);
        TextView nativeHint = new TextView(this);
        nativeHint.setText("Connect two Android phones directly over a private local hotspot. This is separate from Browser Sharing and Wi-Fi Direct.");
        nativeHint.setTextSize(13); nativeHint.setTextColor(0xFF526477);
        LinearLayout.LayoutParams nativeHintLp = params(-1, -2); nativeHintLp.topMargin = dp(5); root.addView(nativeHint, nativeHintLp);
        LinearLayout nativeCard = new LinearLayout(this); nativeCard.setOrientation(LinearLayout.VERTICAL); nativeCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        android.graphics.drawable.GradientDrawable nativeBg = new android.graphics.drawable.GradientDrawable(); nativeBg.setColor(0xFFFFFFFF); nativeBg.setCornerRadius(dp(14)); nativeBg.setStroke(dp(1), 0xFFE0E7EF); nativeCard.setBackground(nativeBg);
        LinearLayout.LayoutParams nativeCardLp = params(-1, -2); nativeCardLp.topMargin = dp(9); root.addView(nativeCard, nativeCardLp);
        nativeStatus = new TextView(this); nativeStatus.setText("Not connected · Choose Create Connection or Connect to Sender"); nativeStatus.setTextSize(13); nativeStatus.setTextColor(0xFF526477); nativeCard.addView(nativeStatus, params(-1, -2));
        nativeHostDetails = new TextView(this); nativeHostDetails.setTextSize(12); nativeHostDetails.setTextColor(0xFF18324A); nativeHostDetails.setTextIsSelectable(true); nativeHostDetails.setVisibility(View.GONE);
        LinearLayout.LayoutParams hostDetailsLp = params(-1, -2); hostDetailsLp.topMargin = dp(8); nativeCard.addView(nativeHostDetails, hostDetailsLp);
        createNativeButton = button("Create Connection"); LinearLayout.LayoutParams createLp = params(-1, dp(46)); createLp.topMargin = dp(10); nativeCard.addView(createNativeButton, createLp);
        connectNativeButton = button("Connect to Sender"); LinearLayout.LayoutParams connectLp = params(-1, dp(46)); connectLp.topMargin = dp(8); nativeCard.addView(connectNativeButton, connectLp);
        disconnectNativeButton = button("Disconnect"); LinearLayout.LayoutParams disconnectLp = params(-1, dp(42)); disconnectLp.topMargin = dp(8); nativeCard.addView(disconnectNativeButton, disconnectLp); disconnectNativeButton.setVisibility(View.GONE);
        nativeQrButton = button("Show Connection QR Code"); LinearLayout.LayoutParams qrLp = params(-1, dp(44)); qrLp.topMargin = dp(8); nativeCard.addView(nativeQrButton, qrLp); nativeQrButton.setVisibility(View.GONE);
        Button scanNativeQrButton = button("Scan Sender QR Code"); LinearLayout.LayoutParams scanQrLp = params(-1, dp(44)); scanQrLp.topMargin = dp(8); nativeCard.addView(scanNativeQrButton, scanQrLp);
        scanNativeQrButton.setOnClickListener(v -> scanNativeQrCode());
        nativePickFilesButton = button("Choose Files to Send"); LinearLayout.LayoutParams nativePickLp = params(-1, dp(46)); nativePickLp.topMargin = dp(8); nativeCard.addView(nativePickFilesButton, nativePickLp); nativePickFilesButton.setEnabled(false);
        createNativeButton.setOnClickListener(v -> requestNativePermissionThenHost());
        connectNativeButton.setOnClickListener(v -> showNativeConnectDialog());
        nativeQrButton.setOnClickListener(v -> showNativeQrCode());
        nativePickFilesButton.setOnClickListener(v -> pickNativeFiles());
        disconnectNativeButton.setOnClickListener(v -> { if (nativeHotspot != null) nativeHotspot.disconnect(); if (nativeHostDetails != null) nativeHostDetails.setVisibility(View.GONE); });

        errorText = new TextView(this); errorText.setTextColor(0xFFB42318); errorText.setTextSize(12);
        LinearLayout.LayoutParams errLp = params(-1, -2); errLp.topMargin = dp(10); root.addView(errorText, errLp);

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true); webView.getSettings().setDomStorageEnabled(true); webView.getSettings().setAllowFileAccess(false);
        if (Build.VERSION.SDK_INT >= 26) webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, android.webkit.ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null); fileCallback = callback;
                try { startActivityForResult(params.createIntent(), FILE_PICKER); return true; }
                catch (Exception e) { fileCallback = null; Toast.makeText(MainActivity.this, "Unable to open file picker", Toast.LENGTH_SHORT).show(); return false; }
            }
        });
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                String fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setTitle(fileName).setDescription("Downloading with QuickDrop").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setAllowedOverMetered(true).setAllowedOverRoaming(true);
                if (mimeType != null) request.setMimeType(mimeType);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
                Toast.makeText(MainActivity.this, "Download started", Toast.LENGTH_SHORT).show();
            } catch (Exception e) { Toast.makeText(MainActivity.this, "Download could not start: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        });

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.addView(root);
        shell.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        // Full-screen drawer overlay: the scrim closes on outside taps and blocks
        // accidental interaction with the controls underneath it.
        drawer = new FrameLayout(this);
        drawer.setVisibility(View.GONE);
        drawer.setClickable(true);
        drawer.setFocusable(true);
        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(0x66000000);
        drawerScrim.setOnClickListener(v -> toggleDrawer(false));
        drawer.addView(drawerScrim, new FrameLayout.LayoutParams(-1, -1));

        drawerPanel = new FrameLayout(this);
        drawerPanel.setBackgroundColor(0xFFFFFFFF);
        drawerPanel.setElevation(dp(12));
        drawerPanel.setClickable(true);
        drawerPanel.setFocusable(true);
        LinearLayout drawerContent = new LinearLayout(this);
        drawerContent.setOrientation(LinearLayout.VERTICAL);
        drawerContent.setPadding(dp(18), dp(12), dp(18), dp(18));
        LinearLayout drawerTop = new LinearLayout(this);
        drawerTop.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("‹"); back.setTextSize(30); back.setAllCaps(false);
        back.setTextColor(0xFF263746); back.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        back.setMinWidth(0); back.setMinHeight(0); back.setPadding(0, 0, 0, 0);
        drawerTop.addView(back, params(dp(42), dp(42)));
        back.setOnClickListener(v -> toggleDrawer(false));
        drawerContent.addView(drawerTop, params(-1, dp(48)));
        TextView storageTitle = sectionTitle("Storage folder");
        LinearLayout.LayoutParams secLp = params(-1, -2); secLp.topMargin = dp(18); drawerContent.addView(storageTitle, secLp);
        folderLabel = new TextView(this); folderLabel.setTextSize(13); folderLabel.setTextColor(0xFF526477);
        LinearLayout.LayoutParams folderLp = params(-1, -2); folderLp.topMargin = dp(6); drawerContent.addView(folderLabel, folderLp);
        Button chooseFolder = button("Choose a folder");
        LinearLayout.LayoutParams drawerChooseLp = params(-1, dp(46)); drawerChooseLp.topMargin = dp(9); drawerContent.addView(chooseFolder, drawerChooseLp);
        chooseFolder.setOnClickListener(v -> { toggleDrawer(false); chooseFolder(); });
        openPanel = button("Open control panel");
        LinearLayout.LayoutParams panelLp = params(-1, dp(48)); panelLp.topMargin = dp(12); drawerContent.addView(openPanel, panelLp);
        openPanel.setOnClickListener(v -> { toggleDrawer(false); openControlPanel(); });
        drawerPanel.addView(drawerContent, new FrameLayout.LayoutParams(-1, -1));
        int drawerWidth = Math.min(dp(320), (int)(getResources().getDisplayMetrics().widthPixels * 0.86f));
        FrameLayout.LayoutParams panelFrameLp = new FrameLayout.LayoutParams(drawerWidth, -1, Gravity.RIGHT | Gravity.TOP);
        drawerPanel.setTranslationX(drawerWidth);
        drawer.addView(drawerPanel, panelFrameLp);
        shell.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
        setContentView(shell);
    }

    private void toggleDrawer(boolean show) {
        if (drawer == null || drawerOpen == show) return;
        drawerOpen = show;
        if (show) drawer.setVisibility(View.VISIBLE);
        float target = show ? 0f : drawerPanel.getWidth();
        if (drawerPanel.getWidth() == 0) drawerPanel.post(() -> animateDrawer(show));
        else animateDrawer(show);
    }

    private void animateDrawer(boolean show) {
        drawerPanel.animate().translationX(show ? 0f : drawerPanel.getWidth())
                .setDuration(220).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> { if (!show && !drawerOpen) drawer.setVisibility(View.GONE); })
                .start();
    }

    private void handleIncomingShare(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        ArrayList<Uri> incoming = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null && intent.getData() != null) uri = intent.getData();
            if (uri != null) incoming.add(uri);
        } else {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) incoming.addAll(list);
        }
        if (incoming.isEmpty()) { Toast.makeText(this, "No shared file was received.", Toast.LENGTH_LONG).show(); return; }
        if (nativeHotspot != null && nativeHotspot.isConnected()) {
            nativeHotspot.sendFiles(incoming);
            Toast.makeText(this, incoming.size() + " file(s) queued for QuickDrop transfer.", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            File folder = new File(getFilesDir(), "QuickDrop"); if (!folder.exists()) folder.mkdirs();
            int saved = 0;
            for (Uri uri : incoming) {
                try {
                    String name = incomingName(uri);
                    File target = uniqueIncomingFile(folder, name);
                    try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(target)) {
                        if (in == null) continue;
                        byte[] buffer = new byte[64 * 1024]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    }
                    StorageBridge.exportFile(folder.getAbsolutePath(), target.getName()); saved++;
                } catch (Exception e) { android.util.Log.e("QuickDrop", "Could not import shared file", e); }
            }
            final int count = saved;
            runOnUiThread(() -> Toast.makeText(this, count + " file(s) saved to QuickDrop storage" + (StorageBridge.getTreeUri().isEmpty() ? ". Choose a shared folder from the menu to mirror them there." : " and copied to your shared folder."), Toast.LENGTH_LONG).show());
        }, "QuickDrop-Share-Import").start();
    }

    private String incomingName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (ix >= 0) return safeIncomingName(c.getString(ix)); }
        } catch (Exception ignored) { }
        return safeIncomingName(uri.getLastPathSegment());
    }
    private String safeIncomingName(String name) {
        String v = name == null ? "shared-file" : name.replaceAll("[\\/:*?\"<>|]", "_").trim();
        return v.isEmpty() ? "shared-file" : v;
    }
    private File uniqueIncomingFile(File folder, String name) {
        File f = new File(folder, name); if (!f.exists()) return f;
        String base = name, ext = ""; int dot = name.lastIndexOf('.'); if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        for (int i = 1; i < 10000; i++) { f = new File(folder, base + " (" + i + ")" + ext); if (!f.exists()) return f; }
        return new File(folder, System.currentTimeMillis() + "-" + name);
    }

    private void startServer() {
        prefs.edit().remove("server_error").apply();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        }
        Intent intent = new Intent(this, ServerService.class).setAction(ServerService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        status.setText("Starting QuickDrop…");
    }

    private void stopServer() {
        startService(new Intent(this, ServerService.class).setAction(ServerService.ACTION_STOP));
        panelVisible = false;
        setContentView(shell);
        refreshUi();
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_FOLDER);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == com.journeyapps.barcodescanner.IntentIntegrator.REQUEST_CODE) {
            com.google.zxing.integration.android.IntentResult scan = com.journeyapps.barcodescanner.IntentIntegrator.parseActivityResult(resultCode, data);
            if (scan != null) {
                if (scan.getContents() == null) Toast.makeText(this, "QR scan cancelled.", Toast.LENGTH_SHORT).show();
                else connectFromNativeQr(scan.getContents());
            }
            return;
        }
        if (requestCode == NATIVE_FILE_PICKER && resultCode == RESULT_OK && data != null) {
            ArrayList<Uri> selected = new ArrayList<>();
            if (data.getClipData() != null) { for (int i = 0; i < data.getClipData().getItemCount(); i++) selected.add(data.getClipData().getItemAt(i).getUri()); }
            else if (data.getData() != null) selected.add(data.getData());
            if (!selected.isEmpty() && nativeHotspot != null) nativeHotspot.sendFiles(selected);
            return;
        }
        if (requestCode == FILE_PICKER) {
            if (fileCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data.getData() != null) results = new Uri[]{data.getData()};
                }
                fileCallback.onReceiveValue(results);
                fileCallback = null;
            }
            return;
        }
        if (requestCode == WIFI_FILE_PICKER && resultCode == RESULT_OK && data != null) {
            wifiSelectedFiles.clear();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) wifiSelectedFiles.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) wifiSelectedFiles.add(data.getData());
            selectedFilesLabel.setText(wifiSelectedFiles.isEmpty() ? "No files selected" : wifiSelectedFiles.size() + " file(s) selected · Tap Send after connecting");
            Toast.makeText(this, wifiSelectedFiles.size() + " file(s) selected", Toast.LENGTH_SHORT).show();
            return;
        }
        if (requestCode == WIFI_DIRECT_PICKER && resultCode == RESULT_OK && data != null) {
            java.util.ArrayList<Uri> selected = new java.util.ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) selected.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) selected.add(data.getData());
            if (wifiDirect != null && !selected.isEmpty()) wifiDirect.sendFiles(selected);
            return;
        }
        if (requestCode == PICK_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception ignored) { }
            StorageBridge.setTreeUri(uri.toString());
            Toast.makeText(this, "Storage folder selected. Restart sharing to apply it.", Toast.LENGTH_LONG).show();
            refreshUi();
        }
    }

    private void startWifiMode(String mode) {
        wifiMode = mode;
        wifiPickerOpened = false;
        if (wifiProgress != null) wifiProgress.setVisibility(View.VISIBLE);
        wifiCardTitle.setText(mode.equals("receive") ? "Sharing to you" : "Sending to nearby devices");
        wifiDirectStatus.setText(mode.equals("receive") ? "receiving ready" : "looking for devices");
        wifiDeviceCard.removeViews(1, Math.max(0, wifiDeviceCard.getChildCount() - 1));
        wifiDeviceCard.addView(logoAnimation, new LinearLayout.LayoutParams(dp(112), dp(112)));
        logoAnimation.setAnimating(true);
        wifiSendButton.setAlpha(mode.equals("send") ? 1f : 0.72f);
        wifiReceiveButton.setAlpha(mode.equals("receive") ? 1f : 0.72f);
        if (wifiDirect != null && wifiDirect.isConnected()) {
            if (mode.equals("receive")) wifiDirectStatus.setText("receiving ready");
            else if (wifiDirect.canSend()) wifiDirectStatus.setText(wifiSelectedFiles.isEmpty() ? "Connected · Choose files, then tap Send" : "Connected · Ready to send selected files");
            return;
        }
        ensureWifiDirectPermission();
    }

    private void ensureWifiDirectPermission() {
        String permission = Build.VERSION.SDK_INT >= 33 ? "android.permission.NEARBY_WIFI_DEVICES" : Manifest.permission.ACCESS_FINE_LOCATION;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission}, WIFI_PERMISSION);
            return;
        }
        if (wifiDirect != null) wifiDirect.discover(wifiMode.equals("receive"));
    }

    private void requestNativePermissionThenHost() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.NEARBY_WIFI_DEVICES"}, 508); return;
        }
        if (nativeHostDetails != null) { nativeHostDetails.setVisibility(View.VISIBLE); nativeHostDetails.setText("Starting private hotspot…"); }
        if (nativeHotspot != null) nativeHotspot.createConnection();
    }

    private void pickNativeFiles() {
        if (nativeHotspot == null || !nativeHotspot.isConnected()) { Toast.makeText(this, "Connect to another QuickDrop phone first.", Toast.LENGTH_SHORT).show(); return; }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*"); intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, NATIVE_FILE_PICKER); }
        catch (Exception e) { Toast.makeText(this, "Unable to open file picker.", Toast.LENGTH_SHORT).show(); }
    }

    private void scanNativeQrCode() {
        try {
            com.google.zxing.integration.android.IntentIntegrator scanner = new com.google.zxing.integration.android.IntentIntegrator(this);
            scanner.setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE);
            scanner.setPrompt("Scan the QR code shown on the sender's QuickDrop app");
            scanner.setBeepEnabled(false);
            scanner.setOrientationLocked(false);
            scanner.initiateScan();
        } catch (Exception e) {
            Toast.makeText(this, "Unable to open QR scanner: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void connectFromNativeQr(String payload) {
        try {
            String[] parts = payload.split("\\|", -1);
            if (parts.length != 6 || !"QDHOTSPOT".equals(parts[0]) || !"2".equals(parts[1]) || parts[2].isEmpty() || parts[4].isEmpty())
                throw new IllegalArgumentException("This is not a valid QuickDrop connection QR code.");
            int port = Integer.parseInt(parts[5]);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid sender port.");
            String details = "Sender: " + parts[2] + "\nAddress: " + parts[4] + ":" + port + "\n\nAndroid will now ask you to approve joining this private hotspot.";
            new AlertDialog.Builder(this).setTitle("Connect to QuickDrop sender?").setMessage(details)
                    .setNegativeButton("Cancel", (d, w) -> {})
                    .setPositiveButton("Connect", (d, w) -> {
                        if (nativeHostDetails != null) nativeHostDetails.setVisibility(View.GONE);
                        if (nativeHotspot != null) nativeHotspot.connectToSender(parts[2], parts[3], parts[4], port);
                    }).show();
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage() == null ? "Could not read this QR code." : e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showNativeQrCode() {
        if (nativeQrPayload == null || nativeQrPayload.isEmpty()) return;
        try {
            com.google.zxing.common.BitMatrix matrix = new com.google.zxing.qrcode.QRCodeWriter().encode(nativeQrPayload, com.google.zxing.BarcodeFormat.QR_CODE, 720, 720);
            Bitmap bitmap = Bitmap.createBitmap(720, 720, Bitmap.Config.RGB_565);
            for (int y = 0; y < 720; y++) for (int x = 0; x < 720; x++) bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            ImageView image = new ImageView(this); image.setImageBitmap(bitmap); image.setPadding(dp(12), dp(12), dp(12), dp(12)); image.setAdjustViewBounds(true);
            new AlertDialog.Builder(this).setTitle("QuickDrop Connection QR").setMessage("Scan this code to read the sender hotspot name and password, then enter them under Connect to Sender.").setView(image).setPositiveButton("Close", (d,w) -> {}).show();
        } catch (Exception e) { Toast.makeText(this, "Could not generate connection QR code: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void showNativeConnectDialog() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText ssid = new EditText(this); ssid.setSingleLine(true); ssid.setHint("Sender hotspot name (SSID)"); form.addView(ssid, params(-1, dp(52)));
        EditText password = new EditText(this); password.setSingleLine(true); password.setHint("Hotspot password"); password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD); form.addView(password, params(-1, dp(52)));
        new AlertDialog.Builder(this).setTitle("Connect to a QuickDrop sender").setMessage("Enter the hotspot name and password displayed on the sender phone. Android will ask you to approve the Wi-Fi connection.").setView(form).setNegativeButton("Cancel", (d, w) -> {}).setPositiveButton("Connect", (d, w) -> {
            if (nativeHostDetails != null) nativeHostDetails.setVisibility(View.GONE);
            if (nativeHotspot != null) nativeHotspot.connectToSender(ssid.getText().toString().trim(), password.getText().toString());
        }).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 508) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) requestNativePermissionThenHost();
            else if (nativeStatus != null) nativeStatus.setText("Nearby Wi-Fi permission is required for native hotspot sharing. Browser Sharing and Wi-Fi Direct are unaffected.");
            return;
        }
        if (requestCode == WIFI_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) { if (wifiDirect != null) wifiDirect.discover(wifiMode.equals("receive")); }
            else wifiDirectStatus.setText("Nearby devices permission is needed for Wi-Fi Direct. Browser sharing still works without it.");
        }
    }

    private void showWifiDirectPeers(java.util.List<android.net.wifi.p2p.WifiP2pDevice> peers) {
        if (wifiDeviceCard == null || wifiMode.isEmpty()) return;
        // Keep the status and animated logo at the top; replace only device rows.
        while (wifiDeviceCard.getChildCount() > 2) wifiDeviceCard.removeViewAt(2);
        if (peers == null || peers.isEmpty()) {
            logoAnimation.setAnimating(true);
            wifiDirectStatus.setText(wifiMode.equals("receive") ? "receiving ready" : "looking for devices");
            return;
        }
        logoAnimation.setAnimating(false);
        if (wifiProgress != null) wifiProgress.setVisibility(View.GONE);
        wifiDirectStatus.setText(wifiMode.equals("receive") ? "Nearby device detected · Ready to receive" : "Select a nearby device to connect");
        for (android.net.wifi.p2p.WifiP2pDevice device : peers) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
            rowBg.setColor(0xFFF4F8FD); rowBg.setCornerRadius(dp(10)); row.setBackground(rowBg);
            LinearLayout.LayoutParams rowLp = params(-1, -2); rowLp.topMargin = dp(7); wifiDeviceCard.addView(row, rowLp);
            TextView deviceIcon = new TextView(this); deviceIcon.setText("▣"); deviceIcon.setTextSize(25); deviceIcon.setTextColor(0xFF2563EB);
            row.addView(deviceIcon, params(dp(38), -2));
            LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView name = new TextView(this); name.setText(device.deviceName == null || device.deviceName.trim().isEmpty() ? "Nearby Android device" : device.deviceName);
            name.setTextSize(14); name.setTypeface(null, 1); name.setTextColor(0xFF18324A); labels.addView(name);
            TextView state = new TextView(this); state.setText(device.status == android.net.wifi.p2p.WifiP2pDevice.AVAILABLE ? "Available · QuickDrop nearby" : "Nearby · Tap to connect");
            state.setTextSize(11); state.setTextColor(0xFF68798A); labels.addView(state);
            TextView action = new TextView(this); action.setText(wifiMode.equals("receive") ? "Receive" : "Connect"); action.setTextSize(12); action.setTypeface(null, 1); action.setTextColor(0xFF2563EB);
            row.addView(action, params(-2, -2));
            row.setOnClickListener(v -> {
                logoAnimation.setAnimating(false);
                wifiDirectStatus.setText("Connecting to " + name.getText() + "…");
                wifiDirect.connect(device, wifiMode.equals("receive"));
            });
        }
    }

    private void chooseFilesForWifiDirect() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE); picker.setType("*/*");
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(picker, WIFI_FILE_PICKER); }
        catch (Exception e) { Toast.makeText(this, "No file picker is available.", Toast.LENGTH_SHORT).show(); }
    }

    private void openControlPanel() {
        String url = prefs.getString("admin_url", "");
        if (url.isEmpty() || !prefs.getBoolean("server_running", false)) {
            Toast.makeText(this, "Start sharing first.", Toast.LENGTH_SHORT).show(); return;
        }
        panelVisible = true;
        webView.loadUrl(url);
        setContentView(webView);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); handleIncomingShare(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        if (wifiDirect != null) wifiDirect.register();
    }

    @Override protected void onPause() {
        if (wifiDirect != null) wifiDirect.unregister();
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (drawerOpen) { toggleDrawer(false); return; }
        if (panelVisible) {
            panelVisible = false;
            setContentView(shell);
            refreshUi();
        } else super.onBackPressed();
    }

    private void refreshUi() {
        boolean running = prefs.getBoolean("server_running", false);
        status.setText(running ? "●  QuickDrop is running" : "●  Server is stopped");
        status.setTextColor(running ? 0xFF176B43 : 0xFF8A3B12);
        status.setBackgroundColor(running ? 0xFFE2F5EA : 0xFFFFF0DF);
        startStop.setText(running ? "Stop sharing" : "Start sharing");
        shareLink.setText(running ? "Share link (tap and hold to copy):\n" + prefs.getString("share_url", "") : "");
        openPanel.setEnabled(running); openPanel.setAlpha(running ? 1f : 0.5f);
        String uri = StorageBridge.getTreeUri();
        if (uri == null || uri.isEmpty()) {
            folderLabel.setText("Default: QuickDrop's private app folder. Choose a folder to use another location.");
        } else {
            DocumentFile tree = DocumentFile.fromTreeUri(this, Uri.parse(uri));
            folderLabel.setText("Selected: " + (tree != null && tree.getName() != null ? tree.getName() : "Folder access granted") + "\nFiles are mirrored to this folder while QuickDrop runs.");
        }
        errorText.setText(prefs.getString("server_error", ""));
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refreshUi();
            handler.postDelayed(this, 1200);
        }
    };

    private Button button(String label) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF2563EB));
        return b;
    }
    private TextView sectionTitle(String text) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(17); v.setTypeface(null, 1); v.setTextColor(0xFF102A43); return v;
    }
    private LinearLayout.LayoutParams params(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onDestroy() {
        handler.removeCallbacks(poll);
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        if (wifiDirect != null) wifiDirect.close();
        if (nativeHotspot != null) nativeHotspot.stop();
        super.onDestroy();
    }
}
