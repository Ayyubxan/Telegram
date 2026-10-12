package org.telegram.messenger.finance;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * FORK MOD (Finance): net worth = accounts + assets + money owed to me − money I owe, in the base currency.
 * Today's value is stored as a daily snapshot; history before the first snapshot is estimated from account balances,
 * manual valuations and debt payments at that day (quoted assets use today's rates).
 */
public class NetWorth {
    public static class Breakdown {
        public double accounts, assets, receivable, payable;
        public final HashSet<String> noRate = new HashSet<>();

        public double total() {
            return accounts + assets + receivable - payable;
        }
    }

    /** Current value of an asset in the base currency; NaN if the rate is unknown */
    public static double assetValue(Asset a) {
        return assetValueAt(a, Long.MAX_VALUE);
    }

    public static double assetValueAt(Asset a, long day) {
        return assetValueAt(a, day, Finance.baseFor(a.entity));
    }

    /** Value in {@code base}; NaN if the rate is unknown */
    public static double assetValueAt(Asset a, long day, String base) {
        if (a.cls == Asset.CLS_QUOTED) {
            if (a.code == null || a.code.isEmpty()) return 0;
            double r = Rates.uzs(a.code);
            if (Double.isNaN(r)) return Double.NaN;
            return Rates.convertOr0(a.qty * r, "UZS", base);
        }
        double v = Finance.db().assetValueAt(a.id, day);
        if (Double.isNaN(v)) return 0;
        if (!Rates.known(a.currency)) return Double.NaN;
        return Rates.convertOr0(v, a.currency, base);
    }

    /** Price of one unit of a quoted asset in the asset's entity currency (NaN if unknown) */
    public static double unitPrice(Asset a) {
        if (a.cls != Asset.CLS_QUOTED || a.code == null) return Double.NaN;
        double r = Rates.uzs(a.code);
        return Double.isNaN(r) ? Double.NaN : Rates.convertOr0(r, "UZS", Finance.baseFor(a.entity));
    }

    public static Breakdown now(String entity) {
        return at(Long.MAX_VALUE, entity);
    }

    /** Estimate at the end of {@code day} (epoch day; Long.MAX_VALUE = now). {@code entity} null — everything. */
    public static Breakdown at(long day, String entity) {
        FinanceDb db = Finance.db();
        Breakdown b = new Breakdown();
        String base = Finance.baseFor(entity);
        Map<String, Double> balances = day == Long.MAX_VALUE ? db.balances() : db.balancesAt(day);
        for (Account a : db.accounts(true)) {
            if (!a.wealth || (entity != null && !entity.equals(a.entity))) continue;
            if (a.archived && day == Long.MAX_VALUE) continue;
            Double v = balances.get(a.id);
            if (v == null || v == 0) continue;
            if (!Rates.known(a.currency)) { b.noRate.add(a.currency); continue; }
            b.accounts += Rates.convertOr0(v, a.currency, base);
        }
        for (Asset a : db.assets(false)) {
            if (entity != null && !entity.equals(a.entity)) continue;
            double v = assetValueAt(a, day, base);
            if (Double.isNaN(v)) { b.noRate.add(a.cls == Asset.CLS_QUOTED ? a.code : a.currency); continue; }
            b.assets += v;
        }
        for (Debt d : db.debts(false)) {
            String de = d.entity == null || d.entity.isEmpty() ? Entity.PERSONAL : d.entity;
            if (entity != null && !entity.equals(de)) continue;
            if (day != Long.MAX_VALUE && d.day > day) continue;
            double paid = d.paid;
            if (day != Long.MAX_VALUE) {
                paid = 0;
                for (Object[] p : db.debtPayments(d.id)) if ((Long) p[2] <= day) paid += (Double) p[1];
            }
            double left = Math.max(0, d.amount - paid);
            if (left == 0) continue;
            if (!Rates.known(d.currency)) { b.noRate.add(d.currency); continue; }
            double v = Rates.convertOr0(left, d.currency, base);
            if (d.dir == Debt.OWED_TO_ME) b.receivable += v; else b.payable += v;
        }
        return b;
    }

    /** Stores today's total (all entities) — called whenever the Finance tab refreshes */
    public static void snapshotToday() {
        try {
            Breakdown b = now(null);
            if (!b.noRate.isEmpty() && b.total() == 0) return;
            JSONObject j = new JSONObject().put("accounts", b.accounts).put("assets", b.assets).put("receivable", b.receivable).put("payable", b.payable).put("base", Finance.base());
            double uzs = Rates.convertOr0(b.total(), Finance.base(), "UZS");
            Finance.db().saveSnapshot(FinanceDb.today(), uzs, j.toString());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * {day, value in base} points from {@code fromDay} to today. Daily up to 120 days, otherwise ~120 samples.
     * All-entity series use stored snapshots where they exist; the rest is estimated.
     */
    public static List<double[]> series(long fromDay, String entity) {
        long today = FinanceDb.today();
        if (fromDay > today) fromDay = today;
        long span = today - fromDay;
        long step = Math.max(1, span / 120);
        List<double[]> out = new ArrayList<>();
        Map<Long, Double> snaps = null;
        if (entity == null) {
            snaps = new java.util.HashMap<>();
            for (double[] s : Finance.db().snapshots(fromDay)) snaps.put((long) s[0], s[1]);
        }
        String base = Finance.baseFor(entity);
        for (long d = fromDay; d <= today; d += step) {
            long day = Math.min(d, today);
            double v;
            if (snaps != null && snaps.containsKey(day)) v = Rates.convertOr0(snaps.get(day), "UZS", base);
            else v = at(day == today ? Long.MAX_VALUE : day, entity).total();
            out.add(new double[]{day, v});
            if (day == today) break;
        }
        if (out.isEmpty() || (long) out.get(out.size() - 1)[0] != today) out.add(new double[]{today, now(entity).total()});
        return out;
    }

    /** First day that has any data (earliest transaction / valuation / debt), or today */
    public static long firstDay() {
        FinanceDb db = Finance.db();
        long first = FinanceDb.today();
        FinanceDb.Filter f = new FinanceDb.Filter();
        List<Tx> all = db.txs(f);
        if (!all.isEmpty()) first = Math.min(first, all.get(all.size() - 1).day);
        for (Asset a : db.assets(true)) for (double[] v : db.assetValues(a.id)) first = Math.min(first, (long) v[0]);
        for (Debt d : db.debts(true)) if (d.day > 0) first = Math.min(first, d.day);
        List<double[]> snaps = db.snapshots(0);
        if (!snaps.isEmpty()) first = Math.min(first, (long) snaps.get(0)[0]);
        return first;
    }

    public static LocalDate dayOf(long day) {
        return LocalDate.ofEpochDay(day);
    }
}
