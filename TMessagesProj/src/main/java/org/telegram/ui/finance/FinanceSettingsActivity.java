package org.telegram.ui.finance;

import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.finance.Entity;
import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.finance.FinanceDb;
import org.telegram.messenger.finance.Rates;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.habits.HabitsSettingsBase;

import java.util.ArrayList;
import java.util.List;

/** FORK MOD (Finance): Settings → Finance — base currency, rates, businesses, accounts, categories */
public class FinanceSettingsActivity extends HabitsSettingsBase {
    private static final int ID_BASE = 1;
    private static final int ID_RATES = 2;
    private static final int ID_ACCOUNTS = 3;
    private static final int ID_CATEGORIES = 4;
    private static final int ID_ADD_BUSINESS = 5;
    private static final int ID_ASSETS = 6;
    private static final int ID_DEBTS = 7;
    private static final int ID_IMPORT = 8;
    private static final int ID_REPORTS = 9;
    private static final int ID_ENTITY = 100;

    private final Runnable listener = this::update;
    private final ArrayList<Entity> shown = new ArrayList<>();

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
        return LocaleController.getString(R.string.Finance);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        FinanceDb db = Finance.db();
        items.add(UItem.asHeader("Money"));
        items.add(UItem.asButton(ID_BASE, "Base currency", Finance.base()));
        long upd = Rates.lastUpdated();
        items.add(UItem.asButton(ID_RATES, "Exchange rates", upd == 0 ? "Not loaded" : LocaleController.formatDateAudio(upd / 1000, false)));
        items.add(UItem.asShadow("Totals for \"All\" are shown in the base currency; each business can have its own display currency (open the business → \"Shown in\"). Rates: Central Bank of Uzbekistan, CoinGecko, Yahoo Finance."));
        items.add(UItem.asHeader("Automation"));
        int rules = db.rules().size();
        int pending = org.telegram.messenger.finance.BotImport.pendingCount();
        items.add(UItem.asButton(ID_IMPORT, "Bot import", rules == 0 ? "Off" : rules + (rules == 1 ? " chat" : " chats") + (pending > 0 ? " · " + pending + " to review" : "")));
        items.add(UItem.asButton(ID_REPORTS, "Reports"));
        items.add(UItem.asShadow("Payment messages from bank and business bots become transactions."));
        items.add(UItem.asHeader("Businesses"));
        shown.clear();
        List<Entity> ents = db.entities(true);
        for (Entity e : ents) {
            int n = db.accounts(e.id, false).size();
            items.add(UItem.asButton(ID_ENTITY + shown.size(), e.emoji + "  " + e.name, n + (n == 1 ? " account" : " accounts") + " · " + Finance.baseFor(e.id)));
            shown.add(e);
        }
        items.add(UItem.asButton(ID_ADD_BUSINESS, "＋  Add business").accent());
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Lists"));
        items.add(UItem.asButton(ID_ACCOUNTS, "Accounts", String.valueOf(db.accounts(false).size())));
        items.add(UItem.asButton(ID_CATEGORIES, "Categories"));
        items.add(UItem.asButton(ID_ASSETS, "Assets", String.valueOf(db.assets(false).size())));
        items.add(UItem.asButton(ID_DEBTS, "Debts", String.valueOf(db.debts(false).size())));
        items.add(UItem.asShadow("Finance data is included in the Telegram backup (Settings → Mods)."));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_BASE) {
            FinanceUi.pickCurrency(this, Finance.base(), code -> Finance.setBase(code));
        } else if (item.id == ID_RATES) {
            presentFragment(new RatesActivity());
        } else if (item.id == ID_ACCOUNTS) {
            presentFragment(new AccountsActivity());
        } else if (item.id == ID_CATEGORIES) {
            presentFragment(new FinanceCategoriesActivity());
        } else if (item.id == ID_IMPORT) {
            presentFragment(new ImportRulesActivity());
        } else if (item.id == ID_REPORTS) {
            presentFragment(new ReportsActivity(null));
        } else if (item.id == ID_ASSETS) {
            presentFragment(new WealthActivity(null));
        } else if (item.id == ID_DEBTS) {
            presentFragment(new DebtsActivity(null, 0));
        } else if (item.id == ID_ADD_BUSINESS) {
            FinanceUi.entityDialog(this, null, (n, em) -> {
                Entity e = new Entity();
                e.id = Finance.newId(); e.name = n; e.emoji = em; e.color = ""; e.sort = shown.size();
                Finance.db().saveEntity(e);
            });
        } else if (item.id >= ID_ENTITY && item.id - ID_ENTITY < shown.size()) {
            presentFragment(new EntityActivity(shown.get(item.id - ID_ENTITY).id));
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
