package org.telegram.ui.finance;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.finance.Account;
import org.telegram.messenger.finance.Category;
import org.telegram.messenger.finance.Entity;
import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.finance.FinanceDb;
import org.telegram.messenger.finance.Money;
import org.telegram.messenger.finance.NetWorth;
import org.telegram.messenger.finance.Rates;
import org.telegram.messenger.finance.Tx;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SectionsScrollView;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.habits.HabitUi;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * FORK MOD (Finance): the Finance tab — net worth, accounts, this month's spending by category and recent transactions,
 * filtered by entity (Personal / each business). Replaces the Settings tab in {@link MainTabsActivity}.
 */
@SuppressLint("SetTextI18n")
public class FinanceActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {
    private static final int MENU_LIST = 1;
    private static final int MENU_ACCOUNTS = 2;
    private static final int MENU_REPORTS = 3;

    private boolean hasMainTabs;
    private FinanceDb db;

    private FrameLayout root;
    private SectionsScrollView scroll;
    private LinearLayout content;
    private FragmentFloatingButton fab;
    private int insetTop, insetBottom;

    /** null — all entities */
    private String entity;
    private YearMonth month = YearMonth.now();

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

    public FinanceActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        db = Finance.db();
        if (arguments != null) hasMainTabs = arguments.getBoolean("hasMainTabs", false);
        String e = Finance.prefs().getString("tabEntity", "");
        entity = e.isEmpty() ? null : e;
        return super.onFragmentCreate();
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean hasOwnBackground() {
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.Finance));
        if (!hasMainTabs) actionBar.setBackButtonDrawable(new BackDrawable(false));
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_ACCOUNTS, R.drawable.msg_payment_card).setContentDescription(LocaleController.getString(R.string.FinanceAccounts));
        menu.addItem(MENU_REPORTS, R.drawable.msg_stats).setContentDescription(LocaleController.getString(R.string.FinanceReports));
        menu.addItem(MENU_LIST, R.drawable.msg_list).setContentDescription(LocaleController.getString(R.string.FinanceTransactions));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (!hasMainTabs) finishFragment();
                } else if (id == MENU_LIST) {
                    presentFragment(TxListActivity.all(entity));
                } else if (id == MENU_ACCOUNTS) {
                    presentFragment(new AccountsActivity());
                } else if (id == MENU_REPORTS) {
                    presentFragment(new ReportsActivity(entity));
                }
            }
        });

        root = new FrameLayout(context);
        root.setBackgroundColor(HabitUi.bg());
        LinearLayout[] c = new LinearLayout[1];
        scroll = HabitUi.page(context, c);
        content = c[0];
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(scroll);

        fab = new FragmentFloatingButton(context, resourceProvider);
        fab.setImageResource(R.drawable.msg_add);
        fab.setContentDescription(LocaleController.getString(R.string.FinanceNewTransaction));
        fab.setOnClickListener(v -> presentFragment(TxEditActivity.create(Tx.EXPENSE, entity, null)));
        fab.setOnLongClickListener(v -> {
            org.telegram.ui.Components.ItemOptions o = org.telegram.ui.Components.ItemOptions.makeOptions(this, v);
            o.add(R.drawable.msg_add, "Expense", () -> presentFragment(TxEditActivity.create(Tx.EXPENSE, entity, null)));
            o.add(R.drawable.msg_add, "Income", () -> presentFragment(TxEditActivity.create(Tx.INCOME, entity, null)));
            o.add(R.drawable.msg_add, "Transfer", () -> presentFragment(TxEditActivity.create(Tx.TRANSFER, entity, null)));
            o.add(R.drawable.msg_stats, "Asset", () -> presentFragment(new AssetEditActivity(null, entity)));
            o.add(R.drawable.msg_viewchats, "Debt", () -> presentFragment(new DebtEditActivity(null, 0, entity)));
            o.setGravity(Gravity.RIGHT);
            o.show();
            return true;
        });
        root.addView(fab, FragmentFloatingButton.createDefaultLayoutParams());

        fragmentView = root;
        ViewCompat.setOnApplyWindowInsetsListener(root, this::applyInsets);
        applyPaddings();
        refresh();
        return root;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        insetTop = top;
        insetBottom = bottom;
        if (content != null) applyPaddings();
    }

    @NonNull
    private WindowInsetsCompat applyInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
        Insets i = insets.getInsets(WindowInsetsCompat.Type.navigationBars() | WindowInsetsCompat.Type.statusBars());
        insetTop = i.top;
        insetBottom = i.bottom;
        applyPaddings();
        return WindowInsetsCompat.CONSUMED;
    }

    private void applyPaddings() {
        int tabs = hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0;
        content.setPadding(dp(12), insetTop + ActionBar.getCurrentActionBarHeight() + dp(8), dp(12), insetBottom + tabs + dp(88));
        fab.setTranslationY(-insetBottom - (hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT + DialogsActivity.MAIN_TABS_MARGIN) : 0));
    }

    @Override
    public void onResume() {
        super.onResume();
        Finance.addListener(listener);
        Rates.refreshIfStale(null);
        refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        Finance.removeListener(listener);
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return true;
    }

    @Override
    public void onParentScrollToTop() {
        if (scroll != null) scroll.smoothScrollTo(0, 0);
    }

    // region Content

    private void refresh() {
        Context ctx = getContext();
        if (ctx == null || content == null) return;
        content.removeAllViews();
        List<Entity> entities = db.entities(false);
        if (entity != null && db.entity(entity) == null) entity = null;
        boolean multi = entities.size() > 1;
        if (multi) {
            View chips = FinanceUi.entityChips(ctx, entities, entity, id -> {
                entity = id;
                Finance.prefs().edit().putString("tabEntity", id == null ? "" : id).apply();
                refresh();
            });
            chips.setPadding(0, 0, 0, dp(10));
            content.addView(chips);
        }
        content.addView(buildPeriodRow(ctx));

        // Inbox (bot messages waiting for review)
        int pending = org.telegram.messenger.finance.BotImport.pendingCount();
        if (pending > 0) {
            HabitUi.Card inbox = HabitUi.card(ctx);
            inbox.setOrientation(LinearLayout.HORIZONTAL);
            inbox.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(40), dp(40));
            elp.rightMargin = dp(12);
            inbox.addView(FinanceUi.emojiCircle(ctx, "🤖", HabitUi.fire(), 40), elp);
            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(HabitUi.text(ctx, pending + (pending == 1 ? " message to review" : " messages to review"), 16, HabitUi.text(), true));
            texts.addView(HabitUi.text(ctx, "From your bank and business bots", 13, HabitUi.sub()));
            inbox.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            inbox.addView(HabitUi.text(ctx, "Review", 14, HabitUi.accent(), true));
            inbox.setOnClickListener(v -> presentFragment(new InboxActivity()));
            content.addView(inbox);
            content.addView(HabitUi.spacer(ctx, 12));
        }

        String base = Finance.baseFor(entity);
        List<Account> accounts = db.accounts(entity, false);
        Map<String, Double> balances = db.balances();
        long dayFrom = month.atDay(1).toEpochDay();
        long dayTo = month.atEndOfMonth().toEpochDay();
        long today = LocalDate.now().toEpochDay();
        long periodEnd = Math.min(dayTo, today);

        // Overview (net worth = accounts + assets + owed to me − I owe)
        NetWorth.Breakdown nw = NetWorth.now(entity);
        NetWorth.snapshotToday();
        boolean currentMonth = month.equals(YearMonth.now());
        double worthEnd = currentMonth ? nw.total() : NetWorth.at(periodEnd, entity).total();
        double worthStart = NetWorth.at(dayFrom - 1, entity).total();
        double income = db.sumKind(Tx.INCOME, dayFrom, dayTo, entity);
        double expense = db.sumKind(Tx.EXPENSE, dayFrom, dayTo, entity);
        content.addView(buildOverview(ctx, base, nw, worthEnd - worthStart, income, expense));

        // Accounts
        content.addView(HabitUi.spacer(ctx, 12));
        content.addView(buildAccounts(ctx, accounts, balances, multi && entity == null));

        // Assets (by group) — tap → Wealth
        if (nw.assets != 0) {
            content.addView(HabitUi.spacer(ctx, 12));
            HabitUi.Card ac = HabitUi.card(ctx);
            ac.addView(FinanceUi.cardHeader(ctx, "Assets", "Wealth", () -> presentFragment(new WealthActivity(entity))));
            java.util.LinkedHashMap<String, Double> byGroup = new java.util.LinkedHashMap<>();
            for (String[] gdef : org.telegram.messenger.finance.Asset.GROUPS) byGroup.put(gdef[0], 0d);
            for (org.telegram.messenger.finance.Asset a : db.assets(false)) {
                if (entity != null && !entity.equals(a.entity)) continue;
                double v = NetWorth.assetValueAt(a, Long.MAX_VALUE, base);
                if (!Double.isNaN(v)) byGroup.put(a.group, (byGroup.containsKey(a.group) ? byGroup.get(a.group) : 0) + v);
            }
            double maxG = 1e-9;
            for (Double v : byGroup.values()) maxG = Math.max(maxG, Math.abs(v));
            boolean firstG = true;
            for (java.util.Map.Entry<String, Double> e : byGroup.entrySet()) {
                if (e.getValue() == 0) continue;
                if (!firstG) ac.addView(untagged(ctx, 6));
                firstG = false;
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(4), 0, dp(4));
                LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(36), dp(36));
                elp.rightMargin = dp(12);
                row.addView(FinanceUi.emojiCircle(ctx, org.telegram.messenger.finance.Asset.groupEmoji(e.getKey()), HabitUi.accent(), 36), elp);
                LinearLayout mid = new LinearLayout(ctx);
                mid.setOrientation(LinearLayout.VERTICAL);
                LinearLayout line = new LinearLayout(ctx);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.addView(HabitUi.text(ctx, org.telegram.messenger.finance.Asset.groupTitle(e.getKey()), 15, HabitUi.text()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                int pct = (int) Math.round(Math.abs(e.getValue()) * 100 / Math.max(1e-9, Math.abs(nw.assets)));
                line.addView(HabitUi.text(ctx, Money.compact(e.getValue(), base) + "  ·  " + pct + "%", 14, HabitUi.text(), true));
                mid.addView(line);
                View bar = FinanceUi.bar(ctx, (float) (Math.abs(e.getValue()) / maxG), HabitUi.accent());
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.topMargin = dp(5);
                mid.addView(bar, blp);
                row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.setBackground(org.telegram.ui.ActionBar.Theme.createSelectorDrawable(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_listSelector), 2));
                row.setOnClickListener(v2 -> presentFragment(new WealthActivity(entity)));
                ac.addView(row);
            }
            content.addView(ac);
        }

        // Debts
        if (nw.receivable > 0 || nw.payable > 0) {
            content.addView(HabitUi.spacer(ctx, 12));
            content.addView(buildDebts(ctx, nw, base));
        }

        // Categories
        List<FinanceDb.CatSum> exp = db.sumByCategory(Tx.EXPENSE, dayFrom, dayTo, entity);
        content.addView(HabitUi.spacer(ctx, 12));
        content.addView(buildCategories(ctx, Tx.EXPENSE, exp, expense, base, dayFrom, dayTo));
        List<FinanceDb.CatSum> inc = db.sumByCategory(Tx.INCOME, dayFrom, dayTo, entity);
        if (!inc.isEmpty()) {
            content.addView(HabitUi.spacer(ctx, 12));
            content.addView(buildCategories(ctx, Tx.INCOME, inc, income, base, dayFrom, dayTo));
        }

        // Recent
        FinanceDb.Filter f = new FinanceDb.Filter();
        f.entity = entity;
        f.limit = 8;
        List<Tx> recent = db.txs(f);
        if (!recent.isEmpty()) {
            content.addView(HabitUi.spacer(ctx, 12));
            content.addView(buildRecent(ctx, recent));
        }
    }

    private View buildPeriodRow(Context ctx) {
        LinearLayout l = HabitUi.notSection(new LinearLayout(ctx));
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(4), 0, dp(4), dp(10));
        TextView prev = HabitUi.text(ctx, "‹", 26, HabitUi.text(), true);
        prev.setPadding(dp(8), 0, dp(16), dp(2));
        prev.setOnClickListener(v -> { month = month.minusMonths(1); refresh(); });
        TextView title = HabitUi.text(ctx, FinanceUi.monthLabel(month), 17, HabitUi.text(), true);
        title.setGravity(Gravity.CENTER);
        title.setOnClickListener(v -> { month = YearMonth.now(); refresh(); });
        TextView next = HabitUi.text(ctx, "›", 26, month.isBefore(YearMonth.now()) ? HabitUi.text() : HabitUi.line(), true);
        next.setPadding(dp(16), 0, dp(8), dp(2));
        next.setOnClickListener(v -> { if (month.isBefore(YearMonth.now())) { month = month.plusMonths(1); refresh(); } });
        l.addView(prev);
        l.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        l.addView(next);
        return l;
    }

    private View buildOverview(Context ctx, String base, NetWorth.Breakdown nw, double change, double income, double expense) {
        double worth = nw.total();
        HashSet<String> noRate = nw.noRate;
        HabitUi.Card card = HabitUi.card(ctx);
        card.setOnClickListener(v -> presentFragment(new WealthActivity(entity)));
        card.addView(HabitUi.text(ctx, "Net worth" + (nw.assets > 0 || nw.receivable > 0 || nw.payable > 0 ? "  ·  accounts " + Money.compact(nw.accounts, base) + (nw.assets > 0 ? " · assets " + Money.compact(nw.assets, base) : "") : ""), 13, HabitUi.sub(), true));
        TextView big = HabitUi.text(ctx, Money.format(worth, base), 30, HabitUi.text(), true);
        big.setMaxLines(1);
        big.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(big);
        if (change != 0) {
            TextView ch = HabitUi.text(ctx, Money.signed(change, base) + " in " + FinanceUi.monthLabel(month), 13, change > 0 ? FinanceUi.income() : FinanceUi.expense());
            ch.setPadding(0, dp(2), 0, 0);
            card.addView(ch);
        }
        if (!noRate.isEmpty()) {
            TextView warn = HabitUi.text(ctx, "No rate for " + TextUtils.join(", ", noRate) + " — set it in Settings → Finance → Rates", 12, HabitUi.fire());
            warn.setPadding(0, dp(4), 0, 0);
            card.addView(warn);
        }
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(14), 0, 0);
        row.addView(stat(ctx, "Income", income, base, FinanceUi.income(), () -> presentFragment(TxListActivity.period("Income", entity, Tx.INCOME, null, month))));
        row.addView(stat(ctx, "Expenses", expense, base, FinanceUi.expense(), () -> presentFragment(TxListActivity.period("Expenses", entity, Tx.EXPENSE, null, month))));
        card.addView(row);
        double net = income - expense;
        if (income > 0 || expense > 0) {
            TextView n = HabitUi.text(ctx, "Net " + Money.signed(net, base), 13, HabitUi.sub());
            n.setPadding(0, dp(8), 0, 0);
            card.addView(n);
        }
        return card;
    }

    private View stat(Context ctx, String label, double value, String base, int color, Runnable onClick) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        l.addView(HabitUi.text(ctx, label, 13, HabitUi.sub()));
        TextView v = HabitUi.text(ctx, Money.compact(value, base), 18, color, true);
        v.setMaxLines(1);
        l.addView(v);
        l.setOnClickListener(x -> onClick.run());
        return l;
    }

    private View buildAccounts(Context ctx, List<Account> accounts, Map<String, Double> balances, boolean showEntity) {
        HabitUi.Card card = HabitUi.card(ctx);
        card.addView(FinanceUi.cardHeader(ctx, "Accounts", "Manage", () -> presentFragment(new AccountsActivity())));
        if (accounts.isEmpty()) {
            TextView t = HabitUi.text(ctx, "No accounts yet", 14, HabitUi.sub());
            card.addView(t);
        }
        Map<String, Entity> ents = new HashMap<>();
        for (Entity e : db.entities(true)) ents.put(e.id, e);
        for (int i = 0; i < accounts.size(); i++) {
            Account a = accounts.get(i);
            if (i > 0) card.addView(HabitUi.divider(ctx, 52));
            Double v = balances.get(a.id);
            double bal = v == null ? 0 : v;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(9), 0, dp(9));
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(40), dp(40));
            elp.rightMargin = dp(12);
            row.addView(FinanceUi.emojiCircle(ctx, a.emoji.isEmpty() ? "💳" : a.emoji, HabitUi.accent(), 40), elp);
            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(HabitUi.text(ctx, a.name, 16, HabitUi.text()));
            Entity e = ents.get(a.entity);
            String sub = showEntity && e != null ? e.name : "";
            String showIn = Finance.baseFor(entity);
            if (!a.currency.equals(showIn) && Rates.known(a.currency)) {
                sub = (sub.isEmpty() ? "" : sub + " · ") + "≈ " + Money.format(Rates.convertOr0(bal, a.currency, showIn), showIn);
            }
            if (!sub.isEmpty()) texts.addView(HabitUi.text(ctx, sub, 13, HabitUi.sub()));
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView amount = HabitUi.text(ctx, Money.format(bal, a.currency), 15, bal < 0 ? FinanceUi.expense() : HabitUi.text(), true);
            amount.setMaxLines(1);
            row.addView(amount);
            row.setBackground(org.telegram.ui.ActionBar.Theme.createSelectorDrawable(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_listSelector), 2));
            row.setOnClickListener(v2 -> presentFragment(TxListActivity.account(a)));
            card.addView(row);
        }
        return card;
    }

    private View buildDebts(Context ctx, NetWorth.Breakdown nw, String base) {
        HabitUi.Card card = HabitUi.card(ctx);
        card.addView(FinanceUi.cardHeader(ctx, "Debts", "All", () -> presentFragment(new DebtsActivity(entity, nw.receivable > 0 ? 0 : 1))));
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(stat(ctx, "Owed to me", nw.receivable, base, FinanceUi.income(), () -> presentFragment(new DebtsActivity(entity, 0))));
        row.addView(stat(ctx, "I owe", nw.payable, base, FinanceUi.expense(), () -> presentFragment(new DebtsActivity(entity, 1))));
        card.addView(row);
        return card;
    }

    private View buildCategories(Context ctx, int kind, List<FinanceDb.CatSum> sums, double total, String base, long dayFrom, long dayTo) {
        HabitUi.Card card = HabitUi.card(ctx);
        String title = (kind == Tx.INCOME ? "Income" : "Expenses") + " · " + month.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH);
        card.addView(FinanceUi.cardHeader(ctx, title, sums.isEmpty() ? null : "All", () -> presentFragment(TxListActivity.period(kind == Tx.INCOME ? "Income" : "Expenses", entity, kind, null, month))));
        if (sums.isEmpty()) {
            card.addView(HabitUi.text(ctx, kind == Tx.INCOME ? "No income this month" : "No expenses this month", 14, HabitUi.sub()));
            return card;
        }
        // merge currencies per category
        HashMap<String, Double> byCat = new HashMap<>();
        HashMap<String, Integer> counts = new HashMap<>();
        for (FinanceDb.CatSum s : sums) {
            String key = s.category == null ? "" : s.category;
            byCat.put(key, (byCat.containsKey(key) ? byCat.get(key) : 0) + s.base);
            counts.put(key, (counts.containsKey(key) ? counts.get(key) : 0) + s.count);
        }
        List<String> keys = new ArrayList<>(byCat.keySet());
        Collections.sort(keys, (a, b) -> Double.compare(byCat.get(b), byCat.get(a)));
        double max = keys.isEmpty() ? 1 : Math.max(1e-9, byCat.get(keys.get(0)));
        int shown = 0;
        for (String key : keys) {
            if (shown++ >= 8) break;
            Category c = key.isEmpty() ? null : db.category(key);
            double v = byCat.get(key);
            int color = c == null ? HabitUi.sub() : c.colorInt();
            if (shown > 1) card.addView(untagged(ctx, 6));
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(36), dp(36));
            elp.rightMargin = dp(12);
            row.addView(FinanceUi.emojiCircle(ctx, c == null ? "📦" : c.emoji, color, 36), elp);
            LinearLayout mid = new LinearLayout(ctx);
            mid.setOrientation(LinearLayout.VERTICAL);
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.addView(HabitUi.text(ctx, c == null ? "No category" : c.name, 15, HabitUi.text()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            int pct = total > 0 ? (int) Math.round(v * 100 / total) : 0;
            line.addView(HabitUi.text(ctx, Money.compact(v, base) + "  ·  " + pct + "%", 14, HabitUi.text(), true));
            mid.addView(line);
            View bar = FinanceUi.bar(ctx, (float) (v / max), color);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(5);
            mid.addView(bar, blp);
            row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final String catId = key.isEmpty() ? null : key;
            row.setBackground(org.telegram.ui.ActionBar.Theme.createSelectorDrawable(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_listSelector), 2));
            row.setOnClickListener(v2 -> presentFragment(TxListActivity.period(c == null ? "No category" : c.title(), entity, kind, catId, month)));
            card.addView(row);
        }
        return card;
    }

    private static View untagged(Context ctx, int h) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private View buildRecent(Context ctx, List<Tx> recent) {
        HabitUi.Card card = HabitUi.card(ctx);
        card.addView(FinanceUi.cardHeader(ctx, "Recent", "All", () -> presentFragment(TxListActivity.all(entity))));
        Map<String, Account> accounts = new HashMap<>();
        for (Account a : db.accounts(true)) accounts.put(a.id, a);
        Map<String, Category> cats = new HashMap<>();
        for (Category c : db.categories(Category.EXPENSE, null, true)) cats.put(c.id, c);
        for (Category c : db.categories(Category.INCOME, null, true)) cats.put(c.id, c);
        for (int i = 0; i < recent.size(); i++) {
            Tx t = recent.get(i);
            if (i > 0) card.addView(HabitUi.divider(ctx, 52));
            card.addView(FinanceUi.txRow(ctx, t, accounts, cats, true, () -> presentFragment(TxEditActivity.edit(t.id))));
        }
        return card;
    }

    // endregion
}
