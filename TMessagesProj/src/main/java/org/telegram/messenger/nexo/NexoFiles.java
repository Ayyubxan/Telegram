package org.telegram.messenger.nexo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** FORK MOD (Nexo): reading files to send, saving received ones and the "file received" notification. */
public class NexoFiles {
    /** ntfy.sh attachment limit is 15 MB; 10 MB with base64 and encryption → ~13.4 MB */
    public static final int MAX_BYTES = 10_000_000;
    private static final String CHANNEL_ID = "files";

    public interface Callback {
        void done(List<LocalFile> files, List<String> skipped);
    }

    private static String authority() {
        return ApplicationLoader.getApplicationId() + ".provider";
    }

    /**
     * Reads the URIs on a background thread and returns the result on the main thread.
     * The permission granted via Share ends when the activity closes — so finish only after reading.
     */
    public static void readAsync(final Context ctx, final List<Uri> uris, final Callback done) {
        Thread t = new Thread(() -> {
            final ArrayList<LocalFile> files = new ArrayList<>();
            final ArrayList<String> skipped = new ArrayList<>();
            for (Uri uri : uris) {
                String name = displayName(ctx, uri);
                LocalFile f = read(ctx, uri, name);
                if (f != null) files.add(f);
                else skipped.add(name);
            }
            AndroidUtilities.runOnUIThread(() -> done.done(files, skipped));
        }, "nexo-read");
        t.start();
    }

    private static LocalFile read(Context ctx, Uri uri, String name) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            InputStream input = cr.openInputStream(uri);
            if (input == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[64 * 1024];
                while (true) {
                    int n = input.read(buf);
                    if (n < 0) break;
                    if (out.size() + n > MAX_BYTES) return null;
                    out.write(buf, 0, n);
                }
            } finally {
                input.close();
            }
            String mime = cr.getType(uri);
            return new LocalFile(name, mime != null ? mime : mimeFor(name), out.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    private static String displayName(Context ctx, Uri uri) {
        String fromProvider = null;
        try {
            Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) fromProvider = c.getString(0);
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignore) {
        }
        return safeName(fromProvider != null ? fromProvider : uri.getLastPathSegment());
    }

    /** Strips directory parts and hidden-file markers from a name that came from another device. */
    public static String safeName(String name) {
        String s = name == null ? "" : name;
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        int back = s.lastIndexOf('\\');
        if (back >= 0) s = s.substring(back + 1);
        s = s.replaceAll("[\\x00-\\x1f:*?\"<>|]", "_").trim();
        int start = 0, end = s.length();
        while (start < end && s.charAt(start) == '.') start++;
        while (end > start && s.charAt(end - 1) == '.') end--;
        s = s.substring(start, end);
        if (s.length() > 120) s = s.substring(0, 120);
        return s.isEmpty() ? "file" : s;
    }

    public static String mimeFor(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }

    public static String sizeLabel(int bytes) {
        if (bytes >= 1_000_000) return String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0);
        if (bytes >= 1_000) return (bytes / 1_000) + " KB";
        return bytes + " B";
    }

    private static void writeBytes(File file, byte[] bytes) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    /** Clipboard image from the Mac: written to the cache, returns a content:// URI other apps can read. */
    public static Uri saveClipImage(Context ctx, String mime, byte[] bytes) {
        try {
            File dir = new File(new File(ctx.getFilesDir(), "cache"), "clips");
            dir.mkdirs();
            // Keep only the last few
            File[] old = dir.listFiles();
            if (old != null) {
                Arrays.sort(old, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (int i = 4; i < old.length; i++) old[i].delete();
            }
            String ext = mime == null ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (ext == null) ext = "png";
            File file = new File(dir, "clip-" + System.currentTimeMillis() + "." + ext);
            writeBytes(file, bytes);
            return FileProvider.getUriForFile(ctx, authority(), file);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Saves a received file to Download/Nexo (Android 10+ — MediaStore, no permission needed). */
    public static Uri saveDownload(Context ctx, String name, String mime, byte[] bytes) {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Nexo");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri;
            try {
                uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            } catch (Exception e) {
                uri = null;
            }
            if (uri == null) return null;
            try {
                OutputStream out = cr.openOutputStream(uri);
                if (out == null) throw new IllegalStateException("no stream");
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, values, null, null);
                return uri;
            } catch (Exception e) {
                try {
                    cr.delete(uri, null, null);
                } catch (Exception ignore) {
                }
                return null;
            }
        }
        // Android 8–9: writing to the shared Download folder needs a permission — save to the app's own folder
        try {
            File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) return null;
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            File file = new File(dir, name);
            int n = 2;
            while (file.exists()) {
                file = new File(dir, base + " (" + n + ")" + ext);
                n++;
            }
            writeBytes(file, bytes);
            return FileProvider.getUriForFile(ctx, authority(), file);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    public static void notifyReceived(Context ctx, String name, String mime, Uri uri, String from) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Received files", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Intent open = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            int id = uri.hashCode();
            PendingIntent pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String where = Build.VERSION.SDK_INT >= 29 ? "Download/Nexo" : "Nexo folder";
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL_ID) : new Notification.Builder(ctx);
            Notification n = b
                .setSmallIcon(R.drawable.notification)
                .setContentTitle("File received: " + name)
                .setContentText(from + " → " + where + " · tap to open")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
            nm.notify(id, n);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
