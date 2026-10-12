package org.telegram.ui.finance;

import android.view.View;

import org.telegram.messenger.finance.Category;
import org.telegram.messenger.finance.Entity;
import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.finance.FinanceDb;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.habits.HabitUi;
import org.telegram.ui.habits.HabitsSettingsBase;

import java.util.ArrayList;
import java.util.List;

/** FORK MOD (Finance): expense and income categories */
public class FinanceCategoriesActivity extends HabitsSettingsBase {
    private static final int ID_ADD_EXPENSE = -1;
    private static final int ID_ADD_INCOME = -2;
    private final Runnable listener = this::update;
    private final ArrayList<Category> shown = new ArrayList<>();

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
        return "Categories";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        FinanceDb db = Finance.db();
        shown.clear();
        for (int kind = 0; kind < 2; kind++) {
            List<Category> cats = db.categories(kind, null, true);
            items.add(UItem.asHeader(kind == Category.EXPENSE ? "Expenses" : "Income"));
            for (Category c : cats) {
                Entity e = c.entity == null ? null : db.entity(c.entity);
                items.add(UItem.asButton(shown.size(), c.emoji + "  " + c.name, e == null ? "" : e.name));
                shown.add(c);
            }
            items.add(UItem.asButton(kind == Category.EXPENSE ? ID_ADD_EXPENSE : ID_ADD_INCOME, "＋  Add category").accent());
            items.add(UItem.asShadow(kind == Category.INCOME ? "A category can be shared or visible for one business only." : null));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        FinanceDb db = Finance.db();
        if (item.id == ID_ADD_EXPENSE || item.id == ID_ADD_INCOME) {
            int kind = item.id == ID_ADD_EXPENSE ? Category.EXPENSE : Category.INCOME;
            FinanceUi.catDialog(this, null, kind, null, (n, e, col, ent) -> {
                Category c = new Category();
                c.id = Finance.newId(); c.kind = kind; c.name = n; c.emoji = e; c.color = col; c.entity = ent;
                c.sort = db.categories(kind, null, true).size();
                db.saveCategory(c);
            });
        } else if (item.id >= 0 && item.id < shown.size()) {
            Category c = shown.get(item.id);
            FinanceUi.catDialog(this, c, c.kind, () -> db.deleteCategory(c.id), (n, e, col, ent) -> {
                c.name = n; c.emoji = e; c.color = col; c.entity = ent;
                db.saveCategory(c);
            });
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
