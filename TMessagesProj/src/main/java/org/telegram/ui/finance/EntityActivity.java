package org.telegram.ui.finance;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.finance.Account;
import org.telegram.messenger.finance.Entity;
import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.finance.FinanceDb;
import org.telegram.messenger.finance.Money;
import org.telegram.messenger.finance.Rates;
import org.telegram.messenger.finance.Tx;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SectionsScrollView;
import org.telegram.ui.habits.HabitUi;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/** FORK MOD (Finance): one entity (business) — balance, capital in/out, income, expenses, profit; its accounts */
@SuppressLint("SetTextI18n")
public class EntityActivity extends BaseFragment {
    private static final int MENU_EDIT = 1;

    private final String id;
    private FinanceDb db;
    private Entity entity;
    private LinearLayout content;
    private boolean pendingRefresh;
    private final Runnable listener = () -> {
        if (fragmentView == null) return;
        if (parentLayout != null && parentLayout.isTransitionAnimationInProgress()) { pendingRefresh = true; return; }
        refresh();
    };

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (pendingRefresh && fragmentView != null) { pendingRefresh = false; refresh(); }
    }

    public EntityActivity(String id) {
        this.id = id;
    }

    @Override
    public boolean onFragmentCreate() {
        db = Finance.db();
        entity = db.entity(id);
        return entity != null && super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(entity.title());
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_EDIT, R.drawable.msg_edit);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int mid) {
                if (mid == -1) finishFragment();
                else if (mid == MENU_EDIT) FinanceUi.entityDialog(EntityActivity.this, entity, (n, em) -> {
                    entity.name = n; entity.emoji = em;
                    db.saveEntity(entity);
                    actionBar.setTitle(entity.title());
                });
            }
        });
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(HabitUi.bg());
        LinearLayout[] c = new LinearLayout[1];
        SectionsScrollView scroll = HabitUi.page(context, c);
        content = c[0];
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(scroll);
        fragmentView = root;
        applyPaddings();
        refresh();
        return root;
    }

    private void applyPaddings() {
        if (content != null) content.setPadding(dp(12), dp(12), dp(12), dp(88));
    }

    @Override
    public void onResume() {
        super.onResume();
        Finance.addListener(listener);
        refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        Finance.removeListener(listener);
    }

    private void refresh() {
        Context ctx = getContext();
        if (ctx == null) return;
        entity = db.entity(id);
        if (entity == null) { finishFragment(); return; }
        content.removeAllViews();
        String base = Finance.baseFor(id);
        List<Account> accounts = db.accounts(id, false);
        Map<String, Double> balances = db.balances();
        double balance = 0;
        for (Account a : accounts) { Double v = balances.get(a.id); if (v != null) balance += Rates.toBase(v, a.currency); }

        HabitUi.Card top = HabitUi.card(ctx);
        top.addView(HabitUi.text(ctx, "Balance", 13, HabitUi.sub(), true));
        top.addView(HabitUi.text(ctx, Money.format(balance, base), 30, HabitUi.text(), true));
        TextView curRow = HabitUi.text(ctx, "Shown in " + base + (entity.currency == null || entity.currency.isEmpty() ? " (default) · change" : " · change"), 13, HabitUi.accent());
        curRow.setPadding(0, dp(6), 0, 0);
        curRow.setOnClickListener(v -> FinanceUi.pick(this, "Display currency for " + entity.name, new CharSequence[]{"Default (" + Finance.base() + ")", "Choose…"}, w -> {
            if (w == 0) { entity.currency = ""; db.saveEntity(entity); }
            else FinanceUi.pickCurrency(this, base, c -> { entity.currency = c; db.saveEntity(entity); });
        }));
        top.addView(curRow);
        content.addView(top);

        YearMonth m = YearMonth.now();
        content.addView(statsCard(ctx, "This month", m.atDay(1).toEpochDay(), m.atEndOfMonth().toEpochDay(), base));
        content.addView(HabitUi.spacer(ctx, 12));
        content.addView(statsCard(ctx, "All time", Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, base));

        content.addView(HabitUi.spacer(ctx, 12));
        HabitUi.Card acc = HabitUi.card(ctx);
        acc.addView(FinanceUi.cardHeader(ctx, "Accounts", "Add", () -> presentFragment(new AccountEditActivity(null, id))));
        for (int i = 0; i < accounts.size(); i++) {
            Account a = accounts.get(i);
            if (i > 0) acc.addView(HabitUi.divider(ctx, 0));
            Double v = balances.get(a.id);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            row.addView(HabitUi.text(ctx, a.title(), 16, HabitUi.text()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(HabitUi.text(ctx, Money.format(v == null ? 0 : v, a.currency), 15, HabitUi.text(), true));
            row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            row.setOnClickListener(x -> presentFragment(TxListActivity.account(a)));
            acc.addView(row);
        }
        if (accounts.isEmpty()) acc.addView(HabitUi.text(ctx, "No accounts yet", 14, HabitUi.sub()));
        content.addView(acc);

        if (!entity.isPersonal()) {
            content.addView(HabitUi.spacer(ctx, 24));
            content.addView(FinanceUi.dangerRow(ctx, "Delete business", () -> {
                if (!db.accounts(id, true).isEmpty()) { HabitUi.toast(this, "Delete or move its accounts first"); return; }
                HabitUi.confirm(this, null, "Delete \"" + entity.name + "\"?", LocaleController.getString(R.string.Delete), true, () -> {
                    db.deleteEntity(id);
                    finishFragment();
                });
            }));
        }
    }

    private View statsCard(Context ctx, String title, long from, long to, String base) {
        double income = db.sumKind(Tx.INCOME, from, to, id);
        double expense = db.sumKind(Tx.EXPENSE, from, to, id);
        double[] flows = db.entityFlows(id, from, to);
        HabitUi.Card c = HabitUi.card(ctx);
        c.addView(HabitUi.text(ctx, title, 16, HabitUi.text(), true));
        c.addView(line(ctx, "Income", income, base, FinanceUi.income()));
        c.addView(line(ctx, "Expenses", -expense, base, FinanceUi.expense()));
        c.addView(line(ctx, "Profit", income - expense, base, income - expense >= 0 ? FinanceUi.income() : FinanceUi.expense()));
        c.addView(HabitUi.divider(ctx, 0));
        c.addView(line(ctx, "Invested (from other accounts)", flows[0], base, HabitUi.text()));
        c.addView(line(ctx, "Withdrawn (to other accounts)", -flows[1], base, HabitUi.text()));
        return c;
    }

    private View line(Context ctx, String label, double v, String base, int color) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(6));
        row.addView(HabitUi.text(ctx, label, 15, HabitUi.sub()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = HabitUi.text(ctx, Money.signed(v, base), 15, v == 0 ? HabitUi.sub() : color, true);
        row.addView(t);
        return row;
    }
}
