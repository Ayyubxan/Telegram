package org.telegram.messenger.gmail;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FORK MOD (gmail): the engine. Polls Gmail (initial inbox pull, then history.list), turns every email into a local
 * message of its sender's dialog, pushes replies / read marks / archive / spam / trash back to Gmail, and sends what
 * the user types in a sender chat as an email.
 */
public final class GmailSync {

    private static final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gmail-sync");
        t.setDaemon(true);
        return t;
    });
    private static volatile long lastSyncStart;
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final int ALARM_REQUEST = 7301;

    private GmailSync() {}

    // ---- lifecycle ------------------------------------------------------------------------------------------------

    /** From Nexo.init: restore polling and catch up */
    public static void init(Context ctx) {
        if (GmailStore.isConnected() && GmailStore.isEnabled()) {
            schedule(ctx);
            sync("start", null);
        }
    }

    public static void addListener(Runnable r) {
        listeners.add(r);
    }

    public static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private static void notifyListeners() {
        AndroidUtilities.runOnUIThread(() -> {
            for (Runnable r : listeners) {
                try {
                    r.run();
                } catch (Throwable ignore) {}
            }
        });
    }

    private static PendingIntent alarmIntent(Context ctx) {
        return PendingIntent.getBroadcast(ctx, ALARM_REQUEST, new Intent(ctx, GmailAlarmReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** (Re)arms the poll alarm for one interval from now */
    public static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = alarmIntent(ctx);
            if (!GmailStore.isConnected() || !GmailStore.isEnabled()) {
                am.cancel(pi);
                return;
            }
            long at = System.currentTimeMillis() + GmailStore.intervalMinutes() * 60_000L;
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void cancel(Context ctx) {
        try {
            ((AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE)).cancel(alarmIntent(ctx));
        } catch (Throwable ignore) {}
    }

    /** Cheap "maybe sync" for foreground events; at most one run per 20 s */
    public static void kick(String reason) {
        if (!GmailStore.isConnected() || !GmailStore.isEnabled()) {
            return;
        }
        if (System.currentTimeMillis() - lastSyncStart < 20_000) {
            return;
        }
        sync(reason, null);
    }

    public static void sync(String reason, Runnable done) {
        exec.execute(() -> {
            try {
                doSync(reason);
            } catch (Throwable e) {
                FileLog.e(e);
                GmailStore.setSyncResult(describe(e));
            } finally {
                if (done != null) {
                    done.run();
                }
                notifyListeners();
            }
        });
    }

    /** First-time connection: resolve the account's address, remember it, pull the inbox */
    public static void connect(int account, String token, Utilities.Callback<String> onDone) {
        exec.execute(() -> {
            String error = null;
            try {
                JSONObject profile = GmailApi.profile(token);
                String email = GmailIds.normalize(profile.optString("emailAddress"));
                if (email.isEmpty()) {
                    throw new IOException("Gmail returned no address");
                }
                GmailStore.setConnected(account, email);
                schedule(org.telegram.messenger.ApplicationLoader.applicationContext);
                doSync("connect");
            } catch (Throwable e) {
                FileLog.e(e);
                error = describe(e);
                GmailStore.setSyncResult(error);
            }
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> onDone.run(err));
            notifyListeners();
        });
    }

    public static void disconnect(Context ctx) {
        cancel(ctx);
        GmailAuth.forget();
        GmailStore.disconnect();
        exec.execute(GmailStore::clearAll);
        notifyListeners();
    }

    static String describe(Throwable e) {
        if (e instanceof GmailApi.HttpError) {
            GmailApi.HttpError h = (GmailApi.HttpError) e;
            if (h.code == 401) {
                return "Not authorized — reconnect the Google account";
            }
            if (h.code == 403) {
                return "Gmail refused (403): check the Gmail API is enabled and the scopes were granted";
            }
            return e.getMessage();
        }
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    // ---- token helpers --------------------------------------------------------------------------------------------

    private interface Job {
        void run(String token) throws Exception;
    }

    /** Runs the job with a token; on 401 refreshes once and retries */
    private static void withToken(Job job) throws Exception {
        String token = GmailAuth.tokenBlocking();
        if (token == null) {
            throw new IOException("Needs re-authorization");
        }
        try {
            job.run(token);
        } catch (GmailApi.HttpError e) {
            if (e.code != 401) {
                throw e;
            }
            token = GmailAuth.refreshBlocking();
            if (token == null) {
                throw new IOException("Needs re-authorization");
            }
            job.run(token);
        }
    }

    // ---- polling --------------------------------------------------------------------------------------------------

    private static void doSync(String reason) throws Exception {
        if (!GmailStore.isConnected() || !GmailStore.isEnabled()) {
            return;
        }
        lastSyncStart = System.currentTimeMillis();
        final int account = GmailStore.account();
        withToken(token -> {
            String historyId = GmailStore.historyId();
            if (historyId == null) {
                initialSync(account, token);
            } else {
                incrementalSync(account, token, historyId);
            }
        });
        GmailStore.setSyncResult(null);
    }

    private static void initialSync(int account, String token) throws Exception {
        JSONObject profile = GmailApi.profile(token);
        String newHistory = profile.optString("historyId", null);
        JSONObject list = GmailApi.listMessages(token, "in:inbox", Math.max(1, GmailStore.initialCount()), null);
        JSONArray arr = list.optJSONArray("messages");
        ArrayList<String> ids = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    ids.add(o.optString("id"));
                }
            }
        }
        Collections.reverse(ids); // oldest first so local ids follow time
        for (String id : ids) {
            if (!GmailStore.hasGmailId(id)) {
                fetchAndInsert(account, token, id, false);
            }
        }
        if (newHistory != null) {
            GmailStore.setHistoryId(newHistory);
        }
    }

    private static void incrementalSync(int account, String token, String historyId) throws Exception {
        LinkedHashSet<String> added = new LinkedHashSet<>();
        LinkedHashSet<String> readIds = new LinkedHashSet<>();
        String latest = historyId, pageToken = null;
        try {
            do {
                JSONObject h = GmailApi.history(token, historyId, null, pageToken);
                latest = h.optString("historyId", latest);
                JSONArray hist = h.optJSONArray("history");
                if (hist != null) {
                    for (int i = 0; i < hist.length(); i++) {
                        JSONObject rec = hist.optJSONObject(i);
                        if (rec == null) {
                            continue;
                        }
                        JSONArray ma = rec.optJSONArray("messagesAdded");
                        if (ma != null) {
                            for (int k = 0; k < ma.length(); k++) {
                                JSONObject m = ma.optJSONObject(k) == null ? null : ma.optJSONObject(k).optJSONObject("message");
                                if (m != null && wanted(m.optJSONArray("labelIds"))) {
                                    added.add(m.optString("id"));
                                }
                            }
                        }
                        JSONArray lr = rec.optJSONArray("labelsRemoved");
                        if (lr != null) {
                            for (int k = 0; k < lr.length(); k++) {
                                JSONObject e = lr.optJSONObject(k);
                                if (e != null && contains(e.optJSONArray("labelIds"), "UNREAD")) {
                                    JSONObject m = e.optJSONObject("message");
                                    if (m != null) {
                                        readIds.add(m.optString("id"));
                                    }
                                }
                            }
                        }
                    }
                }
                pageToken = h.optString("nextPageToken", null);
                if (TextUtils.isEmpty(pageToken)) {
                    pageToken = null;
                }
            } while (pageToken != null);
        } catch (GmailApi.HttpError e) {
            if (e.code == 404) { // history too old: start over, known emails are skipped
                GmailStore.setHistoryId(null);
                initialSync(account, token);
                return;
            }
            throw e;
        }
        for (String id : added) {
            if (!GmailStore.hasGmailId(id)) {
                try {
                    fetchAndInsert(account, token, id, true);
                } catch (GmailApi.HttpError e) {
                    if (e.code != 404) { // deleted meanwhile
                        throw e;
                    }
                }
            }
        }
        if (!readIds.isEmpty()) {
            applyRemoteRead(account, new ArrayList<>(readIds));
        }
        GmailStore.setHistoryId(latest);
    }

    /** Only inbox mail and what I sent myself become messages; drafts, spam, trash and chats do not */
    private static boolean wanted(JSONArray labels) {
        if (labels == null) {
            return true; // history records may omit labels; the full fetch decides
        }
        boolean inbox = contains(labels, "INBOX"), sent = contains(labels, "SENT");
        if (contains(labels, "DRAFT") || contains(labels, "SPAM") || contains(labels, "TRASH") || contains(labels, "CHAT")) {
            return false;
        }
        return inbox || sent;
    }

    private static boolean contains(JSONArray arr, String s) {
        if (arr == null) {
            return false;
        }
        for (int i = 0; i < arr.length(); i++) {
            if (s.equals(arr.optString(i))) {
                return true;
            }
        }
        return false;
    }

    private static void fetchAndInsert(int account, String token, String id, boolean notify) throws Exception {
        JSONObject m = GmailApi.getMessage(token, id, "full");
        insert(account, GmailMime.parse(m), notify);
    }

    // ---- email → local message ------------------------------------------------------------------------------------

    private static void insert(int account, GmailMime.Parsed p, boolean notify) {
        if (p.labels.contains("DRAFT") || p.labels.contains("SPAM") || p.labels.contains("TRASH")) {
            return;
        }
        String me = GmailIds.normalize(GmailStore.email());
        boolean out = p.labels.contains("SENT") || !p.labels.contains("INBOX") && p.fromEmail.equals(me);
        String counterpart = out ? GmailMime.firstAddress(p.to) : p.fromEmail;
        if (counterpart.isEmpty()) {
            counterpart = p.fromEmail;
        }
        if (counterpart.isEmpty()) {
            return;
        }
        long did = GmailIds.senderId(counterpart);
        if (GmailStore.isSpamSender(did)) {
            return;
        }
        String name = out ? displayName(counterpart, null) : displayName(counterpart, p.fromName);
        ensureUser(account, did, counterpart, name);

        List<GmailMime.Body> bodies = GmailMime.toTelegram(p);
        if (!out) { // FORK MOD (gmail): a one-time code goes on top as monospace — visible in the notification, one tap copies it
            String otp = GmailMime.findOtp(p.subject, !TextUtils.isEmpty(p.text) ? p.text : bodies.get(0).text);
            if (otp != null) {
                GmailMime.Body first = bodies.get(0);
                String prefix = "🔑 " + otp + "\n";
                int shift = prefix.length();
                for (TLRPC.MessageEntity e : first.entities) {
                    e.offset += shift;
                }
                TLRPC.TL_messageEntityCode code = new TLRPC.TL_messageEntityCode();
                code.offset = "🔑 ".length();
                code.length = otp.length();
                first.entities.add(0, code);
                first.text = prefix + first.text;
            }
        }

        int date = (int) (p.internalDate / 1000);
        boolean unread = !out && p.labels.contains("UNREAD");
        long myId = UserConfig.getInstance(account).getClientUserId();
        ArrayList<TLRPC.Message> msgs = new ArrayList<>();
        ArrayList<MessageObject> objs = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            GmailMime.Body body = bodies.get(i);
            int mid = GmailStore.nextMid();
            TLRPC.TL_message m = new TLRPC.TL_message();
            m.id = mid;
            m.dialog_id = did;
            m.date = date;
            m.message = body.text;
            m.entities = body.entities;
            if (!body.entities.isEmpty()) {
                m.flags |= TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
            }
            m.peer_id = new TLRPC.TL_peerUser();
            m.peer_id.user_id = did;
            m.from_id = new TLRPC.TL_peerUser();
            m.from_id.user_id = out ? myId : did;
            m.flags |= TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
            m.out = out;
            m.unread = unread;
            m.send_state = 0;
            msgs.add(m);
            objs.add(new MessageObject(account, m, false, true));

            GmailStore.Row row = new GmailStore.Row();
            row.gmailId = i == 0 ? p.id : p.id + "#" + i;
            row.threadId = p.threadId;
            row.did = did;
            row.mid = mid;
            row.messageIdHeader = p.messageId;
            row.references = p.references;
            row.subject = p.subject;
            row.fromEmail = p.fromEmail;
            row.date = date;
            row.out = out;
            row.unread = unread;
            row.inbox = p.labels.contains("INBOX");
            GmailStore.putMessage(row);
        }
        // every attachment is a file message of its own, downloaded from Gmail when tapped
        int idx = 0;
        for (GmailMime.Attachment a : p.attachments) {
            if (a.attachmentId == null || a.inline && a.size < 15 * 1024) {
                continue; // signature logos, tracking pixels
            }
            int mid = GmailStore.nextMid();
            TLRPC.TL_message m = new TLRPC.TL_message();
            m.id = mid;
            m.dialog_id = did;
            m.date = date;
            m.message = "";
            m.peer_id = new TLRPC.TL_peerUser();
            m.peer_id.user_id = did;
            m.from_id = new TLRPC.TL_peerUser();
            m.from_id.user_id = out ? myId : did;
            m.flags |= TLRPC.MESSAGE_FLAG_HAS_FROM_ID | TLRPC.MESSAGE_FLAG_HAS_MEDIA;
            TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
            media.flags |= 1;
            media.document = GmailFiles.buildDocument(mid, date, a.filename, a.mime, a.size);
            m.media = media;
            m.out = out;
            m.unread = unread;
            m.send_state = 0;
            msgs.add(m);
            objs.add(new MessageObject(account, m, false, true));

            GmailStore.Attachment sa = new GmailStore.Attachment();
            sa.did = did;
            sa.mid = mid;
            sa.index = idx++;
            sa.gmailId = p.id;
            sa.attachmentId = a.attachmentId;
            sa.filename = a.filename;
            sa.mime = a.mime;
            sa.size = a.size;
            GmailStore.putAttachment(sa);
        }
        GmailStore.putSender(did, counterpart, name);

        MessagesStorage st = MessagesStorage.getInstance(account);
        st.putMessages(msgs, false, true, false, 0, 0, 0);
        st.getStorageQueue().postRunnable(() -> {
            try { // non-zero read ids keep processLoadedDialogs from asking the server about this dialog
                st.getDatabase().executeFast("UPDATE dialogs SET inbox_max = 1, outbox_max = 1 WHERE did = " + did + " AND (inbox_max = 0 OR outbox_max = 0)").stepThis().dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
        AndroidUtilities.runOnUIThread(() -> {
            MessagesController mc = MessagesController.getInstance(account);
            mc.updateInterfaceWithMessages(did, objs, 0);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
            if (notify && unread) {
                NotificationsController.getInstance(account).processNewMessages(objs, true, false, null);
            }
        });
    }

    private static String displayName(String email, String headerName) {
        String n = headerName == null ? "" : headerName.trim();
        if (n.isEmpty() || n.contains("@")) {
            String existing = null;
            long did = GmailIds.senderId(email);
            TLRPC.User u = MessagesController.getInstance(Math.max(0, GmailStore.account())).getUser(did);
            if (u != null && !TextUtils.isEmpty(u.first_name)) {
                existing = u.first_name;
            }
            if (existing != null) {
                return existing;
            }
            int at = email.indexOf('@');
            n = at > 0 ? email.substring(0, at) : email;
            n = n.replace('.', ' ').replace('_', ' ');
            if (!n.isEmpty()) {
                n = Character.toUpperCase(n.charAt(0)) + n.substring(1);
            }
        }
        return n;
    }

    private static void ensureUser(int account, long did, String email, String name) {
        MessagesController mc = MessagesController.getInstance(account);
        boolean hasAvatar = GmailFiles.avatarState(did) >= 0 && GmailFiles.wantsAvatar(email) && GmailFiles.fetchAvatar(did, email);
        TLRPC.User existing = mc.getUser(did);
        if (existing != null && name.equals(existing.first_name) && (existing.photo != null) == hasAvatar && existing.access_hash == GmailFiles.USER_HASH) {
            return;
        }
        TLRPC.TL_user u = new TLRPC.TL_user();
        u.id = did;
        u.first_name = name;
        u.access_hash = GmailFiles.USER_HASH;
        u.flags = 1 | 2;
        if (hasAvatar) {
            u.photo = GmailFiles.avatarPhoto(did);
            u.flags |= 32;
        }
        ArrayList<TLRPC.User> list = new ArrayList<>();
        list.add(u);
        MessagesStorage.getInstance(account).putUsersAndChats(list, null, true, true);
        AndroidUtilities.runOnUIThread(() -> mc.putUsers(list, false));
    }

    private static void applyRemoteRead(int account, List<String> gmailIds) {
        GmailStore.markRead(gmailIds);
        HashMap<Long, int[]> maxByDialog = new HashMap<>();
        for (String id : gmailIds) {
            GmailStore.Row r = GmailStore.byGmailId(id);
            if (r == null || r.out) {
                continue;
            }
            int[] cur = maxByDialog.get(r.did);
            if (cur == null) {
                maxByDialog.put(r.did, new int[]{r.mid, r.date});
            } else if (r.mid > cur[0]) {
                cur[0] = r.mid;
                cur[1] = r.date;
            }
        }
        if (maxByDialog.isEmpty()) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            MessagesController mc = MessagesController.getInstance(account);
            for (Map.Entry<Long, int[]> e : maxByDialog.entrySet()) {
                mc.markDialogAsRead(e.getKey(), e.getValue()[0], 0, e.getValue()[1], false, 0, 0, true, 0);
            }
        });
    }

    // ---- actions from the app ------------------------------------------------------------------------------------

    private static String apiId(String storedId) {
        int i = storedId.indexOf('#');
        return i < 0 ? storedId : storedId.substring(0, i);
    }

    private static List<String> apiIds(long did) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (GmailStore.Row r : GmailStore.all(did)) {
            ids.add(apiId(r.gmailId));
        }
        return new ArrayList<>(ids);
    }

    /** The user read the chat up to {@code maxId} (from completeReadTask) */
    public static void onRead(int account, long did, int maxId) {
        exec.execute(() -> {
            try {
                List<String> stored = GmailStore.unreadUpTo(did, maxId);
                if (stored.isEmpty()) {
                    return;
                }
                LinkedHashSet<String> ids = new LinkedHashSet<>();
                for (String s : stored) {
                    ids.add(apiId(s));
                }
                withToken(token -> GmailApi.batchModify(token, new ArrayList<>(ids), null, Collections.singletonList("UNREAD")));
                GmailStore.markRead(stored);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** Telegram archive ↔ Gmail inbox: archiving the chat removes INBOX from the sender's emails, unarchiving puts it back */
    public static void onFolderChanged(int account, long did, int folderId) {
        final boolean archived = folderId == 1;
        exec.execute(() -> {
            try {
                List<String> ids = apiIds(did);
                if (ids.isEmpty()) {
                    return;
                }
                withToken(token -> GmailApi.batchModify(token, ids, archived ? null : Collections.singletonList("INBOX"), archived ? Collections.singletonList("INBOX") : null));
                GmailStore.setInbox(did, !archived);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** Report the sender as spam: Gmail moves everything to Spam, the chat disappears here, later mail is ignored */
    public static void markSpam(int account, long did, Utilities.Callback<String> onDone) {
        exec.execute(() -> {
            String error = null;
            try {
                List<String> ids = apiIds(did);
                if (!ids.isEmpty()) {
                    withToken(token -> GmailApi.batchModify(token, ids, Collections.singletonList("SPAM"), Collections.singletonList("INBOX")));
                }
                GmailStore.setSpamSender(did, true);
            } catch (Throwable e) {
                FileLog.e(e);
                error = describe(e);
            }
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (err == null) {
                    MessagesController.getInstance(account).deleteDialog(did, 0);
                }
                onDone.run(err);
            });
        });
    }

    /** Messages deleted in the chat go to Gmail's trash */
    public static void onMessagesDeleted(int account, long did, List<Integer> mids) {
        if (mids == null || mids.isEmpty()) {
            return;
        }
        final ArrayList<Integer> copy = new ArrayList<>(mids);
        exec.execute(() -> {
            try {
                LinkedHashSet<String> ids = new LinkedHashSet<>();
                for (String s : GmailStore.gmailIds(did, copy)) {
                    ids.add(apiId(s));
                }
                if (!ids.isEmpty()) {
                    withToken(token -> {
                        for (String id : ids) {
                            try {
                                GmailApi.trash(token, id);
                            } catch (GmailApi.HttpError e) {
                                if (e.code != 404) {
                                    throw e;
                                }
                            }
                        }
                    });
                }
                GmailStore.deleteMessages(did, copy);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** The chat was deleted locally: forget the mapping (the emails stay in Gmail) */
    public static void onDialogDeleted(long did) {
        exec.execute(() -> GmailStore.deleteDialog(did));
    }

    // ---- sending --------------------------------------------------------------------------------------------------

    /** Called from SendMessagesHelper after the message was stored locally as "sending" */
    public static void send(int account, MessageObject obj, String path, TLRPC.TL_document document, TLRPC.TL_photo photo) {
        final TLRPC.Message msg = obj.messageOwner;
        final long did = msg.dialog_id;
        final int mid = msg.id;
        final String text = msg.message == null ? "" : msg.message;
        exec.execute(() -> {
            String error = null;
            try {
                String to = GmailStore.senderEmail(did);
                if (TextUtils.isEmpty(to)) {
                    throw new IOException("Unknown recipient address");
                }
                ArrayList<GmailMime.OutAttachment> files = new ArrayList<>();
                if (!TextUtils.isEmpty(path)) {
                    File f = new File(path);
                    if (f.exists() && f.length() > 0) {
                        if (f.length() > 24L * 1024 * 1024) {
                            throw new IOException("Attachments over 24 MB cannot be emailed");
                        }
                        String name = f.getName();
                        String mime = null;
                        if (document != null) {
                            mime = document.mime_type;
                            for (TLRPC.DocumentAttribute a : document.attributes) {
                                if (a instanceof TLRPC.TL_documentAttributeFilename && !TextUtils.isEmpty(a.file_name)) {
                                    name = a.file_name;
                                }
                            }
                        } else if (photo != null) {
                            mime = "image/jpeg";
                            if (!name.toLowerCase(Locale.US).endsWith(".jpg")) {
                                name = "photo.jpg";
                            }
                        }
                        if (TextUtils.isEmpty(mime)) {
                            String ext = MimeTypeMap.getFileExtensionFromUrl(name);
                            mime = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.US));
                            if (mime == null) {
                                mime = "application/octet-stream";
                            }
                        }
                        files.add(new GmailMime.OutAttachment(name, mime, readFile(f)));
                    }
                }
                if (text.trim().isEmpty() && files.isEmpty()) {
                    throw new IOException("Only text and files can be sent by email");
                }
                GmailStore.Row last = GmailStore.lastIncoming(did);
                String subject = last != null ? GmailMime.replySubject(last.subject) : firstLine(text);
                String inReplyTo = last != null ? last.messageIdHeader : null;
                String refs = last != null ? ((last.references == null ? "" : last.references) + " " + (last.messageIdHeader == null ? "" : last.messageIdHeader)).trim() : null;
                String messageId = GmailMime.newMessageId();
                String from = GmailStore.email();
                byte[] raw = GmailMime.build(from, to, subject, messageId, inReplyTo, refs, text, files);
                final String threadId = last != null ? last.threadId : null;
                final JSONObject[] result = new JSONObject[1];
                withToken(token -> result[0] = GmailApi.send(token, raw, threadId));
                GmailStore.Row row = new GmailStore.Row();
                row.gmailId = result[0].optString("id", messageId);
                row.threadId = result[0].optString("threadId", threadId);
                row.did = did;
                row.mid = mid;
                row.messageIdHeader = messageId;
                row.references = refs;
                row.subject = subject;
                row.fromEmail = from;
                row.date = msg.date;
                row.out = true;
                row.unread = false;
                row.inbox = false;
                GmailStore.putMessage(row);
            } catch (Throwable e) {
                FileLog.e(e);
                error = describe(e);
            }
            final String err = error;
            MessagesStorage st = MessagesStorage.getInstance(account);
            NotificationCenter nc = NotificationCenter.getInstance(account);
            AndroidUtilities.runOnUIThread(() -> {
                if (err == null) {
                    msg.send_state = MessageObject.MESSAGE_SEND_STATE_SENT;
                    st.getStorageQueue().postRunnable(() -> {
                        try {
                            st.getDatabase().executeFast("UPDATE messages_v2 SET send_state = 0 WHERE mid = " + mid + " AND uid = " + did).stepThis().dispose();
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    });
                    nc.postNotificationName(NotificationCenter.messageReceivedByServer, mid, mid, msg, did, 0L, 0, false);
                    nc.postNotificationName(NotificationCenter.messageReceivedByServer2, mid, mid, msg, did, 0L, 0, false);
                } else {
                    msg.send_state = MessageObject.MESSAGE_SEND_STATE_SEND_ERROR;
                    st.markMessageAsSendError(msg, 0);
                    nc.postNotificationName(NotificationCenter.messageSendError, mid);
                    GmailStore.setSyncResult("Send failed: " + err);
                    notifyListeners();
                }
            });
        });
    }

    private static String firstLine(String text) {
        String t = text.trim();
        int nl = t.indexOf('\n');
        if (nl > 0) {
            t = t.substring(0, nl);
        }
        return t.length() > 70 ? t.substring(0, 67) + "…" : t;
    }

    private static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] out = new byte[(int) f.length()];
            int off = 0, n;
            while (off < out.length && (n = in.read(out, off, out.length - off)) > 0) {
                off += n;
            }
            return out;
        }
    }
}
