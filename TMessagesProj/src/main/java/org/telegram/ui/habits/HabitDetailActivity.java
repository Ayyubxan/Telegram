package org.telegram.ui.habits;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.nexo.Cat;
import org.telegram.messenger.nexo.Dates;
import org.telegram.messenger.nexo.Habit;
import org.telegram.messenger.nexo.HabitStore;
import org.telegram.messenger.nexo.Measure;
import org.telegram.messenger.nexo.Nexo;
import org.telegram.messenger.nexo.Status;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SectionsScrollView;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** FORK MOD (Nexo): one habit — statistics, monthly calendar, edit and delete. */
@SuppressLint("SetTextI18n")
public class HabitDetailActivity extends BaseFragment {
    private static final int MENU_EDIT = 1;

    private final String habitId;
    private HabitStore store;
    private TextView title, subtitle, monthTitle, calendarHint;
    private LinearLayout stats;
    private HabitUi.MonthView calendar;
    private HabitUi.ConfettiView confetti;
    private final Runnable listener = this::refresh;

    public HabitDetailActivity(String habitId) {
        this.habitId = habitId;
    }

    @Override
    public boolean onFragmentCreate() {
        store = Nexo.store();
        return store.habit(habitId) != null && super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.HabitsHabit));
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_EDIT, R.drawable.msg_edit).setContentDescription(LocaleController.getString(R.string.Edit));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_EDIT) {
                    presentFragment(new HabitEditActivity(habitId));
                }
            }
        });
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(HabitUi.bg());
        LinearLayout[] c = new LinearLayout[1];
        SectionsScrollView scroll = HabitUi.page(context, c);
        LinearLayout content = c[0];
        content.setPadding(dp(12), dp(12), dp(12), dp(40));
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(scroll);

        title = HabitUi.notSection(HabitUi.text(context, "", 26, HabitUi.text(), true));
        title.setPadding(dp(6), 0, dp(6), 0);
        content.addView(title);
        subtitle = HabitUi.notSection(HabitUi.text(context, "", 14, HabitUi.sub()));
        subtitle.setPadding(dp(6), dp(2), dp(6), dp(14));
        content.addView(subtitle);
        stats = new LinearLayout(context);
        stats.setOrientation(LinearLayout.VERTICAL);
        content.addView(stats);
        content.addView(HabitUi.spacer(context, 12));

        HabitUi.Card cal = HabitUi.card(context);
        LinearLayout nav = new LinearLayout(context);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(0, 0, 0, dp(8));
        TextView prev = HabitUi.text(context, "‹", 26, HabitUi.text(), true);
        prev.setPadding(dp(8), 0, dp(16), 0);
        prev.setOnClickListener(v -> { calendar.setMonth(calendar.getMonth().minusMonths(1)); refreshMonth(); });
        nav.addView(prev);
        monthTitle = HabitUi.text(context, "", 17, HabitUi.text(), true);
        monthTitle.setGravity(Gravity.CENTER);
        nav.addView(monthTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView next = HabitUi.text(context, "›", 26, HabitUi.text(), true);
        next.setPadding(dp(16), 0, dp(8), 0);
        next.setOnClickListener(v -> {
            if (calendar.getMonth().isBefore(YearMonth.now())) calendar.setMonth(calendar.getMonth().plusMonths(1));
            refreshMonth();
        });
        nav.addView(next);
        cal.addView(nav);
        calendar = new HabitUi.MonthView(context, d -> {
            Habit h = store.habit(habitId);
            return h == null ? null : HabitUi.habitFill(store, h, d);
        }, date -> mark(date, Status.DONE), date -> mark(date, Status.SKIPPED));
        cal.addView(calendar);
        calendarHint = HabitUi.text(context, "", 12, HabitUi.sub());
        calendarHint.setPadding(0, dp(8), 0, 0);
        cal.addView(calendarHint);
        content.addView(cal);

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

    private View stat(String value, String label, int color) {
        Context ctx = getContext();
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(12), dp(16), dp(12));
        l.addView(HabitUi.text(ctx, value, 22, color, true));
        l.addView(HabitUi.text(ctx, label, 12, HabitUi.sub()));
        return l;
    }

    private View statRow(View a, View b) {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.addView(a, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        l.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        l.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    private int statRows;

    private void addStatRow(View row) {
        if (statRows++ > 0) stats.addView(HabitUi.divider(getContext(), 16));
        stats.addView(row);
    }

    private void refresh() {
        if (fragmentView == null) return;
        Habit h = store.habit(habitId);
        if (h == null) {
            finishFragment();
            return;
        }
        title.setText(h.emoji + "  " + h.name);
        Cat cat = store.cat(h.cat);
        subtitle.setText((cat != null ? cat.emoji + " " + cat.name : "Other") + " · " + h.repeat.describe());
        stats.removeAllViews();
        statRows = 0;
        LocalDate today = HabitStore.today();
        List<LocalDate> dates = store.doneDates(h.id);
        if (h.daily()) {
            int streak = store.streak(h.id);
            addStatRow(statRow(
                stat("🔥 " + streak, "current streak (" + h.repeat.units(streak) + ")", HabitUi.fire()),
                stat("🏆 " + store.bestStreak(h.id), "best streak", HabitUi.text())));
            addStatRow(statRow(
                stat(store.completion(h.id, 30) + "%", "last 30 days", HabitUi.done()),
                stat(String.valueOf(dates.size()), "total done", HabitUi.text())));
        } else if (h.once()) {
            LocalDate doneOn = store.completedOn(h.id);
            String dl = h.repeat.deadline;
            addStatRow(statRow(
                stat(doneOn != null ? "🏆" : "⏳", doneOn != null ? "done: " + Dates.shortDate(doneOn) : "not done yet", doneOn != null ? HabitUi.done() : HabitUi.text()),
                stat(dl.isEmpty() ? "—" : Dates.shortDate(dl), dl.isEmpty() ? "no deadline" : Dates.left(dl, today), HabitUi.text())));
            if (h.measure != null) {
                Measure m = h.measure;
                addStatRow(statRow(
                    stat(Measure.fmt(m.now) + " " + m.unit, "now (from " + Measure.fmt(m.from) + ")", HabitUi.text()),
                    stat((int) (m.progress() * 100) + "%", "goal: " + Measure.fmt(m.to) + " " + m.unit, HabitUi.done())));
            }
        } else {
            String last = dates.isEmpty() ? "—" : Dates.daysAgo(ChronoUnit.DAYS.between(dates.get(dates.size() - 1), today));
            int month = 0, year = 0;
            for (LocalDate d : dates) {
                if (d.getYear() == today.getYear()) {
                    year++;
                    if (d.getMonth() == today.getMonth()) month++;
                }
            }
            addStatRow(statRow(stat(String.valueOf(month), "this month", HabitUi.done()), stat(String.valueOf(year), "this year", HabitUi.text())));
            addStatRow(statRow(stat(String.valueOf(dates.size()), "total", HabitUi.text()), stat(last, "last time", HabitUi.text())));
        }
        calendarHint.setText(h.daily() ? HabitUi.CALENDAR_HINT : "Tap a day — done or undo");
        int earned = store.earned(null, null, h.id);
        addStatRow(statRow(
            stat("⭐ " + (earned > 0 ? "+" : "") + earned, "total points", HabitUi.fire()),
            stat("+" + h.plus + " / −" + h.minus, h.once() ? "done / deadline missed" : "done / skipped", HabitUi.text())));
        refreshMonth();
    }

    private void refreshMonth() {
        YearMonth m = calendar.getMonth();
        int done = 0;
        for (int d = 1; d <= m.lengthOfMonth(); d++) if (store.status(habitId, m.atDay(d)) == Status.DONE) done++;
        monthTitle.setText(Dates.MONTHS[m.getMonthValue() - 1] + " " + m.getYear() + "  ·  ✓ " + done);
        calendar.invalidate();
    }

    private void mark(LocalDate date, int target) {
        Habit h = store.habit(habitId);
        if (h != null) HabitUi.toggleMark(this, confetti, h, date, target);
    }
}
