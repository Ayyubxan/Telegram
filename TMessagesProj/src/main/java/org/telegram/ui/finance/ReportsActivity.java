package org.telegram.ui.finance;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.finance.Category;
import org.telegram.messenger.finance.Entity;
import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.finance.FinanceDb;
import org.telegram.messenger.finance.Money;
import org.telegram.messenger.finance.Tx;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.habits.HabitUi;

import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** FORK MOD (Finance): reports — income vs expenses per month, totals by category and by business for a range */
@SuppressLint("SetTextI18n")
public class ReportsActivity extends FinancePage {
    private final String entity;
    private int months = 6;
    private final FinanceDb db = Finance.db();

    public ReportsActivity(String entity) {
        this.entity = entity;
    }

    @Override
    protected CharSequence title() {
        return "Reports";
    }

    @Override
    protected void refresh() {
        Context ctx = getContext();
        content.removeAllViews();
        String base = Finance.baseFor(entity);
        YearMonth now = YearMonth.now();

        LinearLayout chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(4), 0, dp(4), dp(10));
        int[] opts = {3, 6, 12, 24};
        for (int m : opts) {
            TextView chip = HabitUi.chip(ctx, m + "M", m == months, () -> { months = m; refresh(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chips.addView(chip, lp);
        }
        HorizontalScrollView hs = HabitUi.notSection(new HorizontalScrollView(ctx));
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        content.addView(hs);

        // monthly
        String[] labels = new String[months];
        double[] inc = new double[months], exp = new double[months];
        YearMonth[] ym = new YearMonth[months];
        double totalIn = 0, totalOut = 0;
        for (int i = 0; i < months; i++) {
            YearMonth m = now.minusMonths(months - 1 - i);
            ym[i] = m;
            labels[i] = months > 12 ? (m.getMonthValue() == 1 ? String.valueOf(m.getYear() % 100) : m.getMonth().getDisplayName(TextStyle.NARROW, Locale.ENGLISH)) : m.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            long from = m.atDay(1).toEpochDay(), to = m.atEndOfMonth().toEpochDay();
            inc[i] = db.sumKind(Tx.INCOME, from, to, entity);
            exp[i] = db.sumKind(Tx.EXPENSE, from, to, entity);
            totalIn += inc[i]; totalOut += exp[i];
        }
        long rangeFrom = ym[0].atDay(1).toEpochDay(), rangeTo = now.atEndOfMonth().toEpochDay();

        HabitUi.Card head = HabitUi.card(ctx);
        head.addView(HabitUi.text(ctx, "Income vs expenses · last " + months + " months", 13, HabitUi.sub(), true));
        LinearLayout tot = new LinearLayout(ctx);
        tot.setOrientation(LinearLayout.HORIZONTAL);
        tot.setPadding(0, dp(6), 0, 0);
        tot.addView(stat(ctx, "Income", totalIn, base, FinanceUi.income()));
        tot.addView(stat(ctx, "Expenses", totalOut, base, FinanceUi.expense()));
        tot.addView(stat(ctx, "Net", totalIn - totalOut, base, totalIn - totalOut >= 0 ? FinanceUi.income() : FinanceUi.expense()));
        head.addView(tot);
        BarsView bars = new BarsView(ctx);
        bars.setData(labels, inc, exp, base);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170));
        blp.topMargin = dp(10);
        head.addView(bars, blp);
        TextView avg = HabitUi.text(ctx, "Average per month: income " + Money.compact(totalIn / months, base) + " · expenses " + Money.compact(totalOut / months, base), 13, HabitUi.sub());
        avg.setPadding(0, dp(6), 0, 0);
        head.addView(avg);
        content.addView(head);

        content.addView(HabitUi.spacer(ctx, 12));
        HabitUi.Card table = HabitUi.card(ctx);
        for (int i = months - 1; i >= 0; i--) {
            if (i < months - 1) table.addView(HabitUi.divider(ctx, 0));
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(9), 0, dp(9));
            row.addView(HabitUi.text(ctx, FinanceUi.monthLabel(ym[i]), 15, HabitUi.text()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout nums = new LinearLayout(ctx);
            nums.setOrientation(LinearLayout.VERTICAL);
            nums.setGravity(Gravity.END);
            nums.addView(HabitUi.text(ctx, (inc[i] > 0 ? "+" : "") + Money.compact(inc[i], base) + "   " + (exp[i] > 0 ? "−" : "") + Money.compact(exp[i], base), 13, HabitUi.sub()));
            double net = inc[i] - exp[i];
            TextView n = HabitUi.text(ctx, Money.signed(net, base), 15, net >= 0 ? FinanceUi.income() : FinanceUi.expense(), true);
            n.setGravity(Gravity.END);
            nums.addView(n);
            row.addView(nums);
            final YearMonth mm = ym[i];
            row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            row.setOnClickListener(v -> presentFragment(TxListActivity.period("Transactions", entity, -1, null, mm)));
            table.addView(row);
        }
        content.addView(table);

        // by category
        content.addView(HabitUi.section(ctx, "EXPENSES BY CATEGORY"));
        content.addView(categoryCard(ctx, Tx.EXPENSE, rangeFrom, rangeTo, totalOut, base));
        List<FinanceDb.CatSum> incs = db.sumByCategory(Tx.INCOME, rangeFrom, rangeTo, entity);
        if (!incs.isEmpty()) {
            content.addView(HabitUi.section(ctx, "INCOME BY CATEGORY"));
            content.addView(categoryCard(ctx, Tx.INCOME, rangeFrom, rangeTo, totalIn, base));
        }

        // by entity
        List<Entity> ents = db.entities(false);
        if (entity == null && ents.size() > 1) {
            content.addView(HabitUi.section(ctx, "BY BUSINESS"));
            HabitUi.Card ec = HabitUi.card(ctx);
            for (int i = 0; i < ents.size(); i++) {
                Entity e = ents.get(i);
                if (i > 0) ec.addView(HabitUi.divider(ctx, 0));
                double ei = db.sumKind(Tx.INCOME, rangeFrom, rangeTo, e.id), eo = db.sumKind(Tx.EXPENSE, rangeFrom, rangeTo, e.id);
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(9), 0, dp(9));
                LinearLayout texts = new LinearLayout(ctx);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.addView(HabitUi.text(ctx, e.title(), 15, HabitUi.text()));
                texts.addView(HabitUi.text(ctx, (ei > 0 ? "+" : "") + Money.compact(ei, base) + "   " + (eo > 0 ? "−" : "") + Money.compact(eo, base), 13, HabitUi.sub()));
                row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(HabitUi.text(ctx, Money.signed(ei - eo, base), 15, ei - eo >= 0 ? FinanceUi.income() : FinanceUi.expense(), true));
                row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
                row.setOnClickListener(v -> presentFragment(new EntityActivity(e.id)));
                ec.addView(row);
            }
            content.addView(ec);
        }
    }

    private View categoryCard(Context ctx, int kind, long from, long to, double total, String base) {
        HabitUi.Card card = HabitUi.card(ctx);
        List<FinanceDb.CatSum> sums = db.sumByCategory(kind, from, to, entity);
        HashMap<String, Double> byCat = new HashMap<>();
        for (FinanceDb.CatSum s : sums) {
            String key = s.category == null ? "" : s.category;
            byCat.put(key, (byCat.containsKey(key) ? byCat.get(key) : 0) + s.base);
        }
        List<String> keys = new ArrayList<>(byCat.keySet());
        Collections.sort(keys, (a, b) -> Double.compare(byCat.get(b), byCat.get(a)));
        if (keys.isEmpty()) {
            card.addView(HabitUi.text(ctx, "Nothing in this range", 14, HabitUi.sub()));
            return card;
        }
        double max = Math.max(1e-9, byCat.get(keys.get(0)));
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            Category c = key.isEmpty() ? null : db.category(key);
            double v = byCat.get(key);
            int color = c == null ? HabitUi.sub() : c.colorInt();
            if (i > 0) { View gap = new View(ctx); gap.setLayoutParams(new LinearLayout.LayoutParams(1, dp(6))); card.addView(gap); }
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(36), dp(36));
            elp.rightMargin = dp(12);
            row.addView(FinanceUi.emojiCircle(ctx, c == null ? "📦" : c.emoji, color, 36), elp);
            LinearLayout mid = new LinearLayout(ctx);
            mid.setOrientation(LinearLayout.VERTICAL);
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.addView(HabitUi.text(ctx, c == null ? "No category" : c.name, 15, HabitUi.text()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            int pct = total > 0 ? (int) Math.round(v * 100 / total) : 0;
            line.addView(HabitUi.text(ctx, Money.compact(v, base) + "  ·  " + pct + "%", 14, HabitUi.text(), true));
            mid.addView(line);
            TextView per = HabitUi.text(ctx, Money.compact(v / months, base) + " / month", 12, HabitUi.sub());
            mid.addView(per);
            View bar = FinanceUi.bar(ctx, (float) (v / max), color);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(4);
            mid.addView(bar, blp);
            row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(row);
        }
        return card;
    }

    private View stat(Context ctx, String label, double v, String base, int color) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        l.addView(HabitUi.text(ctx, label, 12, HabitUi.sub()));
        TextView t = HabitUi.text(ctx, Money.compact(v, base), 16, color, true);
        t.setMaxLines(1);
        l.addView(t);
        return l;
    }
}
