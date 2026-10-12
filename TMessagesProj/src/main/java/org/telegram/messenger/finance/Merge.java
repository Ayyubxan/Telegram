package org.telegram.messenger.finance;

import java.util.ArrayList;
import java.util.List;

/**
 * FORK MOD (Finance): the same payment recorded twice — e.g. a personal card paid for the business (bank bot) and the
 * business bot reported the expense. Merging turns the "paid from" record into a transfer into the other side's account
 * and keeps the other record as the real expense, so nothing is counted twice.
 */
public class Merge {
    /** Candidates for {@code t}: same kind and amount (±1%), other entity, within 3 days, not a transfer */
    public static List<Tx> candidates(Tx t) {
        List<Tx> out = new ArrayList<>();
        if (t == null || t.isTransfer() || t.amount <= 0) return out;
        FinanceDb db = Finance.db();
        FinanceDb.Filter f = new FinanceDb.Filter();
        f.kind = t.kind;
        f.dayFrom = t.day - 3;
        f.dayTo = t.day + 3;
        for (Tx o : db.txs(f)) {
            if (o.id != null && o.id.equals(t.id)) continue;
            if (o.isTransfer() || o.entity == null || o.entity.equals(t.entity)) continue;
            double a = Rates.convert(o.amount, o.currency, t.currency);
            if (Double.isNaN(a)) continue;
            if (Math.abs(a - t.amount) <= Math.max(1, t.amount * 0.01)) out.add(o);
        }
        return out;
    }

    /** Which of the two is the "paid from" side: the personal one, else {@code a} */
    public static Tx paidFrom(Tx a, Tx b) {
        if (Entity.PERSONAL.equals(a.entity)) return a;
        if (Entity.PERSONAL.equals(b.entity)) return b;
        return a;
    }

    /** Converts the "paid from" record into a transfer to the other record's account; the other record stays as the expense/income */
    public static void merge(Tx a, Tx b) {
        FinanceDb db = Finance.db();
        Tx from = paidFrom(a, b);
        Tx keep = from == a ? b : a;
        Account target = db.account(keep.account);
        Entity e = target == null ? null : db.entity(target.entity);
        from.kind = Tx.TRANSFER;
        from.toAccount = keep.account;
        from.toAmount = keep.amount;
        from.category = null;
        String tag = "→ " + (e == null ? "business" : e.name);
        from.note = from.note == null || from.note.isEmpty() ? tag : tag + " · " + from.note;
        db.saveTx(from);
    }
}
