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
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

public class MainActivity extends Activity {
    private static final int PICK_FOLDER = 501;
    private static final int NOTIFICATION_PERMISSION = 502;
    private static final int FILE_PICKER = 503;
    private android.webkit.ValueCallback<Uri[]> fileCallback;
    private final Handler handler = new Handler();
    private SharedPreferences prefs;
    private LinearLayout root;
    private TextView status, folderLabel, shareLink, errorText;
    private Button startStop, openPanel;
    private CheckBox keepRunning;
    private WebView webView;
    private boolean panelVisible = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("quickdrop_settings", MODE_PRIVATE);
        StorageBridge.initialize(this);
        buildUi();
        refreshUi();
        handler.post(poll);
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(16));
        root.setBackgroundColor(0xFFF4F7FB);

        TextView title = new TextView(this);
        title.setText("QuickDrop"); title.setTextSize(30); title.setTextColor(0xFF102A43);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Share files between your phone and devices on the same Wi-Fi or hotspot.");
        subtitle.setTextSize(15); subtitle.setTextColor(0xFF526477);
        LinearLayout.LayoutParams subLp = params(-1, -2); subLp.topMargin = dp(5);
        root.addView(subtitle, subLp);

        status = new TextView(this);
        status.setText("Server is stopped"); status.setTextSize(16); status.setTextColor(0xFF8A3B12);
        status.setPadding(dp(14), dp(14), dp(14), dp(14)); status.setBackgroundColor(0xFFFFF0DF);
        LinearLayout.LayoutParams statusLp = params(-1, -2); statusLp.topMargin = dp(20);
        root.addView(status, statusLp);

        startStop = button("Start sharing");
        LinearLayout.LayoutParams startLp = params(-1, dp(52)); startLp.topMargin = dp(14);
        root.addView(startStop, startLp);
        startStop.setOnClickListener(v -> {
            if (prefs.getBoolean("server_running", false)) stopServer(); else startServer();
        });

        TextView storageTitle = sectionTitle("Storage folder");
        LinearLayout.LayoutParams secLp = params(-1, -2); secLp.topMargin = dp(22);
        root.addView(storageTitle, secLp);
        folderLabel = new TextView(this); folderLabel.setTextSize(13); folderLabel.setTextColor(0xFF526477);
        LinearLayout.LayoutParams folderLp = params(-1, -2); folderLp.topMargin = dp(6);
        root.addView(folderLabel, folderLp);
        Button chooseFolder = button("Choose a folder");
        LinearLayout.LayoutParams chooseLp = params(-1, dp(46)); chooseLp.topMargin = dp(9);
        root.addView(chooseFolder, chooseLp);
        chooseFolder.setOnClickListener(v -> chooseFolder());

        keepRunning = new CheckBox(this);
        keepRunning.setText("Keep sharing after leaving QuickDrop");
        keepRunning.setTextColor(0xFF263746); keepRunning.setTextSize(14);
        keepRunning.setChecked(prefs.getBoolean("keep_running", false));
        LinearLayout.LayoutParams keepLp = params(-1, -2); keepLp.topMargin = dp(15);
        root.addView(keepRunning, keepLp);
        keepRunning.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean("keep_running", checked).apply());

        TextView note = new TextView(this);
        note.setText("When this is off, QuickDrop stops if you swipe the app away from Recents. When it is on, sharing continues in the background until you stop it.");
        note.setTextColor(0xFF68798A); note.setTextSize(12);
        LinearLayout.LayoutParams noteLp = params(-1, -2); noteLp.topMargin = dp(2);
        root.addView(note, noteLp);

        shareLink = new TextView(this); shareLink.setTextSize(13); shareLink.setTextColor(0xFF1456A0);
        shareLink.setTextIsSelectable(true);
        LinearLayout.LayoutParams linkLp = params(-1, -2); linkLp.topMargin = dp(18);
        root.addView(shareLink, linkLp);

        openPanel = button("Open QuickDrop control panel");
        LinearLayout.LayoutParams panelLp = params(-1, dp(48)); panelLp.topMargin = dp(10);
        root.addView(openPanel, panelLp);
        openPanel.setOnClickListener(v -> openControlPanel());

        errorText = new TextView(this); errorText.setTextColor(0xFFB42318); errorText.setTextSize(12);
        LinearLayout.LayoutParams errLp = params(-1, -2); errLp.topMargin = dp(10);
        root.addView(errorText, errLp);

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, android.webkit.ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent picker = params.createIntent();
                    startActivityForResult(picker, FILE_PICKER);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "Unable to open file picker", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                String fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setTitle(fileName).setDescription("Downloading with QuickDrop")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setAllowedOverMetered(true).setAllowedOverRoaming(true);
                if (mimeType != null) request.setMimeType(mimeType);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
                Toast.makeText(MainActivity.this, "Download started", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "Download could not start: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        setContentView(root);
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
        setContentView(root);
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
        if (requestCode == PICK_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception ignored) { }
            StorageBridge.setTreeUri(uri.toString());
            Toast.makeText(this, "Storage folder selected. Restart sharing to apply it.", Toast.LENGTH_LONG).show();
            refreshUi();
        }
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

    @Override public void onBackPressed() {
        if (panelVisible) {
            panelVisible = false;
            setContentView(root);
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
        super.onDestroy();
    }
}
