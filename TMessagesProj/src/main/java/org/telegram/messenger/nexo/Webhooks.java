package org.telegram.messenger.nexo;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * FORK MOD: webhooks — every new message of a chosen chat is delivered to a target, with a persistent retry queue.
 * Target = {@code https://…} (HTTP POST, JSON body, optional secret header) or {@code app:<package>/<receiver class>}
 * (ordered explicit broadcast, action {@link #APP_ACTION}, the same JSON in the {@code json} extra; the receiver must
 * confirm with {@code setResultCode(Activity.RESULT_OK)}, otherwise the item stays queued). Failed deliveries are kept in
 * {@code files/webhook_queue.json} and retried with exponential backoff (1 min → 6 h) by an alarm and whenever the
 * app starts or a new message arrives; after {@link #MAX_ATTEMPTS} the item is dropped and counted as failed.
 */
public class Webhooks {
    public static final String APP_ACTION = "org.telegram.messenger.fork.WEBHOOK";
    /** Initial result code of an app delivery; the receiving app replies RESULT_OK (-1) to confirm */
    private static final int ACK_NONE = 0;
    public static final String ACTION_RETRY = "org.telegram.messenger.fork.WEBHOOK_RETRY";
    public static final int MAX_ATTEMPTS = 20;
    private static final long[] BACKOFF_MS = {60_000L, 120_000L, 300_000L, 900_000L, 1_800_000L, 3_600_000L, 6 * 3_600_000L};
    private static final int ALARM_REQUEST = 401;

    /** One hook: which chat → where */
    public static class Hook {
        public String id;
        public long dialogId;
        public String name = "";
        public String target = "";
        public String secret = "";
        public boolean enabled = true;
        /** include outgoing (my own) messages too */
        public boolean outgoing = false;
        public long sent, failed, lastOkAt, lastErrorAt;
        public String lastError = "";

        public boolean isApp() { return target.startsWith("app:"); }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("dialogId", dialogId).put("name", name).put("target", target).put("secret", secret)
                .put("enabled", enabled).put("outgoing", outgoing).put("sent", sent).put("failed", failed)
                .put("lastOkAt", lastOkAt).put("lastErrorAt", lastErrorAt).put("lastError", lastError);
        }

        static Hook fromJson(JSONObject o) {
            Hook h = new Hook();
            h.id = o.optString("id"); h.dialogId = o.optLong("dialogId"); h.name = o.optString("name"); h.target = o.optString("target");
            h.secret = o.optString("secret"); h.enabled = o.optBoolean("enabled", true); h.outgoing = o.optBoolean("outgoing");
            h.sent = o.optLong("sent"); h.failed = o.optLong("failed"); h.lastOkAt = o.optLong("lastOkAt"); h.lastErrorAt = o.optLong("lastErrorAt");
            h.lastError = o.optString("lastError");
            return h;
        }
    }

    /** A queued delivery */
    private static class Item {
        String id, hookId; JSONObject payload; int attempts; long nextAt;
    }

    private static final Object lock = new Object();
    private static List<Hook> hooks;
    private static List<Item> queue;
    private static boolean inited, flushing;

    // region Config

    public static synchronized List<Hook> all() {
        if (hooks == null) {
            hooks = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(Nexo.prefs().getWebhooks());
                for (int i = 0; i < a.length(); i++) hooks.add(Hook.fromJson(a.getJSONObject(i)));
            } catch (Exception ignore) {}
        }
        return hooks;
    }

    public static synchronized Hook get(String id) {
        for (Hook h : all()) if (h.id.equals(id)) return h;
        return null;
    }

    public static synchronized void save(Hook h) {
        if (h.id == null || h.id.isEmpty()) h.id = UUID.randomUUID().toString().substring(0, 8);
        List<Hook> list = all();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(h.id)) { list.set(i, h); found = true; }
        if (!found) list.add(h);
        persist();
    }

    public static synchronized void delete(String id) {
        all().removeIf(h -> h.id.equals(id));
        synchronized (lock) { if (queue != null) queue.removeIf(it -> it.hookId.equals(id)); saveQueue(); }
        persist();
    }

    private static void persist() {
        try {
            JSONArray a = new JSONArray();
            for (Hook h : all()) a.put(h.toJson());
            Nexo.prefs().setWebhooks(a.toString());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // endregion

    // region Observer

    /** recently queued "account:dialog:msg" keys, so one message is never queued twice by the two events */
    private static final java.util.LinkedHashSet<String> recent = new java.util.LinkedHashSet<>();

    private static boolean firstTime(int account, long dialogId, int msgId) {
        synchronized (recent) {
            if (!recent.add(account + ":" + dialogId + ":" + msgId)) return false;
            if (recent.size() > 500) { Iterator<String> it = recent.iterator(); it.next(); it.remove(); }
            return true;
        }
    }

    private static final NotificationCenter.NotificationCenterDelegate observer = (id, account, args) -> {
        if (id == NotificationCenter.messageReceivedByServer) {
            // a message sent from THIS device got its real id (it never comes through didReceiveNewMessages)
            try {
                if (args.length > 6 && args[6] instanceof Boolean && (Boolean) args[6]) return; // scheduled
                if (!(args[2] instanceof TLRPC.Message) || !(args[3] instanceof Long)) return;
                TLRPC.Message msg = (TLRPC.Message) args[2];
                long dialogId = (Long) args[3];
                List<Hook> matching = new ArrayList<>();
                for (Hook h : all()) if (h.enabled && h.outgoing && h.dialogId == dialogId) matching.add(h);
                if (matching.isEmpty() || msg.id <= 0 || !firstTime(account, dialogId, msg.id)) return;
                MessageObject m = new MessageObject(account, msg, false, false);
                JSONObject payload = payload(account, dialogId, m);
                if (payload == null) return;
                for (Hook h : matching) enqueue(h, payload);
                flush(Nexo.ctx());
            } catch (Throwable e) {
                FileLog.e(e);
            }
            return;
        }
        if (id != NotificationCenter.didReceiveNewMessages) return;
        try {
            long dialogId = (Long) args[0];
            @SuppressWarnings("unchecked") ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
            boolean scheduled = args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2];
            if (scheduled) return;
            List<Hook> matching = new ArrayList<>();
            for (Hook h : all()) if (h.enabled && h.dialogId == dialogId) matching.add(h);
            if (matching.isEmpty()) return;
            for (MessageObject m : messages) {
                if (m.getId() < 0) continue; // local echo of an outgoing message — messageReceivedByServer brings the real one
                if (!firstTime(account, dialogId, m.getId())) continue;
                JSONObject payload = payload(account, dialogId, m);
                if (payload == null) continue;
                for (Hook h : matching) {
                    if (m.isOutOwner() && !h.outgoing) continue;
                    enqueue(h, payload);
                }
            }
            flush(Nexo.ctx());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    };

    public static void init(Context ctx) {
        if (inited) return;
        inited = true;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter.getInstance(a).addObserver(observer, NotificationCenter.didReceiveNewMessages);
            NotificationCenter.getInstance(a).addObserver(observer, NotificationCenter.messageReceivedByServer);
        }
        flush(ctx);
    }

    public static String chatTitle(int account, long dialogId) {
        MessagesController mc = MessagesController.getInstance(account);
        if (dialogId > 0) {
            TLRPC.User u = mc.getUser(dialogId);
            if (u != null) return UserObject.getUserName(u);
        } else {
            TLRPC.Chat c = mc.getChat(-dialogId);
            if (c != null) return c.title;
        }
        return "Chat " + dialogId;
    }

    /** The JSON sent for one message; null for service messages without text or media */
    static JSONObject payload(int account, long dialogId, MessageObject m) {
        try {
            if (m == null || m.messageOwner == null) return null;
            String text = m.messageOwner.message;
            if ((text == null || text.isEmpty()) && m.messageText != null) text = m.messageText.toString();
            String media = m.isPhoto() ? "photo" : m.isVideo() ? "video" : m.isVoice() ? "voice" : m.isMusic() ? "audio" : m.isSticker() ? "sticker" : m.getDocument() != null ? "document" : null;
            if ((text == null || text.trim().isEmpty()) && media == null) return null;
            JSONObject o = new JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("message_id", m.getId())
                .put("date", m.messageOwner.date * 1000L)
                .put("chat_id", dialogId)
                .put("chat", chatTitle(account, dialogId))
                .put("outgoing", m.isOutOwner())
                .put("text", text == null ? "" : text);
            if (media != null) o.put("media", media);
            long from = m.getFromChatId();
            if (from != 0) o.put("from_id", from).put("from", chatTitle(account, from));
            if (m.messageOwner.reply_to != null && m.messageOwner.reply_to.reply_to_msg_id != 0) o.put("reply_to", m.messageOwner.reply_to.reply_to_msg_id);
            return o;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    // endregion

    // region Queue

    private static File queueFile() {
        return new File(Nexo.ctx().getFilesDir(), "webhook_queue.json");
    }

    private static void loadQueue() {
        if (queue != null) return;
        queue = new ArrayList<>();
        try {
            File f = queueFile();
            if (!f.exists()) return;
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { int off = 0; while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) break; off += n; } }
            JSONArray a = new JSONArray(new String(b, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.id = o.optString("id"); it.hookId = o.optString("hook"); it.payload = o.optJSONObject("payload"); it.attempts = o.optInt("attempts"); it.nextAt = o.optLong("nextAt");
                if (it.payload != null) queue.add(it);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void saveQueue() {
        try {
            JSONArray a = new JSONArray();
            for (Item it : queue) a.put(new JSONObject().put("id", it.id).put("hook", it.hookId).put("payload", it.payload).put("attempts", it.attempts).put("nextAt", it.nextAt));
            File f = queueFile();
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(a.toString().getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void enqueue(Hook h, JSONObject payload) {
        synchronized (lock) {
            loadQueue();
            Item it = new Item();
            it.id = payload.optString("id", UUID.randomUUID().toString()); it.hookId = h.id; it.payload = payload; it.attempts = 0; it.nextAt = 0;
            queue.add(it);
            saveQueue();
        }
    }

    public static int pending() {
        synchronized (lock) { loadQueue(); return queue.size(); }
    }

    public static int pending(String hookId) {
        synchronized (lock) { loadQueue(); int n = 0; for (Item it : queue) if (it.hookId.equals(hookId)) n++; return n; }
    }

    private static boolean online(Context ctx) {
        try {
            ConnectivityManager cm = ContextCompat.getSystemService(ctx, ConnectivityManager.class);
            if (cm == null) return true;
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }

    /** Delivers everything that is due, on a background thread; schedules the next retry alarm when something remains */
    public static void flush(Context ctx) {
        synchronized (lock) { if (flushing) return; flushing = true; }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                long now = System.currentTimeMillis();
                List<Item> due = new ArrayList<>();
                synchronized (lock) { loadQueue(); for (Item it : queue) if (it.nextAt <= now) due.add(it); }
                boolean net = online(ctx);
                for (Item it : due) {
                    Hook h = get(it.hookId);
                    if (h == null || !h.enabled) { synchronized (lock) { queue.remove(it); } continue; }
                    if (!net && !h.isApp()) continue;
                    String err = deliver(ctx, h, it.payload);
                    synchronized (lock) {
                        if (err == null) {
                            queue.remove(it);
                            h.sent++; h.lastOkAt = System.currentTimeMillis();
                        } else {
                            it.attempts++;
                            h.lastError = err; h.lastErrorAt = System.currentTimeMillis();
                            if (it.attempts >= MAX_ATTEMPTS) { queue.remove(it); h.failed++; }
                            else it.nextAt = System.currentTimeMillis() + BACKOFF_MS[Math.min(it.attempts - 1, BACKOFF_MS.length - 1)];
                        }
                    }
                }
                synchronized (lock) { saveQueue(); }
                synchronized (Webhooks.class) { persist(); }
                scheduleRetry(ctx);
                Nexo.notifyChanged();
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                synchronized (lock) { flushing = false; }
            }
        });
    }

    /** Alarm for the earliest pending retry (exact-ish, allowed while idle); cancelled when the queue is empty */
    private static void scheduleRetry(Context ctx) {
        AlarmManager am = ContextCompat.getSystemService(ctx, AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, ALARM_REQUEST,
            new Intent(ctx, ForkBackupReceiver.class).setAction(ACTION_RETRY), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        long next = Long.MAX_VALUE;
        synchronized (lock) { for (Item it : queue) next = Math.min(next, it.nextAt); }
        if (next == Long.MAX_VALUE) { am.cancel(pi); return; }
        long at = Math.max(next, System.currentTimeMillis() + 15_000);
        try {
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /** Sends one payload; null on success, else the error text */
    static String deliver(Context ctx, Hook h, JSONObject payload) {
        try {
            if (h.isApp()) {
                String t = h.target.substring(4);
                int slash = t.indexOf('/');
                if (slash <= 0) return "bad app target";
                String pkg = t.substring(0, slash), cls = t.substring(slash + 1);
                if (cls.startsWith(".")) cls = pkg + cls;
                try { ctx.getPackageManager().getPackageInfo(pkg, 0); } catch (Exception e) { return "app not installed: " + pkg; }
                Intent i = new Intent(APP_ACTION).setClassName(pkg, cls);
                i.putExtra("json", payload.toString());
                i.putExtra("hook", h.id);
                if (!h.secret.isEmpty()) i.putExtra("secret", h.secret);
                i.putExtra("ack", true);
                i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                // Ordered broadcast: the receiver must answer setResultCode(RESULT_OK) once it has stored the
                // message. No answer (app blocked from starting by MIUI autostart, crashed, old version) = not
                // delivered, so it stays queued and is retried. Receivers dedupe by payload "id".
                final int[] code = {ACK_NONE};
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                ctx.sendOrderedBroadcast(i, null, new android.content.BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent in) { code[0] = getResultCode(); latch.countDown(); }
                }, null, ACK_NONE, null, null);
                if (!latch.await(45, java.util.concurrent.TimeUnit.SECONDS)) return "app did not answer in 45 s";
                if (code[0] == android.app.Activity.RESULT_OK) return null;
                return "app did not confirm (not started, or no ack) — on Xiaomi allow Autostart for " + pkg;
            }
            HttpURLConnection conn = (HttpURLConnection) new URL(h.target).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "TelegramFork-Webhook/1.0");
            if (!h.secret.isEmpty()) conn.setRequestProperty("X-Webhook-Secret", h.secret);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) { os.write(body); }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) return null;
            String resp = "";
            try (BufferedReader r = new BufferedReader(new InputStreamReader(code >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line; StringBuilder sb = new StringBuilder();
                while ((line = r.readLine()) != null && sb.length() < 300) sb.append(line);
                resp = sb.toString();
            } catch (Exception ignore) {}
            return "HTTP " + code + (resp.isEmpty() ? "" : ": " + resp);
        } catch (Exception e) {
            return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
    }

    /** A test message through the hook, delivered immediately on a background thread; {@code done} gets null or the error */
    public static void test(Context ctx, Hook h, Utilities.Callback<String> done) {
        Utilities.globalQueue.postRunnable(() -> {
            String err;
            try {
                JSONObject o = new JSONObject().put("id", UUID.randomUUID().toString()).put("test", true).put("chat_id", h.dialogId).put("chat", h.name)
                    .put("date", System.currentTimeMillis()).put("text", "Test from Telegram fork");
                err = deliver(ctx, h, o);
            } catch (Exception e) {
                err = e.toString();
            }
            final String e = err;
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> done.run(e));
        });
    }

    /** Clears the queue of one hook (or all when null) */
    public static void clearQueue(String hookId) {
        synchronized (lock) { loadQueue(); queue.removeIf(it -> hookId == null || it.hookId.equals(hookId)); saveQueue(); }
    }

    // endregion
}
