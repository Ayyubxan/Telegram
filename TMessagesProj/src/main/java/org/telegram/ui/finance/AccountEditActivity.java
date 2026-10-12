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

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.finance.Account;
import org.telegram.messenger.finance.Entity;
import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.finance.FinanceDb;
import org.telegram.messenger.finance.Money;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SectionsScrollView;
import org.telegram.ui.habits.EmojiPickerSheet;
import org.telegram.ui.habits.HabitUi;

/** FORK MOD (Finance): create / edit an account — name, emoji, owner, currency, type, opening balance */
@SuppressLint("SetTextI18n")
public class AccountEditActivity extends BaseFragment {
    private static final int MENU_DONE = 1;

    private FinanceDb db;
    private Account acc;
    private boolean isNew;
    private LinearLayout content;
    private EditTextBoldCursor name;

    public AccountEditActivity(String id, String entity) {
        db = Finance.db();
        acc = id == null ? null : db.account(id);
        isNew = acc == null;
        if (isNew) {
            acc = new Account();
            acc.id = Finance.newId();
            acc.entity = entity == null ? Entity.PERSONAL : entity;
            acc.name = "";
            acc.emoji = "💳";
            acc.currency = Finance.base();
            acc.type = Account.TYPE_CARD;
            acc.sort = db.accounts(true).size();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(isNew ? "New account" : "Account");
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_DONE, R.drawable.ic_ab_done);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (id == MENU_DONE) save();
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
        build();
        return root;
    }

    private void applyPaddings() {
        if (content != null) content.setPadding(dp(12), dp(12), dp(12), dp(88));
    }

    private void build() {
        Context ctx = getContext();
        String typed = name == null ? acc.name : name.getText().toString();
        content.removeAllViews();
        HabitUi.Form form = new HabitUi.Form(ctx);

        HabitUi.Card head = HabitUi.card(ctx);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView emoji = EmojiPickerSheet.button(this, acc.emoji.isEmpty() ? "💳" : acc.emoji, 56, e -> acc.emoji = e);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(56), dp(56));
        elp.rightMargin = dp(14);
        row.addView(emoji, elp);
        name = HabitUi.input(ctx, "Account name");
        name.setText(typed);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(row);
        content.addView(head);

        HabitUi.Form.Group g = form.new Group(null, content);
        Entity e = db.entity(acc.entity);
        g.add(form.new Row("Owner", () -> FinanceUi.pickEntity(this, "Owner", false, ent -> { acc.entity = ent.id; build(); })).value(e == null ? "Personal" : e.title()).view);
        g.add(form.new Row("Currency", () -> FinanceUi.pickCurrency(this, acc.currency, code -> { acc.currency = code; build(); })).value(acc.currency).view);
        String[][] types = {{"0", "Cash"}, {"1", "Card"}, {"2", "Bank"}, {"3", "Other"}};
        g.add(form.segmentedRow(types, String.valueOf(acc.type), key -> { acc.type = Integer.parseInt(key); build(); }));
        g.add(form.new Row(isNew ? "Opening balance" : "Opening balance", () -> FinanceUi.amountDialog(this, "Opening balance", acc.currency, acc.initial, v -> { acc.initial = v; build(); }))
            .value(Money.format(acc.initial, acc.currency)).view);
        g.add(form.new SwitchRow("Count in net worth", acc.wealth, on -> acc.wealth = on).view);
        if (!isNew) {
            g.add(form.new SwitchRow("Archived", acc.archived, on -> acc.archived = on).sub("Hidden from lists, history is kept").view);
            content.addView(HabitUi.spacer(ctx, 24));
            int n = db.txCount(acc.id);
            content.addView(FinanceUi.dangerRow(ctx, "Delete account", () -> {
                if (n > 0) {
                    HabitUi.confirm(this, null, "This account has " + n + " transactions. Delete the account and all of them?", LocaleController.getString(R.string.Delete), true, () -> {
                        for (org.telegram.messenger.finance.Tx t : db.txs(txFilter())) db.deleteTx(t.id);
                        db.deleteAccount(acc.id);
                        finishFragment();
                    });
                } else {
                    HabitUi.confirm(this, null, "Delete \"" + acc.name + "\"?", LocaleController.getString(R.string.Delete), true, () -> {
                        db.deleteAccount(acc.id);
                        finishFragment();
                    });
                }
            }));
        }
        if (isNew && typed.isEmpty()) AndroidUtilities.runOnUIThread(() -> HabitUi.focusWithKeyboard(name), 150);
    }

    private FinanceDb.Filter txFilter() {
        FinanceDb.Filter f = new FinanceDb.Filter();
        f.account = acc.id;
        return f;
    }

    private void save() {
        String n = name.getText().toString().trim();
        if (n.isEmpty()) { HabitUi.toast(this, "Enter a name"); return; }
        acc.name = n;
        db.saveAccount(acc);
        AndroidUtilities.hideKeyboard(fragmentView);
        finishFragment();
    }
}
