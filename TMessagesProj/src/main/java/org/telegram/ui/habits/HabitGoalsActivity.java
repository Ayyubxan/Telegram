package org.telegram.ui.habits;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.nexo.Dates;
import org.telegram.messenger.nexo.Goal;
import org.telegram.messenger.nexo.HabitStore;
import org.telegram.messenger.nexo.Nexo;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** FORK MOD (Nexo): rewards — goals that points are collected for, in order. */
public class HabitGoalsActivity extends HabitsSettingsBase {
    private static final int ID_ADD = -1;
    private final Runnable listener = this::update;
    private final ArrayList<Goal> shown = new ArrayList<>();

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
        return LocaleController.getString(R.string.HabitsRewards);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        HabitStore store = Nexo.store();
        int balance = store.balance();
        Context ctx = getContext();
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(21), dp(16), dp(21), dp(8));
        top.addView(HabitUi.text(ctx, "⭐ " + balance + " points", 30, HabitUi.text(), true));
        items.add(UItem.asCustom(HabitUi.notSection(top)));
        List<Goal> all = store.goals();
        shown.clear();
        shown.addAll(all);
        items.add(UItem.asHeader("Goals"));
        boolean first = true;
        for (int i = 0; i < all.size(); i++) {
            Goal g = all.get(i);
            if (g.claimed != null) continue;
            String sub = first ? Math.min(100, Math.max(0, balance) * 100 / Math.max(1, g.target)) + "% · first in line" : "in line";
            items.add(UItem.asButton(i, g.emoji + "  " + g.title + "  ·  " + sub, g.target + " points"));
            first = false;
        }
        items.add(UItem.asButton(ID_ADD, "＋  Add goal").accent());
        boolean anyClaimed = false;
        for (Goal g : all) if (g.claimed != null) { anyClaimed = true; break; }
        if (anyClaimed) {
            items.add(UItem.asShadow(null));
            items.add(UItem.asHeader("Claimed rewards"));
            for (int i = 0; i < all.size(); i++) {
                Goal g = all.get(i);
                if (g.claimed == null) continue;
                LocalDate d = Instant.ofEpochMilli(g.claimed).atZone(ZoneId.systemDefault()).toLocalDate();
                items.add(UItem.asButton(1000 + i, g.emoji + "  " + g.title + "  ·  " + g.target + " points", Dates.shortDate(d)));
            }
        }
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ADD) goalDialog(null);
        else if (item.id >= 0 && item.id < shown.size()) goalDialog(shown.get(item.id));
        else if (item.id >= 1000 && item.id - 1000 < shown.size()) goalDialog(shown.get(item.id - 1000));
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void goalDialog(Goal goal) {
        Context ctx = getContext();
        HabitStore store = Nexo.store();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(4), dp(24), 0);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final String[] emoji = {goal == null ? "🎁" : goal.emoji};
        TextView emojiView = EmojiPickerSheet.button(this, emoji[0], 48, e -> emoji[0] = e);
        EditTextBoldCursor title = HabitUi.input(ctx, "Reward (e.g. headphones)");
        if (goal != null) { title.setText(goal.title); title.setSelection(title.length()); }
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(48), dp(48));
        elp.rightMargin = dp(12);
        row.addView(emojiView, elp);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);
        EditTextBoldCursor target = HabitUi.input(ctx, "Points needed (e.g. 150)");
        target.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (goal != null) target.setText(String.valueOf(goal.target));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(8);
        box.addView(target, tlp);
        AlertDialog.Builder b = new AlertDialog.Builder(ctx)
            .setTitle(goal == null ? "New goal" : "Goal")
            .setView(box)
            .setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
                String t = title.getText().toString().trim();
                Integer n = null;
                try { n = Integer.parseInt(target.getText().toString().trim()); } catch (Exception ignore) {}
                if (t.isEmpty() || n == null || n < 1 || n > 1_000_000) {
                    HabitUi.toast(this, "Enter the reward name and points");
                    return;
                }
                String e = emoji[0].trim().isEmpty() ? "🎁" : emoji[0].trim();
                if (goal == null) {
                    store.addGoal(t, e, n);
                } else {
                    Goal c = goal.copy();
                    c.title = t; c.emoji = e; c.target = n;
                    store.updateGoal(c);
                }
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        if (goal != null) {
            b.setNeutralButton(LocaleController.getString(R.string.Delete), (d, w) ->
                HabitUi.confirm(this, null, "Delete \"" + goal.title + "\"?", LocaleController.getString(R.string.Delete), true, () -> store.deleteGoal(goal.id)));
        }
        showDialog(b.create());
        HabitUi.focusWithKeyboard(title);
    }
}
