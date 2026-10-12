package org.telegram.ui.finance;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.finance.Account;
import org.telegram.messenger.finance.Category;
import org.telegram.messenger.finance.Currencies;
import org.telegram.messenger.finance.Entity;
import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.finance.Money;
import org.telegram.messenger.finance.Rates;
import org.telegram.messenger.finance.Tx;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.habits.EmojiPickerSheet;
import org.telegram.ui.habits.HabitUi;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** FORK MOD (Finance): shared views, pickers and formatting for the finance screens (Telegram island style, see {@link HabitUi}) */
public class FinanceUi {
    public static int expense() { return Theme.getColor(Theme.key_text_RedRegular); }
    public static int income() { return 0xFF34C759; }
    public static int transfer() { return HabitUi.accent(); }

    public static int kindColor(int kind) {
        return kind == Tx.INCOME ? income() : kind == Tx.EXPENSE ? expense() : transfer();
    }

    // region Dates

    public static String dayLabel(LocalDate d) {
        LocalDate today = LocalDate.now();
        if (d.equals(today)) return "Today";
        if (d.equals(today.minusDays(1))) return "Yesterday";
        if (d.getYear() == today.getYear()) return d.format(DateTimeFormatter.ofPattern("EEE, d MMMM", Locale.ENGLISH));
        return d.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
    }

    public static String shortDate(LocalDate d) {
        return d.format(DateTimeFormatter.ofPattern(d.getYear() == LocalDate.now().getYear() ? "d MMM" : "d MMM yyyy", Locale.ENGLISH));
    }

    public static String monthLabel(YearMonth m) {
        return m.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + m.getYear();
    }

    public static void pickDate(BaseFragment f, LocalDate current, Utilities.Callback<LocalDate> onPick) {
        LocalDate now = LocalDate.now();
        AlertDialog.Builder b = AlertsCreator.createDatePickerDialog(f.getContext(), -10, 1, current.getYear() - now.getYear(), current.getDayOfMonth(), current.getMonthValue() - 1, current.getYear(), "Date", false,
            (year, month, dayOfMonth) -> onPick.run(LocalDate.of(year, month + 1, Math.min(dayOfMonth, YearMonth.of(year, month + 1).lengthOfMonth()))));
        f.showDialog(b.create());
    }

    // endregion

    // region Small views

    /** Emoji in a tinted circle */
    public static TextView emojiCircle(Context ctx, String emoji, int color, int sizeDp) {
        TextView t = HabitUi.text(ctx, emoji, sizeDp >= 44 ? 22 : 18, HabitUi.text());
        t.setGravity(Gravity.CENTER);
        t.setBackground(HabitUi.oval(HabitUi.withAlpha(color, 0x30)));
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return t;
    }

    public static TextView money(Context ctx, double amount, String code, float size, int color, boolean bold) {
        TextView t = HabitUi.text(ctx, Money.format(amount, code), size, color, bold);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** Card header: title on the left, optional link on the right */
    public static View cardHeader(Context ctx, CharSequence title, CharSequence action, Runnable onAction) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(0, 0, 0, dp(8));
        l.addView(HabitUi.text(ctx, title, 16, HabitUi.text(), true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null) {
            TextView a = HabitUi.text(ctx, action, 14, HabitUi.accent(), true);
            a.setPadding(dp(8), dp(4), 0, dp(4));
            a.setOnClickListener(v -> onAction.run());
            l.addView(a);
        }
        return l;
    }

    /** Transaction row: emoji · title / subtitle · amount */
    public static View txRow(Context ctx, Tx t, Map<String, Account> accounts, Map<String, Category> cats, boolean showDate, Runnable onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));
        Account from = accounts.get(t.account);
        Account to = accounts.get(t.toAccount);
        String title, sub, emoji;
        int color;
        if (t.isTransfer()) {
            emoji = "🔁";
            color = transfer();
            title = (from == null ? "?" : from.name) + " → " + (to == null ? "?" : to.name);
            sub = from != null && to != null && !from.currency.equals(to.currency) ? Money.format(t.toAmount, to.currency) : "";
        } else {
            Category c = cats.get(t.category);
            emoji = c == null ? (t.kind == Tx.INCOME ? "💰" : "📦") : c.emoji;
            color = c == null ? HabitUi.sub() : c.colorInt();
            title = c == null ? (t.kind == Tx.INCOME ? "Income" : "Expense") : c.name;
            sub = from == null ? "" : from.title();
        }
        if (!t.note.isEmpty()) sub = sub.isEmpty() ? t.note : sub + " · " + t.note;
        if (showDate) sub = shortDate(t.date()) + (sub.isEmpty() ? "" : " · " + sub);
        if (t.source == Tx.SOURCE_BOT) sub = sub + " · 🤖";
        TextView e = emojiCircle(ctx, emoji, color, 40);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(40), dp(40));
        elp.rightMargin = dp(12);
        row.addView(e, elp);
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView tt = HabitUi.text(ctx, title, 16, HabitUi.text());
        tt.setMaxLines(1);
        tt.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(tt);
        if (!sub.isEmpty()) {
            TextView st = HabitUi.text(ctx, sub, 13, HabitUi.sub());
            st.setMaxLines(1);
            st.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(st);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String amountText = t.isTransfer() ? Money.format(t.amount, t.currency) : Money.signed(t.signed(), t.currency);
        TextView a = HabitUi.text(ctx, amountText, 15, t.isTransfer() ? HabitUi.text() : kindColor(t.kind), true);
        a.setMaxLines(1);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(10);
        row.addView(a, alp);
        if (onClick != null) {
            row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            row.setOnClickListener(v -> onClick.run());
        }
        return row;
    }

    /** Horizontal chips: All + every entity; {@code selected} null = All */
    public static View entityChips(Context ctx, List<Entity> entities, String selected, Utilities.Callback<String> onSelect) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(dp(4), 0, dp(4), 0);
        addChip(l, ctx, "All", selected == null, () -> onSelect.run(null));
        for (Entity e : entities) addChip(l, ctx, e.title(), e.id.equals(selected), () -> onSelect.run(e.id));
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.addView(l);
        return HabitUi.notSection(hs);
    }

    private static void addChip(LinearLayout l, Context ctx, CharSequence label, boolean on, Runnable onClick) {
        TextView chip = HabitUi.chip(ctx, label, on, onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        l.addView(chip, lp);
    }

    /** Thin proportional bar */
    public static View bar(Context ctx, float fraction, int color) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        View fill = new View(ctx);
        fill.setBackground(HabitUi.roundRect(color, HabitUi.dpf(3)));
        View rest = new View(ctx);
        rest.setBackground(HabitUi.roundRect(HabitUi.withAlpha(HabitUi.sub(), 0x22), HabitUi.dpf(3)));
        float f = Math.max(0.02f, Math.min(1f, fraction));
        l.addView(fill, new LinearLayout.LayoutParams(0, dp(6), f));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(6), 1f - f);
        rlp.leftMargin = f < 1f ? dp(3) : 0;
        l.addView(rest, rlp);
        return l;
    }

    // endregion

    // region Pickers

    /** Simple list sheet */
    public static void pick(BaseFragment f, CharSequence title, CharSequence[] items, Utilities.Callback<Integer> onPick) {
        BottomSheet.Builder b = new BottomSheet.Builder(f.getContext());
        if (title != null) b.setTitle(title, true);
        b.setItems(items, (d, which) -> onPick.run(which));
        f.showDialog(b.create());
    }

    public static void pickAccount(BaseFragment f, CharSequence title, List<Account> accounts, Utilities.Callback<Account> onPick) {
        if (accounts.isEmpty()) {
            HabitUi.toast(f, "Add an account first");
            return;
        }
        Map<String, Double> bal = Finance.db().balances();
        boolean multiEntity = Finance.db().entities(false).size() > 1;
        CharSequence[] items = new CharSequence[accounts.size()];
        for (int i = 0; i < accounts.size(); i++) {
            Account a = accounts.get(i);
            Double v = bal.get(a.id);
            Entity e = multiEntity ? Finance.db().entity(a.entity) : null;
            items[i] = a.title() + (e != null ? " · " + e.name : "") + "   " + Money.format(v == null ? 0 : v, a.currency);
        }
        pick(f, title, items, i -> onPick.run(accounts.get(i)));
    }

    public static void pickEntity(BaseFragment f, CharSequence title, boolean allowNone, Utilities.Callback<Entity> onPick) {
        List<Entity> list = Finance.db().entities(false);
        CharSequence[] items = new CharSequence[list.size() + (allowNone ? 1 : 0)];
        int off = 0;
        if (allowNone) { items[0] = "Everyone"; off = 1; }
        for (int i = 0; i < list.size(); i++) items[i + off] = list.get(i).title();
        final int o = off;
        pick(f, title, items, i -> onPick.run(i < o ? null : list.get(i - o)));
    }

    /** Categories as a grid of chips in a sheet */
    public static void pickCategory(BaseFragment f, int kind, String entity, String current, Utilities.Callback<Category> onPick) {
        Context ctx = f.getContext();
        List<Category> cats = Finance.db().categories(kind, entity, false);
        BottomSheet[] sheet = new BottomSheet[1];
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(16));
        box.addView(HabitUi.text(ctx, kind == Category.INCOME ? "Income category" : "Expense category", 18, HabitUi.text(), true));
        HabitUi.FlowLayout flow = new HabitUi.FlowLayout(ctx, dp(8));
        flow.setPadding(0, dp(12), 0, 0);
        for (Category c : cats) {
            boolean on = c.id.equals(current);
            TextView chip = HabitUi.text(ctx, c.title(), 15, on ? HabitUi.white() : HabitUi.text(), on);
            chip.setPadding(dp(14), dp(9), dp(14), dp(9));
            chip.setBackground(on ? HabitUi.roundRect(c.colorInt(), HabitUi.dpf(18)) : HabitUi.roundRect(HabitUi.withAlpha(c.colorInt(), 0x26), HabitUi.dpf(18)));
            chip.setOnClickListener(v -> {
                HabitUi.haptic(v);
                if (sheet[0] != null) sheet[0].dismiss();
                onPick.run(c);
            });
            flow.addView(chip);
        }
        TextView add = HabitUi.text(ctx, "＋ New", 15, HabitUi.accent(), true);
        add.setPadding(dp(14), dp(9), dp(14), dp(9));
        add.setBackground(HabitUi.roundRect(HabitUi.card(), HabitUi.dpf(18), HabitUi.line(), dp(1)));
        add.setOnClickListener(v -> {
            if (sheet[0] != null) sheet[0].dismiss();
            catDialog(f, null, kind, null, (n, e, col, ent) -> {
                Category c = new Category();
                c.id = Finance.newId(); c.kind = kind; c.name = n; c.emoji = e; c.color = col; c.entity = ent; c.sort = cats.size();
                Finance.db().saveCategory(c);
                onPick.run(c);
            });
        });
        flow.addView(add);
        box.addView(flow);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(box);
        BottomSheet.Builder b = new BottomSheet.Builder(ctx);
        b.setCustomView(sv);
        sheet[0] = b.create();
        f.showDialog(sheet[0]);
    }

    /** Currency picker with a search field */
    public static void pickCurrency(BaseFragment f, String current, Utilities.Callback<String> onPick) {
        Context ctx = f.getContext();
        BottomSheet[] sheet = new BottomSheet[1];
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        box.addView(HabitUi.text(ctx, "Currency", 18, HabitUi.text(), true));
        EditTextBoldCursor search = HabitUi.input(ctx, "Search");
        box.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        Runnable fill = () -> {
            list.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            for (Currencies.Cur c : Currencies.all()) {
                if (!q.isEmpty() && !c.code.toLowerCase(Locale.ROOT).contains(q) && !c.name.toLowerCase(Locale.ROOT).contains(q)) continue;
                boolean on = c.code.equals(current);
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(12), dp(4), dp(12));
                row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
                TextView code = HabitUi.text(ctx, c.code, 16, on ? HabitUi.accent() : HabitUi.text(), true);
                code.setMinWidth(dp(64));
                row.addView(code);
                row.addView(HabitUi.text(ctx, c.name, 15, HabitUi.sub()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                double r = Rates.uzs(c.code);
                if (!Double.isNaN(r) && !"UZS".equals(c.code)) row.addView(HabitUi.text(ctx, Money.number(r, r < 10 ? 4 : 0), 13, HabitUi.sub()));
                row.setOnClickListener(v -> {
                    if (sheet[0] != null) sheet[0].dismiss();
                    onPick.run(c.code);
                });
                list.addView(row);
            }
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { fill.run(); }
        });
        fill.run();
        BottomSheet.Builder b = new BottomSheet.Builder(ctx);
        b.setCustomView(box);
        sheet[0] = b.create();
        f.showDialog(sheet[0]);
    }

    public interface OnCategory {
        void onSave(String name, String emoji, String color, String entity);
    }

    /** Create / edit a category: name, emoji, color and who sees it */
    public static void catDialog(BaseFragment f, Category existing, int kind, Runnable onDelete, OnCategory onSave) {
        Context ctx = f.getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(4), dp(24), 0);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final String[] emoji = {existing == null ? (kind == Category.INCOME ? "💰" : "🧾") : existing.emoji};
        TextView emojiView = HabitUi.text(ctx, emoji[0], 24, HabitUi.text());
        emojiView.setGravity(Gravity.CENTER);
        emojiView.setBackground(HabitUi.oval(HabitUi.bg()));
        emojiView.setOnClickListener(v -> EmojiPickerSheet.show(f, emoji[0], e -> {
            emoji[0] = e;
            emojiView.setText(e);
        }));
        EditTextBoldCursor name = HabitUi.input(ctx, "Category name");
        if (existing != null) {
            name.setText(existing.name);
            name.setSelection(name.length());
        }
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(48), dp(48));
        elp.rightMargin = dp(12);
        row.addView(emojiView, elp);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);

        final String[] color = {existing == null ? Category.PALETTE.get((int) (Math.random() * Category.PALETTE.size())) : existing.color};
        LinearLayout swatches = new LinearLayout(ctx);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        swatches.setPadding(0, dp(16), 0, dp(4));
        Runnable paint = () -> {
            for (int i = 0; i < swatches.getChildCount(); i++) {
                View v = swatches.getChildAt(i);
                String c = (String) v.getTag();
                boolean sel = c.equalsIgnoreCase(color[0]);
                v.setBackground(HabitUi.oval(Color.parseColor(c), HabitUi.text(), sel ? dp(3) : 0));
                v.setScaleX(sel ? 1.15f : 1f);
                v.setScaleY(v.getScaleX());
            }
        };
        for (String c : Category.PALETTE) {
            View v = new View(ctx);
            v.setTag(c);
            v.setOnClickListener(view -> {
                color[0] = c;
                HabitUi.haptic(view);
                paint.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
            lp.rightMargin = dp(8);
            swatches.addView(v, lp);
        }
        paint.run();
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(swatches);
        box.addView(hs);

        final String[] entity = {existing == null ? null : existing.entity};
        Entity cur = entity[0] == null ? null : Finance.db().entity(entity[0]);
        TextView scope = HabitUi.text(ctx, "Visible for: " + (cur == null ? "everyone" : cur.title()), 14, HabitUi.accent());
        scope.setPadding(0, dp(12), 0, dp(4));
        scope.setOnClickListener(v -> pickEntity(f, "Visible for", true, e -> {
            entity[0] = e == null ? null : e.id;
            scope.setText("Visible for: " + (e == null ? "everyone" : e.title()));
        }));
        box.addView(scope);

        AlertDialog.Builder b = new AlertDialog.Builder(ctx)
            .setTitle(existing == null ? "New category" : "Category")
            .setView(box)
            .setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
                String n = name.getText().toString().trim();
                if (n.isEmpty()) return;
                onSave.onSave(n, emoji[0].isEmpty() ? "📁" : emoji[0], color[0], entity[0]);
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        if (onDelete != null) {
            b.setNeutralButton(LocaleController.getString(R.string.Delete), (d, w) ->
                HabitUi.confirm(f, null, "Delete \"" + existing.name + "\"? Its transactions are kept without a category.", LocaleController.getString(R.string.Delete), true, onDelete));
        }
        f.showDialog(b.create());
        HabitUi.focusWithKeyboard(name);
    }

    public interface OnEntity {
        void onSave(String name, String emoji);
    }

    /** Create / edit an entity (business): name + emoji */
    public static void entityDialog(BaseFragment f, Entity existing, OnEntity onSave) {
        Context ctx = f.getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(24), dp(4), dp(24), 0);
        final String[] emoji = {existing == null ? "🏪" : existing.emoji};
        TextView emojiView = HabitUi.text(ctx, emoji[0], 24, HabitUi.text());
        emojiView.setGravity(Gravity.CENTER);
        emojiView.setBackground(HabitUi.oval(HabitUi.bg()));
        emojiView.setOnClickListener(v -> EmojiPickerSheet.show(f, emoji[0], e -> {
            emoji[0] = e;
            emojiView.setText(e);
        }));
        EditTextBoldCursor name = HabitUi.input(ctx, "Business name");
        if (existing != null) {
            name.setText(existing.name);
            name.setSelection(name.length());
        }
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(48), dp(48));
        elp.rightMargin = dp(12);
        row.addView(emojiView, elp);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        AlertDialog.Builder b = new AlertDialog.Builder(ctx)
            .setTitle(existing == null ? "New business" : "Business")
            .setView(row)
            .setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
                String n = name.getText().toString().trim();
                if (n.isEmpty()) return;
                onSave.onSave(n, emoji[0]);
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        f.showDialog(b.create());
        HabitUi.focusWithKeyboard(name);
    }

    /** Amount input dialog */
    public static void amountDialog(BaseFragment f, CharSequence title, String code, double initial, Utilities.Callback<Double> onDone) {
        HabitUi.textDialog(f, title, null, Money.plain(initial, code), "0 " + code, true, s -> {
            double v = Money.parse(s);
            if (!Double.isNaN(v)) onDone.run(v);
        });
    }

    // endregion

    /** Big amount field (numeric keyboard) */
    public static EditTextBoldCursor amountInput(Context ctx, String hint) {
        EditTextBoldCursor e = HabitUi.input(ctx, hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 30);
        e.setTypeface(org.telegram.messenger.AndroidUtilities.bold());
        return e;
    }

    /** Multi-line notes field */
    public static EditTextBoldCursor notesInput(Context ctx, CharSequence hint) {
        EditTextBoldCursor e = HabitUi.input(ctx, hint);
        e.setSingleLine(false);
        e.setMaxLines(8);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return e;
    }

    /** Multi-line notes dialog */
    public static void notesDialog(BaseFragment f, CharSequence title, String initial, Utilities.Callback<String> onDone) {
        Context ctx = f.getContext();
        EditTextBoldCursor input = notesInput(ctx, "Notes");
        if (initial != null) { input.setText(initial); input.setSelection(input.length()); }
        FrameLayout box = new FrameLayout(ctx);
        box.setPadding(dp(24), dp(4), dp(24), 0);
        box.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        AlertDialog.Builder b = new AlertDialog.Builder(ctx).setTitle(title).setView(box)
            .setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> onDone.run(input.getText().toString().trim()))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        f.showDialog(b.create());
        HabitUi.focusWithKeyboard(input);
    }

    /** Red row at the bottom of an edit page */
    public static View dangerRow(Context ctx, CharSequence label, Runnable onClick) {
        TextView t = HabitUi.text(ctx, label, 16, Theme.getColor(Theme.key_text_RedRegular));
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(52));
        t.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
        t.setOnClickListener(v -> onClick.run());
        return t;
    }

    public static FrameLayout wrap(View v) {
        FrameLayout fl = new FrameLayout(v.getContext());
        fl.addView(v, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return fl;
    }

    // region Phase 2: avatars, Telegram user picker, asset code picker

    /** Avatar of a linked Telegram user, or the first letter of the name in a circle */
    public static View avatar(BaseFragment f, long userId, String name, int sizeDp) {
        Context ctx = f.getContext();
        org.telegram.tgnet.TLRPC.User user = userId == 0 ? null : org.telegram.messenger.MessagesController.getInstance(f.getCurrentAccount()).getUser(userId);
        if (user != null) {
            org.telegram.ui.Components.BackupImageView iv = new org.telegram.ui.Components.BackupImageView(ctx);
            iv.setRoundRadius(dp(sizeDp / 2));
            org.telegram.ui.Components.AvatarDrawable ad = new org.telegram.ui.Components.AvatarDrawable();
            ad.setInfo(f.getCurrentAccount(), user);
            iv.setForUserOrChat(user, ad);
            iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
            return iv;
        }
        String letter = name == null || name.trim().isEmpty() ? "?" : name.trim().substring(0, 1).toUpperCase(Locale.ROOT);
        TextView t = HabitUi.text(ctx, letter, sizeDp >= 56 ? 24 : 17, HabitUi.white(), true);
        t.setGravity(Gravity.CENTER);
        int[] palette = {0xFFE56555, 0xFFF28C48, 0xFF8E85EE, 0xFF76C84D, 0xFF5FBED5, 0xFF549CDD, 0xFFF2749A};
        t.setBackground(HabitUi.oval(palette[Math.abs((name == null ? "" : name).hashCode()) % palette.length]));
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return t;
    }

    /** Opens the contact list; returns the chosen user */
    public static void pickUser(BaseFragment f, Utilities.Callback<org.telegram.tgnet.TLRPC.User> onPick) {
        android.os.Bundle args = new android.os.Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", org.telegram.ui.DialogsActivity.DIALOGS_TYPE_USERS_ONLY);
        args.putBoolean("allowGlobalSearch", false);
        args.putBoolean("resetDelegate", false);
        org.telegram.ui.DialogsActivity activity = new org.telegram.ui.DialogsActivity(args);
        activity.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (!dids.isEmpty() && dids.get(0).dialogId > 0) {
                org.telegram.tgnet.TLRPC.User u = org.telegram.messenger.MessagesController.getInstance(f.getCurrentAccount()).getUser(dids.get(0).dialogId);
                if (u != null) onPick.run(u);
            }
            fragment.finishFragment();
            return true;
        });
        f.presentFragment(activity);
    }

    /** What a quoted asset is priced as: a currency / crypto / metal code, or a stock ticker */
    public static void pickCode(BaseFragment f, String current, Utilities.Callback<String> onPick) {
        pick(f, "Priced as", new CharSequence[]{"Currency, crypto or metal", "Stock ticker (Yahoo symbol, e.g. AAPL)"}, which -> {
            if (which == 0) pickCurrency(f, current, onPick);
            else HabitUi.textDialog(f, "Stock ticker", "Yahoo Finance symbol", current == null || Currencies.isKnown(current) ? "" : current, "AAPL", false, s -> {
                String t = s.trim().toUpperCase(Locale.ROOT);
                if (!t.isEmpty()) onPick.run(t);
            });
        });
    }

    /** Numeric value dialog with free decimals (quantities) */
    public static void quantityDialog(BaseFragment f, CharSequence title, double initial, Utilities.Callback<Double> onDone) {
        HabitUi.textDialog(f, title, null, initial == 0 ? "" : new java.math.BigDecimal(initial).stripTrailingZeros().toPlainString(), "0", true, s -> {
            double v = Money.parse(s);
            if (!Double.isNaN(v)) onDone.run(v);
        });
    }

    // endregion

    /** Name of a chat (user, bot, group or channel) */
    public static String dialogName(BaseFragment f, long dialogId) {
        org.telegram.messenger.MessagesController mc = org.telegram.messenger.MessagesController.getInstance(f.getCurrentAccount());
        if (dialogId > 0) {
            org.telegram.tgnet.TLRPC.User u = mc.getUser(dialogId);
            if (u != null) return org.telegram.messenger.UserObject.getUserName(u);
        } else {
            org.telegram.tgnet.TLRPC.Chat c = mc.getChat(-dialogId);
            if (c != null) return c.title;
        }
        return "Chat " + dialogId;
    }

    /** Opens the chat list (any chat) and returns the chosen dialog id */
    public static void pickDialog(BaseFragment f, Utilities.Callback<Long> onPick) {
        android.os.Bundle args = new android.os.Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("allowGlobalSearch", false);
        args.putBoolean("resetDelegate", false);
        org.telegram.ui.DialogsActivity activity = new org.telegram.ui.DialogsActivity(args);
        activity.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            fragment.finishFragment();
            if (!dids.isEmpty()) onPick.run(dids.get(0).dialogId);
            return true;
        });
        f.presentFragment(activity);
    }
}
