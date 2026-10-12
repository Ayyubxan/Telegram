package org.telegram.ui.finance;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.finance.Asset;
import org.telegram.messenger.finance.Currencies;
import org.telegram.messenger.finance.Entity;
import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.finance.FinanceDb;
import org.telegram.messenger.finance.Money;
import org.telegram.messenger.finance.QuickAsset;
import org.telegram.messenger.finance.Rates;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.habits.EmojiPickerSheet;
import org.telegram.ui.habits.HabitUi;

/**
 * FORK MOD (Finance): create / edit an asset. One line is enough: "XAU 50", "AAPL 10", "USD 2000" (quantity × market price)
 * or "350000000" (value by hand); the detailed rows below are for fine-tuning.
 */
@SuppressLint("SetTextI18n")
public class AssetEditActivity extends FinancePage {
    private static final int MENU_DONE = 1;
    private final FinanceDb db = Finance.db();
    private final Asset asset;
    private final boolean isNew;
    private double manualValue = Double.NaN;
    private boolean groupTouched, emojiTouched;
    private EditTextBoldCursor name, note, quick;
    private TextView quickPreview, emojiView;
    private String quickText = "";

    public AssetEditActivity(String id, String entity) {
        Asset a = id == null ? null : db.asset(id);
        isNew = a == null;
        if (isNew) {
            a = new Asset();
            a.id = Finance.newId();
            a.entity = entity == null ? Entity.PERSONAL : entity;
            a.emoji = "💎";
            a.cls = Asset.CLS_MANUAL;
            a.currency = Finance.base();
            a.code = null;
            a.group = "other";
            a.sort = db.assets(true).size();
        } else {
            manualValue = db.assetValueAt(a.id, Long.MAX_VALUE);
            groupTouched = true;
            emojiTouched = true;
        }
        asset = a;
    }

    @Override
    protected CharSequence title() {
        return isNew ? "New asset" : "Asset";
    }

    @Override
    protected void buildMenu(ActionBarMenu menu) {
        menu.addItem(MENU_DONE, R.drawable.ic_ab_done);
    }

    @Override
    protected void onMenuClick(int id) {
        if (id == MENU_DONE) save();
    }

    /** Applies "XAU 50" / "AAPL 10" / "350000000" to the asset */
    private void applyQuick(String text, boolean rebuild) {
        quickText = text;
        QuickAsset q = QuickAsset.parse(text);
        if (quickPreview != null) {
            quickPreview.setText(q.ok() ? q.describe() : text.trim().isEmpty() ? "e.g. XAU 50 · AAPL 10 · USD 2000 · 350000000" : "Not understood yet");
            quickPreview.setTextColor(q.ok() ? HabitUi.accent() : HabitUi.sub());
        }
        if (!q.ok()) return;
        asset.cls = q.cls;
        if (q.cls == Asset.CLS_QUOTED) {
            asset.code = q.code;
            asset.qty = q.qty;
            if (!groupTouched) asset.group = Asset.groupForCode(q.code);
            if (!emojiTouched) asset.emoji = Asset.groupEmoji(asset.group);
            if (name != null && name.getText().toString().trim().isEmpty()) {
                Currencies.Cur c = Currencies.get(q.code);
                name.setText(Currencies.isKnown(q.code) ? c.name.replace(" (gram)", "") : q.code);
            }
        } else {
            asset.currency = q.currency;
            manualValue = q.value;
        }
        if (emojiView != null) emojiView.setText(asset.emoji);
        if (rebuild) refresh();
    }

    @Override
    protected void refresh() {
        Context ctx = getContext();
        String typedName = name == null ? asset.name : name.getText().toString();
        String typedNote = note == null ? asset.note : note.getText().toString();
        content.removeAllViews();
        HabitUi.Form form = new HabitUi.Form(ctx);

        HabitUi.Card head = HabitUi.card(ctx);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        emojiView = EmojiPickerSheet.button(this, asset.emoji.isEmpty() ? "💎" : asset.emoji, 56, e -> { asset.emoji = e; emojiTouched = true; });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(56), dp(56));
        elp.rightMargin = dp(14);
        row.addView(emojiView, elp);
        name = HabitUi.input(ctx, "Asset name");
        name.setText(typedName);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(row);
        // quick entry
        quick = HabitUi.input(ctx, "Quantity and code, or a value");
        quick.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        quick.setText(quickText);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qlp.topMargin = dp(10);
        head.addView(quick, qlp);
        quickPreview = HabitUi.text(ctx, "", 13, HabitUi.sub());
        quickPreview.setPadding(0, dp(6), 0, 0);
        head.addView(quickPreview);
        quick.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { if (quick.hasFocus()) applyQuick(s.toString(), false); }
        });
        quick.setOnFocusChangeListener((v, has) -> { if (!has) applyQuick(quick.getText().toString(), true); });
        applyQuick(quickText, false);
        content.addView(head);

        HabitUi.Form.Group g = form.new Group(null, content);
        Entity e = db.entity(asset.entity);
        g.add(form.new Row("Owner", () -> FinanceUi.pickEntity(this, "Owner", false, ent -> { asset.entity = ent.id; refresh(); })).value(e == null ? "Personal" : e.title()).view);
        g.add(form.new Row("Group", () -> pickGroup()).value(Asset.groupEmoji(asset.group) + " " + Asset.groupTitle(asset.group)).view);
        String[][] kinds = {{"0", "Value by hand"}, {"1", "Quantity × price"}};
        g.add(form.segmentedRow(kinds, String.valueOf(asset.cls), key -> { asset.cls = Integer.parseInt(key); refresh(); }));
        if (asset.cls == Asset.CLS_MANUAL) {
            g.add(form.new Row("Currency", () -> FinanceUi.pickCurrency(this, asset.currency, c -> { asset.currency = c; refresh(); })).value(asset.currency).view);
            g.add(form.new Row("Current value", () -> FinanceUi.amountDialog(this, "Current value", asset.currency, Double.isNaN(manualValue) ? 0 : manualValue, v -> { manualValue = v; refresh(); }))
                .value(Double.isNaN(manualValue) ? "Set" : Money.format(manualValue, asset.currency)).valueColor(Double.isNaN(manualValue) ? HabitUi.accent() : HabitUi.sub()).view);
        } else {
            String code = asset.code == null ? "" : asset.code;
            Currencies.Cur c = Currencies.get(code);
            String label = code.isEmpty() ? "Choose" : (Currencies.isKnown(code) ? c.title() : code + " · stock");
            g.add(form.new Row("Priced as", () -> FinanceUi.pickCode(this, asset.code, cc -> { asset.code = cc; if (!groupTouched) asset.group = Asset.groupForCode(cc); refresh(); })).value(label).view);
            String unit = c.kind == Currencies.METAL ? "grams" : Currencies.isKnown(code) ? code : "shares";
            g.add(form.new Row("Quantity (" + unit + ")", () -> FinanceUi.quantityDialog(this, "Quantity", asset.qty, v -> { asset.qty = v; refresh(); }))
                .value(asset.qty == 0 ? "Set" : Money.number(asset.qty, 8)).valueColor(asset.qty == 0 ? HabitUi.accent() : HabitUi.sub()).view);
            double price = Rates.uzs(code);
            if (!code.isEmpty()) {
                String p = Double.isNaN(price) ? "unknown yet — fetched on save, or set by hand in Settings → Finance → Exchange rates" : Money.format(Rates.convertOr0(price, "UZS", Finance.base()), Finance.base()) + (c.kind == Currencies.METAL ? " per gram" : "");
                content.addView(form.helper("Price: " + p));
            }
        }
        LinearLayout noteRow = new LinearLayout(ctx);
        noteRow.setPadding(dp(16), dp(6), dp(16), dp(6));
        note = FinanceUi.notesInput(ctx, "Notes");
        note.setText(typedNote);
        noteRow.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        g.add(noteRow);

        if (!isNew) {
            g.add(form.new SwitchRow("Archived", asset.archived, on -> asset.archived = on).sub("Sold or gone — kept in history, not counted in net worth").view);
            content.addView(HabitUi.spacer(ctx, 24));
            content.addView(FinanceUi.dangerRow(ctx, "Delete asset", () -> HabitUi.confirm(this, null, "Delete \"" + asset.name + "\"?", LocaleController.getString(R.string.Delete), true, () -> {
                db.deleteAsset(asset.id);
                removeSelfFromStack();
                if (parentLayout != null && parentLayout.getLastFragment() instanceof AssetActivity) parentLayout.getLastFragment().finishFragment();
            })));
        }
        if (isNew && typedName.isEmpty() && quickText.isEmpty()) AndroidUtilities.runOnUIThread(() -> HabitUi.focusWithKeyboard(quick), 150);
    }

    private void pickGroup() {
        CharSequence[] items = new CharSequence[Asset.GROUPS.length];
        for (int i = 0; i < items.length; i++) items[i] = Asset.GROUPS[i][2] + "  " + Asset.GROUPS[i][1];
        FinanceUi.pick(this, "Group", items, i -> {
            asset.group = Asset.GROUPS[i][0];
            groupTouched = true;
            if (!emojiTouched) asset.emoji = Asset.groupEmoji(asset.group);
            refresh();
        });
    }

    private void save() {
        applyQuick(quick.getText().toString(), false);
        String n = name.getText().toString().trim();
        if (n.isEmpty()) {
            if (asset.cls == Asset.CLS_QUOTED && asset.code != null) n = Currencies.isKnown(asset.code) ? Currencies.get(asset.code).name.replace(" (gram)", "") : asset.code;
            else { HabitUi.toast(this, "Enter a name"); return; }
        }
        if (asset.cls == Asset.CLS_QUOTED && (asset.code == null || asset.code.isEmpty())) { HabitUi.toast(this, "Choose what it is priced as"); return; }
        if (asset.cls == Asset.CLS_QUOTED && asset.qty <= 0) { HabitUi.toast(this, "Enter the quantity"); return; }
        if (asset.cls == Asset.CLS_MANUAL && Double.isNaN(manualValue)) { HabitUi.toast(this, "Enter the value"); return; }
        asset.name = n;
        asset.note = note.getText().toString().trim();
        db.saveAsset(asset);
        if (asset.cls == Asset.CLS_MANUAL && !Double.isNaN(manualValue)) {
            double old = db.assetValueAt(asset.id, Long.MAX_VALUE);
            if (Double.isNaN(old) || Math.abs(old - manualValue) > 1e-9) db.saveAssetValue(asset.id, FinanceDb.today(), manualValue);
        }
        if (asset.cls == Asset.CLS_QUOTED && !Rates.known(asset.code)) Rates.refresh(null);
        AndroidUtilities.hideKeyboard(fragmentView);
        finishFragment();
    }
}
