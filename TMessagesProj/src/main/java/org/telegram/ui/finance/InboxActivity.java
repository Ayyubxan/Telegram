package org.telegram.ui.finance;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.messenger.finance.Account;
import org.telegram.messenger.finance.BotImport;
import org.telegram.messenger.finance.BotParser;
import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.finance.FinanceDb;
import org.telegram.messenger.finance.ImportRule;
import org.telegram.messenger.finance.InboxItem;
import org.telegram.messenger.finance.Money;
import org.telegram.messenger.finance.Tx;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.habits.HabitUi;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** FORK MOD (Finance): bot messages waiting for review — tap to confirm (opens the editor), long-press to dismiss */
@SuppressLint("SetTextI18n")
public class InboxActivity extends FinancePage {
    private static final int MENU_DISMISS_ALL = 1;
    private static final int MENU_ACCEPT_ALL = 2;
    private final FinanceDb db = Finance.db();

    @Override
    protected CharSequence title() {
        return "Inbox";
    }

    @Override
    protected void buildMenu(ActionBarMenu menu) {
        org.telegram.ui.ActionBar.ActionBarMenuItem more = menu.addItem(0, R.drawable.ic_ab_other);
        more.addSubItem(MENU_ACCEPT_ALL, R.drawable.msg_select, "Accept all complete");
        more.addSubItem(MENU_DISMISS_ALL, R.drawable.msg_delete, "Dismiss all");
    }

    @Override
    protected void onMenuClick(int id) {
        if (id == MENU_DISMISS_ALL) {
            HabitUi.confirm(this, null, "Dismiss everything in the Inbox?", "Dismiss", true, () -> { for (InboxItem i : db.inbox(InboxItem.PENDING)) BotImport.dismiss(i); });
        } else if (id == MENU_ACCEPT_ALL) {
            int n = 0;
            for (InboxItem i : db.inbox(InboxItem.PENDING)) {
                Tx tx = BotImport.toTx(db.rule(i.rule), i);
                if (tx != null && tx.account != null && i.parsed != null && i.parsed.kind >= 0) { BotImport.accept(i, tx); n++; }
            }
            HabitUi.toastSuccess(this, n + " added");
        }
    }

    @Override
    protected void refresh() {
        Context ctx = getContext();
        content.removeAllViews();
        List<InboxItem> items = db.inbox(InboxItem.PENDING);
        java.util.Collections.sort(items, (a, b) -> Long.compare(b.parsed == null ? b.created : b.parsed.ts, a.parsed == null ? a.created : a.parsed.ts));
        if (items.isEmpty()) {
            TextView t = HabitUi.notSection(HabitUi.text(ctx, "Nothing to review.\nMessages from your bank and business bots will appear here.", 15, HabitUi.sub()));
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(16), dp(48), dp(16), 0);
            content.addView(t);
            return;
        }
        HabitUi.Card card = HabitUi.card(ctx);
        for (int i = 0; i < items.size(); i++) {
            InboxItem it = items.get(i);
            if (i > 0) card.addView(HabitUi.divider(ctx, 0));
            ImportRule rule = db.rule(it.rule);
            BotParser.Parsed p = it.parsed == null ? new BotParser.Parsed() : it.parsed;
            Tx pre = BotImport.toTx(rule, it);
            Account acc = pre == null || pre.account == null ? null : db.account(pre.account);
            org.telegram.messenger.finance.Category cat = pre == null ? null : db.category(pre.category);
            boolean complete = acc != null && p.kind >= 0 && p.hasAmount();
            boolean dup = pre != null && !org.telegram.messenger.finance.Merge.candidates(pre).isEmpty();
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            String title = (p.title.isEmpty() ? (p.kind == Tx.INCOME ? "Income" : p.kind == Tx.EXPENSE ? "Expense" : "Transaction") : p.title);
            TextView t1 = HabitUi.text(ctx, title, 16, HabitUi.text());
            t1.setMaxLines(1);
            t1.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(t1);
            String sub = (rule == null ? "" : rule.name) + (acc != null ? " · " + acc.title() : p.card != null ? " · card *" + p.card + " → ?" : "");
            if (cat != null) sub += " · " + cat.title();
            if (!p.note.isEmpty()) sub += " · " + p.note;
            if (dup) sub += " · ⚠ same payment exists";
            if (p.ts > 0) sub = FinanceUi.shortDate(Instant.ofEpochMilli(p.ts).atZone(ZoneId.systemDefault()).toLocalDate()) + " · " + sub;
            TextView t2 = HabitUi.text(ctx, sub, 13, complete ? HabitUi.sub() : HabitUi.fire());
            t2.setMaxLines(2);
            t2.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(t2);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            String amount = p.hasAmount() ? (p.kind == Tx.INCOME ? "+" : p.kind == Tx.EXPENSE ? "−" : "") + Money.format(p.amount, p.currency == null ? (acc == null ? Finance.base() : acc.currency) : p.currency) : "?";
            TextView a = HabitUi.text(ctx, amount, 15, p.kind == Tx.INCOME ? FinanceUi.income() : p.kind == Tx.EXPENSE ? FinanceUi.expense() : HabitUi.text(), true);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.leftMargin = dp(10);
            row.addView(a, alp);
            row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            row.setOnClickListener(v -> presentFragment(TxEditActivity.fromInbox(it.id)));
            row.setOnLongClickListener(v -> {
                FinanceUi.pick(this, null, new CharSequence[]{"Dismiss", "Show message"}, which -> {
                    if (which == 0) BotImport.dismiss(it);
                    else showDialog(new org.telegram.ui.ActionBar.AlertDialog.Builder(ctx).setTitle(rule == null ? "Message" : rule.name).setMessage(it.text).setPositiveButton("OK", null).create());
                });
                return true;
            });
            card.addView(row);
        }
        content.addView(card);
        content.addView(new HabitUi.Form(ctx).helper("Tap to review and add. Orange lines need an account or a type. Long-press to dismiss or see the original message."));
    }
}
