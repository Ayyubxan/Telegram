package org.telegram.ui.habits;

import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.nexo.Cat;
import org.telegram.messenger.nexo.Habit;
import org.telegram.messenger.nexo.HabitStore;
import org.telegram.messenger.nexo.Nexo;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.List;

/** FORK MOD (Nexo): habit categories — name, emoji and color. */
public class HabitCategoriesActivity extends HabitsSettingsBase {
    private static final int ID_ADD = -1;
    private final Runnable listener = this::update;
    private final ArrayList<Cat> shown = new ArrayList<>();

    private void update() {
        if (listView != null && listView.adapter != null) listView.adapter.update(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        Nexo.store().addListener(listener);
        update();
    }

    @Override
    public void onPause() {
        super.onPause();
        Nexo.store().removeListener(listener);
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.HabitsCategories);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        HabitStore store = Nexo.store();
        List<Habit> habits = store.active();
        List<Cat> cats = store.cats();
        shown.clear();
        shown.addAll(cats);
        items.add(UItem.asHeader(LocaleController.getString(R.string.HabitsCategories)));
        for (int i = 0; i < cats.size(); i++) {
            Cat c = cats.get(i);
            int n = 0;
            for (Habit h : habits) if (h.cat.equals(c.id)) n++;
            items.add(UItem.asButton(i, c.emoji + "  " + c.name, n + (n == 1 ? " habit" : " habits")));
        }
        items.add(UItem.asButton(ID_ADD, "＋  Add category").accent());
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        HabitStore store = Nexo.store();
        if (item.id == ID_ADD) {
            HabitUi.catDialog(this, null, null, (n, e, c) -> store.addCat(n, e, c));
        } else if (item.id >= 0 && item.id < shown.size()) {
            Cat c = shown.get(item.id);
            HabitUi.catDialog(this, c, () -> store.deleteCat(c.id), (n, e, col) -> {
                Cat u = c.copy();
                u.name = n; u.emoji = e; u.color = col;
                store.updateCat(u);
            });
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
