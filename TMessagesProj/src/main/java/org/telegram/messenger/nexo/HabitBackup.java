package org.telegram.messenger.nexo;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * FORK MOD (Nexo): automatic backup — one file per day `Download/Telegram/Habits/habits-YYYY-MM-DD.json`,
 * refreshed a minute after a change; files older than {@link #KEEP_DAYS} days are deleted.
 * Download survives an uninstall and is easy to move to a new phone.
 */
public class HabitBackup {
    public static final int KEEP_DAYS = 7;
    public static final String FOLDER = "Download/Telegram/Habits";
    private static final String PREFIX = "telegram-backup-";
    private static final long DEBOUNCE_MS = 60_000L;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Runnable pending;

    /** After every change: consecutive marks are collected into one write */
    public static void schedule(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (pending != null) main.removeCallbacks(pending);
        Runnable r = () -> {
            Thread t = new Thread(() -> {
                try {
                    write(app);
                } catch (Throwable e) {
                    org.telegram.messenger.FileLog.e(e);
                }
            }, "nexo-backup");
            t.start();
        };
        pending = r;
        main.postDelayed(r, DEBOUNCE_MS);
    }

    private static LocalDate dateOf(String name) {
        if (name == null || !name.startsWith(PREFIX)) return null;
        String ext = name.endsWith(".json") ? ".json" : name.endsWith(ForkCrypto.EXT) ? ForkCrypto.EXT : null;
        if (ext == null) return null;
        return Dates.parse(name.substring(PREFIX.length(), name.length() - ext.length()));
    }

    public static synchronized void write(Context ctx) throws Exception {
        HabitStore store = Nexo.store();
        if (store.isEmpty()) return;
        LocalDate today = HabitStore.today();
        boolean enc = Nexo.prefs().isBackupEncrypted();
        String name = PREFIX + today + (enc ? ForkCrypto.EXT : ".json");
        byte[] bytes = ForkBackup.fileBytes();
        LocalDate oldest = today.minusDays(KEEP_DAYS - 1L);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ctx.getContentResolver();
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            // Without a permission the query only returns the files this app created
            HashMap<String, Uri> own = new HashMap<>();
            Cursor c = cr.query(
                collection, new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME},
                MediaStore.MediaColumns.RELATIVE_PATH + "=?", new String[]{FOLDER + "/"}, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) own.put(c.getString(1), Uri.withAppendedPath(collection, String.valueOf(c.getLong(0))));
                } finally {
                    c.close();
                }
            }
            Uri uri = own.get(name);
            if (uri == null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, enc ? "application/octet-stream" : "application/json");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
                uri = cr.insert(collection, v);
                if (uri == null) return;
            }
            OutputStream os = cr.openOutputStream(uri, "wt");
            if (os == null) return;
            try {
                os.write(bytes);
            } finally {
                os.close();
            }
            for (Map.Entry<String, Uri> e : own.entrySet()) {
                LocalDate d = dateOf(e.getKey());
                if (d != null && d.isBefore(oldest)) {
                    try { cr.delete(e.getValue(), null, null); } catch (Exception ignore) {}
                }
            }
        } else {
            // Android 8–9: writing to the shared Download needs a permission — the app's own folder instead
            File base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (base == null) return;
            File dir = new File(base, "Habits");
            dir.mkdirs();
            FileOutputStream fos = new FileOutputStream(new File(dir, name));
            try {
                fos.write(bytes);
            } finally {
                fos.close();
            }
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    LocalDate d = dateOf(f.getName());
                    if (d != null && d.isBefore(oldest)) f.delete();
                }
            }
        }
        Nexo.prefs().setLastBackupAt(System.currentTimeMillis());
    }
}
