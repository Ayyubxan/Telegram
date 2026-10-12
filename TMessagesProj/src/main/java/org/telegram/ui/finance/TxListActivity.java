package org.telegram.ui.finance;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.finance.Account;
import org.telegram.messenger.finance.Category;
import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.finance.FinanceDb;
import org.telegram.messenger.finance.Money;
import org.telegram.messenger.finance.Rates;
import org.telegram.messenger.finance.Tx;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SectionsScrollView;
import org.telegram.ui.habits.HabitUi;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** FORK MOD (Finance): transactions grouped by day, with an optional entity / account / category / kind / period filter and search */
@SuppressLint("SetTextI18n")
public class TxListActivity extends BaseFragment {
    private static final int MENU_SEARCH = 1;

    private FinanceDb db;
    private final FinanceDb.Filter filter = new FinanceDb.Filter();
    private String title;
    private String accountId;

    private LinearLayout content;
    private FragmentFloatingButton fab;
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

    public static TxListActivity all(String entity) {
        Bundle b = new Bundle();
        b.putString("title", LocaleController.getString(R.string.FinanceTransactions));
        if (entity != null) b.putString("entity", entity);
        return new TxListActivity(b);
    }

    public static TxListActivity account(Account a) {
        Bundle b = new Bundle();
        b.putString("title", a.title());
        b.putString("account", a.id);
        return new TxListActivity(b);
    }

    public static TxListActivity period(String title, String entity, int kind, String category, YearMonth month) {
        Bundle b = new Bundle();
        b.putString("title", title + " · " + FinanceUi.monthLabel(month));
        if (entity != null) b.putString("entity", entity);
        b.putInt("kind", kind);
        if (category != null) b.putString("category", category);
        b.putLong("dayFrom", month.atDay(1).toEpochDay());
        b.putLong("dayTo", month.atEndOfMonth().toEpochDay());
        return new TxListActivity(b);
    }

    private TxListActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        db = Finance.db();
        title = arguments.getString("title", "Transactions");
        filter.entity = arguments.getString("entity");
        accountId = arguments.getString("account");
        filter.account = accountId;
        filter.category = arguments.getString("category");
        filter.kind = arguments.getInt("kind", -1);
        if (arguments.containsKey("dayFrom")) filter.dayFrom = arguments.getLong("dayFrom");
        if (arguments.containsKey("dayTo")) filter.dayTo = arguments.getLong("dayTo");
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(title);
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem search = menu.addItem(MENU_SEARCH, R.drawable.msg_search).setIsSearchField(true);
        search.setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchCollapse() {
                filter.query = null;
                refresh();
            }

            @Override
            public void onTextChanged(EditText editText) {
                filter.query = editText.getText().toString().trim();
                refresh();
            }
        });
        search.setSearchFieldHint(LocaleController.getString(R.string.Search));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(HabitUi.bg());
        LinearLayout[] c = new LinearLayout[1];
        SectionsScrollView scroll = HabitUi.page(context, c);
        content = c[0];
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(scroll);

        fab = new FragmentFloatingButton(context, resourceProvider);
        fab.setImageResource(R.drawable.msg_add);
        fab.setOnClickListener(v -> presentFragment(TxEditActivity.create(filter.kind == Tx.INCOME ? Tx.INCOME : Tx.EXPENSE, filter.entity, accountId)));
        root.addView(fab, FragmentFloatingButton.createDefaultLayoutParams());

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
        content.removeAllViews();
        Map<String, Account> accounts = new HashMap<>();
        for (Account a : db.accounts(true)) accounts.put(a.id, a);
        Map<String, Category> cats = new HashMap<>();
        for (Category c : db.categories(Category.EXPENSE, null, true)) cats.put(c.id, c);
        for (Category c : db.categories(Category.INCOME, null, true)) cats.put(c.id, c);
        List<Tx> list = db.txs(filter);
        if (list.isEmpty()) {
            TextView empty = HabitUi.notSection(HabitUi.text(ctx, filter.query != null && !filter.query.isEmpty() ? "Nothing found" : "No transactions yet", 15, HabitUi.sub()));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(48), 0, 0);
            content.addView(empty);
            return;
        }
        String base = Finance.baseFor(filter.entity);
        Account acc = accountId == null ? null : accounts.get(accountId);
        if (acc != null) {
            Double bal = db.balances().get(acc.id);
            TextView t = HabitUi.notSection(HabitUi.text(ctx, "Balance " + Money.format(bal == null ? 0 : bal, acc.currency), 15, HabitUi.sub()));
            t.setPadding(dp(6), dp(2), 0, dp(10));
            content.addView(t);
        }
        long curDay = Long.MIN_VALUE;
        HabitUi.Card card = null;
        int i = 0;
        while (i < list.size()) {
            Tx t = list.get(i);
            if (t.day != curDay) {
                curDay = t.day;
                if (card != null) content.addView(HabitUi.spacer(ctx, 12));
                card = HabitUi.card(ctx);
                content.addView(card);
                // day header with the day's net
                double net = 0;
                for (int j = i; j < list.size() && list.get(j).day == curDay; j++) {
                    Tx x = list.get(j);
                    if (acc != null) {
                        if (x.isTransfer()) net += acc.id.equals(x.account) ? -x.amount : x.toAmount;
                        else net += x.signed();
                    } else if (!x.isTransfer()) net += Rates.toBase(x.signed(), x.currency);
                }
                LinearLayout h = new LinearLayout(ctx);
                h.setOrientation(LinearLayout.HORIZONTAL);
                h.setPadding(0, 0, 0, dp(4));
                h.addView(HabitUi.text(ctx, FinanceUi.dayLabel(LocalDate.ofEpochDay(curDay)), 13, HabitUi.sub(), true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                h.addView(HabitUi.text(ctx, Money.signed(net, acc != null ? acc.currency : base), 13, HabitUi.sub(), true));
                card.addView(h);
            } else {
                card.addView(HabitUi.divider(ctx, 52));
            }
            final Tx tt = t;
            card.addView(FinanceUi.txRow(ctx, t, accounts, cats, false, () -> presentFragment(TxEditActivity.edit(tt.id))));
            i++;
        }
    }
}
