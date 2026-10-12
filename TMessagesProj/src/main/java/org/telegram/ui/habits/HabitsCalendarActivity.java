package org.telegram.ui.habits;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.nexo.Dates;
import org.telegram.messenger.nexo.Habit;
import org.telegram.messenger.nexo.HabitStore;
import org.telegram.messenger.nexo.MarkResult;
import org.telegram.messenger.nexo.Nexo;
import org.telegram.messenger.nexo.Repeat;
import org.telegram.messenger.nexo.Status;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SectionsScrollView;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FORK MOD (Nexo): habits calendar. In "All" mode a day's color is the share of completed habits;
 * with one habit selected — its status. Tapping a day shows that day's habits below.
 */
@SuppressLint("SetTextI18n")
public class HabitsCalendarActivity extends BaseFragment {
    private HabitStore store;
    private LinearLayout chips;
    private TextView monthTitle, monthSummary, calendarHint, dayTitle, daySummary;
    private HabitUi.MonthView calendar;
    private LinearLayout dayList;
    private HabitUi.ConfettiView confetti;
    /** null — all, otherwise a habit id */
    private String filter;
    private LocalDate selected = HabitStore.today();
    private LocalDate shownRowsDay;
    private final ArrayList<DayRow> dayRows = new ArrayList<>();
    private final Runnable listener = this::refresh;

    @Override
    public boolean onFragmentCreate() {
        store = Nexo.store();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.HabitsCalendar));
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
        LinearLayout content = c[0];
        content.setPadding(dp(12), dp(8), dp(12), dp(40));
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(scroll);

        chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView hs = HabitUi.notSection(new HorizontalScrollView(context));
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        hs.setPadding(dp(4), dp(6), dp(4), dp(10));
        content.addView(hs);

        HabitUi.Card cal = HabitUi.card(context);
        LinearLayout nav = new LinearLayout(context);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        TextView prev = HabitUi.text(context, "‹", 26, HabitUi.text(), true);
        prev.setPadding(dp(8), 0, dp(16), 0);
        prev.setOnClickListener(v -> { calendar.setMonth(calendar.getMonth().minusMonths(1)); refreshMonth(); });
        nav.addView(prev);
        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        monthTitle = HabitUi.text(context, "", 17, HabitUi.text(), true);
        monthTitle.setGravity(Gravity.CENTER);
        monthSummary = HabitUi.text(context, "", 13, HabitUi.sub());
        monthSummary.setGravity(Gravity.CENTER);
        titles.addView(monthTitle);
        titles.addView(monthSummary);
        nav.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView next = HabitUi.text(context, "›", 26, HabitUi.text(), true);
        next.setPadding(dp(16), 0, dp(8), 0);
        next.setOnClickListener(v -> {
            if (calendar.getMonth().isBefore(YearMonth.now())) calendar.setMonth(calendar.getMonth().plusMonths(1));
            refreshMonth();
        });
        nav.addView(next);
        cal.addView(nav);
        calendar = new HabitUi.MonthView(context, this::fill, date -> tapDay(date, Status.DONE), date -> tapDay(date, Status.SKIPPED));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        cal.addView(calendar, clp);
        calendarHint = HabitUi.text(context, "", 12, HabitUi.sub());
        calendarHint.setPadding(0, dp(8), 0, 0);
        cal.addView(calendarHint);
        content.addView(cal);
        content.addView(HabitUi.spacer(context, 12));

        HabitUi.Card day = HabitUi.card(context);
        dayTitle = HabitUi.text(context, "", 17, HabitUi.text(), true);
        daySummary = HabitUi.text(context, "", 13, HabitUi.sub());
        daySummary.setPadding(0, dp(2), 0, dp(6));
        dayList = new LinearLayout(context);
        dayList.setOrientation(LinearLayout.VERTICAL);
        day.addView(dayTitle);
        day.addView(daySummary);
        day.addView(dayList);
        content.addView(day);

        confetti = new HabitUi.ConfettiView(context);
        root.addView(confetti, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        refresh();
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        store.addListener(listener);
        refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        store.removeListener(listener);
    }

    /** Day color: the selected habit's status, or intensity by the share of completed habits */
    private Integer fill(LocalDate date) {
        if (filter != null) {
            Habit h = store.habit(filter);
            return h == null ? null : HabitUi.habitFill(store, h, date);
        }
        List<Habit> due = store.dueOn(date);
        if (due.isEmpty() || date.isAfter(HabitStore.today())) return null;
        int done = 0, total = 0;
        for (Habit h : due) {
            int[] p = store.progress(h.id, date);
            done += p[0];
            total += p[1];
        }
        if (done == 0) {
            for (Habit h : due) if (store.status(h.id, date) == Status.SKIPPED) return HabitUi.withAlpha(HabitUi.skip(), 0xB0);
            return null;
        }
        return HabitUi.withAlpha(HabitUi.done(), 0x70 + 0x8F * done / total);
    }

    /** "All": the day is only selected; with a habit: tap ✓, hold ✕ (again — undo) */
    private void tapDay(LocalDate date, int target) {
        selected = date;
        calendar.setSelected(date);
        if (filter != null) {
            Habit h = store.habit(filter);
            if (h != null) HabitUi.toggleMark(this, confetti, h, date, target);
        }
        refreshDay();
        refreshMonth();
    }

    private void refresh() {
        if (fragmentView == null) return;
        if (filter != null && store.habit(filter) == null) filter = null;
        Habit h = filter == null ? null : store.habit(filter);
        calendarHint.setText(h == null ? "Tap a day — its habits are listed below" : h.daily() ? HabitUi.CALENDAR_HINT : "Tap a day — done or undo");
        refreshChips();
        calendar.setSelected(selected);
        refreshMonth();
        refreshDay();
    }

    private void refreshChips() {
        chips.removeAllViews();
        addChip("All", null);
        for (Habit h : store.active()) addChip(h.emoji + " " + h.name, h.id);
    }

    private void addChip(String label, String id) {
        boolean on = id == null ? filter == null : id.equals(filter);
        TextView chip = HabitUi.chip(getContext(), label, on, () -> {
            filter = id;
            refresh();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        chips.addView(chip, lp);
    }

    private void refreshMonth() {
        YearMonth m = calendar.getMonth();
        monthTitle.setText(Dates.MONTHS[m.getMonthValue() - 1] + " " + m.getYear());
        LocalDate today = HabitStore.today();
        ArrayList<LocalDate> days = new ArrayList<>();
        for (int d = 1; d <= m.lengthOfMonth(); d++) {
            LocalDate date = m.atDay(d);
            if (!date.isAfter(today)) days.add(date);
        }
        if (filter != null) {
            int done = 0, skip = 0;
            for (LocalDate d : days) {
                int s = store.status(filter, d);
                if (s == Status.DONE) done++;
                else if (s == Status.SKIPPED) skip++;
            }
            monthSummary.setText("✓ " + done + " · ✕ " + skip);
        } else {
            int expected = 0, done = 0;
            for (LocalDate d : days) {
                for (Habit h : store.dueOn(d)) {
                    int[] p = store.progress(h.id, d);
                    done += p[0];
                    expected += p[1];
                }
            }
            monthSummary.setText(expected == 0 ? "No marks yet" : "Completed: " + (done * 100 / expected) + "%");
        }
        calendar.invalidate();
    }

    private void refreshDay() {
        LocalDate d = selected;
        dayTitle.setText(d.equals(HabitStore.today()) ? "Today, " + Dates.dayMonth(d) : Dates.longDate(d));
        ArrayList<Habit> habits = new ArrayList<>();
        for (Habit h : store.active()) {
            if (filter != null) {
                if (h.id.equals(filter)) habits.add(h);
                continue;
            }
            if ((!h.daily() && !h.once()) || store.isDue(h, d) || store.status(h.id, d) != Status.NONE) habits.add(h);
        }
        int done = 0, skip = 0;
        for (Habit h : habits) {
            int s = store.status(h.id, d);
            if (s == Status.DONE) done++;
            else if (s == Status.SKIPPED) skip++;
        }
        daySummary.setText("✓ " + done + " · ✕ " + skip);
        // Rows are rebuilt only when the day or the habit list changes — otherwise a quick second tap is lost
        ArrayList<Habit> shown = new ArrayList<>();
        for (DayRow r : dayRows) shown.add(r.habit);
        if (!d.equals(shownRowsDay) || !sameHabits(shown, habits)) {
            shownRowsDay = d;
            dayList.removeAllViews();
            dayRows.clear();
            for (Habit h : habits) {
                DayRow r = new DayRow(h);
                dayRows.add(r);
                dayList.addView(r.view);
            }
        }
        for (DayRow r : dayRows) r.bind();
    }

    private static boolean sameHabits(List<Habit> a, List<Habit> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).id.equals(b.get(i).id) || a.get(i).u != b.get(i).u) return false;
        return true;
    }

    private class DayRow {
        final Habit habit;
        final LinearLayout view;
        private final TextView skip, done;
        private final LinkedHashMap<String, TextView> slots = new LinkedHashMap<>();

        DayRow(Habit habit) {
            this.habit = habit;
            Context ctx = getContext();
            view = new LinearLayout(ctx);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(0, dp(6), 0, dp(6));
            skip = mark("✕", Status.SKIPPED);
            done = mark("✓", Status.DONE);
            view.addView(HabitUi.text(ctx, habit.emoji + "  " + habit.name, 15, HabitUi.text()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (habit.repeat.slotted()) {
                for (String t : habit.repeat.times) {
                    TextView chip = slotChip(t);
                    slots.put(t, chip);
                    view.addView(chip);
                }
            } else {
                if (habit.daily()) view.addView(skip);
                view.addView(done);
            }
        }

        /** Tap — ✓/undo, hold — ✕/undo */
        private TextView slotChip(String t) {
            TextView chip = HabitUi.text(getContext(), Repeat.shortLabel(t, selected), 13, HabitUi.text(), true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(8), dp(6), dp(8), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(6);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> { HabitUi.haptic(v); toggleSlot(t, Status.DONE); });
            chip.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                toggleSlot(t, Status.SKIPPED);
                return true;
            });
            return chip;
        }

        private void toggleSlot(String t, int target) {
            LocalDate d = selected;
            int next = store.slotStatus(habit.id, d, t) == target ? Status.NONE : target;
            MarkResult r = store.setSlot(habit.id, d, t, next);
            refreshDay();
            refreshMonth();
            if (next == Status.DONE) HabitUi.celebrate(HabitsCalendarActivity.this, confetti, habit, r);
        }

        private TextView mark(String label, int target) {
            TextView t = HabitUi.text(getContext(), label, 14, HabitUi.sub(), true);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
            lp.leftMargin = dp(8);
            t.setLayoutParams(lp);
            t.setOnClickListener(v -> {
                HabitUi.haptic(v);
                // Read the state at tap time — correct even if the screen is not refreshed yet
                LocalDate d = selected;
                boolean wasOn = store.status(habit.id, d) == target;
                MarkResult r = store.setStatus(habit.id, d, wasOn ? Status.NONE : target);
                refreshDay();
                refreshMonth();
                if (!wasOn && target == Status.DONE) HabitUi.celebrate(HabitsCalendarActivity.this, confetti, habit, r);
            });
            return t;
        }

        void bind() {
            int s = store.status(habit.id, selected);
            paint(skip, s == Status.SKIPPED, HabitUi.skip());
            paint(done, s == Status.DONE, HabitUi.done());
            for (Map.Entry<String, TextView> e : slots.entrySet()) {
                int st = store.slotStatus(habit.id, selected, e.getKey());
                Integer color = st == Status.DONE ? Integer.valueOf(HabitUi.done()) : st == Status.SKIPPED ? Integer.valueOf(HabitUi.skip()) : null;
                e.getValue().setText(Repeat.shortLabel(e.getKey(), selected));
                e.getValue().setTextColor(color != null ? HabitUi.white() : HabitUi.sub());
                e.getValue().setBackground(color != null ? HabitUi.roundRect(color, HabitUi.dpf(14)) : HabitUi.roundRect(0, HabitUi.dpf(14), HabitUi.line(), dp(2)));
            }
        }

        private void paint(TextView v, boolean on, int color) {
            v.setTextColor(on ? HabitUi.white() : HabitUi.sub());
            v.setBackground(on ? HabitUi.oval(color) : HabitUi.oval(0, HabitUi.line(), dp(2)));
        }
    }
}
