package org.telegram.ui.finance;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.finance.Debt;
import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.finance.FinanceDb;
import org.telegram.messenger.finance.Money;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.habits.HabitUi;

import java.time.LocalDate;
import java.util.List;

/** FORK MOD (Finance): one debt — remaining amount, payments, close / reopen, open the Telegram chat */
@SuppressLint("SetTextI18n")
public class DebtActivity extends FinancePage {
    private static final int MENU_EDIT = 1;
    private final String id;
    private final FinanceDb db = Finance.db();

    public DebtActivity(String id) {
        this.id = id;
    }

    @Override
    protected CharSequence title() {
        Debt d = db.debt(id);
        return d == null ? "Debt" : d.name;
    }

    @Override
    protected void buildMenu(ActionBarMenu menu) {
        menu.addItem(MENU_EDIT, R.drawable.msg_edit);
    }

    @Override
    protected void onMenuClick(int mid) {
        if (mid == MENU_EDIT) presentFragment(new DebtEditActivity(id, 0, null));
    }

    @Override
    protected void refresh() {
        Context ctx = getContext();
        Debt d = db.debt(id);
        if (d == null) { finishFragment(); return; }
        actionBar.setTitle(d.name);
        content.removeAllViews();
        long today = FinanceDb.today();

        HabitUi.Card head = HabitUi.card(ctx);
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        View av = FinanceUi.avatar(this, d.userId, d.name, 72);
        head.addView(av, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView nm = HabitUi.text(ctx, d.name, 20, HabitUi.text(), true);
        nm.setPadding(0, dp(10), 0, 0);
        nm.setGravity(Gravity.CENTER);
        head.addView(nm);
        TextView dirText = HabitUi.text(ctx, d.dir == Debt.OWED_TO_ME ? "owes you" : "you owe", 13, HabitUi.sub());
        dirText.setGravity(Gravity.CENTER);
        head.addView(dirText);
        TextView left = HabitUi.text(ctx, Money.format(d.closed ? 0 : d.left(), d.currency), 30, d.closed ? HabitUi.sub() : d.dir == Debt.OWED_TO_ME ? FinanceUi.income() : FinanceUi.expense(), true);
        left.setPadding(0, dp(8), 0, 0);
        left.setGravity(Gravity.CENTER);
        head.addView(left);
        String sub = (d.paid > 0 ? "paid " + Money.format(d.paid, d.currency) + " of " + Money.format(d.amount, d.currency) : "since " + FinanceUi.shortDate(LocalDate.ofEpochDay(d.day)));
        if (d.due > 0 && !d.closed) {
            long l = d.due - today;
            sub += " · " + (l < 0 ? "overdue by " + (-l) + " days" : l == 0 ? "due today" : "due " + FinanceUi.shortDate(LocalDate.ofEpochDay(d.due)));
        }
        TextView subText = HabitUi.text(ctx, sub, 13, d.due > 0 && !d.closed && d.due <= today ? FinanceUi.expense() : HabitUi.sub());
        subText.setGravity(Gravity.CENTER);
        head.addView(subText);
        if (!d.note.isEmpty()) {
            TextView n = HabitUi.text(ctx, d.note, 14, HabitUi.text());
            n.setGravity(Gravity.CENTER);
            n.setPadding(0, dp(8), 0, 0);
            head.addView(n);
        }
        if (!d.closed && d.paid > 0 && d.amount > 0) {
            View bar = FinanceUi.bar(ctx, (float) (d.paid / d.amount), FinanceUi.income());
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(12);
            head.addView(bar, blp);
        }
        content.addView(head);

        content.addView(HabitUi.spacer(ctx, 12));
        HabitUi.Form form = new HabitUi.Form(ctx);
        if (!d.closed) {
            View pay = form.primaryButton(d.dir == Debt.OWED_TO_ME ? "Add received payment" : "Add payment", () ->
                FinanceUi.amountDialog(this, "Payment", d.currency, d.left(), v -> {
                    if (v <= 0) return;
                    db.addDebtPayment(d.id, v, FinanceDb.today(), "");
                    Debt nd = db.debt(d.id);
                    if (nd != null && nd.left() <= 0) HabitUi.confirm(this, null, "Fully paid. Close this debt?", "Close", false, () -> { nd.closed = true; db.saveDebt(nd); });
                }));
            content.addView(pay, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (d.userId != 0) {
            content.addView(HabitUi.pill(ctx, "Open chat", HabitUi.accent(), () -> {
                Bundle args = new Bundle();
                args.putLong("user_id", d.userId);
                presentFragment(new ChatActivity(args));
            }));
        }

        List<Object[]> pays = db.debtPayments(d.id);
        if (!pays.isEmpty()) {
            content.addView(HabitUi.section(ctx, "PAYMENTS"));
            HabitUi.Card card = HabitUi.card(ctx);
            for (int i = 0; i < pays.size(); i++) {
                Object[] p = pays.get(i);
                if (i > 0) card.addView(HabitUi.divider(ctx, 0));
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(10), 0, dp(10));
                row.addView(HabitUi.text(ctx, FinanceUi.dayLabel(LocalDate.ofEpochDay((Long) p[2])), 15, HabitUi.text()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(HabitUi.text(ctx, Money.format((Double) p[1], d.currency), 15, FinanceUi.income(), true));
                row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
                final String pid = (String) p[0];
                row.setOnLongClickListener(x -> {
                    HabitUi.confirm(this, null, "Delete this payment?", LocaleController.getString(R.string.Delete), true, () -> db.deleteDebtPayment(pid));
                    return true;
                });
                card.addView(row);
            }
            content.addView(card);
            content.addView(form.helper("Long-press a payment to delete it."));
        }

        content.addView(HabitUi.spacer(ctx, 24));
        HabitUi.Card c2 = HabitUi.card(ctx);
        c2.setPadding(0, 0, 0, 0);
        HabitUi.Form.Row toggle = form.new Row(d.closed ? "Reopen" : "Mark as closed", () -> { d.closed = !d.closed; db.saveDebt(d); });
        toggle.titleColor(HabitUi.accent());
        c2.addView(toggle.view);
        content.addView(c2);
    }
}
