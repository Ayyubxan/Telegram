package org.telegram.messenger.nexo;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageCustomParamsHelper;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * FORK MOD (anti-delete backup): exports / imports the messages the fork kept — rows of {@code messages_v2} whose
 * local params say "deleted by the other side" or carry an edit history. Whole rows are exported (all columns, blobs as
 * base64), so a restored deleted message shows up in its chat again. Runs on the account's storage queue; callers block
 * on a background thread.
 */
public class KeptMessages {
    private static final String[] COLS = {"mid", "uid", "read_state", "send_state", "date", "data", "out", "ttl", "media", "replydata", "imp",
        "mention", "forwards", "replies_data", "thread_reply_id", "is_channel", "reply_to_message_id", "custom_params", "group_id", "reply_to_story_id"};
    private static final int CUSTOM = 17;

    private interface Job { Object run(SQLiteDatabase db) throws Exception; }

    private static Object onStorage(int account, Job job) {
        MessagesStorage st = MessagesStorage.getInstance(account);
        final Object[] out = {null};
        CountDownLatch latch = new CountDownLatch(1);
        st.getStorageQueue().postRunnable(() -> {
            try { out[0] = job.run(st.getDatabase()); } catch (Throwable e) { FileLog.e(e); } finally { latch.countDown(); }
        });
        try { latch.await(120, TimeUnit.SECONDS); } catch (InterruptedException ignore) {}
        return out[0];
    }

    private static boolean isKept(TLRPC.Message m) {
        return m.forkDeleted || (m.forkEditHistory != null && !m.forkEditHistory.isEmpty());
    }

    /** Kept rows of one account (background thread) */
    public static JSONArray export(int account) {
        Object r = onStorage(account, db -> {
            JSONArray a = new JSONArray();
            SQLiteCursor c = db.queryFinalized("SELECT " + String.join(", ", COLS) + " FROM messages_v2 WHERE custom_params IS NOT NULL");
            try {
                while (c.next()) {
                    TLRPC.Message m = new TLRPC.TL_message();
                    NativeByteBuffer cp = c.byteBufferValue(CUSTOM);
                    if (cp == null) continue;
                    MessageCustomParamsHelper.readLocalParams(m, cp);
                    cp.reuse();
                    if (!isKept(m)) continue;
                    JSONObject o = new JSONObject();
                    for (int i = 0; i < COLS.length; i++) {
                        if (c.isNull(i)) continue;
                        int type = c.getTypeOf(i);
                        if (type == 4) { // blob
                            byte[] b = c.byteArrayValue(i);
                            if (b != null) o.put(COLS[i], Base64.encodeToString(b, Base64.NO_WRAP));
                        } else {
                            o.put(COLS[i], c.longValue(i));
                        }
                    }
                    a.put(o);
                }
            } finally {
                c.dispose();
            }
            return a;
        });
        return r instanceof JSONArray ? (JSONArray) r : new JSONArray();
    }

    /** Puts the rows back: missing messages are inserted, existing ones get the deleted flag / edit history merged. Returns how many changed. */
    public static int restore(int account, JSONArray rows) {
        if (rows == null || rows.length() == 0) return 0;
        Object r = onStorage(account, db -> {
            int n = 0;
            db.beginTransaction();
            try {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject o = rows.optJSONObject(i);
                    if (o == null || !o.has("mid") || !o.has("uid") || !o.has("data")) continue;
                    long mid = o.getLong("mid"), uid = o.getLong("uid");
                    byte[] backupParams = o.has("custom_params") ? Base64.decode(o.getString("custom_params"), Base64.NO_WRAP) : null;
                    SQLiteCursor c = db.queryFinalized("SELECT custom_params FROM messages_v2 WHERE mid = ? AND uid = ?", mid, uid);
                    boolean exists = c.next();
                    NativeByteBuffer current = exists ? c.byteBufferValue(0) : null;
                    TLRPC.Message here = new TLRPC.TL_message();
                    if (current != null) { MessageCustomParamsHelper.readLocalParams(here, current); current.reuse(); }
                    c.dispose();
                    if (exists) {
                        if (backupParams == null) continue;
                        TLRPC.Message saved = new TLRPC.TL_message();
                        NativeByteBuffer bb = new NativeByteBuffer(backupParams.length);
                        bb.writeBytes(backupParams); bb.position(0);
                        MessageCustomParamsHelper.readLocalParams(saved, bb);
                        bb.reuse();
                        if (!merge(here, saved)) continue;
                        SQLitePreparedStatement st = db.executeFast("UPDATE messages_v2 SET custom_params = ? WHERE mid = ? AND uid = ?");
                        NativeByteBuffer out = MessageCustomParamsHelper.writeLocalParams(here);
                        if (out != null) st.bindByteBuffer(1, out); else st.bindNull(1);
                        st.bindLong(2, mid); st.bindLong(3, uid);
                        st.step(); st.dispose();
                        if (out != null) out.reuse();
                        n++;
                    } else {
                        StringBuilder q = new StringBuilder("INSERT INTO messages_v2 (").append(String.join(", ", COLS)).append(") VALUES(");
                        for (int k = 0; k < COLS.length; k++) q.append(k == 0 ? "?" : ", ?");
                        SQLitePreparedStatement st = db.executeFast(q.append(")").toString());
                        ArrayList<NativeByteBuffer> bufs = new ArrayList<>();
                        for (int k = 0; k < COLS.length; k++) {
                            String col = COLS[k];
                            if (!o.has(col) || o.isNull(col)) { st.bindNull(k + 1); continue; }
                            Object v = o.get(col);
                            if (v instanceof String) {
                                byte[] b = Base64.decode((String) v, Base64.NO_WRAP);
                                NativeByteBuffer nb = new NativeByteBuffer(b.length);
                                nb.writeBytes(b); nb.position(0);
                                st.bindByteBuffer(k + 1, nb);
                                bufs.add(nb);
                            } else {
                                st.bindLong(k + 1, ((Number) v).longValue());
                            }
                        }
                        st.step(); st.dispose();
                        for (NativeByteBuffer nb : bufs) nb.reuse();
                        n++;
                    }
                }
                db.commitTransaction();
            } catch (Throwable e) {
                try { db.commitTransaction(); } catch (Throwable ignore) {}
                throw e;
            }
            return n;
        });
        return r instanceof Integer ? (Integer) r : 0;
    }

    /** Merges the backup's fork fields into {@code here}; true when something changed */
    private static boolean merge(TLRPC.Message here, TLRPC.Message saved) {
        boolean changed = false;
        if (saved.forkDeleted && !here.forkDeleted) { here.forkDeleted = true; changed = true; }
        if (saved.forkEditHistory != null && !saved.forkEditHistory.isEmpty()) {
            TreeMap<String, Object[]> all = new TreeMap<>();
            if (here.forkEditHistory != null) for (int i = 0; i < here.forkEditHistory.size(); i++) {
                int d = here.forkEditHistoryDates != null && i < here.forkEditHistoryDates.size() ? here.forkEditHistoryDates.get(i) : 0;
                all.put(String.format(java.util.Locale.US, "%012d|%s", d, here.forkEditHistory.get(i)), new Object[]{here.forkEditHistory.get(i), d});
            }
            int before = all.size();
            for (int i = 0; i < saved.forkEditHistory.size(); i++) {
                int d = saved.forkEditHistoryDates != null && i < saved.forkEditHistoryDates.size() ? saved.forkEditHistoryDates.get(i) : 0;
                all.put(String.format(java.util.Locale.US, "%012d|%s", d, saved.forkEditHistory.get(i)), new Object[]{saved.forkEditHistory.get(i), d});
            }
            if (all.size() != before) {
                here.forkEditHistory = new ArrayList<>();
                here.forkEditHistoryDates = new ArrayList<>();
                for (Object[] v : all.values()) { here.forkEditHistory.add((String) v[0]); here.forkEditHistoryDates.add((Integer) v[1]); }
                changed = true;
            }
        }
        return changed;
    }
}
