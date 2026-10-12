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
import org.telegram.messenger.nexo.Cat;
import org.telegram.messenger.nexo.Dates;
import org.telegram.messenger.nexo.Goal;
import org.telegram.messenger.nexo.Habit;
import org.telegram.messenger.nexo.HabitStore;
import org.telegram.messenger.nexo.MarkResult;
import org.telegram.messenger.nexo.Nexo;
import org.telegram.messenger.nexo.Status;
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

    // region Confetti

    /** Confetti over the whole screen, lets touches through */
    public static class ConfettiView extends View {
        private static class P {
            float x, y, vx, vy, rot;
            final float vr, w, h;
            final int color;
            float life;
            P(float x, float y, float vx, float vy, float rot, float vr, int color, float w, float h, float life) {
                this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.rot = rot; this.vr = vr; this.color = color; this.w = w; this.h = h; this.life = life;
            }
        }

        private final java.util.ArrayList<P> parts = new java.util.ArrayList<>();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random random = new Random();
        private long last;
        private final int[] colors = {0xFF34C759, 0xFFFF9500, 0xFF3B82F6, 0xFFEC4899, 0xFFFACC15, 0xFF8B5CF6};

        public ConfettiView(Context ctx) {
            super(ctx);
        }

        /** {@code big} — streak milestone or reward: rain from the top of the screen */
        public void burst(float x, float y, boolean big) {
            float d = AndroidUtilities.density;
            int n = big ? 160 : 36;
            for (int i = 0; i < n; i++) {
                float px, py, vx, vy;
                if (big) {
                    px = random.nextFloat() * getWidth();
                    py = -random.nextFloat() * getHeight() * 0.3f;
                    vx = (random.nextFloat() - 0.5f) * 300 * d;
                    vy = random.nextFloat() * 200 * d;
                } else {
                    float a = random.nextFloat() * (float) Math.PI * 2;
                    float speed = (150 + random.nextFloat() * 350) * d;
                    px = x; py = y;
                    vx = (float) Math.cos(a) * speed;
                    vy = (float) Math.sin(a) * speed - 250 * d;
                }
                parts.add(new P(px, py, vx, vy, random.nextFloat() * 360, (random.nextFloat() - 0.5f) * 720,
                    colors[random.nextInt(colors.length)], (4 + random.nextFloat() * 5) * d, (6 + random.nextFloat() * 8) * d, big ? 3.2f : 1.3f));
            }
            last = System.nanoTime();
            postInvalidateOnAnimation();
        }

        public void burstAt(View v) {
            int[] loc = new int[2];
            v.getLocationInWindow(loc);
            int[] base = new int[2];
            getLocationInWindow(base);
            burst(loc[0] - base[0] + v.getWidth() / 2f, loc[1] - base[1] + v.getHeight() / 2f, false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (parts.isEmpty()) return;
            long now = System.nanoTime();
            float dt = Math.min(0.05f, (now - last) / 1e9f);
            last = now;
            float g = 900 * AndroidUtilities.density;
            java.util.Iterator<P> it = parts.iterator();
            while (it.hasNext()) {
                P p = it.next();
                p.vy += g * dt;
                p.vx *= 0.99f;
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                p.rot += p.vr * dt;
                p.life -= dt;
                if (p.life <= 0 || p.y > getHeight() + 50) {
                    it.remove();
                    continue;
                }
                paint.setColor(p.color);
                paint.setAlpha((int) (255 * Math.min(0.5f, p.life) * 2));
                canvas.save();
                canvas.rotate(p.rot, p.x, p.y);
                canvas.drawRect(p.x - p.w / 2, p.y - p.h / 2, p.x + p.w / 2, p.y + p.h / 2, paint);
                canvas.restore();
            }
            if (!parts.isEmpty()) postInvalidateOnAnimation();
        }
    }

    // endregion

    // region Month calendar

    public interface DayFill {
        /** Color of the day cell or null for an empty day */
        Integer fill(LocalDate date);
    }

    public interface DayTap {
        void onTap(LocalDate date);
    }

    /** Monthly calendar. {@link DayFill} colors the days, taps on past/today days call the listeners. */
    @SuppressLint("ViewConstructor")
    public static class MonthView extends View {
        private YearMonth month = YearMonth.now();
        private LocalDate selected;
        private final DayFill fill;
        private final DayTap onTap;
        private final DayTap onLongTap;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float header = dpf(24);
        private final float gap = dpf(5);

        public MonthView(Context ctx, DayFill fill, DayTap onTap, DayTap onLongTap) {
            super(ctx);
            this.fill = fill;
            this.onTap = onTap;
            this.onLongTap = onLongTap;
            paint.setTextAlign(Paint.Align.CENTER);
        }

        public YearMonth getMonth() { return month; }

        public void setMonth(YearMonth m) {
            month = m;
            requestLayout();
            invalidate();
        }

        public void setSelected(LocalDate d) {
            selected = d;
            invalidate();
        }

        private int offset() { return month.atDay(1).getDayOfWeek().getValue() - 1; }
        private int rows() { return (offset() + month.lengthOfMonth() + 6) / 7; }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(w, (int) (header + rows() * (w / 7f)));
        }

        private RectF cellRect(int i) {
            float cell = getWidth() / 7f;
            int col = i % 7, row = i / 7;
            return new RectF(col * cell + gap / 2, header + row * cell + gap / 2, (col + 1) * cell - gap / 2, header + (row + 1) * cell - gap / 2);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cell = getWidth() / 7f;
            paint.setTextSize(dpf(11));
            paint.setTypeface(AndroidUtilities.bold());
            paint.setColor(sub());
            for (int i = 0; i < 7; i++) canvas.drawText(Dates.WEEKDAYS_SHORT[i], cell * i + cell / 2, header - dpf(8), paint);
            LocalDate today = LocalDate.now();
            float r = dpf(10);
            int offset = offset();
            for (int day = 1; day <= month.lengthOfMonth(); day++) {
                LocalDate date = month.atDay(day);
                RectF rect = cellRect(offset + day - 1);
                Integer color = fill.fill(date);
                boolean future = date.isAfter(today);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(color != null ? color : future ? withAlpha(line(), 0x40) : withAlpha(line(), 0x80));
                canvas.drawRoundRect(rect, r, r, paint);
                Integer ring = date.equals(selected) ? Integer.valueOf(text()) : date.equals(today) ? Integer.valueOf(withAlpha(text(), 0x66)) : null;
                if (ring != null) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dpf(2.5f));
                    paint.setColor(ring);
                    canvas.drawRoundRect(rect, r, r, paint);
                    paint.setStyle(Paint.Style.FILL);
                }
                paint.setTypeface(date.equals(today) ? AndroidUtilities.bold() : Typeface.DEFAULT);
                paint.setTextSize(dpf(13));
                paint.setColor(color != null ? Color.WHITE : future ? withAlpha(sub(), 0x88) : text());
                canvas.drawText(String.valueOf(day), rect.centerX(), rect.centerY() - (paint.descent() + paint.ascent()) / 2, paint);
            }
        }

        /** Day under the point (future and empty cells — null) */
        private LocalDate dateAt(float x, float y) {
            if (y < header || getWidth() == 0) return null;
            float cell = getWidth() / 7f;
            int day = (int) ((y - header) / cell) * 7 + (int) (x / cell) - offset() + 1;
            if (day < 1 || day > month.lengthOfMonth()) return null;
            LocalDate d = month.atDay(day);
            return d.isAfter(LocalDate.now()) ? null : d;
        }

        private final GestureDetector gestures = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) { return true; }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                LocalDate date = dateAt(e.getX(), e.getY());
                if (date == null) return true;
                haptic(MonthView.this);
                onTap.onTap(date);
                invalidate();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                LocalDate date = dateAt(e.getX(), e.getY());
                if (date == null || onLongTap == null) return;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                onLongTap.onTap(date);
                invalidate();
            }
        });

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            getParent().requestDisallowInterceptTouchEvent(true);
            return gestures.onTouchEvent(e);
        }
    }

    public static final String CALENDAR_HINT = "Tap — ✓ or undo  ·  Hold — ✕ or undo";

    /** Calendar color for one habit's status */
    public static Integer statusFill(int s) {
        if (s == Status.DONE) return done();
        if (s == Status.SKIPPED) return skip();
        return null;
    }

    /** One habit's color on a day: partially completed multi-time habit — lighter green */
    public static Integer habitFill(HabitStore store, Habit h, LocalDate date) {
        Integer f = statusFill(store.status(h.id, date));
        if (f != null) return f;
        int[] p = store.progress(h.id, date);
        return p[0] > 0 ? withAlpha(done(), 0x70 + 0x60 * p[0] / p[1]) : null;
    }

    /**
     * Marking from the calendar: sets {@code target}, or clears it when already set.
     * Unscheduled habits have no ✕ — returns null and nothing changes.
     */
    public static Integer toggleMark(BaseFragment f, ConfettiView confetti, Habit h, LocalDate date, int target) {
        HabitStore store = Nexo.store();
        if (target == Status.SKIPPED && !h.daily()) return null;
        int next = store.status(h.id, date) == target ? Status.NONE : target;
        MarkResult r = store.setStatus(h.id, date, next);
        if (next == Status.DONE) celebrate(f, confetti, h, r);
        return next;
    }

    // endregion

    // region Flow layout

    /** Lays children in rows, wrapping to the next row when they don't fit */
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

    public interface OnCat {
        void onSave(String name, String emoji, String color);
    }

    /** Create or edit a category: name, emoji and color. {@code onDelete} — shows a delete button. */
    public static void catDialog(BaseFragment f, Cat existing, Runnable onDelete, OnCat onSave) {
        Context ctx = f.getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(4), dp(24), 0);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final String[] emoji = {existing == null ? "📁" : existing.emoji};
        TextView emojiView = text(ctx, emoji[0], 24, text());
        emojiView.setGravity(Gravity.CENTER);
        emojiView.setBackground(oval(bg()));
        emojiView.setOnClickListener(v -> EmojiPickerSheet.show(f, emoji[0], e -> {
            emoji[0] = e;
            emojiView.setText(e);
        }));
        EditTextBoldCursor name = input(ctx, "Category name");
        if (existing != null) {
            name.setText(existing.name);
            name.setSelection(name.length());
        }
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(48), dp(48));
        elp.rightMargin = dp(12);
        row.addView(emojiView, elp);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);

        final String[] color = {existing == null ? Cat.PALETTE.get(0) : existing.color};
        LinearLayout swatches = new LinearLayout(ctx);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        swatches.setPadding(0, dp(16), 0, dp(4));
        Runnable paint = () -> {
            for (int i = 0; i < swatches.getChildCount(); i++) {
                View v = swatches.getChildAt(i);
                String c = (String) v.getTag();
                boolean sel = c.equalsIgnoreCase(color[0]);
                v.setBackground(oval(Color.parseColor(c), text(), sel ? dp(3) : 0));
                v.setScaleX(sel ? 1.15f : 1f);
                v.setScaleY(v.getScaleX());
            }
        };
        for (String c : Cat.PALETTE) {
            View v = new View(ctx);
            v.setTag(c);
            v.setOnClickListener(view -> {
                color[0] = c;
                haptic(view);
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

        AlertDialog.Builder b = new AlertDialog.Builder(ctx)
            .setTitle(existing == null ? "New category" : "Category")
            .setView(box)
            .setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
                String n = name.getText().toString().trim();
                if (n.isEmpty()) return;
                onSave.onSave(n, emoji[0].isEmpty() ? "📁" : emoji[0], color[0]);
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        if (onDelete != null) {
            b.setNeutralButton(LocaleController.getString(R.string.Delete), (d, w) ->
                confirm(f, null, "Delete \"" + existing.name + "\"? Its habits are kept — they move under \"Other\".", LocaleController.getString(R.string.Delete), true, onDelete));
        }
        f.showDialog(b.create());
        focusWithKeyboard(name);
    }

    /** Celebration for streak milestones and rewards */
    public static void celebrate(BaseFragment f, ConfettiView confetti, Habit habit, MarkResult result) {
        Goal goal = result.goalReached;
        if (result.milestone == null && goal == null) return;
        if (confetti != null) confetti.burst(0, 0, true);
        if (f == null || f.getContext() == null) return;
        StringBuilder msg = new StringBuilder();
        if (goal != null) {
            msg.append(goal.emoji).append(' ').append(goal.title).append(": ").append(goal.target).append(" points collected!\n\nTap \"Claim reward\" on the Habits screen — you've earned it.");
        }
        if (result.milestone != null) {
            if (msg.length() > 0) msg.append("\n\n");
            int m = result.milestone;
            if (result.total) {
                msg.append(habit.emoji).append(' ').append(habit.name).append(": ").append(m == 1 ? "first time" : Dates.ordinal(m) + " time").append("! 🎉\n\n").append(cheerTotal(m));
            } else {
                msg.append(habit.emoji).append(' ').append(habit.name).append(": ").append(m).append(' ').append(habit.repeat.units(m)).append(" in a row! 🔥\n\n").append(cheer(m));
            }
        }
        f.showDialog(new AlertDialog.Builder(f.getContext())
            .setTitle(goal != null ? "🎉 Goal reached!" : result.total ? "🎉 Great!" : "🔥 Great!")
            .setMessage(msg)
            .setPositiveButton("Keep going 💪", null)
            .create());
    }

    private static String cheerTotal(int n) {
        if (n == 1) return "The count has started. Every one is recorded.";
        if (n < 25) return "It's adding up — keep going.";
        if (n < 100) return "This is part of your life now.";
        return "A big number. That takes years.";
    }

    private static String cheer(int days) {
        if (days < 7) return "Good start. Don't break the chain!";
        if (days < 30) return "The habit is forming — your brain is already learning.";
        if (days < 100) return "This is no accident anymore, this is your character.";
        return "Legendary. Few people get this far.";
    }

    // endregion

    /** Last {@code n} occurrences (old → new), including today if due today */
    public static List<LocalDate> recentDue(HabitStore store, Habit h, int n, LocalDate today) {
        LocalDate first = store.startOf(h);
        java.util.ArrayList<LocalDate> out = new java.util.ArrayList<>();
        LocalDate d = today;
        int guard = 0;
        while (out.size() < n && !d.isBefore(first) && guard++ < 800) {
            if (h.repeat.isDue(d, first)) out.add(d);
            d = d.minusDays(1);
        }
        java.util.Collections.reverse(out);
        return out;
    }
}
