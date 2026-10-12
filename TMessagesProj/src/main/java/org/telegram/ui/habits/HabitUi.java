package org.telegram.ui.habits;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SectionsScrollView;
import org.telegram.ui.Components.Switch;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Random;

/**
 * FORK MOD (Nexo): shared views and helpers for the habits screens, themed with Telegram colors.
 */
public class HabitUi {

    // region Colors

    public static int bg() { return Theme.getColor(Theme.key_windowBackgroundGray); }
    public static int card() { return Theme.getColor(Theme.key_windowBackgroundWhite); }
    public static int text() { return Theme.getColor(Theme.key_windowBackgroundWhiteBlackText); }
    public static int sub() { return Theme.getColor(Theme.key_windowBackgroundWhiteGrayText); }
    public static int accent() { return Theme.getColor(Theme.key_windowBackgroundWhiteBlueText); }
    /** Thin line / inactive pill border */
    public static int line() { return Theme.multAlpha(sub(), 0.35f); }
    public static int done() { return 0xFF34C759; }
    public static int skip() { return Theme.getColor(Theme.key_text_RedRegular); }
    public static int fire() { return 0xFFFF9500; }
    public static int white() { return 0xFFFFFFFF; }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    public static GradientDrawable roundRect(int color, float radius) {
        return roundRect(color, radius, 0, 0);
    }

    public static GradientDrawable roundRect(int color, float radius, int stroke, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius);
        d.setColor(color);
        if (strokeWidth > 0) d.setStroke(strokeWidth, stroke);
        return d;
    }

    public static GradientDrawable oval(int color) {
        return oval(color, 0, 0);
    }

    public static GradientDrawable oval(int color, int stroke, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        if (strokeWidth > 0) d.setStroke(strokeWidth, stroke);
        return d;
    }

    public static float dpf(float v) {
        return AndroidUtilities.density * v;
    }

    // endregion

    // region Basic views

    public static TextView text(Context ctx, CharSequence s, float size, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        t.setTextColor(color);
        if (bold) t.setTypeface(AndroidUtilities.bold());
        return t;
    }

    public static TextView text(Context ctx, CharSequence s, float size, int color) {
        return text(ctx, s, size, color, false);
    }

    /** Section header: small gray uppercase */
    public static TextView section(Context ctx, CharSequence s) {
        TextView t = text(ctx, s, 13, HabitUi.sub(), true);
        t.setPadding(dp(16), dp(18), 0, dp(6));
        return notSection(t);
    }

    /**
     * Card content inside a Telegram "island": the island background is drawn by {@link SectionsScrollView},
     * this view only lays out its children. A FrameLayout wrapper keeps the padding inside the island
     * (SectionsScrollView descends into full-width vertical LinearLayouts and would skip the padding).
     */
    public static class Card extends FrameLayout {
        public final LinearLayout body;

        public Card(Context ctx) {
            super(ctx);
            body = new LinearLayout(ctx);
            body.setOrientation(LinearLayout.VERTICAL);
            super.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        public void setOrientation(int orientation) { body.setOrientation(orientation); }
        public void setGravity(int gravity) { body.setGravity(gravity); }

        @Override
        public void setPadding(int left, int top, int right, int bottom) {
            if (body == null) { super.setPadding(left, top, right, bottom); return; }
            body.setPadding(left, top, right, bottom);
        }

        @Override
        public void addView(View child) { body.addView(child); }

        @Override
        public void addView(View child, ViewGroup.LayoutParams params) { body.addView(child, params); }

        @Override
        public void addView(View child, int index) { body.addView(child, index); }

        @Override
        public void addView(View child, int width, int height) { body.addView(child, width, height); }
    }

    /** Island card (vertical content) */
    public static Card card(Context ctx) {
        Card c = new Card(ctx);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    /** Marks a view as "not part of an island" (section headers, notes, spacers) */
    public static <V extends View> V notSection(V v) {
        v.setTag(RecyclerListView.TAG_NOT_SECTION);
        return v;
    }

    /** Gap between two islands */
    public static View spacer(Context ctx, int h) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return notSection(v);
    }

    /** Thin divider between rows inside an island */
    public static View divider(Context ctx, int leftDp) {
        View line = new View(ctx);
        line.setBackgroundColor(Theme.getColor(Theme.key_divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(leftDp);
        line.setLayoutParams(lp);
        return line;
    }

    /** Scrollable page in Telegram island style: SectionsScrollView draws rounded white islands behind the content */
    public static SectionsScrollView page(Context ctx, LinearLayout[] contentOut) {
        SectionsScrollView.SectionsLinearLayout content = new SectionsScrollView.SectionsLinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        SectionsScrollView scroll = new SectionsScrollView(ctx, content, null);
        content.setPadding(dp(12), dp(8), dp(12), dp(96));
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentOut[0] = content;
        return scroll;
    }

    /** Full-width outlined button inside a card list */
    public static TextView pill(Context ctx, CharSequence label, int color, Runnable onClick) {
        TextView t = text(ctx, label, 15, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(13), 0, dp(13));
        t.setBackground(roundRect(card(), dpf(16), line(), dp(1)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> onClick.run());
        return notSection(t);
    }

    /** Selectable chip (used for filters, kinds, weekdays…) */
    public static TextView chip(Context ctx, CharSequence label, boolean on, Runnable onClick) {
        TextView t = text(ctx, label, 14, on ? white() : text(), on);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setBackground(on ? roundRect(accent(), dpf(18)) : roundRect(card(), dpf(18), line(), dp(1)));
        t.setOnClickListener(v -> {
            haptic(v);
            onClick.run();
        });
        return t;
    }

    public static EditTextBoldCursor input(Context ctx, CharSequence hint) {
        EditTextBoldCursor e = new EditTextBoldCursor(ctx);
        e.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        e.setTextColor(text());
        e.setHintColor(sub());
        e.setHintText(hint);
        e.setCursorColor(text());
        e.setCursorSize(dp(20));
        e.setCursorWidth(1.5f);
        e.setLineColors(line(), accent(), skip());
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setPadding(0, dp(8), 0, dp(8));
        return e;
    }

    public static void haptic(View v) {
        v.performHapticFeedback(Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
    }

    public static void bounce(View v) {
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
            ObjectAnimator.ofFloat(v, View.SCALE_X, 0.7f, 1f),
            ObjectAnimator.ofFloat(v, View.SCALE_Y, 0.7f, 1f)
        );
        set.setDuration(380);
        set.setInterpolator(new OvershootInterpolator(4f));
        set.start();
    }

    public static void focusWithKeyboard(EditTextBoldCursor field) {
        field.requestFocus();
        field.post(() -> AndroidUtilities.showKeyboard(field));
    }

    public static void toast(BaseFragment f, CharSequence s) {
        if (f == null || f.getContext() == null) return;
        BulletinFactory.of(f).createSimpleBulletin(R.raw.chats_infotip, s).show();
    }

    public static void toastSuccess(BaseFragment f, CharSequence s) {
        if (f == null || f.getContext() == null) return;
        BulletinFactory.of(f).createSuccessBulletin(s).show();
    }

    // endregion

    // region Flow layout

    public static class FlowLayout extends ViewGroup {
        private final int gap;

        public FlowLayout(Context ctx, int gap) {
            super(ctx);
            this.gap = gap;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int full = MeasureSpec.getSize(widthMeasureSpec);
            int maxW = full - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
                if (x > 0 && x + c.getMeasuredWidth() > maxW) {
                    x = 0;
                    y += rowH + gap;
                    rowH = 0;
                }
                x += c.getMeasuredWidth() + gap;
                rowH = Math.max(rowH, c.getMeasuredHeight());
            }
            setMeasuredDimension(full, y + rowH + getPaddingTop() + getPaddingBottom());
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxW = r - l - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                if (x > 0 && x + c.getMeasuredWidth() > maxW) {
                    x = 0;
                    y += rowH + gap;
                    rowH = 0;
                }
                c.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + c.getMeasuredWidth(), getPaddingTop() + y + c.getMeasuredHeight());
                x += c.getMeasuredWidth() + gap;
                rowH = Math.max(rowH, c.getMeasuredHeight());
            }
        }
    }

    // endregion

    // region Form (settings-like rows inside cards)

    /** Rows inside rounded cards: title — value, switch rows, segmented control and stepper. */
    public static class Form {
        public final Context ctx;

        public Form(Context ctx) {
            this.ctx = ctx;
        }

        public TextView text(CharSequence s, float size, int color, boolean bold) {
            return HabitUi.text(ctx, s, size, color, bold);
        }

        /** Group: small header + card; thin dividers between rows */
        public class Group {
            public final LinearLayout card;
            public final TextView header;

            public Group(CharSequence title, LinearLayout parent) {
                card = new LinearLayout(ctx);
                card.setOrientation(LinearLayout.VERTICAL);
                if (title != null) {
                    header = notSection(text(title.toString().toUpperCase(), 13, HabitUi.sub(), true));
                    header.setPadding(dp(16), dp(20), 0, dp(8));
                    parent.addView(header);
                } else {
                    header = null;
                    // Two islands right after each other would merge — keep a gap
                    parent.addView(HabitUi.spacer(ctx, 12));
                }
                parent.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            public View add(View v) {
                if (card.getChildCount() > 0) {
                    View line = new View(ctx);
                    line.setBackgroundColor(Theme.getColor(Theme.key_divider));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
                    lp.leftMargin = dp(16);
                    card.addView(line, lp);
                }
                card.addView(v);
                return v;
            }

            public void setVisible(boolean v) {
                card.setVisibility(v ? View.VISIBLE : View.GONE);
                if (header != null) header.setVisibility(card.getVisibility());
            }
        }

        /** Row: Title / subtitle ............ value */
        public class Row {
            public final TextView titleView;
            public final TextView subView;
            public final TextView valueView;
            public final LinearLayout view;

            public Row(CharSequence title, Runnable onClick) {
                titleView = text(title, 16, HabitUi.text(), false);
                subView = text("", 13, HabitUi.sub(), false);
                subView.setVisibility(View.GONE);
                valueView = text("", 15, HabitUi.sub(), false);
                valueView.setMaxLines(1);
                valueView.setEllipsize(TextUtils.TruncateAt.END);
                valueView.setGravity(Gravity.END);
                view = new LinearLayout(ctx);
                view.setOrientation(LinearLayout.HORIZONTAL);
                view.setGravity(Gravity.CENTER_VERTICAL);
                view.setMinimumHeight(dp(52));
                view.setPadding(dp(16), dp(10), dp(16), dp(10));
                LinearLayout texts = new LinearLayout(ctx);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.addView(titleView);
                texts.addView(subView);
                view.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                vlp.leftMargin = dp(12);
                view.addView(valueView, vlp);
                if (onClick != null) {
                    view.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
                    view.setOnClickListener(v -> onClick.run());
                }
            }

            public Row value(CharSequence v) { valueView.setText(v); return this; }
            public Row valueColor(int c) { valueView.setTextColor(c); return this; }
            public Row sub(CharSequence v) {
                subView.setText(v);
                subView.setVisibility(v == null || v.length() == 0 ? View.GONE : View.VISIBLE);
                return this;
            }
            public Row titleColor(int c) { titleView.setTextColor(c); return this; }
        }

        public interface OnChange {
            void onChange(boolean on);
        }

        /** Row with a switch. {@code enabled} false — dimmed and not clickable */
        public class SwitchRow {
            public final Switch switchView;
            public final TextView subView;
            public final LinearLayout view;
            private boolean checked;

            public SwitchRow(CharSequence title, boolean initial, OnChange onChange) {
                checked = initial;
                switchView = new Switch(ctx);
                switchView.setChecked(initial, false);
                switchView.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
                subView = text("", 13, HabitUi.sub(), false);
                subView.setVisibility(View.GONE);
                view = new LinearLayout(ctx);
                view.setOrientation(LinearLayout.HORIZONTAL);
                view.setGravity(Gravity.CENTER_VERTICAL);
                view.setMinimumHeight(dp(52));
                view.setPadding(dp(16), dp(10), dp(16), dp(10));
                LinearLayout texts = new LinearLayout(ctx);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.addView(text(title, 16, HabitUi.text(), false));
                texts.addView(subView);
                view.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                view.addView(switchView, new LinearLayout.LayoutParams(dp(37), dp(20)));
                view.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
                view.setOnClickListener(v -> {
                    if (!view.isEnabled()) return;
                    checked = !checked;
                    switchView.setChecked(checked, true);
                    onChange.onChange(checked);
                });
            }

            public SwitchRow sub(CharSequence v) {
                subView.setText(v);
                subView.setVisibility(v == null || v.length() == 0 ? View.GONE : View.VISIBLE);
                return this;
            }

            public SwitchRow enabled(boolean v) {
                view.setEnabled(v);
                view.setAlpha(v ? 1f : 0.45f);
                return this;
            }
        }

        public interface OnSelect {
            void onSelect(String key);
        }

        /** Segmented control: one of several options */
        public LinearLayout segmented(String[][] options, String selected, OnSelect onSelect) {
            LinearLayout l = notSection(new LinearLayout(ctx));
            l.setOrientation(LinearLayout.HORIZONTAL);
            l.setPadding(dp(4), dp(4), dp(4), dp(4));
            l.setBackground(roundRect(bg(), dpf(14)));
            for (String[] opt : options) {
                final String key = opt[0];
                boolean on = key.equals(selected);
                TextView t = text(opt[1], 14, on ? HabitUi.text() : sub(), on);
                t.setGravity(Gravity.CENTER);
                t.setMinHeight(dp(36));
                t.setBackground(on ? roundRect(HabitUi.card(), dpf(11)) : null);
                t.setElevation(on ? dpf(1) : 0);
                t.setOnClickListener(v -> {
                    if (key.equals(selected)) return;
                    haptic(v);
                    onSelect.onSelect(key);
                });
                l.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            return l;
        }

        /** Segmented control wrapped with card padding */
        public LinearLayout segmentedRow(String[][] options, String selected, OnSelect onSelect) {
            LinearLayout wrap = new LinearLayout(ctx);
            wrap.setPadding(dp(12), dp(10), dp(12), dp(10));
            View seg = segmented(options, selected, onSelect);
            seg.setTag(null);
            wrap.addView(seg, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return wrap;
        }

        public interface Display {
            String get();
        }

        public interface OnStep {
            void step(int d);
        }

        /** − value + row */
        public View stepperRow(CharSequence title, Display display, OnStep onStep) {
            TextView value = text(display.get(), 16, HabitUi.text(), true);
            value.setGravity(Gravity.CENTER);
            value.setMinWidth(dp(96));
            LinearLayout l = new LinearLayout(ctx);
            l.setOrientation(LinearLayout.HORIZONTAL);
            l.setGravity(Gravity.CENTER_VERTICAL);
            l.setMinimumHeight(dp(52));
            l.setPadding(dp(16), dp(8), dp(12), dp(8));
            l.addView(text(title, 16, HabitUi.text(), false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            l.addView(stepBtn("−", -1, onStep, value, display), new LinearLayout.LayoutParams(dp(36), dp(36)));
            l.addView(value);
            l.addView(stepBtn("+", 1, onStep, value, display), new LinearLayout.LayoutParams(dp(36), dp(36)));
            return l;
        }

        private TextView stepBtn(String label, int d, OnStep onStep, TextView value, Display display) {
            TextView b = text(label, 20, HabitUi.text(), false);
            b.setGravity(Gravity.CENTER);
            b.setBackground(oval(bg()));
            b.setOnClickListener(v -> {
                haptic(v);
                onStep.step(d);
                value.setText(display.get());
            });
            return b;
        }

        /** Note under a card */
        public TextView helper(CharSequence s) {
            TextView t = text(s, 13, HabitUi.sub(), false);
            t.setPadding(dp(16), dp(8), dp(16), 0);
            return notSection(t);
        }

        /** Warning banner (e.g. missing permission) — tap fixes it */
        public TextView banner(CharSequence s, Runnable onClick) {
            TextView t = text(s, 14, HabitUi.text(), true);
            t.setPadding(dp(16), dp(14), dp(16), dp(14));
            t.setBackground(roundRect(withAlpha(fire(), 0x2E), dpf(14)));
            t.setOnClickListener(v -> onClick.run());
            return notSection(t);
        }

        public TextView primaryButton(CharSequence label, Runnable onClick) {
            TextView t = text(label, 16, white(), true);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(48));
            t.setBackground(roundRect(Theme.getColor(Theme.key_featuredStickers_addButton), dpf(12)));
            t.setOnClickListener(v -> {
                haptic(v);
                onClick.run();
            });
            return notSection(t);
        }

        public View spacer(int h) {
            return HabitUi.spacer(ctx, h);
        }
    }

    // endregion

    // region Dialogs

    /** Simple list sheet */
    public static void pick(BaseFragment f, CharSequence title, CharSequence[] items, org.telegram.messenger.Utilities.Callback<Integer> onPick) {
        org.telegram.ui.ActionBar.BottomSheet.Builder b = new org.telegram.ui.ActionBar.BottomSheet.Builder(f.getContext());
        if (title != null) b.setTitle(title, true);
        b.setItems(items, (d, which) -> onPick.run(which));
        f.showDialog(b.create());
    }

    public interface OnText {
        void onText(String s);
    }

    /** Single text field dialog */
    public static void textDialog(BaseFragment f, CharSequence title, CharSequence message, String initial, CharSequence hint, boolean numeric, OnText onDone) {
        Context ctx = f.getContext();
        EditTextBoldCursor input = input(ctx, hint);
        if (numeric) input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (initial != null && !"0".equals(initial)) {
            input.setText(initial);
            input.setSelection(input.length());
        }
        FrameLayout box = new FrameLayout(ctx);
        box.setPadding(dp(24), dp(4), dp(24), 0);
        box.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        AlertDialog.Builder b = new AlertDialog.Builder(ctx).setTitle(title).setView(box)
            .setPositiveButton(LocaleController.getString(R.string.Done), (d, w) -> onDone.onText(input.getText().toString().trim()))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        if (message != null) b.setMessage(message);
        f.showDialog(b.create());
        focusWithKeyboard(input);
    }

    public static void confirm(BaseFragment f, CharSequence title, CharSequence message, CharSequence positive, boolean red, Runnable onYes) {
        AlertDialog.Builder b = new AlertDialog.Builder(f.getContext())
            .setMessage(message)
            .setPositiveButton(positive, (d, w) -> onYes.run())
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        if (title != null) b.setTitle(title);
        AlertDialog dialog = b.create();
        f.showDialog(dialog);
        if (red) {
            TextView button = (TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (button != null) button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

}
