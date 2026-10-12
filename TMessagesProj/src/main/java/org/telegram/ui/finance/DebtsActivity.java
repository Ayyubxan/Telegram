package org.telegram.ui.finance;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.messenger.finance.Debt;
import org.telegram.messenger.finance.Entity;
import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.finance.FinanceDb;
import org.telegram.messenger.finance.Money;
import org.telegram.messenger.finance.Rates;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.habits.HabitUi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** FORK MOD (Finance): debts — people who owe me and people I owe, with remaining amounts and due dates */
@SuppressLint("SetTextI18n")
public class DebtsActivity extends FinancePage {
    private final String entity;
    private int dir;
    private boolean showClosed;
    private FragmentFloatingButton fab;

    public DebtsActivity(String entity, int dir) {
        this.entity = entity;
        this.dir = dir;
    }

    @Override
    protected CharSequence title() {
        return "Debts";
    }

    @Override
    protected int bottomPaddingDp() {
        return 96;
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        fab = new FragmentFloatingButton(context, resourceProvider);
        fab.setImageResource(R.drawable.msg_add);
        fab.setOnClickListener(x -> presentFragment(new DebtEditActivity(null, dir, entity)));
        root.addView(fab, FragmentFloatingButton.createDefaultLayoutParams());
        return v;
    }

    @Override
    protected void refresh() {
        Context ctx = getContext();
        if (ctx == null) return;
        content.removeAllViews();
        FinanceDb db = Finance.db();
        String base = Finance.baseFor(entity);
        HabitUi.Form form = new HabitUi.Form(ctx);
        String[][] dirs = {{"0", "Owed to me"}, {"1", "I owe"}};
        View seg = form.segmented(dirs, String.valueOf(dir), key -> { dir = Integer.parseInt(key); refresh(); });
        content.addView(seg, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(HabitUi.spacer(ctx, 12));

        List<Debt> open = new ArrayList<>(), closed = new ArrayList<>();
        double total = 0;
        for (Debt d : db.debts(true)) {
            if (d.dir != dir) continue;
            String de = d.entity == null || d.entity.isEmpty() ? Entity.PERSONAL : d.entity;
            if (entity != null && !entity.equals(de)) continue;
            if (d.closed) closed.add(d); else { open.add(d); total += Rates.convertOr0(d.left(), d.currency, base); }
        }
        HabitUi.Card head = HabitUi.card(ctx);
        head.addView(HabitUi.text(ctx, dir == Debt.OWED_TO_ME ? "Owed to me" : "I owe", 13, HabitUi.sub(), true));
        head.addView(HabitUi.text(ctx, Money.format(total, base), 30, dir == Debt.OWED_TO_ME ? FinanceUi.income() : FinanceUi.expense(), true));
        head.addView(HabitUi.text(ctx, open.size() + (open.size() == 1 ? " open debt" : " open debts"), 13, HabitUi.sub()));
        content.addView(head);

        if (!open.isEmpty()) {
            content.addView(HabitUi.spacer(ctx, 12));
            content.addView(list(ctx, open));
        } else {
            content.addView(new HabitUi.Form(ctx).helper(dir == Debt.OWED_TO_ME ? "Nobody owes you anything. Tap + to record a loan you gave." : "You owe nothing. Tap + to record a loan you took."));
        }
        if (!closed.isEmpty()) {
            TextView t = HabitUi.section(ctx, (showClosed ? "▾ " : "▸ ") + "CLOSED (" + closed.size() + ")");
            t.setOnClickListener(v -> { showClosed = !showClosed; refresh(); });
            content.addView(t);
            if (showClosed) content.addView(list(ctx, closed));
        }
    }

    private View list(Context ctx, List<Debt> debts) {
        HabitUi.Card card = HabitUi.card(ctx);
        long today = FinanceDb.today();
        for (int i = 0; i < debts.size(); i++) {
            Debt d = debts.get(i);
            if (i > 0) card.addView(HabitUi.divider(ctx, 52));
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(9), 0, dp(9));
            View av = FinanceUi.avatar(this, d.userId, d.name, 40);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(40), dp(40));
            alp.rightMargin = dp(12);
            row.addView(av, alp);
            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(HabitUi.text(ctx, d.name, 16, d.closed ? HabitUi.sub() : HabitUi.text()));
            String sub;
            int subColor = HabitUi.sub();
            if (d.closed) sub = "closed · " + Money.format(d.amount, d.currency);
            else {
                sub = d.paid > 0 ? "of " + Money.format(d.amount, d.currency) : FinanceUi.shortDate(LocalDate.ofEpochDay(d.day));
                if (d.due > 0) {
                    long left = d.due - today;
                    sub += " · " + (left < 0 ? "overdue " + (-left) + "d" : left == 0 ? "due today" : "due " + FinanceUi.shortDate(LocalDate.ofEpochDay(d.due)));
                    if (left <= 0) subColor = FinanceUi.expense();
                }
            }
            texts.addView(HabitUi.text(ctx, sub, 13, subColor));
            if (!d.closed && d.paid > 0 && d.amount > 0) {
                View bar = FinanceUi.bar(ctx, (float) (d.paid / d.amount), FinanceUi.income());
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.topMargin = dp(5);
                texts.addView(bar, blp);
            }
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView amount = HabitUi.text(ctx, Money.format(d.closed ? 0 : d.left(), d.currency), 15, d.closed ? HabitUi.sub() : HabitUi.text(), true);
            LinearLayout.LayoutParams amlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            amlp.leftMargin = dp(10);
            row.addView(amount, amlp);
            row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            row.setOnClickListener(x -> presentFragment(new DebtActivity(d.id)));
            card.addView(row);
        }
        return card;
    }
}
