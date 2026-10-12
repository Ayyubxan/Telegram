package org.telegram.ui.finance;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
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
import org.telegram.messenger.finance.Category;
import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.finance.FinanceDb;
import org.telegram.messenger.finance.Money;
import org.telegram.messenger.finance.Rates;
import org.telegram.messenger.finance.Tx;
import org.telegram.messenger.finance.Entity;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SectionsScrollView;
import org.telegram.ui.habits.HabitUi;

import java.time.LocalDate;
import java.util.List;

/** FORK MOD (Finance): add / edit a transaction — expense, income or transfer */
@SuppressLint("SetTextI18n")
public class TxEditActivity extends BaseFragment {
    private static final int MENU_DONE = 1;

    private FinanceDb db;
    private Tx tx;
    private boolean isNew;
    private org.telegram.messenger.finance.InboxItem inboxItem;
    private boolean remember, rememberAuto;
    private String mergeWithId;
    private boolean toAmountManual;

    private LinearLayout content;
    private EditTextBoldCursor amountInput, toAmountInput, noteInput;
    private TextView currencyLabel;

    public static TxEditActivity create(int kind, String entity, String accountId) {
        Bundle b = new Bundle();
        b.putInt("kind", kind);
        if (entity != null) b.putString("entity", entity);
        if (accountId != null) b.putString("account", accountId);
        return new TxEditActivity(b);
    }

    public static TxEditActivity fromInbox(String inboxId) {
        Bundle b = new Bundle();
        b.putString("inbox", inboxId);
        return new TxEditActivity(b);
    }

    public static TxEditActivity edit(String id) {
        Bundle b = new Bundle();
        b.putString("id", id);
        return new TxEditActivity(b);
    }

    private TxEditActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        db = Finance.db();
        String id = arguments.getString("id");
        tx = id == null ? null : db.tx(id);
        isNew = tx == null;
        String inboxId = arguments.getString("inbox");
        if (inboxId != null) {
            inboxItem = db.inboxItem(inboxId);
            if (inboxItem != null) {
                Tx pre = org.telegram.messenger.finance.BotImport.toTx(db.rule(inboxItem.rule), inboxItem);
                if (pre != null) {
                    tx = pre;
                    isNew = true;
                    if (tx.account == null) {
                        List<Account> list = db.accounts(false);
                        if (!list.isEmpty()) { tx.account = list.get(0).id; tx.currency = list.get(0).currency; tx.entity = list.get(0).entity; }
                    }
                    toAmountManual = true;
                    return super.onFragmentCreate();
                }
            }
        }
        if (isNew) {
            tx = new Tx();
            tx.kind = arguments.getInt("kind", Tx.EXPENSE);
            tx.setMoment(System.currentTimeMillis());
            String entity = arguments.getString("entity");
            String accId = arguments.getString("account");
            Account acc = accId != null ? db.account(accId) : null;
            if (acc == null) {
                List<Account> list = db.accounts(entity, false);
                if (list.isEmpty()) list = db.accounts(false);
                String last = Finance.prefs().getString("lastAccount", null);
                for (Account a : list) if (a.id.equals(last)) acc = a;
                if (acc == null && !list.isEmpty()) acc = list.get(0);
            }
            if (acc != null) {
                tx.account = acc.id;
                tx.currency = acc.currency;
                tx.entity = acc.entity;
            }
        } else {
            toAmountManual = true;
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(inboxItem != null ? "Review" : isNew ? LocaleController.getString(R.string.FinanceNewTransaction) : LocaleController.getString(R.string.FinanceTransaction));
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_DONE, R.drawable.ic_ab_done).setContentDescription(LocaleController.getString(R.string.Done));
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

    private Account account() { return db.account(tx.account); }
    private Account toAccount() { return db.account(tx.toAccount); }

    private void build() {
        Context ctx = getContext();
        content.removeAllViews();
        HabitUi.Form form = new HabitUi.Form(ctx);

        // Kind
        String[][] kinds = {{"0", "Expense"}, {"1", "Income"}, {"2", "Transfer"}};
        View seg = form.segmented(kinds, String.valueOf(tx.kind), key -> {
            readInputs();
            tx.kind = Integer.parseInt(key);
            if (tx.kind == Tx.TRANSFER && tx.toAccount == null) {
                for (Account a : db.accounts(false)) if (!a.id.equals(tx.account)) { tx.toAccount = a.id; break; }
            }
            if (tx.kind != Tx.TRANSFER) {
                Category cur = db.category(tx.category);
                if (cur == null || cur.kind != tx.kind) tx.category = null;
            }
            build();
        });
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        content.addView(seg, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(HabitUi.spacer(ctx, 12));

        // Amount
        HabitUi.Card amountCard = HabitUi.card(ctx);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        amountInput = FinanceUi.amountInput(ctx, "0");
        amountInput.setTextColor(FinanceUi.kindColor(tx.kind));
        if (tx.amount > 0) amountInput.setText(Money.plain(tx.amount, tx.currency == null ? "UZS" : tx.currency));
        row.addView(amountInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Account acc = account();
        currencyLabel = HabitUi.text(ctx, acc == null ? "" : acc.currency, 18, HabitUi.sub(), true);
        currencyLabel.setPadding(dp(12), 0, 0, 0);
        row.addView(currencyLabel);
        amountCard.addView(row);
        content.addView(amountCard);

        // Details
        HabitUi.Form.Group g = form.new Group(null, content);
        if (tx.kind == Tx.TRANSFER) {
            g.add(form.new Row("From", () -> FinanceUi.pickAccount(this, "From", db.accounts(false), a -> {
                readInputs();
                tx.account = a.id; tx.currency = a.currency; tx.entity = a.entity;
                build();
            })).value(acc == null ? "Choose" : acc.title()).view);
            Account to = toAccount();
            g.add(form.new Row("To", () -> FinanceUi.pickAccount(this, "To", db.accounts(false), a -> {
                readInputs();
                tx.toAccount = a.id;
                toAmountManual = false;
                build();
            })).value(to == null ? "Choose" : to.title()).view);
            if (acc != null && to != null && !acc.currency.equals(to.currency)) {
                LinearLayout rr = new LinearLayout(ctx);
                rr.setOrientation(LinearLayout.HORIZONTAL);
                rr.setGravity(Gravity.CENTER_VERTICAL);
                rr.setMinimumHeight(dp(52));
                rr.setPadding(dp(16), dp(6), dp(16), dp(6));
                rr.addView(HabitUi.text(ctx, "Received", 16, HabitUi.text()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                toAmountInput = HabitUi.input(ctx, "0");
                toAmountInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
                toAmountInput.setGravity(Gravity.END);
                if (!toAmountManual || tx.toAmount <= 0) {
                    double a = currentAmount();
                    tx.toAmount = Money.round(Rates.convertOr0(a, acc.currency, to.currency), to.currency);
                }
                if (tx.toAmount > 0) toAmountInput.setText(Money.plain(tx.toAmount, to.currency));
                toAmountInput.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                    @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                    @Override public void afterTextChanged(android.text.Editable s) { if (toAmountInput.hasFocus()) toAmountManual = true; }
                });
                rr.addView(toAmountInput, new LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT));
                rr.addView(HabitUi.text(ctx, " " + to.currency, 15, HabitUi.sub(), true));
                g.add(rr);
                // follow the main amount while not edited by hand
                amountInput.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                    @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                    @Override public void afterTextChanged(android.text.Editable s) {
                        if (toAmountManual || toAmountInput == null) return;
                        double a = Money.parse(s.toString());
                        toAmountInput.setText(Double.isNaN(a) ? "" : Money.plain(Money.round(Rates.convertOr0(a, acc.currency, to.currency), to.currency), to.currency));
                    }
                });
            } else {
                toAmountInput = null;
            }
        } else {
            g.add(form.new Row("Account", () -> FinanceUi.pickAccount(this, "Account", db.accounts(false), a -> {
                readInputs();
                tx.account = a.id; tx.currency = a.currency; tx.entity = a.entity;
                build();
            })).value(acc == null ? "Choose" : acc.title()).view);
            Category cat = db.category(tx.category);
            g.add(form.new Row("Category", () -> FinanceUi.pickCategory(this, tx.kind, tx.entity, tx.category, c2 -> {
                readInputs();
                tx.category = c2.id;
                build();
            })).value(cat == null ? "Choose" : cat.title()).valueColor(cat == null ? HabitUi.accent() : HabitUi.sub()).view);
        }
        g.add(form.new Row("Date", () -> FinanceUi.pickDate(this, tx.date(), d -> {
            readInputs();
            tx.setDate(d);
            build();
        })).value(FinanceUi.dayLabel(tx.date())).view);
        LinearLayout noteRow = new LinearLayout(ctx);
        noteRow.setPadding(dp(16), dp(6), dp(16), dp(6));
        noteInput = HabitUi.input(ctx, "Note");
        if (!tx.note.isEmpty()) noteInput.setText(tx.note);
        noteRow.addView(noteInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        g.add(noteRow);

        // Merge with a matching record from another business / personal (same payment seen twice)
        readInputs();
        java.util.List<Tx> matches = tx.kind == Tx.TRANSFER ? new java.util.ArrayList<>() : org.telegram.messenger.finance.Merge.candidates(tx);
        if (!matches.isEmpty()) {
            content.addView(HabitUi.section(ctx, "SAME PAYMENT?"));
            HabitUi.Card mc = HabitUi.card(ctx);
            Tx chosen = mergeWithId == null ? null : db.tx(mergeWithId);
            Account ca = chosen == null ? null : db.account(chosen.account);
            Entity ce = ca == null ? null : db.entity(ca.entity);
            String label = chosen == null ? "Merge with…" : "Merge with " + (ce == null ? "" : ce.name + " · ") + Money.format(chosen.amount, chosen.currency) + " " + FinanceUi.shortDate(chosen.date());
            HabitUi.Form.Row mrow = form.new Row(label, () -> {
                CharSequence[] items = new CharSequence[matches.size() + 1];
                for (int i = 0; i < matches.size(); i++) {
                    Tx m = matches.get(i);
                    Account ma = db.account(m.account);
                    Entity me = ma == null ? null : db.entity(ma.entity);
                    items[i] = (me == null ? "" : me.name + " · ") + (ma == null ? "" : ma.name + " · ") + Money.format(m.amount, m.currency) + " · " + FinanceUi.shortDate(m.date()) + (m.note.isEmpty() ? "" : " · " + m.note);
                }
                items[matches.size()] = "Don't merge";
                FinanceUi.pick(this, "Same payment recorded twice?", items, i -> { mergeWithId = i < matches.size() ? matches.get(i).id : null; refreshKeepingInputs(); });
            });
            mrow.titleColor(HabitUi.accent());
            mc.setPadding(0, 0, 0, 0);
            mc.addView(mrow.view);
            content.addView(mc);
            content.addView(form.helper(matches.size() + (matches.size() == 1 ? " similar record" : " similar records") + " in another business or personal. Merging keeps this one as the expense and turns the other into a transfer into this account, so nothing is counted twice."));
        }
        if (inboxItem != null && inboxItem.parsed != null) {
            String key = org.telegram.messenger.finance.BotImport.merchantKey(inboxItem.parsed);
            if (!key.isEmpty()) {
                HabitUi.Form.Group rg = form.new Group("Remember", content);
                rg.add(form.new SwitchRow("\"" + (key.length() > 28 ? key.substring(0, 28) + "…" : key) + "\" → this category", remember, on -> { remember = on; if (!on) rememberAuto = false; refreshKeepingInputs(); })
                    .sub("Future messages with this merchant get the category and account you choose here").view);
                if (remember) rg.add(form.new SwitchRow("Add them automatically", rememberAuto, on -> rememberAuto = on).sub("Skip the Inbox for this merchant").view);
            }
        }
        if (inboxItem != null) {
            content.addView(HabitUi.section(ctx, "FROM THE BOT"));
            HabitUi.Card msg = HabitUi.card(ctx);
            msg.addView(HabitUi.text(ctx, inboxItem.text, 13, HabitUi.sub()));
            content.addView(msg);
            content.addView(HabitUi.spacer(ctx, 12));
            content.addView(FinanceUi.dangerRow(ctx, "Dismiss (not a transaction)", () -> {
                org.telegram.messenger.finance.BotImport.dismiss(inboxItem);
                finishFragment();
            }));
        }
        if (!isNew) {
            content.addView(HabitUi.spacer(ctx, 24));
            content.addView(FinanceUi.dangerRow(ctx, LocaleController.getString(R.string.FinanceDeleteTransaction), () ->
                HabitUi.confirm(this, null, "Delete this transaction?", LocaleController.getString(R.string.Delete), true, () -> {
                    db.deleteTx(tx.id);
                    finishFragment();
                })));
        }
        if (isNew && tx.amount == 0) {
            AndroidUtilities.runOnUIThread(() -> HabitUi.focusWithKeyboard(amountInput), 150);
        }
    }

    private void refreshKeepingInputs() {
        readInputs();
        build();
    }

    private double currentAmount() {
        if (amountInput == null) return tx.amount;
        double a = Money.parse(amountInput.getText().toString());
        return Double.isNaN(a) ? 0 : a;
    }

    private void readInputs() {
        tx.amount = currentAmount();
        if (noteInput != null) tx.note = noteInput.getText().toString().trim();
        if (toAmountInput != null) {
            double t = Money.parse(toAmountInput.getText().toString());
            if (!Double.isNaN(t)) tx.toAmount = t;
        }
    }

    private void save() {
        readInputs();
        Account acc = account();
        if (acc == null) { HabitUi.toast(this, "Choose an account"); return; }
        if (tx.amount <= 0) { HabitUi.toast(this, "Enter the amount"); return; }
        tx.currency = acc.currency;
        tx.entity = acc.entity;
        if (tx.kind == Tx.TRANSFER) {
            Account to = toAccount();
            if (to == null) { HabitUi.toast(this, "Choose the receiving account"); return; }
            if (to.id.equals(acc.id)) { HabitUi.toast(this, "Choose two different accounts"); return; }
            if (acc.currency.equals(to.currency)) tx.toAmount = tx.amount;
            else if (tx.toAmount <= 0) { HabitUi.toast(this, "Enter the received amount"); return; }
            tx.category = null;
        } else {
            if (tx.category == null) { HabitUi.toast(this, "Choose a category"); return; }
        }
        tx.amount = Money.round(tx.amount, tx.currency);
        if (isNew && tx.source == 0) tx.source = Tx.SOURCE_MANUAL;
        if (inboxItem != null) org.telegram.messenger.finance.BotImport.accept(inboxItem, tx);
        else db.saveTx(tx);
        if (inboxItem != null && remember && inboxItem.parsed != null && tx.category != null) {
            org.telegram.messenger.finance.CatRule cr = new org.telegram.messenger.finance.CatRule();
            cr.rule = inboxItem.rule;
            cr.pattern = org.telegram.messenger.finance.BotImport.merchantKey(inboxItem.parsed);
            cr.category = tx.category;
            cr.account = tx.account;
            cr.auto = rememberAuto;
            db.saveCatRule(cr);
        }
        if (mergeWithId != null) {
            Tx other = db.tx(mergeWithId);
            if (other != null) org.telegram.messenger.finance.Merge.merge(tx, other);
        }
        Finance.prefs().edit().putString("lastAccount", tx.account).apply();
        AndroidUtilities.hideKeyboard(fragmentView);
        finishFragment();
    }
}
