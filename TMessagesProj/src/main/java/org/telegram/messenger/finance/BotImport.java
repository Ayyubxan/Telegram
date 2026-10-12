package org.telegram.messenger.finance;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;
import java.util.List;

/**
 * FORK MOD (Finance): listens to new messages of chats that have an {@link ImportRule}, parses them with {@link BotParser}
 * and either adds the transaction (auto rules) or puts it into the Inbox for review. Can also scan a chat's recent history.
 */
public class BotImport {
    private static boolean inited;
    private static final NotificationCenter.NotificationCenterDelegate observer = (id, account, args) -> {
        if (id != NotificationCenter.didReceiveNewMessages) return;
        try {
            long dialogId = (Long) args[0];
            @SuppressWarnings("unchecked") ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
            boolean scheduled = args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2];
            if (scheduled) return;
            ImportRule rule = Finance.db().ruleForDialog(dialogId);
            if (rule == null || !rule.enabled) return;
            int n = 0;
            for (MessageObject m : messages) n += handle(rule, m) ? 1 : 0;
            if (n > 0) Finance.notifyChanged();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    };

    /** Observes every account */
    public static void init() {
        if (inited) return;
        inited = true;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter.getInstance(a).addObserver(observer, NotificationCenter.didReceiveNewMessages);
        }
    }

    private static String textOf(MessageObject m) {
        if (m == null || m.messageOwner == null) return null;
        String t = m.messageOwner.message;
        if ((t == null || t.isEmpty()) && m.messageText != null) t = m.messageText.toString();
        return t == null || t.trim().isEmpty() ? null : t;
    }

    /** Parses one message; returns true if something was added (inbox or transaction) */
    public static boolean handle(ImportRule rule, MessageObject m) {
        String text = textOf(m);
        if (text == null || m.isOutOwner()) return false;
        long dialogId = m.getDialogId();
        int msgId = m.getId();
        long dateMs = m.messageOwner.date * 1000L;
        return process(rule, dialogId, msgId, text, dateMs);
    }

    public static boolean process(ImportRule rule, long dialogId, int msgId, String text, long dateMs) {
        FinanceDb db = Finance.db();
        String ext = "tg:" + dialogId + ":" + msgId;
        if (db.hasExt(ext) || db.hasInboxMessage(dialogId, msgId)) return false;
        BotParser.Parsed p = BotParser.parse(text, rule.mapping);
        if (!p.hasAmount()) return false; // not a transaction message (service text, reports…)
        if (p.ts == 0) p.ts = dateMs > 0 ? dateMs : System.currentTimeMillis();
        InboxItem item = new InboxItem();
        item.rule = rule.id; item.dialogId = dialogId; item.msgId = msgId; item.text = text; item.parsed = p;
        CatRule learned = db.catRuleFor(rule.id, merchantKey(p));
        if (rule.auto || (learned != null && learned.auto)) {
            Tx tx = toTx(rule, item);
            if (tx != null && tx.account != null && tx.kind >= 0 && p.kind >= 0) {
                db.saveTx(tx);
                item.status = InboxItem.ACCEPTED;
                db.saveInbox(item);
                return true;
            }
        }
        item.status = InboxItem.PENDING;
        db.saveInbox(item);
        return true;
    }

    /** The text a learned rule is matched against: merchant (📍) line, else the operation title */
    public static String merchantKey(BotParser.Parsed p) {
        if (p == null) return "";
        if (p.note != null && !p.note.isEmpty()) return p.note;
        return p.title == null ? "" : p.title;
    }

    /** A transaction prefilled from an inbox item (account may be null if the card is unknown) */
    public static Tx toTx(ImportRule rule, InboxItem item) {
        BotParser.Parsed p = item.parsed;
        if (p == null || !p.hasAmount()) return null;
        FinanceDb db = Finance.db();
        Tx tx = new Tx();
        tx.kind = p.kind < 0 ? Tx.EXPENSE : p.kind;
        String accId = rule == null ? null : rule.accountFor(p.card);
        Account acc = db.account(accId);
        if (acc != null) {
            tx.account = acc.id;
            tx.currency = acc.currency;
            tx.entity = acc.entity;
        } else {
            tx.currency = p.currency == null ? Finance.base() : p.currency;
        }
        tx.amount = Money.round(p.amount, tx.currency);
        if (rule != null) tx.category = tx.kind == Tx.INCOME ? rule.catIn : rule.catOut;
        CatRule cr = db.catRuleFor(rule == null ? null : rule.id, merchantKey(p));
        if (cr != null) {
            if (cr.category != null && db.category(cr.category) != null) tx.category = cr.category;
            Account ca = cr.account == null ? null : db.account(cr.account);
            if (ca != null) { tx.account = ca.id; tx.currency = ca.currency; tx.entity = ca.entity; tx.amount = Money.round(p.amount, ca.currency); }
        }
        String note = p.note;
        if (!p.title.isEmpty()) note = note.isEmpty() ? p.title : p.title + " · " + note;
        tx.note = note;
        tx.setMoment(p.ts > 0 ? p.ts : System.currentTimeMillis());
        tx.source = Tx.SOURCE_BOT;
        tx.extId = item.extId();
        return tx;
    }

    /** Saves the (edited) transaction, marks the inbox item accepted and remembers the card → account choice */
    public static void accept(InboxItem item, Tx tx) {
        FinanceDb db = Finance.db();
        tx.source = Tx.SOURCE_BOT;
        tx.extId = item.extId();
        db.saveTx(tx);
        db.setInboxStatus(item.id, InboxItem.ACCEPTED);
        ImportRule rule = db.rule(item.rule);
        if (rule != null && item.parsed != null && item.parsed.card != null && tx.account != null && !tx.account.equals(rule.cards.get(item.parsed.card))) {
            rule.cards.put(item.parsed.card, tx.account);
            db.saveRule(rule);
        }
        if (rule != null && tx.category != null) {
            boolean changed = false;
            if (tx.kind == Tx.INCOME && rule.catIn == null) { rule.catIn = tx.category; changed = true; }
            if (tx.kind == Tx.EXPENSE && rule.catOut == null) { rule.catOut = tx.category; changed = true; }
            if (changed) db.saveRule(rule);
        }
    }

    public static void dismiss(InboxItem item) {
        Finance.db().setInboxStatus(item.id, InboxItem.DISMISSED);
    }

    public static int pendingCount() {
        try { return Finance.db().pendingInboxCount(); } catch (Throwable e) { return 0; }
    }

    /** Text of the chat's last message (from the dialog list cache), or null */
    public static String lastMessageText(int account, long dialogId) {
        try {
            ArrayList<MessageObject> list = MessagesController.getInstance(account).dialogMessage.get(dialogId);
            if (list != null) for (MessageObject m : list) { String t = textOf(m); if (t != null) return t; }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    /** Loads the last {@code count} messages of the chat and runs the rule on them; {@code done} gets the number added */
    public static void scan(int account, ImportRule rule, int count, Utilities.Callback<Integer> done) {
        final int guid = ConnectionsManager.generateClassGuid();
        final NotificationCenter nc = NotificationCenter.getInstance(account);
        final boolean[] finished = {false};
        final NotificationCenter.NotificationCenterDelegate obs = new NotificationCenter.NotificationCenterDelegate() {
            @Override
            public void didReceivedNotification(int id, int acc, Object... args) {
                if (id != NotificationCenter.messagesDidLoad || args.length < 11 || !(args[10] instanceof Integer) || (Integer) args[10] != guid) return;
                @SuppressWarnings("unchecked") ArrayList<MessageObject> list = (ArrayList<MessageObject>) args[2];
                boolean isCache = args[3] instanceof Boolean && (Boolean) args[3];
                if (list == null || list.isEmpty() && isCache) return; // the controller re-requests from the server
                if (finished[0]) return;
                finished[0] = true;
                nc.removeObserver(this, NotificationCenter.messagesDidLoad);
                int n = 0;
                for (MessageObject m : list) { try { if (handle(rule, m)) n++; } catch (Throwable e) { FileLog.e(e); } }
                if (n > 0) Finance.notifyChanged();
                done.run(n);
            }
        };
        nc.addObserver(obs, NotificationCenter.messagesDidLoad);
        MessagesController.getInstance(account).loadMessages(rule.dialogId, 0, false, count, 0, 0, false, 0, guid, 2, 0, 0, 0, 0, 1, false);
        AndroidUtilities.runOnUIThread(() -> {
            if (!finished[0]) {
                finished[0] = true;
                nc.removeObserver(obs, NotificationCenter.messagesDidLoad);
                done.run(-1);
            }
        }, 20000);
    }
}
