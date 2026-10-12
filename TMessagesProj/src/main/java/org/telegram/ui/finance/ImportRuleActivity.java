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

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.finance.Account;
import org.telegram.messenger.finance.BotImport;
import org.telegram.messenger.finance.BotParser;
import org.telegram.messenger.finance.Category;
import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.finance.FinanceDb;
import org.telegram.messenger.finance.ImportRule;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.habits.HabitUi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FORK MOD (Finance): set up how one chat's messages are parsed — the sample message is shown line by line and every
 * line is mapped to a field; plus default account / categories, card → account map, review vs auto, history scan.
 */
@SuppressLint("SetTextI18n")
public class ImportRuleActivity extends FinancePage {
    private final String id;
    private final FinanceDb db = Finance.db();
    private ImportRule rule;

    public ImportRuleActivity(String id) {
        this.id = id;
    }

    @Override
    protected CharSequence title() {
        ImportRule r = db.rule(id);
        return r == null ? "Import" : r.name;
    }

    @Override
    protected void refresh() {
        Context ctx = getContext();
        rule = db.rule(id);
        if (rule == null) { finishFragment(); return; }
        content.removeAllViews();
        HabitUi.Form form = new HabitUi.Form(ctx);

        HabitUi.Form.Group g = form.new Group(null, content);
        g.add(form.new SwitchRow("Enabled", rule.enabled, on -> { rule.enabled = on; db.saveRule(rule); }).view);
        g.add(form.new SwitchRow("Add automatically", rule.auto, on -> { rule.auto = on; db.saveRule(rule); })
            .sub("Off — new messages wait in the Inbox for review").view);
        Account acc = db.account(rule.account);
        g.add(form.new Row("Default account", () -> FinanceUi.pickAccount(this, "Default account", db.accounts(false), a -> { rule.account = a.id; db.saveRule(rule); }))
            .value(acc == null ? "Choose" : acc.title()).valueColor(acc == null ? HabitUi.accent() : HabitUi.sub()).view);
        Category cin = db.category(rule.catIn), cout = db.category(rule.catOut);
        g.add(form.new Row("Income category", () -> FinanceUi.pickCategory(this, Category.INCOME, acc == null ? null : acc.entity, rule.catIn, c -> { rule.catIn = c.id; db.saveRule(rule); }))
            .value(cin == null ? "Ask" : cin.title()).view);
        g.add(form.new Row("Expense category", () -> FinanceUi.pickCategory(this, Category.EXPENSE, acc == null ? null : acc.entity, rule.catOut, c -> { rule.catOut = c.id; db.saveRule(rule); }))
            .value(cout == null ? "Ask" : cout.title()).view);

        // card map
        if (!rule.cards.isEmpty()) {
            content.addView(HabitUi.section(ctx, "CARDS → ACCOUNTS"));
            HabitUi.Card cards = HabitUi.card(ctx);
            cards.setPadding(0, 0, 0, 0);
            boolean first = true;
            for (Map.Entry<String, String> e : new ArrayList<>(rule.cards.entrySet())) {
                if (!first) cards.addView(HabitUi.divider(ctx, 16));
                first = false;
                Account a = db.account(e.getValue());
                HabitUi.Form.Row row = form.new Row("💳 *" + e.getKey(), () -> FinanceUi.pickAccount(this, "Card *" + e.getKey(), db.accounts(false), a2 -> { rule.cards.put(e.getKey(), a2.id); db.saveRule(rule); }));
                row.value(a == null ? "Choose" : a.title());
                row.view.setOnLongClickListener(v -> { rule.cards.remove(e.getKey()); db.saveRule(rule); return true; });
                cards.addView(row.view);
            }
            content.addView(cards);
            content.addView(form.helper("Learned when you accept Inbox items. Long-press to forget a card."));
        }

        // learned merchant → category rules
        java.util.List<org.telegram.messenger.finance.CatRule> learned = db.catRules(rule.id);
        if (!learned.isEmpty()) {
            content.addView(HabitUi.section(ctx, "REMEMBERED MERCHANTS"));
            HabitUi.Card lc = HabitUi.card(ctx);
            lc.setPadding(0, 0, 0, 0);
            boolean first = true;
            for (org.telegram.messenger.finance.CatRule cr : learned) {
                if (!first) lc.addView(HabitUi.divider(ctx, 16));
                first = false;
                Category c = db.category(cr.category);
                Account a = db.account(cr.account);
                HabitUi.Form.Row row = form.new Row(cr.pattern, null);
                row.value((c == null ? "?" : c.title()) + (a == null ? "" : " · " + a.name) + (cr.auto ? " · auto" : ""));
                row.view.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
                row.view.setOnClickListener(v -> FinanceUi.pick(this, cr.pattern, new CharSequence[]{cr.auto ? "Review in Inbox instead" : "Add automatically", "Forget"}, w -> {
                    if (w == 0) { cr.auto = !cr.auto; db.saveCatRule(cr); } else db.deleteCatRule(cr.id);
                }));
                lc.addView(row.view);
            }
            content.addView(lc);
            content.addView(form.helper("Learned when you tick \"Remember\" while reviewing. Tap to change or forget."));
        }

        // sample + mapping
        content.addView(HabitUi.section(ctx, "SAMPLE MESSAGE"));
        HabitUi.Card sample = HabitUi.card(ctx);
        List<BotParser.Line> lines = BotParser.lines(rule.sample);
        if (lines.isEmpty()) {
            sample.addView(HabitUi.text(ctx, "No sample yet — paste a message from this chat.", 14, HabitUi.sub()));
        }
        for (int i = 0; i < lines.size(); i++) {
            BotParser.Line l = lines.get(i);
            if (i > 0) sample.addView(HabitUi.divider(ctx, 0));
            BotParser.LineRule lr = ruleFor(l);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            TextView raw = HabitUi.text(ctx, l.raw, 15, HabitUi.text());
            raw.setMaxLines(2);
            raw.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(raw);
            TextView field = HabitUi.text(ctx, "→ " + BotParser.fieldTitle(lr == null ? BotParser.F_IGNORE : lr.field), 13, lr == null || BotParser.F_IGNORE.equals(lr.field) ? HabitUi.sub() : HabitUi.accent());
            row.addView(field);
            row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            row.setOnClickListener(v -> pickField(l));
            sample.addView(row);
        }
        content.addView(sample);
        content.addView(form.helper("Tap a line to say what it means. Lines starting with the same symbol are treated the same way."));
        BotParser.Parsed test = BotParser.parse(rule.sample, rule.mapping);
        if (!lines.isEmpty()) {
            HabitUi.Card t = HabitUi.card(ctx);
            t.addView(HabitUi.text(ctx, "Result", 13, HabitUi.sub(), true));
            String kind = test.kind == 1 ? "Income" : test.kind == 0 ? "Expense" : "Type unknown (asked on review)";
            String date = test.ts == 0 ? "message time" : FinanceUi.dayLabel(java.time.Instant.ofEpochMilli(test.ts).atZone(java.time.ZoneId.systemDefault()).toLocalDate());
            t.addView(HabitUi.text(ctx, kind + " · " + BotParser.summary(test) + "\nDate: " + date + (test.card != null ? " · card *" + test.card : ""), 14, test.hasAmount() ? HabitUi.text() : FinanceUi.expense()));
            content.addView(HabitUi.spacer(ctx, 8));
            content.addView(t);
        }
        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.addView(HabitUi.pill(ctx, "Use the chat's last message", HabitUi.accent(), () -> {
            String s = BotImport.lastMessageText(getCurrentAccount(), rule.dialogId);
            if (s == null) { HabitUi.toast(this, "No message found"); return; }
            setSample(s);
        }));
        actions.addView(HabitUi.pill(ctx, "Paste a sample", HabitUi.accent(), () -> HabitUi.textDialog(this, "Sample message", "Paste one message from the chat", "", "", false, s -> { if (!s.isEmpty()) setSample(s); })));
        content.addView(HabitUi.notSection(actions));

        content.addView(HabitUi.section(ctx, "HISTORY"));
        HabitUi.Card hist = HabitUi.card(ctx);
        hist.setPadding(0, 0, 0, 0);
        hist.addView(form.new Row("Scan the last 100 messages", () -> scan(100)).titleColor(HabitUi.accent()).view);
        hist.addView(HabitUi.divider(ctx, 16));
        hist.addView(form.new Row("Scan the last 500 messages", () -> scan(500)).titleColor(HabitUi.accent()).view);
        content.addView(hist);
        content.addView(form.helper("Already imported messages are skipped. Found transactions go to the Inbox (or straight in, if automatic)."));

        content.addView(HabitUi.spacer(ctx, 24));
        content.addView(FinanceUi.dangerRow(ctx, "Remove chat from import", () -> HabitUi.confirm(this, null, "Remove \"" + rule.name + "\"?", "Remove", true, () -> {
            db.deleteRule(rule.id);
            finishFragment();
        })));
    }

    private BotParser.LineRule ruleFor(BotParser.Line l) {
        for (BotParser.LineRule r : rule.mapping) if (r.matches(l.prefix, l.text)) return r;
        return null;
    }

    private void setSample(String s) {
        rule.sample = s;
        if (rule.mapping.isEmpty()) rule.mapping.addAll(BotParser.guess(s));
        else {
            // keep existing rules, add guesses for new prefixes
            for (BotParser.LineRule g : BotParser.guess(s)) {
                boolean known = false;
                for (BotParser.LineRule r : rule.mapping) if (r.prefix.equals(g.prefix) && r.match.equals(g.match)) known = true;
                if (!known) rule.mapping.add(g);
            }
        }
        db.saveRule(rule);
    }

    private void pickField(BotParser.Line l) {
        CharSequence[] items = new CharSequence[BotParser.FIELDS.length];
        for (int i = 0; i < items.length; i++) items[i] = BotParser.fieldTitle(BotParser.FIELDS[i]);
        FinanceUi.pick(this, l.raw, items, which -> {
            String field = BotParser.FIELDS[which];
            BotParser.LineRule existing = ruleFor(l);
            boolean keyword = BotParser.F_KIND_IN.equals(field) || BotParser.F_KIND_OUT.equals(field);
            String match = keyword && l.text.startsWith("#") ? l.text.split(" ")[0] : "";
            if (existing != null && existing.match.equals(match)) existing.field = field;
            else {
                rule.mapping.add(0, new BotParser.LineRule(l.prefix, match, field));
            }
            db.saveRule(rule);
        });
    }

    private void scan(int count) {
        HabitUi.toast(this, "Loading…");
        BotImport.scan(getCurrentAccount(), rule, count, n -> {
            if (n < 0) HabitUi.toast(this, "Could not load messages");
            else HabitUi.toastSuccess(this, n == 0 ? "Nothing new" : n + " added");
        });
    }
}
