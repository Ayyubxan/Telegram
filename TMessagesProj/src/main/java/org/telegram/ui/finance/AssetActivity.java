package org.telegram.ui.finance;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.finance.Asset;
import org.telegram.messenger.finance.Currencies;
import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.finance.FinanceDb;
import org.telegram.messenger.finance.Money;
import org.telegram.messenger.finance.NetWorth;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.habits.HabitUi;

import java.time.LocalDate;
import java.util.List;

/** FORK MOD (Finance): one asset — current value, price, valuation history (manual assets: "Update value") */
@SuppressLint("SetTextI18n")
public class AssetActivity extends FinancePage {
    private static final int MENU_EDIT = 1;
    private final String id;
    private final FinanceDb db = Finance.db();

    public AssetActivity(String id) {
        this.id = id;
    }

    @Override
    protected CharSequence title() {
        Asset a = db.asset(id);
        return a == null ? "Asset" : a.title();
    }

    @Override
    protected void buildMenu(ActionBarMenu menu) {
        menu.addItem(MENU_EDIT, R.drawable.msg_edit);
    }

    @Override
    protected void onMenuClick(int mid) {
        if (mid == MENU_EDIT) presentFragment(new AssetEditActivity(id, null));
    }

    @Override
    protected void refresh() {
        Context ctx = getContext();
        Asset a = db.asset(id);
        if (a == null) { finishFragment(); return; }
        actionBar.setTitle(a.title());
        content.removeAllViews();
        String base = Finance.baseFor(a.entity);
        double v = NetWorth.assetValue(a);

        HabitUi.Card head = HabitUi.card(ctx);
        head.addView(HabitUi.text(ctx, "Value", 13, HabitUi.sub(), true));
        head.addView(HabitUi.text(ctx, Double.isNaN(v) ? "—" : Money.format(v, base), 30, HabitUi.text(), true));
        head.addView(HabitUi.text(ctx, WealthActivity.assetSub(a) + " · " + Asset.groupEmoji(a.group) + " " + Asset.groupTitle(a.group), 13, HabitUi.sub()));
        content.addView(head);

        // notes — tap to edit
        content.addView(HabitUi.spacer(ctx, 12));
        HabitUi.Card notes = HabitUi.card(ctx);
        notes.addView(FinanceUi.cardHeader(ctx, "Notes", a.note.isEmpty() ? "Add" : "Edit", () -> FinanceUi.notesDialog(this, a.name, a.note, s -> { a.note = s; db.saveAsset(a); })));
        TextView n = HabitUi.text(ctx, a.note.isEmpty() ? "Where it is, documents, who manages it, plans…" : a.note, 15, a.note.isEmpty() ? HabitUi.sub() : HabitUi.text());
        notes.addView(n);
        notes.setOnClickListener(x -> FinanceUi.notesDialog(this, a.name, a.note, s -> { a.note = s; db.saveAsset(a); }));
        content.addView(notes);

        if (a.archived) {
            TextView arch = HabitUi.text(ctx, "Archived · not counted in net worth", 13, HabitUi.fire());
            arch.setPadding(0, dp(6), 0, 0);
            head.addView(arch);
        }
        if (a.cls == Asset.CLS_MANUAL) {
            content.addView(HabitUi.spacer(ctx, 12));
            HabitUi.Form form = new HabitUi.Form(ctx);
            View btn = form.primaryButton("Update value", () -> {
                double cur = db.assetValueAt(a.id, Long.MAX_VALUE);
                FinanceUi.amountDialog(this, "Value today", a.currency, Double.isNaN(cur) ? 0 : cur, nv -> db.saveAssetValue(a.id, FinanceDb.today(), nv));
            });
            content.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            List<double[]> hist = db.assetValues(a.id);
            if (!hist.isEmpty()) {
                content.addView(HabitUi.section(ctx, "HISTORY"));
                HabitUi.Card card = HabitUi.card(ctx);
                for (int i = hist.size() - 1; i >= 0; i--) {
                    double[] h = hist.get(i);
                    double prev = i > 0 ? hist.get(i - 1)[1] : Double.NaN;
                    if (i < hist.size() - 1) card.addView(HabitUi.divider(ctx, 0));
                    LinearLayout row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dp(10), 0, dp(10));
                    LinearLayout texts = new LinearLayout(ctx);
                    texts.setOrientation(LinearLayout.VERTICAL);
                    texts.addView(HabitUi.text(ctx, FinanceUi.dayLabel(LocalDate.ofEpochDay((long) h[0])), 15, HabitUi.text()));
                    if (!Double.isNaN(prev) && prev != 0) {
                        double d = h[1] - prev;
                        texts.addView(HabitUi.text(ctx, Money.signed(d, a.currency) + String.format(java.util.Locale.US, " (%+.1f%%)", d * 100 / Math.abs(prev)), 13, d >= 0 ? FinanceUi.income() : FinanceUi.expense()));
                    }
                    row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    row.addView(HabitUi.text(ctx, Money.format(h[1], a.currency), 15, HabitUi.text(), true));
                    row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
                    final long day = (long) h[0];
                    row.setOnLongClickListener(x -> {
                        HabitUi.confirm(this, null, "Delete this valuation?", LocaleController.getString(R.string.Delete), true, () -> db.deleteAssetValue(a.id, day));
                        return true;
                    });
                    card.addView(row);
                }
                content.addView(card);
                content.addView(new HabitUi.Form(ctx).helper("Long-press a row to delete it."));
            }
        } else {
            content.addView(HabitUi.spacer(ctx, 12));
            HabitUi.Card card = HabitUi.card(ctx);
            String code = a.code == null ? "" : a.code;
            Currencies.Cur c = Currencies.get(code);
            double price = NetWorth.unitPrice(a);
            card.addView(line(ctx, "Quantity", Money.number(a.qty, 8) + (c.kind == Currencies.METAL ? " g" : " " + (Currencies.isKnown(code) ? code : "shares"))));
            card.addView(HabitUi.divider(ctx, 0));
            card.addView(line(ctx, "Price", Double.isNaN(price) ? "unknown" : Money.format(price, base) + (c.kind == Currencies.METAL ? " / g" : "")));
            content.addView(card);
            content.addView(new HabitUi.Form(ctx).helper("Prices come from the exchange-rate sources and refresh every few hours."));
        }
        content.addView(HabitUi.spacer(ctx, 24));
        HabitUi.Card ac = HabitUi.card(ctx);
        ac.setPadding(0, 0, 0, 0);
        HabitUi.Form.Row toggle = new HabitUi.Form(ctx).new Row(a.archived ? "Unarchive" : "Archive (sold / gone)", () -> { a.archived = !a.archived; db.saveAsset(a); });
        toggle.titleColor(HabitUi.accent());
        ac.addView(toggle.view);
        content.addView(ac);
    }

    private View line(Context ctx, String label, String value) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.addView(HabitUi.text(ctx, label, 15, HabitUi.sub()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(HabitUi.text(ctx, value, 15, HabitUi.text(), true));
        return row;
    }
}
