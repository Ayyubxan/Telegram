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

import org.telegram.messenger.R;
import org.telegram.messenger.finance.Asset;
import org.telegram.messenger.finance.Currencies;
import org.telegram.messenger.finance.Entity;
import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.finance.FinanceDb;
import org.telegram.messenger.finance.Money;
import org.telegram.messenger.finance.NetWorth;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.habits.HabitUi;

import java.time.LocalDate;
import java.util.List;

/** FORK MOD (Finance): wealth — net worth with a growth chart, breakdown, and the asset list */
@SuppressLint("SetTextI18n")
public class WealthActivity extends FinancePage {
    private static final int MENU_ADD = 1;
    private final String entity;
    private String range = "3M";
    private boolean showArchived;

    public WealthActivity(String entity) {
        this.entity = entity;
    }

    @Override
    protected CharSequence title() {
        return "Wealth";
    }

    @Override
    protected void buildMenu(ActionBarMenu menu) {
        menu.addItem(MENU_ADD, R.drawable.msg_add);
    }

    @Override
    protected void onMenuClick(int id) {
        if (id == MENU_ADD) presentFragment(new AssetEditActivity(null, entity));
    }

    private long fromDay() {
        LocalDate t = LocalDate.now();
        switch (range) {
            case "1M": return t.minusMonths(1).toEpochDay();
            case "6M": return t.minusMonths(6).toEpochDay();
            case "1Y": return t.minusYears(1).toEpochDay();
            case "All": return Math.min(NetWorth.firstDay(), t.minusMonths(1).toEpochDay());
            default: return t.minusMonths(3).toEpochDay();
        }
    }

    @Override
    protected void refresh() {
        Context ctx = getContext();
        if (ctx == null) return;
        content.removeAllViews();
        FinanceDb db = Finance.db();
        String base = Finance.baseFor(entity);
        NetWorth.Breakdown b = NetWorth.now(entity);
        NetWorth.snapshotToday();

        // range chips
        LinearLayout chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(4), 0, dp(4), dp(10));
        for (String r : new String[]{"1M", "3M", "6M", "1Y", "All"}) {
            TextView chip = HabitUi.chip(ctx, r, r.equals(range), () -> { range = r; refresh(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chips.addView(chip, lp);
        }
        HorizontalScrollView hs = HabitUi.notSection(new HorizontalScrollView(ctx));
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        content.addView(hs);

        // header + chart
        HabitUi.Card head = HabitUi.card(ctx);
        head.addView(HabitUi.text(ctx, entity == null ? "Net worth" : "Net worth · " + entityName(entity), 13, HabitUi.sub(), true));
        head.addView(HabitUi.text(ctx, Money.format(b.total(), base), 30, HabitUi.text(), true));
        List<double[]> series = NetWorth.series(fromDay(), entity);
        if (series.size() >= 2) {
            double first = series.get(0)[1], last = series.get(series.size() - 1)[1];
            double diff = last - first;
            String pct = Math.abs(first) > 1e-9 ? String.format(java.util.Locale.US, " (%+.1f%%)", diff * 100 / Math.abs(first)) : "";
            TextView ch = HabitUi.text(ctx, Money.signed(diff, base) + pct + " · " + range, 13, diff >= 0 ? FinanceUi.income() : FinanceUi.expense());
            ch.setPadding(0, dp(2), 0, 0);
            head.addView(ch);
        }
        if (!b.noRate.isEmpty()) {
            TextView warn = HabitUi.text(ctx, "No rate for " + android.text.TextUtils.join(", ", b.noRate), 12, HabitUi.fire());
            warn.setPadding(0, dp(4), 0, 0);
            head.addView(warn);
        }
        ChartView chart = new ChartView(ctx);
        chart.setData(series, base);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170));
        clp.topMargin = dp(10);
        head.addView(chart, clp);
        content.addView(head);

        // breakdown
        content.addView(HabitUi.spacer(ctx, 12));
        HabitUi.Card br = HabitUi.card(ctx);
        br.addView(line(ctx, "Accounts", b.accounts, base, HabitUi.text(), () -> presentFragment(new AccountsActivity())));
        br.addView(HabitUi.divider(ctx, 0));
        br.addView(line(ctx, "Assets", b.assets, base, HabitUi.text(), null));
        br.addView(HabitUi.divider(ctx, 0));
        br.addView(line(ctx, "Owed to me", b.receivable, base, FinanceUi.income(), () -> presentFragment(new DebtsActivity(entity, 0))));
        br.addView(HabitUi.divider(ctx, 0));
        br.addView(line(ctx, "I owe", -b.payable, base, FinanceUi.expense(), () -> presentFragment(new DebtsActivity(entity, 1))));
        content.addView(br);

        // assets by group
        List<Asset> assets = db.assets(false);
        java.util.LinkedHashMap<String, List<Asset>> byGroup = new java.util.LinkedHashMap<>();
        java.util.HashMap<String, Double> groupTotals = new java.util.HashMap<>();
        double assetsTotal = 0;
        for (String[] gdef : Asset.GROUPS) byGroup.put(gdef[0], new java.util.ArrayList<>());
        for (Asset a : assets) {
            if (entity != null && !entity.equals(a.entity)) continue;
            List<Asset> l = byGroup.get(a.group);
            if (l == null) { l = new java.util.ArrayList<>(); byGroup.put(a.group, l); }
            l.add(a);
            double v = NetWorth.assetValueAt(a, Long.MAX_VALUE, base);
            if (!Double.isNaN(v)) { groupTotals.put(a.group, (groupTotals.containsKey(a.group) ? groupTotals.get(a.group) : 0) + v); assetsTotal += v; }
        }
        if (assetsTotal > 0) {
            content.addView(HabitUi.section(ctx, "BY GROUP"));
            HabitUi.Card gc = HabitUi.card(ctx);
            boolean firstG = true;
            double allTotal = Math.max(1e-9, b.accounts + assetsTotal);
            if (b.accounts != 0) {
                gc.addView(groupRow(ctx, "💳", "Accounts", b.accounts, allTotal, base, () -> presentFragment(new AccountsActivity())));
                firstG = false;
            }
            for (String key : byGroup.keySet()) {
                Double v = groupTotals.get(key);
                if (v == null || v == 0) continue;
                if (!firstG) { android.view.View gap = new android.view.View(ctx); gap.setLayoutParams(new LinearLayout.LayoutParams(1, dp(6))); gc.addView(gap); }
                firstG = false;
                gc.addView(groupRow(ctx, Asset.groupEmoji(key), Asset.groupTitle(key), v, allTotal, base, null));
            }
            content.addView(gc);
        }
        content.addView(HabitUi.section(ctx, "ASSETS"));
        HabitUi.Card list = HabitUi.card(ctx);
        list.addView(FinanceUi.cardHeader(ctx, "Assets", "Add", () -> presentFragment(new AssetEditActivity(null, entity))));
        int shown = 0;
        for (String key : byGroup.keySet()) {
            List<Asset> group = byGroup.get(key);
            if (group == null || group.isEmpty()) continue;
            TextView gh = HabitUi.text(ctx, Asset.groupEmoji(key) + " " + Asset.groupTitle(key).toUpperCase(), 12, HabitUi.sub(), true);
            gh.setPadding(0, dp(shown > 0 ? 14 : 4), 0, dp(2));
            list.addView(gh);
            for (Asset a : group) {
            shown++;
            double v = NetWorth.assetValueAt(a, Long.MAX_VALUE, base);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(9), 0, dp(9));
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(40), dp(40));
            elp.rightMargin = dp(12);
            row.addView(FinanceUi.emojiCircle(ctx, a.emoji.isEmpty() ? "💎" : a.emoji, HabitUi.accent(), 40), elp);
            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(HabitUi.text(ctx, a.name, 16, HabitUi.text()));
            texts.addView(HabitUi.text(ctx, assetSub(a), 13, HabitUi.sub()));
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(HabitUi.text(ctx, Double.isNaN(v) ? "—" : Money.format(v, base), 15, HabitUi.text(), true));
            row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            row.setOnClickListener(x -> presentFragment(new AssetActivity(a.id)));
            list.addView(row);
            }
        }
        if (shown == 0) list.addView(HabitUi.text(ctx, "Real estate, cars, gold, crypto, stocks, business shares — add what you own.", 14, HabitUi.sub()));
        content.addView(list);

        // archived (sold / gone)
        List<Asset> archived = new java.util.ArrayList<>();
        for (Asset a : db.assets(true)) if (a.archived && (entity == null || entity.equals(a.entity))) archived.add(a);
        if (!archived.isEmpty()) {
            TextView t = HabitUi.section(ctx, (showArchived ? "▾ " : "▸ ") + "ARCHIVED (" + archived.size() + ")");
            t.setOnClickListener(v -> { showArchived = !showArchived; refresh(); });
            content.addView(t);
            if (showArchived) {
                HabitUi.Card arc = HabitUi.card(ctx);
                for (int i = 0; i < archived.size(); i++) {
                    Asset a = archived.get(i);
                    if (i > 0) arc.addView(HabitUi.divider(ctx, 0));
                    LinearLayout row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dp(10), 0, dp(10));
                    row.addView(HabitUi.text(ctx, a.title(), 15, HabitUi.sub()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    double v = NetWorth.assetValueAt(a, Long.MAX_VALUE, base);
                    row.addView(HabitUi.text(ctx, Double.isNaN(v) ? "—" : Money.format(v, base), 14, HabitUi.sub()));
                    row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
                    row.setOnClickListener(x -> presentFragment(new AssetActivity(a.id)));
                    arc.addView(row);
                }
                content.addView(arc);
            }
        }
    }

    private View groupRow(Context ctx, String emoji, String title, double v, double total, String base, Runnable onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(36), dp(36));
        elp.rightMargin = dp(12);
        row.addView(FinanceUi.emojiCircle(ctx, emoji, HabitUi.accent(), 36), elp);
        LinearLayout mid = new LinearLayout(ctx);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.addView(HabitUi.text(ctx, title, 15, HabitUi.text()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int pct = (int) Math.round(Math.abs(v) * 100 / total);
        line.addView(HabitUi.text(ctx, Money.compact(v, base) + "  ·  " + pct + "%", 14, HabitUi.text(), true));
        mid.addView(line);
        View bar = FinanceUi.bar(ctx, (float) (Math.abs(v) / total), HabitUi.accent());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(5);
        mid.addView(bar, blp);
        row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (onClick != null) {
            row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            row.setOnClickListener(x -> onClick.run());
        }
        return row;
    }

    static String assetSub(Asset a) {
        if (a.cls == Asset.CLS_QUOTED) {
            String code = a.code == null ? "?" : a.code;
            Currencies.Cur c = Currencies.get(code);
            String unit = c.kind == Currencies.METAL ? " g" : " " + code;
            double price = NetWorth.unitPrice(a);
            return Money.number(a.qty, Math.max(Currencies.decimals(code), 2)) + unit + (Double.isNaN(price) ? " · no rate" : " × " + Money.compact(price, Finance.baseFor(a.entity)));
        }
        double v = Finance.db().assetValueAt(a.id, Long.MAX_VALUE);
        List<double[]> hist = Finance.db().assetValues(a.id);
        String when = hist.isEmpty() ? "no value yet" : "valued " + FinanceUi.shortDate(LocalDate.ofEpochDay((long) hist.get(hist.size() - 1)[0]));
        return (Double.isNaN(v) ? "" : Money.format(v, a.currency) + " · ") + when;
    }

    private String entityName(String id) {
        Entity e = Finance.db().entity(id);
        return e == null ? "" : e.name;
    }

    private View line(Context ctx, String label, double v, String base, int color, Runnable onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.addView(HabitUi.text(ctx, label, 15, HabitUi.text()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(HabitUi.text(ctx, Money.signed(v, base).replace("+", ""), 15, v == 0 ? HabitUi.sub() : color, true));
        if (onClick != null) {
            row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            row.setOnClickListener(x -> onClick.run());
        }
        return row;
    }
}
