package org.telegram.messenger.gmail;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;

/**
 * FORK MOD (gmail): the bookkeeping that Telegram's own database does not have — which Gmail message is which local
 * message, thread / header data needed to reply, sender addresses, the Gmail history cursor and the module settings.
 * Own small SQLite file + SharedPreferences, so nothing in Telegram's schema changes.
 */
public final class GmailStore {

    private static final String PREFS = "fork_gmail";
    private static final int DB_VERSION = 1;

    /** One stored email */
    public static final class Row {
        public String gmailId, threadId, messageIdHeader, references, subject, fromEmail;
        public long did;
        public int mid, date;
        public boolean out, unread, inbox;
    }

    public static final class Attachment {
        public long did;
        public int mid, index;
        public String gmailId, attachmentId, filename, mime;
        public long size;
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context ctx) {
            super(ctx, "fork_gmail.db", null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE msgs(gmail_id TEXT PRIMARY KEY, thread_id TEXT, did INTEGER, mid INTEGER, msg_id_hdr TEXT, refs TEXT, subject TEXT, from_email TEXT, date INTEGER, out INTEGER, unread INTEGER, inbox INTEGER)");
            db.execSQL("CREATE INDEX msgs_did ON msgs(did, mid)");
            db.execSQL("CREATE TABLE senders(did INTEGER PRIMARY KEY, email TEXT, name TEXT, spam INTEGER DEFAULT 0)");
            db.execSQL("CREATE TABLE attachments(did INTEGER, mid INTEGER, idx INTEGER, gmail_id TEXT, attachment_id TEXT, filename TEXT, mime TEXT, size INTEGER, PRIMARY KEY(did, mid, idx))");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }

    private static volatile Helper helper;

    private static SQLiteDatabase db() {
        if (helper == null) {
            synchronized (GmailStore.class) {
                if (helper == null) {
                    helper = new Helper(ApplicationLoader.applicationContext);
                }
            }
        }
        return helper.getWritableDatabase();
    }

    static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private GmailStore() {}

    // ---- settings -------------------------------------------------------------------------------------------------

    /** Telegram account index the mailbox is attached to, -1 when not connected */
    public static int account() {
        return prefs().getInt("account", -1);
    }

    public static String email() {
        return prefs().getString("email", "");
    }

    public static boolean isConnected() {
        return account() >= 0 && !TextUtils.isEmpty(email());
    }

    public static void setConnected(int account, String email) {
        prefs().edit().putInt("account", account).putString("email", email).putBoolean("enabled", true).putBoolean("needs_auth", false).apply();
    }

    public static void disconnect() {
        prefs().edit().remove("account").remove("email").remove("history_id").remove("refresh_token").putBoolean("enabled", false).putBoolean("needs_auth", false).apply();
    }

    /** OAuth refresh token of the browser sign-in; null when not signed in */
    public static String refreshToken() {
        return prefs().getString("refresh_token", null);
    }

    public static void setRefreshToken(String token) {
        if (token == null) {
            prefs().edit().remove("refresh_token").apply();
        } else {
            prefs().edit().putString("refresh_token", token).apply();
        }
    }

    public static boolean isEnabled() {
        return prefs().getBoolean("enabled", false);
    }

    public static void setEnabled(boolean v) {
        prefs().edit().putBoolean("enabled", v).apply();
    }

    public static boolean needsAuth() {
        return prefs().getBoolean("needs_auth", false);
    }

    public static void setNeedsAuth(boolean v) {
        prefs().edit().putBoolean("needs_auth", v).apply();
    }

    public static String historyId() {
        return prefs().getString("history_id", null);
    }

    public static void setHistoryId(String id) {
        prefs().edit().putString("history_id", id).apply();
    }

    /** Minutes between polls */
    public static int intervalMinutes() {
        return prefs().getInt("interval_min", 2);
    }

    public static void setIntervalMinutes(int m) {
        prefs().edit().putInt("interval_min", m).apply();
    }

    /** How many inbox emails the first sync pulls in */
    public static int initialCount() {
        return prefs().getInt("initial_count", 30);
    }

    public static void setInitialCount(int n) {
        prefs().edit().putInt("initial_count", n).apply();
    }

    public static long lastSync() {
        return prefs().getLong("last_sync", 0);
    }

    public static String lastError() {
        return prefs().getString("last_error", "");
    }

    public static void setSyncResult(String error) {
        prefs().edit().putLong("last_sync", System.currentTimeMillis()).putString("last_error", error == null ? "" : error).apply();
    }

    /** Next local message id (counted up, persisted) */
    public static synchronized int nextMid() {
        int mid = prefs().getInt("next_mid", GmailIds.MID_BASE);
        prefs().edit().putInt("next_mid", mid + 1).apply();
        return mid;
    }

    // ---- folders --------------------------------------------------------------------------------------------------

    /** Folders (dialog filters) whose "Mails" chat type is on; the flag is local, so it is remembered here by filter id */
    public static boolean isMailFolder(int filterId) {
        java.util.Set<String> ids = prefs().getStringSet("mail_folders", null);
        return ids != null && ids.contains(String.valueOf(filterId));
    }

    public static void setMailFolder(int filterId, boolean on) {
        java.util.Set<String> ids = prefs().getStringSet("mail_folders", null);
        java.util.HashSet<String> next = ids == null ? new java.util.HashSet<>() : new java.util.HashSet<>(ids);
        if (on) {
            next.add(String.valueOf(filterId));
        } else {
            next.remove(String.valueOf(filterId));
        }
        prefs().edit().putStringSet("mail_folders", next).apply();
    }

    // ---- senders --------------------------------------------------------------------------------------------------

    public static void putSender(long did, String email, String name) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("did", did);
            cv.put("email", email);
            cv.put("name", name);
            db().insertWithOnConflict("senders", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static String senderEmail(long did) {
        try (Cursor c = db().rawQuery("SELECT email FROM senders WHERE did = ?", new String[]{String.valueOf(did)})) {
            return c.moveToFirst() ? c.getString(0) : null;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public static boolean isSpamSender(long did) {
        try (Cursor c = db().rawQuery("SELECT spam FROM senders WHERE did = ?", new String[]{String.valueOf(did)})) {
            return c.moveToFirst() && c.getInt(0) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static void setSpamSender(long did, boolean spam) {
        try {
            db().execSQL("UPDATE senders SET spam = ? WHERE did = ?", new Object[]{spam ? 1 : 0, did});
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ---- messages -------------------------------------------------------------------------------------------------

    public static boolean hasGmailId(String gmailId) {
        try (Cursor c = db().rawQuery("SELECT 1 FROM msgs WHERE gmail_id = ?", new String[]{gmailId})) {
            return c.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }

    public static void putMessage(Row r) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("gmail_id", r.gmailId);
            cv.put("thread_id", r.threadId);
            cv.put("did", r.did);
            cv.put("mid", r.mid);
            cv.put("msg_id_hdr", r.messageIdHeader);
            cv.put("refs", r.references);
            cv.put("subject", r.subject);
            cv.put("from_email", r.fromEmail);
            cv.put("date", r.date);
            cv.put("out", r.out ? 1 : 0);
            cv.put("unread", r.unread ? 1 : 0);
            cv.put("inbox", r.inbox ? 1 : 0);
            db().insertWithOnConflict("msgs", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static Row read(Cursor c) {
        Row r = new Row();
        r.gmailId = c.getString(0);
        r.threadId = c.getString(1);
        r.did = c.getLong(2);
        r.mid = c.getInt(3);
        r.messageIdHeader = c.getString(4);
        r.references = c.getString(5);
        r.subject = c.getString(6);
        r.fromEmail = c.getString(7);
        r.date = c.getInt(8);
        r.out = c.getInt(9) != 0;
        r.unread = c.getInt(10) != 0;
        r.inbox = c.getInt(11) != 0;
        return r;
    }

    private static final String COLS = "gmail_id, thread_id, did, mid, msg_id_hdr, refs, subject, from_email, date, out, unread, inbox";

    public static Row byGmailId(String gmailId) {
        try (Cursor c = db().rawQuery("SELECT " + COLS + " FROM msgs WHERE gmail_id = ?", new String[]{gmailId})) {
            return c.moveToFirst() ? read(c) : null;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public static Row byMid(long did, int mid) {
        try (Cursor c = db().rawQuery("SELECT " + COLS + " FROM msgs WHERE did = ? AND mid = ?", new String[]{String.valueOf(did), String.valueOf(mid)})) {
            return c.moveToFirst() ? read(c) : null;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Newest email of the dialog that came from the other side (what a reply answers) */
    public static Row lastIncoming(long did) {
        try (Cursor c = db().rawQuery("SELECT " + COLS + " FROM msgs WHERE did = ? AND out = 0 ORDER BY mid DESC LIMIT 1", new String[]{String.valueOf(did)})) {
            return c.moveToFirst() ? read(c) : null;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public static List<Row> all(long did) {
        ArrayList<Row> out = new ArrayList<>();
        try (Cursor c = db().rawQuery("SELECT " + COLS + " FROM msgs WHERE did = ? ORDER BY mid ASC", new String[]{String.valueOf(did)})) {
            while (c.moveToNext()) {
                out.add(read(c));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return out;
    }

    /** Gmail ids of unread incoming emails of the dialog up to (and including) {@code maxMid} */
    public static List<String> unreadUpTo(long did, int maxMid) {
        ArrayList<String> out = new ArrayList<>();
        try (Cursor c = db().rawQuery("SELECT gmail_id FROM msgs WHERE did = ? AND out = 0 AND unread = 1 AND mid <= ?", new String[]{String.valueOf(did), String.valueOf(maxMid)})) {
            while (c.moveToNext()) {
                out.add(c.getString(0));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return out;
    }

    public static List<String> gmailIds(long did, List<Integer> mids) {
        ArrayList<String> out = new ArrayList<>();
        if (mids == null) {
            return out;
        }
        for (int mid : mids) {
            Row r = byMid(did, mid);
            if (r != null) {
                out.add(r.gmailId);
            }
        }
        return out;
    }

    public static void markRead(List<String> gmailIds) {
        try {
            for (String id : gmailIds) {
                db().execSQL("UPDATE msgs SET unread = 0 WHERE gmail_id = ?", new Object[]{id});
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void setInbox(long did, boolean inbox) {
        try {
            db().execSQL("UPDATE msgs SET inbox = ? WHERE did = ?", new Object[]{inbox ? 1 : 0, did});
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void deleteMessages(long did, List<Integer> mids) {
        try {
            for (int mid : mids) {
                db().execSQL("DELETE FROM msgs WHERE did = ? AND mid = ?", new Object[]{did, mid});
                db().execSQL("DELETE FROM attachments WHERE did = ? AND mid = ?", new Object[]{did, mid});
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void deleteDialog(long did) {
        try {
            db().execSQL("DELETE FROM msgs WHERE did = ?", new Object[]{did});
            db().execSQL("DELETE FROM attachments WHERE did = ?", new Object[]{did});
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ---- attachments ----------------------------------------------------------------------------------------------

    public static void putAttachment(Attachment a) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("did", a.did);
            cv.put("mid", a.mid);
            cv.put("idx", a.index);
            cv.put("gmail_id", a.gmailId);
            cv.put("attachment_id", a.attachmentId);
            cv.put("filename", a.filename);
            cv.put("mime", a.mime);
            cv.put("size", a.size);
            db().insertWithOnConflict("attachments", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** The attachment behind an attachment message (its document id is the message id) */
    public static Attachment attachmentByMid(int mid) {
        try (Cursor c = db().rawQuery("SELECT did, idx, gmail_id, attachment_id, filename, mime, size FROM attachments WHERE mid = ? LIMIT 1", new String[]{String.valueOf(mid)})) {
            if (!c.moveToFirst()) {
                return null;
            }
            Attachment a = new Attachment();
            a.did = c.getLong(0);
            a.mid = mid;
            a.index = c.getInt(1);
            a.gmailId = c.getString(2);
            a.attachmentId = c.getString(3);
            a.filename = c.getString(4);
            a.mime = c.getString(5);
            a.size = c.getLong(6);
            return a;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public static List<Attachment> attachments(long did, int mid) {
        ArrayList<Attachment> out = new ArrayList<>();
        try (Cursor c = db().rawQuery("SELECT idx, gmail_id, attachment_id, filename, mime, size FROM attachments WHERE did = ? AND mid = ? ORDER BY idx", new String[]{String.valueOf(did), String.valueOf(mid)})) {
            while (c.moveToNext()) {
                Attachment a = new Attachment();
                a.did = did;
                a.mid = mid;
                a.index = c.getInt(0);
                a.gmailId = c.getString(1);
                a.attachmentId = c.getString(2);
                a.filename = c.getString(3);
                a.mime = c.getString(4);
                a.size = c.getLong(5);
                out.add(a);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return out;
    }

    /** Wipes everything the module stored (on disconnect) */
    public static void clearAll() {
        try {
            db().execSQL("DELETE FROM msgs");
            db().execSQL("DELETE FROM senders");
            db().execSQL("DELETE FROM attachments");
        } catch (Exception e) {
            FileLog.e(e);
        }
        prefs().edit().remove("history_id").apply();
    }
}
