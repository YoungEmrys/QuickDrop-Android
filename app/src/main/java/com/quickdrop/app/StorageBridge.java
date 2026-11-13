package com.quickdrop.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** SAF bridge: Python works in an app-private folder; this class mirrors files
 * to and from the directory the user grants through Android's folder picker. */
public final class StorageBridge {
    private static Context app;
    private static final String PREFS = "quickdrop_settings";
    private static final String TREE_URI = "selected_tree_uri";

    private StorageBridge() {}

    public static void initialize(Context context) {
        app = context.getApplicationContext();
    }

    private static Context context() {
        if (app == null) throw new IllegalStateException("StorageBridge not initialized");
        return app;
    }

    public static void setTreeUri(String uri) {
        Context c = context();
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TREE_URI, uri).apply();
    }

    public static String getTreeUri() {
        return context().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TREE_URI, "");
    }

    private static DocumentFile selectedTree() {
        String raw = getTreeUri();
        if (raw == null || raw.isEmpty()) return null;
        return DocumentFile.fromTreeUri(context(), Uri.parse(raw));
    }

    public static int importSelectedFolder(String targetPath) {
        DocumentFile tree = selectedTree();
        if (tree == null || !tree.canRead()) return 0;
        File target = new File(targetPath);
        if (!target.exists()) target.mkdirs();
        File[] oldFiles = target.listFiles();
        if (oldFiles != null) {
            for (File old : oldFiles) {
                if (old.isFile() && !old.getName().equals(".nomedia")) old.delete();
            }
        }
        int copied = 0;
        for (DocumentFile item : tree.listFiles()) {
            if (!item.isFile() || item.getName() == null || item.getName().startsWith(".")) continue;
            File out = new File(target, safeName(item.getName()));
            try (InputStream in = context().getContentResolver().openInputStream(item.getUri());
                 OutputStream stream = new FileOutputStream(out)) {
                if (in == null) continue;
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) stream.write(buffer, 0, n);
                copied++;
            } catch (Exception ignored) { }
        }
        return copied;
    }

    public static boolean exportFile(String sourcePath, String name) {
        DocumentFile tree = selectedTree();
        if (tree == null || !tree.canWrite()) return false;
        String safe = safeName(name);
        try {
            DocumentFile existing = tree.findFile(safe);
            if (existing != null) existing.delete();
            DocumentFile created = tree.createFile(mimeFor(safe), safe);
            if (created == null) return false;
            try (InputStream in = new FileInputStream(new File(sourcePath, safe));
                 OutputStream out = context().getContentResolver().openOutputStream(created.getUri(), "w")) {
                if (out == null) return false;
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            }
            return true;
        } catch (Exception e) { return false; }
    }

    public static void deleteFile(String name) {
        DocumentFile tree = selectedTree();
        if (tree == null || !tree.canWrite()) return;
        DocumentFile file = tree.findFile(safeName(name));
        if (file != null) file.delete();
    }

    public static void requestStop() {
        Context c = context();
        c.stopService(new Intent(c, ServerService.class));
    }

    private static String safeName(String name) {
        String value = name == null ? "file" : name.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (value.isEmpty() || value.equals(".") || value.equals("..")) return "file";
        return value;
    }

    private static String mimeFor(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }
}
