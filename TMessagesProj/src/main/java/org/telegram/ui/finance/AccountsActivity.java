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
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SectionsScrollView;
import org.telegram.ui.habits.HabitUi;

import java.util.List;
import java.util.Map;

/** FORK MOD (Finance): accounts grouped by entity (Personal / each business) with balances; tap — transactions, long-press — edit */
@SuppressLint("SetTextI18n")
public class AccountsActivity extends BaseFragment {
    private FinanceDb db;
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

    @Override
    public boolean onFragmentCreate() {
        db = Finance.db();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.FinanceAccounts));
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
        Map<String, Double> balances = db.balances();
        String base = Finance.base();
        List<Entity> entities = db.entities(true);
        boolean first = true;
        for (Entity e : entities) {
            List<Account> accounts = db.accounts(e.id, true);
            if (!first) content.addView(HabitUi.spacer(ctx, 4));
            first = false;
            // header: entity + total
            double total = 0;
            String ebase = Finance.baseFor(e.id);
            for (Account a : accounts) if (!a.archived) { Double v = balances.get(a.id); if (v != null) total += Rates.convertOr0(v, a.currency, ebase); }
            LinearLayout h = HabitUi.notSection(new LinearLayout(ctx));
            h.setOrientation(LinearLayout.HORIZONTAL);
            h.setGravity(Gravity.CENTER_VERTICAL);
            h.setPadding(dp(16), dp(16), dp(16), dp(8));
            h.addView(HabitUi.text(ctx, e.title().toUpperCase(), 13, HabitUi.sub(), true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            h.addView(HabitUi.text(ctx, Money.format(total, ebase), 13, HabitUi.sub(), true));
            h.setOnClickListener(v -> presentFragment(new EntityActivity(e.id)));
            content.addView(h);

            HabitUi.Card card = HabitUi.card(ctx);
            card.setPadding(0, dp(4), 0, dp(4));
            for (Account a : accounts) {
                Double v = balances.get(a.id);
                double bal = v == null ? 0 : v;
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(9), dp(16), dp(9));
                LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(40), dp(40));
                elp.rightMargin = dp(12);
                row.addView(FinanceUi.emojiCircle(ctx, a.emoji.isEmpty() ? "💳" : a.emoji, a.archived ? HabitUi.sub() : HabitUi.accent(), 40), elp);
                LinearLayout texts = new LinearLayout(ctx);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.addView(HabitUi.text(ctx, a.name + (a.archived ? " · archived" : ""), 16, a.archived ? HabitUi.sub() : HabitUi.text()));
                String sub = a.currency;
                if (!a.currency.equals(base) && Rates.known(a.currency)) sub += " · ≈ " + Money.format(Rates.toBase(bal, a.currency), base);
                if (!a.wealth) sub += " · not in net worth";
                texts.addView(HabitUi.text(ctx, sub, 13, HabitUi.sub()));
                row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(HabitUi.text(ctx, Money.format(bal, a.currency), 15, bal < 0 ? FinanceUi.expense() : HabitUi.text(), true));
                row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
                row.setOnClickListener(x -> presentFragment(TxListActivity.account(a)));
                row.setOnLongClickListener(x -> { presentFragment(new AccountEditActivity(a.id, null)); return true; });
                card.addView(row);
                card.addView(HabitUi.divider(ctx, 68));
            }
            TextView add = HabitUi.text(ctx, "＋  Add account", 16, HabitUi.accent());
            add.setPadding(dp(16), dp(14), dp(16), dp(14));
            add.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            add.setOnClickListener(v -> presentFragment(new AccountEditActivity(null, e.id)));
            card.addView(add);
            content.addView(card);
        }
        content.addView(HabitUi.spacer(ctx, 16));
        TextView addBiz = HabitUi.text(ctx, "＋  Add business", 16, HabitUi.accent());
        addBiz.setPadding(dp(16), dp(14), dp(16), dp(14));
        addBiz.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
        addBiz.setOnClickListener(v -> FinanceUi.entityDialog(this, null, (n, em) -> {
            Entity e = new Entity();
            e.id = Finance.newId(); e.name = n; e.emoji = em; e.color = ""; e.sort = entities.size();
            db.saveEntity(e);
        }));
        HabitUi.Card c2 = HabitUi.card(ctx);
        c2.setPadding(0, 0, 0, 0);
        c2.addView(addBiz);
        content.addView(c2);
        TextView hint = HabitUi.notSection(HabitUi.text(ctx, "Long-press an account to edit it. A business has its own accounts; money moved from a personal account into a business account counts as invested capital.", 13, HabitUi.sub()));
        hint.setPadding(dp(16), dp(8), dp(16), 0);
        content.addView(hint);
    }
}
