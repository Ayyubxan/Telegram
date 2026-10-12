package org.telegram.ui.finance;

import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.finance.Currencies;
import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.finance.FinanceDb;
import org.telegram.messenger.finance.Money;
import org.telegram.messenger.finance.Rates;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.habits.HabitUi;
import org.telegram.ui.habits.HabitsSettingsBase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** FORK MOD (Finance): exchange rates (UZS per unit) — refresh, tap to override by hand, long-press to drop the override */
public class RatesActivity extends HabitsSettingsBase {
    private static final int ID_REFRESH = -1;
    private final Runnable listener = this::update;
    private final ArrayList<String> shown = new ArrayList<>();

    private void update() {
        if (listView != null && listView.adapter != null) listView.adapter.update(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        Finance.addListener(listener);
        update();
    }

    @Override
    public void onPause() {
        super.onPause();
        Finance.removeListener(listener);
    }

    @Override
    protected CharSequence getTitle() {
        return "Exchange rates";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        FinanceDb db = Finance.db();
        HashMap<String, FinanceDb.RateRow> rows = new HashMap<>();
        for (FinanceDb.RateRow r : db.rateRows()) rows.put(r.code, r);
        long upd = Rates.lastUpdated();
        items.add(UItem.asButton(ID_REFRESH, Rates.isRefreshing() ? "Refreshing…" : "Refresh now", upd == 0 ? "" : LocaleController.formatDateAudio(upd / 1000, false)).accent());
        items.add(UItem.asShadow("1 unit in UZS. Tap a rate to set it by hand (it will no longer be refreshed), long-press to return to automatic."));
        shown.clear();
        String[] kinds = {"Fiat", "Crypto", "Metals (per gram)"};
        for (int kind = 0; kind < 3; kind++) {
            List<Currencies.Cur> list = Currencies.ofKind(kind);
            if (list.isEmpty()) continue;
            items.add(UItem.asHeader(kinds[kind]));
            for (Currencies.Cur c : list) {
                if ("UZS".equals(c.code)) continue;
                FinanceDb.RateRow r = rows.get(c.code);
                String v = r == null ? "—" : Money.number(r.uzs, r.uzs < 10 ? 4 : r.uzs < 1000 ? 2 : 0) + (r.manual ? " ✎" : "");
                items.add(UItem.asButton(shown.size(), c.code + " · " + c.name, v));
                shown.add(c.code);
            }
        }
        // tickers used by assets
        List<String> tickers = db.quotedCodes();
        boolean header = false;
        for (String t : tickers) {
            if (Currencies.isKnown(t)) continue;
            if (!header) { items.add(UItem.asHeader("Stocks")); header = true; }
            FinanceDb.RateRow r = rows.get(t);
            items.add(UItem.asButton(shown.size(), t, r == null ? "—" : Money.number(r.uzs, 0) + (r.manual ? " ✎" : "")));
            shown.add(t);
        }
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_REFRESH) {
            Rates.refresh(ok -> {
                HabitUi.toast(this, ok ? "Rates updated" : "Could not load rates");
                update();
            });
            update();
        } else if (item.id >= 0 && item.id < shown.size()) {
            String code = shown.get(item.id);
            double cur = Rates.uzs(code);
            HabitUi.textDialog(this, code + " in UZS", "1 " + code + " = ? UZS", Double.isNaN(cur) ? "" : Money.plain(cur, "USD"), "UZS", true, s -> {
                double v = Money.parse(s);
                if (!Double.isNaN(v) && v > 0) Rates.setManual(code, v);
            });
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id >= 0 && item.id < shown.size()) {
            Rates.clearManual(shown.get(item.id));
            HabitUi.toast(this, "Back to automatic");
            Rates.refresh(null);
            return true;
        }
        return false;
    }
}
