package org.telegram.messenger.gmail;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageCustomParamsHelper;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.Locale;

/**
 * FORK MOD (gmail): history of a sender dialog comes straight from {@code messages_v2}; the server is never asked.
 * The result is handed to {@code processLoadedMessages} as if it came from the server (isCache = false), so
 * ChatActivity marks the end of history instead of retrying against Telegram.
 */
public final class GmailLocal {

    private GmailLocal() {}

    public static void loadMessages(int account, long dialogId, long mergeDialogId, int count, int max_id, int offset_date, int classGuid, int load_type, int mode, long threadMessageId, int loadIndex, boolean processMessages, boolean isTopic) {
        MessagesStorage st = MessagesStorage.getInstance(account);
        st.getStorageQueue().postRunnable(() -> {
            TLRPC.TL_messages_messages res = new TLRPC.TL_messages_messages();
            boolean isEnd = true;
            int firstUnread = 0, unreadCount = 0;
            if (mode == 0) {
                SQLiteCursor cursor = null;
                try {
                    String where;
                    boolean ascending = false;
                    if (load_type == MessagesController.LOAD_BACKWARD && max_id > 0) {
                        where = String.format(Locale.US, "m.uid = %d AND m.mid < %d ORDER BY m.mid DESC", dialogId, max_id);
                    } else if (load_type == MessagesController.LOAD_FORWARD && max_id > 0) {
                        where = String.format(Locale.US, "m.uid = %d AND m.mid > %d ORDER BY m.mid ASC", dialogId, max_id);
                        ascending = true;
                    } else {
                        where = String.format(Locale.US, "m.uid = %d ORDER BY m.mid DESC", dialogId);
                    }
                    long currentUserId = UserConfig.getInstance(account).getClientUserId();
                    cursor = st.getDatabase().queryFinalized("SELECT m.read_state, m.data, m.send_state, m.mid, m.date, m.custom_params FROM messages_v2 as m WHERE " + where + " LIMIT " + count);
                    while (cursor.next()) {
                        NativeByteBuffer data = cursor.byteBufferValue(1);
                        if (data == null) {
                            continue;
                        }
                        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                        message.send_state = cursor.intValue(2);
                        message.id = cursor.intValue(3);
                        if (message.id > 0 && message.send_state != 0 && message.send_state != 3) {
                            message.send_state = 0;
                        }
                        message.readAttachPath(data, currentUserId);
                        data.reuse();
                        message.date = cursor.intValue(4);
                        message.dialog_id = dialogId;
                        message.unread = (cursor.intValue(0) & 1) == 0;
                        NativeByteBuffer params = cursor.byteBufferValue(5);
                        if (params != null) {
                            MessageCustomParamsHelper.readLocalParams(message, params);
                            params.reuse();
                        }
                        res.messages.add(message);
                    }
                    cursor.dispose();
                    cursor = null;
                    if (ascending) {
                        java.util.Collections.reverse(res.messages);
                    }
                    isEnd = res.messages.size() < count;
                    if (load_type == MessagesController.LOAD_FROM_UNREAD) {
                        cursor = st.getDatabase().queryFinalized(String.format(Locale.US, "SELECT min(mid), COUNT(*) FROM messages_v2 WHERE uid = %d AND out = 0 AND read_state IN(0,2)", dialogId));
                        if (cursor.next()) {
                            firstUnread = cursor.intValue(0);
                            unreadCount = cursor.intValue(1);
                        }
                        cursor.dispose();
                        cursor = null;
                    }
                    TLRPC.User sender = st.getUser(dialogId);
                    if (sender != null) {
                        res.users.add(sender);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                } finally {
                    if (cursor != null) {
                        cursor.dispose();
                    }
                }
            }
            final boolean endFinal = isEnd;
            final int firstUnreadFinal = firstUnread, unreadCountFinal = unreadCount;
            Utilities.stageQueue.postRunnable(() -> MessagesController.getInstance(account).processLoadedMessages(res, res.messages.size(), dialogId, mergeDialogId, count, max_id, offset_date, false, classGuid, firstUnreadFinal, 0, unreadCountFinal, 0, load_type, endFinal, mode, threadMessageId, loadIndex, false, 0, processMessages, isTopic, null));
        });
    }
}
