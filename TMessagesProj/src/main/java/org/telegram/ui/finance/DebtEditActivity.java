package org.telegram.ui.finance;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.finance.Debt;
import org.telegram.messenger.finance.Entity;
import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.finance.FinanceDb;
import org.telegram.messenger.finance.Money;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.habits.HabitUi;

import java.time.LocalDate;

/** FORK MOD (Finance): create / edit a debt — who, how much, when, due date; optionally linked to a Telegram contact (the name is always kept) */
@SuppressLint("SetTextI18n")
public class DebtEditActivity extends FinancePage {
    private static final int MENU_DONE = 1;
    private final FinanceDb db = Finance.db();
    private final Debt debt;
    private final boolean isNew;
    private EditTextBoldCursor name, note;

    public DebtEditActivity(String id, int dir, String entity) {
        Debt d = id == null ? null : db.debt(id);
        isNew = d == null;
        if (isNew) {
            d = new Debt();
            d.id = Finance.newId();
            d.dir = dir;
            d.currency = Finance.base();
            d.day = FinanceDb.today();
            d.entity = entity == null ? Entity.PERSONAL : entity;
        }
        debt = d;
    }

    @Override
    protected CharSequence title() {
        return isNew ? "New debt" : "Debt";
    }

    @Override
    protected void buildMenu(ActionBarMenu menu) {
        menu.addItem(MENU_DONE, R.drawable.ic_ab_done);
    }

    @Override
    protected void onMenuClick(int id) {
        if (id == MENU_DONE) save();
    }

    @Override
    protected void refresh() {
        Context ctx = getContext();
        String typedName = name == null ? debt.name : name.getText().toString();
        String typedNote = note == null ? debt.note : note.getText().toString();
        content.removeAllViews();
        HabitUi.Form form = new HabitUi.Form(ctx);

        String[][] dirs = {{"0", "Owed to me"}, {"1", "I owe"}};
        View seg = form.segmented(dirs, String.valueOf(debt.dir), key -> { debt.dir = Integer.parseInt(key); refresh(); });
        content.addView(seg, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(HabitUi.spacer(ctx, 12));

        HabitUi.Card head = HabitUi.card(ctx);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View av = FinanceUi.avatar(this, debt.userId, typedName, 56);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(56), dp(56));
        alp.rightMargin = dp(14);
        row.addView(av, alp);
        name = HabitUi.input(ctx, debt.dir == Debt.OWED_TO_ME ? "Who owes you" : "Whom you owe");
        name.setText(typedName);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (debt.userId == 0 && av instanceof TextView) {
            name.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    String t = s.toString().trim();
                    ((TextView) av).setText(t.isEmpty() ? "?" : t.substring(0, 1).toUpperCase(java.util.Locale.ROOT));
                }
            });
        }
        head.addView(row);
        TextView link = HabitUi.text(ctx, debt.userId == 0 ? "Link a Telegram contact" : "Linked to Telegram · tap to change, long-press to unlink", 13, HabitUi.accent());
        link.setPadding(dp(70), dp(8), 0, 0);
        link.setOnClickListener(v -> FinanceUi.pickUser(this, u -> {
            debt.userId = u.id;
            String current = name.getText().toString().trim();
            if (current.isEmpty()) debt.name = UserObject.getUserName(u); else debt.name = current;
            name.setText(debt.name);
            refresh();
        }));
        link.setOnLongClickListener(v -> { debt.userId = 0; refresh(); return true; });
        head.addView(link);
        content.addView(head);

        HabitUi.Form.Group g = form.new Group(null, content);
        g.add(form.new Row("Amount", () -> FinanceUi.amountDialog(this, "Amount", debt.currency, debt.amount, v -> { debt.amount = v; refresh(); }))
            .value(debt.amount == 0 ? "Set" : Money.format(debt.amount, debt.currency)).valueColor(debt.amount == 0 ? HabitUi.accent() : HabitUi.sub()).view);
        g.add(form.new Row("Currency", () -> FinanceUi.pickCurrency(this, debt.currency, c -> { debt.currency = c; refresh(); })).value(debt.currency).view);
        g.add(form.new Row("Date", () -> FinanceUi.pickDate(this, LocalDate.ofEpochDay(debt.day), d -> { debt.day = d.toEpochDay(); refresh(); })).value(FinanceUi.dayLabel(LocalDate.ofEpochDay(debt.day))).view);
        g.add(form.new Row("Due date", () -> FinanceUi.pickDate(this, debt.due > 0 ? LocalDate.ofEpochDay(debt.due) : LocalDate.now().plusMonths(1), d -> { debt.due = d.toEpochDay(); refresh(); }))
            .value(debt.due > 0 ? FinanceUi.shortDate(LocalDate.ofEpochDay(debt.due)) : "None").view);
        if (debt.due > 0) g.add(form.new Row("Remove due date", () -> { debt.due = 0; refresh(); }).titleColor(HabitUi.accent()).view);
        Entity e = db.entity(debt.entity);
        g.add(form.new Row("Owner", () -> FinanceUi.pickEntity(this, "Owner", false, ent -> { debt.entity = ent.id; refresh(); })).value(e == null ? "Personal" : e.title()).view);
        LinearLayout noteRow = new LinearLayout(ctx);
        noteRow.setPadding(dp(16), dp(6), dp(16), dp(6));
        note = HabitUi.input(ctx, "Note");
        note.setText(typedNote);
        noteRow.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        g.add(noteRow);

        if (!isNew) {
            content.addView(HabitUi.spacer(ctx, 24));
            content.addView(FinanceUi.dangerRow(ctx, "Delete debt", () -> HabitUi.confirm(this, null, "Delete this debt and its payments?", LocaleController.getString(R.string.Delete), true, () -> {
                db.deleteDebt(debt.id);
                removeSelfFromStack();
                if (parentLayout != null && parentLayout.getLastFragment() instanceof DebtActivity) parentLayout.getLastFragment().finishFragment();
            })));
        }
        if (isNew && typedName.isEmpty()) AndroidUtilities.runOnUIThread(() -> HabitUi.focusWithKeyboard(name), 150);
    }

    private void save() {
        String n = name.getText().toString().trim();
        if (n.isEmpty()) { HabitUi.toast(this, "Enter a name"); return; }
        if (debt.amount <= 0) { HabitUi.toast(this, "Enter the amount"); return; }
        debt.name = n;
        debt.note = note.getText().toString().trim();
        db.saveDebt(debt);
        AndroidUtilities.hideKeyboard(fragmentView);
        finishFragment();
    }
}
