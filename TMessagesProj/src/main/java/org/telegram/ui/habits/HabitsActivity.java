package org.telegram.ui.habits;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.nexo.Auto;
import org.telegram.messenger.nexo.AutoMarker;
import org.telegram.messenger.nexo.AutoRules;
import org.telegram.messenger.nexo.Calls;
import org.telegram.messenger.nexo.Cat;
import org.telegram.messenger.nexo.Dates;
import org.telegram.messenger.nexo.Goal;
import org.telegram.messenger.nexo.Habit;
import org.telegram.messenger.nexo.HabitStore;
import org.telegram.messenger.nexo.Health;
import org.telegram.messenger.nexo.MarkResult;
import org.telegram.messenger.nexo.Measure;
import org.telegram.messenger.nexo.Nexo;
import org.telegram.messenger.nexo.Places;
import org.telegram.messenger.nexo.Points;
import org.telegram.messenger.nexo.PrayerTimes;
import org.telegram.messenger.nexo.Repeat;
import org.telegram.messenger.nexo.ScreenTime;
import org.telegram.messenger.nexo.Sleep;
import org.telegram.messenger.nexo.Status;
import org.telegram.messenger.nexo.Steps;
import org.telegram.messenger.nexo.SyncService;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SectionsScrollView;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * FORK MOD (Nexo): the Habits tab — today's habits, one tap to mark ✓ or ✕; multi-time habits mark every time separately.
 * Replaces the Contacts tab in {@link MainTabsActivity}.
 */
@SuppressLint("SetTextI18n")
public class HabitsActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {
    private static final int MENU_CALENDAR = 1;
    /** A habit shows in the active list this many minutes before its time */
    public static final int LEAD_MIN = 60;
    public static final long HOLD_MS = 900L;

    private enum Section { ACTIVE, LATER, DONE }

    private boolean hasMainTabs;
    private HabitStore store;

    private FrameLayout root;
    private SectionsScrollView scroll;
    private LinearLayout content;
    private LinearLayout list;
    private HabitUi.ConfettiView confetti;
    private FragmentFloatingButton fab;

    private TextView dateText, progressText, balanceText, todayPoints, goalTitle, goalNumbers, goalHint, claimButton;
    private View progressFill, progressRest, goalFill, goalRest;
    private LinearLayout goalBar;
    private Integer shownBalance;

    private final LinkedHashMap<String, Row> rows = new LinkedHashMap<>();
    private List<Object> shownKey = new ArrayList<>();
    /** A tapped row stays in place for a moment (so ✓ and confetti are visible), then moves to its section */
    private final HashMap<String, Object[]> held = new HashMap<>();
    private boolean laterOpen, doneOpen, autoOpen, otherOpen;
    private LinearLayout doneBox, laterBox;

    private int insetTop, insetBottom;

    private final Runnable listener = this::refresh;
    private final Runnable prayerListener = () -> { if (fragmentView != null) refresh(); };
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (fragmentView == null) return;
            if (fragmentView.isShown()) refresh();
            AndroidUtilities.runOnUIThread(this, 30_000);
        }
    };

    public HabitsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        store = Nexo.store();
        if (arguments != null) hasMainTabs = arguments.getBoolean("hasMainTabs", false);
        return super.onFragmentCreate();
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean hasOwnBackground() {
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.Habits));
        if (!hasMainTabs) actionBar.setBackButtonDrawable(new BackDrawable(false));
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_CALENDAR, R.drawable.msg_calendar).setContentDescription(LocaleController.getString(R.string.HabitsCalendar));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (!hasMainTabs) finishFragment();
                } else if (id == MENU_CALENDAR) {
                    presentFragment(new HabitsCalendarActivity());
                }
            }
        });

        // The view may be recreated (theme change, hidden-fragment cleanup): forget the previous view state
        shownKey = new ArrayList<>();
        rows.clear();
        held.clear();
        doneBox = null;
        laterBox = null;
        shownBalance = null;

        root = new FrameLayout(context);
        root.setBackgroundColor(HabitUi.bg());

        LinearLayout[] c = new LinearLayout[1];
        scroll = HabitUi.page(context, c);
        content = c[0];
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(scroll);

        dateText = HabitUi.notSection(HabitUi.text(context, "", 15, HabitUi.sub()));
        dateText.setPadding(dp(6), dp(6), 0, dp(12));
        content.addView(dateText);
        content.addView(buildPointsCard(context));
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);

        fab = new FragmentFloatingButton(context, resourceProvider);
        fab.setImageResource(R.drawable.msg_add);
        fab.setContentDescription(LocaleController.getString(R.string.HabitsNew));
        fab.setOnClickListener(v -> presentFragment(new HabitEditActivity(null)));
        root.addView(fab, FragmentFloatingButton.createDefaultLayoutParams());

        confetti = new HabitUi.ConfettiView(context);
        root.addView(confetti, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = root;
        ViewCompat.setOnApplyWindowInsetsListener(root, this::applyInsets);
        applyPaddings();
        refresh();
        AndroidUtilities.cancelRunOnUIThread(ticker);
        AndroidUtilities.runOnUIThread(ticker, 30_000);
        return root;
    }

    /** Presented on the stack (not inside the tabs): ActionBarLayout routes insets here */
    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        insetTop = top;
        insetBottom = bottom;
        if (content != null) applyPaddings();
    }

    @NonNull
    private WindowInsetsCompat applyInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
        Insets i = insets.getInsets(WindowInsetsCompat.Type.navigationBars() | WindowInsetsCompat.Type.statusBars());
        insetTop = i.top;
        insetBottom = i.bottom;
        applyPaddings();
        return WindowInsetsCompat.CONSUMED;
    }

    private void applyPaddings() {
        int tabs = hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0;
        content.setPadding(dp(12), insetTop + ActionBar.getCurrentActionBarHeight() + dp(8), dp(12), insetBottom + tabs + dp(88));
        fab.setTranslationY(-insetBottom - (hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT + DialogsActivity.MAIN_TABS_MARGIN) : 0));
    }

    @Override
    public void onResume() {
        super.onResume();
        store.addListener(listener);
        PrayerTimes.addListener(prayerListener);
        Context ctx = getContext();
        if (ctx != null) {
            PrayerTimes.refreshLocation(ctx);
            AutoMarker.run(ctx, true);
            try {
                ctx.registerReceiver(PrayerTimes.providersReceiver, new IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION));
            } catch (Exception ignore) {}
            SyncService.start(ctx);
        }
        refresh();
        if (Nexo.prefs().isHabitReminder()) requestNotifications();
    }

    @Override
    public void onPause() {
        super.onPause();
        store.removeListener(listener);
        PrayerTimes.removeListener(prayerListener);
        Context ctx = getContext();
        if (ctx != null) {
            try { ctx.unregisterReceiver(PrayerTimes.providersReceiver); } catch (Exception ignore) {}
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        AndroidUtilities.cancelRunOnUIThread(ticker);
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && getParentActivity() != null
            && getParentActivity().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            getParentActivity().requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    // region Tab delegate

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return true;
    }

    @Override
    public void onParentScrollToTop() {
        if (scroll != null) scroll.smoothScrollTo(0, 0);
    }

    // endregion

    // region Points card

    private View buildPointsCard(Context ctx) {
        HabitUi.Card points = HabitUi.card(ctx);
        points.setOnClickListener(v -> openGoals());
        balanceText = HabitUi.text(ctx, "", 30, HabitUi.text(), true);
        todayPoints = HabitUi.text(ctx, "", 14, HabitUi.text(), true);
        todayPoints.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(balanceText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(todayPoints);
        points.addView(top);

        progressText = HabitUi.text(ctx, "", 14, HabitUi.sub(), true);
        progressFill = new View(ctx);
        progressFill.setBackground(HabitUi.roundRect(HabitUi.done(), HabitUi.dpf(4)));
        progressRest = new View(ctx);
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(HabitUi.roundRect(HabitUi.withAlpha(HabitUi.sub(), 0x30), HabitUi.dpf(4)));
        bar.setClipToOutline(true);
        bar.addView(progressFill, new LinearLayout.LayoutParams(0, dp(6), 0f));
        bar.addView(progressRest, new LinearLayout.LayoutParams(0, dp(6), 1f));
        LinearLayout progressRow = new LinearLayout(ctx);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progressRow.setPadding(0, dp(10), 0, dp(4));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.rightMargin = dp(10);
        progressRow.addView(progressText, plp);
        progressRow.addView(bar, new LinearLayout.LayoutParams(0, dp(6), 1f));
        points.addView(progressRow);

        View divider = new View(ctx);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        dlp.topMargin = dp(10);
        points.addView(divider, dlp);

        goalTitle = HabitUi.text(ctx, "", 15, HabitUi.text(), true);
        goalTitle.setMaxLines(2);
        goalTitle.setEllipsize(TextUtils.TruncateAt.END);
        goalNumbers = HabitUi.text(ctx, "", 14, HabitUi.sub());
        LinearLayout goalRow = new LinearLayout(ctx);
        goalRow.setOrientation(LinearLayout.HORIZONTAL);
        goalRow.setGravity(Gravity.CENTER_VERTICAL);
        goalRow.setPadding(0, dp(10), 0, 0);
        goalRow.addView(goalTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        goalRow.addView(goalNumbers);
        points.addView(goalRow);

        goalFill = new View(ctx);
        goalFill.setBackground(HabitUi.roundRect(HabitUi.fire(), HabitUi.dpf(6)));
        goalRest = new View(ctx);
        goalBar = new LinearLayout(ctx);
        goalBar.setOrientation(LinearLayout.HORIZONTAL);
        goalBar.setBackground(HabitUi.roundRect(HabitUi.withAlpha(HabitUi.sub(), 0x30), HabitUi.dpf(6)));
        goalBar.setClipToOutline(true);
        goalBar.addView(goalFill, new LinearLayout.LayoutParams(0, dp(10), 0f));
        goalBar.addView(goalRest, new LinearLayout.LayoutParams(0, dp(10), 1f));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        glp.topMargin = dp(8);
        points.addView(goalBar, glp);

        goalHint = HabitUi.text(ctx, "", 14, HabitUi.sub());
        goalHint.setPadding(0, dp(8), 0, 0);
        points.addView(goalHint);

        claimButton = HabitUi.text(ctx, "", 16, HabitUi.white(), true);
        claimButton.setGravity(Gravity.CENTER);
        claimButton.setMinHeight(dp(46));
        claimButton.setBackground(HabitUi.roundRect(HabitUi.fire(), HabitUi.dpf(12)));
        claimButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        points.addView(claimButton, clp);
        return points;
    }

    private void openGoals() {
        presentFragment(new HabitGoalsActivity());
    }

    // endregion

    // region Helpers

    private TextView circle(String label, int size) {
        TextView t = HabitUi.text(getContext(), label, size >= 48 ? 20 : 16, HabitUi.sub(), true);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
        lp.leftMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private long[] nextAt(Habit h, LocalDate today) {
        return nextAt(store, h, today);
    }

    /** Today's habits that are due right now and not marked yet — the tab badge */
    public static int activeCount(HabitStore store) {
        LocalDate today = HabitStore.today();
        long now = System.currentTimeMillis();
        int n = 0;
        for (Habit h : store.dueOn(today)) {
            if (h.auto != null || store.status(h.id, today) != Status.NONE) continue;
            long[] next = nextAt(store, h, today);
            if (next == null || now >= next[0] - LEAD_MIN * 60_000L) n++;
        }
        return n;
    }

    /** {at (epoch ms), guess (1 = estimated from rhythm)} or null — unknown, always shown */
    private static long[] nextAt(HabitStore store, Habit h, LocalDate today) {
        Repeat r = h.repeat;
        if (r.slotted()) {
            for (String t : r.times) {
                if (store.slotStatus(h.id, today, t) == Status.NONE) {
                    Long at = at(today, t);
                    return at == null ? null : new long[]{at, 0};
                }
            }
            return null;
        }
        if (r.times.size() == 1) {
            Long at = at(today, r.times.get(0));
            return at == null ? null : new long[]{at, 0};
        }
        // Automatic habit is active all day: keep the live counter visible
        if (h.auto != null) return null;
        HabitStore.Rhythm rh = store.rhythm(h.id);
        return rh == null ? null : new long[]{rh.nextAt(), 1};
    }

    private static Long at(LocalDate today, String t) {
        Integer m = Repeat.resolve(t, today);
        if (m == null) return null;
        return today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + m * 60_000L;
    }

    /** "~21:30", "tomorrow ~09:00", "Mon, 12 Oct ~22:00" */
    private String whenLabel(long at, boolean guess) {
        ZonedDateTime dt = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault());
        String time = (guess ? "~" : "") + Repeat.hhmm(dt.getHour() * 60 + dt.getMinute());
        LocalDate day = dt.toLocalDate();
        return day.equals(HabitStore.today()) ? time : Dates.shortDay(day, HabitStore.today()) + " " + time;
    }

    private Section sectionOf(Habit h, LocalDate today) {
        Object[] hold = held.get(h.id);
        if (hold != null) {
            if (System.currentTimeMillis() < (Long) hold[1]) return (Section) hold[0];
            held.remove(h.id);
        }
        if (store.status(h.id, today) != Status.NONE) return Section.DONE;
        long[] next = nextAt(h, today);
        if (next == null) return Section.ACTIVE;
        return System.currentTimeMillis() >= next[0] - LEAD_MIN * 60_000L ? Section.ACTIVE : Section.LATER;
    }

    /** Live state: "📱 YouTube: 42 / 60 min", "😴 23:40 → 07:05 · 7 h 25 min" */
    private String autoLine(Auto au, LocalDate today) {
        Context ctx = getContext();
        if (au.needsUsage() && !ScreenTime.hasAccess(ctx)) return "⚠️ No screen-time access (Settings)";
        switch (au.kind) {
            case Auto.UNLOCKS:
                return "🔓 " + ScreenTime.unlocks(ctx, today) + " / " + au.minutes + " unlocks";
            case Auto.STEPS: {
                if (au.fromHealth()) {
                    Health.Day d = Health.day(today);
                    return d != null && d.steps != null ? "⌚ " + Auto.num(d.steps) + " / " + Auto.num(au.minutes) + " steps" : au.describe() + " · from watch";
                }
                if (!Steps.hasPermission(ctx)) return "⚠️ No physical-activity permission";
                return "👟 " + Auto.num(Steps.on(today)) + " / " + Auto.num(au.minutes) + " steps";
            }
            case Auto.MORNING:
                return au.describe();
            case Auto.WORKOUT: {
                Health.Day d = Health.day(today);
                return d != null && d.exercise != null ? "🏋️ " + Auto.duration(d.exercise) + " / " + Auto.duration(au.minutes) : au.describe();
            }
            case Auto.PLACE:
                return !Places.hasPermission(ctx) ? "⚠️ No location permission" : au.describe();
            case Auto.CALL:
                return !Calls.hasPermission(ctx) ? "⚠️ No call-log permission" : au.describe();
            case Auto.APP: {
                org.telegram.messenger.nexo.AppUsage u = au.isTotal() ? ScreenTime.total(ctx, today) : ScreenTime.usage(ctx, au.pkg, today);
                if (Auto.LIMIT.equals(au.mode)) {
                    return "📱 " + (au.isTotal() ? "Screen time" : au.app) + ": " + Auto.duration(u.minutes) + " / " + Auto.duration(au.minutes);
                }
                if (Auto.AVOID.equals(au.mode)) {
                    if (au.hasWindow()) {
                        Integer st = AutoRules.app(au, u, false, ScreenTime.dayStart(today));
                        return "🚫 " + au.app + " " + au.from + "–" + au.to + ": " + (st != null && st == Status.SKIPPED ? "opened in the window" : "clean");
                    }
                    return "🚫 " + au.app + ": " + (u.opened ? "opened" : "not opened today");
                }
                if (au.minutes > 0) return "📱 " + au.app + ": " + Auto.duration(u.minutes) + " / " + Auto.duration(au.minutes);
                return "📱 " + au.app + ": " + (u.opened ? "opened ✓" : "not opened yet");
            }
            case Auto.SLEEP: {
                Sleep s;
                if (au.fromHealth()) {
                    Health.Day d = Health.day(today);
                    s = d == null ? null : d.sleep;
                } else {
                    s = ScreenTime.sleep(ctx, today);
                }
                if (s == null) return au.describe();
                return "😴 " + hm(s.start) + " → " + hm(s.end) + " · " + Auto.duration(s.minutes());
            }
            default:
                return au.describe();
        }
    }

    private static String hm(long ms) {
        ZonedDateTime z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault());
        return Repeat.hhmm(z.getHour() * 60 + z.getMinute());
    }

    private static String fmt(int n) {
        return Auto.num(n);
    }

    // endregion

    // region Row

    /** One habit row. Views are reused — only bind() is called, so animations are not interrupted. */
    private class Row {
        Habit habit;
        final HabitUi.Card view;
        final TextView icon, name, info, badge, sub, skip, done;
        final LinearLayout dots;
        final View[] dotViews = new View[7];
        final HabitUi.FlowLayout slots;
        final LinkedHashMap<String, TextView> slotViews = new LinkedHashMap<>();

        Row(Habit habit) {
            this.habit = habit;
            Context ctx = getContext();
            view = HabitUi.card(ctx);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(12), dp(12), dp(12), dp(12));
            icon = HabitUi.text(ctx, "", 22, HabitUi.text());
            icon.setGravity(Gravity.CENTER);
            name = HabitUi.text(ctx, "", 16, HabitUi.text(), true);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            dots = new LinearLayout(ctx);
            dots.setOrientation(LinearLayout.HORIZONTAL);
            dots.setGravity(Gravity.CENTER_VERTICAL);
            for (int i = 0; i < 7; i++) dotViews[i] = new View(ctx);
            info = HabitUi.text(ctx, "", 13, HabitUi.sub(), true);
            info.setMaxLines(1);
            info.setEllipsize(TextUtils.TruncateAt.END);
            badge = HabitUi.text(ctx, "", 11, HabitUi.text(), true);
            badge.setPadding(dp(7), dp(2), dp(7), dp(2));
            badge.setMaxLines(1);
            sub = HabitUi.text(ctx, "", 13, HabitUi.sub());
            sub.setMaxLines(1);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            slots = new HabitUi.FlowLayout(ctx, dp(6));
            skip = circle("✕", 40);
            done = circle("✓", 48);

            view.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));
            LinearLayout mid = new LinearLayout(ctx);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(dp(12), 0, dp(4), 0);
            mid.addView(name);
            mid.addView(sub);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.rightMargin = dp(8);
            dots.addView(badge, blp);
            for (View d : dotViews) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(9), dp(9));
                lp.rightMargin = dp(4);
                dots.addView(d, lp);
            }
            dots.addView(info);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = dp(6);
            mid.addView(dots, dlp);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(8);
            mid.addView(slots, slp);
            view.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            view.addView(skip);
            view.addView(done);
            view.setOnClickListener(v -> presentFragment(new HabitDetailActivity(this.habit.id)));
            done.setOnClickListener(v -> tap(Status.DONE));
            skip.setOnClickListener(v -> tap(Status.SKIPPED));
        }

        void bind() {
            Habit h = habit;
            Repeat r = h.repeat;
            LocalDate today = HabitStore.today();
            int s = store.status(h.id, today);
            icon.setText(h.emoji);
            Cat cat = store.cat(h.cat);
            icon.setBackground(HabitUi.oval(HabitUi.withAlpha(cat != null ? cat.colorInt() : HabitUi.sub(), 0x2E)));
            badge.setVisibility(cat != null ? View.VISIBLE : View.GONE);
            if (cat != null) {
                badge.setText(cat.emoji + " " + cat.name);
                badge.setTextColor(cat.colorInt());
                badge.setBackground(HabitUi.roundRect(HabitUi.withAlpha(cat.colorInt(), 0x24), HabitUi.dpf(8)));
            }
            name.setText(h.name);

            // Last 7 occurrences
            List<LocalDate> recent = h.daily() ? HabitUi.recentDue(store, h, 7, today) : Collections.emptyList();
            for (int i = 0; i < 7; i++) {
                int idx = i - (7 - recent.size());
                LocalDate d = idx >= 0 && idx < recent.size() ? recent.get(idx) : null;
                dotViews[i].setVisibility(d != null ? View.VISIBLE : View.GONE);
                if (d != null) {
                    Integer f = HabitUi.statusFill(store.status(h.id, d));
                    dotViews[i].setBackground(HabitUi.oval(f != null ? f : HabitUi.withAlpha(HabitUi.sub(), 0x40)));
                }
            }
            // Under the name: schedule (not shown for every day) and a single time; multi-time habits use pills
            ArrayList<String> subParts = new ArrayList<>();
            if (h.daily() && !r.slotted() && (!Repeat.DAILY.equals(r.freq) || r.every > 1)) subParts.add(r.describeBase());
            if (r.times.size() == 1) {
                String t = r.times.get(0);
                if (Repeat.isPrayer(t)) {
                    Integer m = Repeat.resolve(t, today);
                    subParts.add(Repeat.label(t) + (m != null ? " · " + Repeat.hhmm(m) : ""));
                } else {
                    subParts.add("⏰ " + t);
                }
            }
            // Multi-time: "1 / 2 · next 20:00" — ✓ / ✕ apply to that time
            if (r.slotted()) {
                int[] p = store.progress(h.id, today);
                String next = null;
                for (String t : r.times) if (store.slotStatus(h.id, today, t) == Status.NONE) { next = t; break; }
                subParts.add(p[0] + " / " + p[1] + (next != null ? " · next " + Repeat.shortLabel(next, today) : ""));
            }
            if (h.auto != null) subParts.add(autoLine(h.auto, today));
            // Rhythm: "every ~25 h · next tomorrow ~23:00"
            if (r.times.isEmpty() && h.auto == null) {
                HabitStore.Rhythm rh = store.rhythm(h.id);
                if (rh != null) {
                    long hours = (rh.intervalMs + 30 * 60_000L) / 3_600_000L;
                    String every = (hours >= 20 && hours <= 28 && h.daily()) ? "every day" : (hours >= 48 && hours % 24 < 3) ? "every ~" + (hours / 24) + " days" : "every ~" + hours + " h";
                    if (rh.nextAt() > System.currentTimeMillis()) {
                        subParts.add(every + " · next " + whenLabel(rh.nextAt(), true));
                    } else if (h.daily()) {
                        ZonedDateTime z = Instant.ofEpochMilli(rh.nextAt()).atZone(ZoneId.systemDefault());
                        subParts.add(every + " · usually ~" + Repeat.hhmm(z.getHour() * 60 + z.getMinute()));
                    } else {
                        subParts.add(every + " · due now");
                    }
                }
            }
            sub.setText(TextUtils.join("  ·  ", subParts));
            sub.setVisibility(subParts.isEmpty() ? View.GONE : View.VISIBLE);
            if (h.daily()) {
                int streak = store.streak(h.id);
                info.setText(streak > 0 ? "🔥 " + streak : "");
                info.setTextColor(HabitUi.fire());
            } else {
                // Unscheduled: total and how far to the next celebration
                int count = store.doneDates(h.id).size();
                Integer next = null;
                for (int m : HabitStore.COUNT_MILESTONES) if (m > count) { next = m; break; }
                if (count == 0) info.setText("First one — 🎉");
                else if (next != null) info.setText(count + " times · 🎉 " + (next - count) + " to " + next);
                else info.setText(count + " times");
                info.setTextColor(HabitUi.sub());
            }
            info.setPadding(recent.isEmpty() ? 0 : dp(4), 0, 0, 0);

            // Multi-time: pills (status; tap — that time), ✓ / ✕ — the next time
            boolean slotted = r.slotted();
            slots.setVisibility(slotted ? View.VISIBLE : View.GONE);
            if (slotted) {
                if (!new ArrayList<>(slotViews.keySet()).equals(r.times)) {
                    slots.removeAllViews();
                    slotViews.clear();
                    for (String t : r.times) {
                        TextView chip = HabitUi.text(getContext(), t, 14, HabitUi.text(), true);
                        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
                        chip.setOnClickListener(v -> tapSlot(t, Status.DONE, chip));
                        chip.setOnLongClickListener(v -> {
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            tapSlot(t, Status.SKIPPED, chip);
                            return true;
                        });
                        slotViews.put(t, chip);
                        slots.addView(chip);
                    }
                }
                for (java.util.Map.Entry<String, TextView> e : slotViews.entrySet()) {
                    int st = store.slotStatus(h.id, today, e.getKey());
                    Integer color = st == Status.DONE ? Integer.valueOf(HabitUi.done()) : st == Status.SKIPPED ? Integer.valueOf(HabitUi.skip()) : null;
                    String label = Repeat.shortLabel(e.getKey(), today);
                    e.getValue().setText(st == Status.DONE ? "✓ " + label : st == Status.SKIPPED ? "✕ " + label : label);
                    e.getValue().setTextColor(color != null ? HabitUi.white() : HabitUi.text());
                    e.getValue().setBackground(color != null ? HabitUi.roundRect(color, HabitUi.dpf(16)) : HabitUi.roundRect(0, HabitUi.dpf(16), HabitUi.line(), dp(2)));
                }
            }
            skip.setVisibility(h.daily() ? View.VISIBLE : View.GONE);
            done.setVisibility(View.VISIBLE);
            done.setText(h.daily() || s == Status.DONE ? "✓" : "+");
            done.setBackground(s == Status.DONE ? HabitUi.oval(HabitUi.done()) : HabitUi.oval(0, HabitUi.line(), dp(2)));
            done.setTextColor(s == Status.DONE ? HabitUi.white() : HabitUi.sub());
            skip.setBackground(s == Status.SKIPPED ? HabitUi.oval(HabitUi.skip()) : HabitUi.oval(0, HabitUi.line(), dp(2)));
            skip.setTextColor(s == Status.SKIPPED ? HabitUi.white() : HabitUi.sub());
            view.setAlpha(s == Status.SKIPPED ? 0.75f : 1f);
        }

        private void tap(int target) {
            LocalDate today = HabitStore.today();
            View button = target == Status.DONE ? done : skip;
            HabitUi.haptic(button);
            List<String> times = habit.repeat.times;
            if (habit.repeat.slotted()) {
                // The next (first unmarked) time; all marked — undo the last one
                String slot = null;
                for (String t : times) if (store.slotStatus(habit.id, today, t) == Status.NONE) { slot = t; break; }
                if (slot != null) {
                    after(button, target, store.setSlot(habit.id, today, slot, target));
                } else {
                    String last = null;
                    for (String t : times) if (store.slotStatus(habit.id, today, t) != Status.NONE) last = t;
                    if (last != null) after(button, Status.NONE, store.setSlot(habit.id, today, last, Status.NONE));
                }
                return;
            }
            int next = store.status(habit.id, today) == target ? Status.NONE : target;
            after(button, next, store.setStatus(habit.id, today, next));
        }

        /** Tap — ✓/undo, hold — ✕/undo */
        private void tapSlot(String time, int target, View chip) {
            LocalDate today = HabitStore.today();
            int next = store.slotStatus(habit.id, today, time) == target ? Status.NONE : target;
            if (target == Status.DONE) HabitUi.haptic(chip);
            after(chip, next, store.setSlot(habit.id, today, time, next));
        }

        private void after(View button, int next, MarkResult result) {
            hold(habit.id, view);
            boolean wasAllDone = allDone();
            bind();
            refreshProgress();
            HabitUi.bounce(button);
            floatPoints(button, result.points);
            if (next != Status.DONE) return;
            confetti.burstAt(button);
            if (result.milestone != null || result.goalReached != null) {
                HabitUi.celebrate(HabitsActivity.this, confetti, habit, result);
            } else if (!wasAllDone && allDone()) {
                confetti.burst(0, 0, true);
                HabitUi.toastSuccess(HabitsActivity.this, "🎉 Everything done for today!");
            }
        }
    }

    /** {done, total} — multi-time habits count every time */
    private int[] progressCounts() {
        LocalDate today = HabitStore.today();
        int done = 0, total = 0;
        for (Habit h : store.dueOn(today)) {
            int[] p = store.progress(h.id, today);
            done += p[0];
            total += p[1];
        }
        return new int[]{done, total};
    }

    private boolean allDone() {
        int[] p = progressCounts();
        return p[1] > 0 && p[0] == p[1];
    }

    /** Keeps the row in place for a moment, then fades it out and moves it to its section */
    private void hold(String id, View rowView) {
        LocalDate today = HabitStore.today();
        Section current;
        Object[] existing = held.get(id);
        if (existing != null) {
            current = (Section) existing[0];
        } else {
            if (store.habit(id) == null) return;
            // Section before the tap: where the row was
            current = rowView.getParent() == doneBox ? Section.DONE : rowView.getParent() == laterBox ? Section.LATER : Section.ACTIVE;
        }
        held.put(id, new Object[]{current, System.currentTimeMillis() + HOLD_MS});
        final Section from = current;
        AndroidUtilities.runOnUIThread(() -> {
            held.remove(id);
            Habit h = store.habit(id);
            if (h != null && sectionOf(h, today) != from) {
                rowView.animate().alpha(0f).translationX(rowView.getWidth() * 0.15f).setDuration(220).withEndAction(this::refresh).start();
            } else {
                refresh();
            }
        }, HOLD_MS);
    }

    // endregion

    // region Refresh

    private void refresh() {
        if (fragmentView == null || getContext() == null) return;
        LocalDate today = HabitStore.today();
        dateText.setText(Dates.longDate(today));
        List<Habit> due = store.dueOn(today);
        ArrayList<Habit> free = new ArrayList<>();
        ArrayList<Habit> otherDays = new ArrayList<>();
        for (Habit h : store.active()) {
            if (!h.daily() && !h.once()) free.add(h);
            if (h.daily() && !due.contains(h)) otherDays.add(h);
        }
        List<Habit> tasks = store.openTasks(today);
        ArrayList<Habit> all = new ArrayList<>(due);
        all.addAll(free);
        // Automatic ones (marked by themselves) — in their own collapsed group
        ArrayList<Habit> autos = new ArrayList<>();
        ArrayList<Habit> active = new ArrayList<>(), later = new ArrayList<>(), done = new ArrayList<>();
        for (Habit h : all) {
            if (h.auto != null) { autos.add(h); continue; }
            Section s = sectionOf(h, today);
            if (s == Section.ACTIVE) active.add(h);
            else if (s == Section.LATER) later.add(h);
            else done.add(h);
        }
        Collections.sort(later, (a, b) -> {
            long[] na = nextAt(a, today), nb = nextAt(b, today);
            return Long.compare(na == null ? 0 : na[0], nb == null ? 0 : nb[0]);
        });
        ArrayList<Object> key = new ArrayList<>();
        key.add(today);
        key.add(ids(active)); key.add(ids(later)); key.add(ids(done)); key.add(ids(otherDays));
        ArrayList<String> catKeys = new ArrayList<>();
        for (Habit h : all) { Cat c = store.cat(h.cat); catKeys.add(c == null ? "" : c.id + c.name + c.emoji + c.color); }
        key.add(catKeys);
        key.add(laterOpen); key.add(doneOpen); key.add(autoOpen); key.add(otherOpen);
        key.add(ids(autos));
        ArrayList<String> taskKeys = new ArrayList<>();
        for (Habit h : tasks) taskKeys.add(h.id + h.u + store.completedOn(h.id));
        key.add(taskKeys);
        // Rebuild when the day, section contents or categories change; otherwise only re-bind
        if (!key.equals(shownKey)) {
            shownKey = key;
            rebuild(active, later, done, otherDays, tasks, autos);
        } else {
            for (Habit h : all) {
                Row row = rows.get(h.id);
                if (row != null) { row.habit = h; row.bind(); }
            }
        }
        refreshProgress();
    }

    private static List<String> ids(List<Habit> list) {
        ArrayList<String> out = new ArrayList<>();
        for (Habit h : list) out.add(h.id);
        return out;
    }

    private void rebuild(List<Habit> active, List<Habit> later, List<Habit> done, List<Habit> otherDays, List<Habit> tasks, List<Habit> autos) {
        Context ctx = getContext();
        list.removeAllViews();
        rows.clear();
        doneBox = null;
        laterBox = null;
        if (active.isEmpty() && later.isEmpty() && done.isEmpty() && otherDays.isEmpty() && tasks.isEmpty() && autos.isEmpty()) {
            TextView empty = HabitUi.notSection(HabitUi.text(ctx, LocaleController.getString(R.string.HabitsEmpty), 16, HabitUi.sub()));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(48), 0, 0);
            list.addView(empty);
            return;
        }
        LocalDate today = HabitStore.today();
        // The points card above is its own island
        list.addView(HabitUi.spacer(ctx, 12));
        // Due now — categories mixed, by urgency (category is a badge on the row)
        ArrayList<Habit> sortedActive = new ArrayList<>(active);
        Collections.sort(sortedActive, urgency(today));
        for (Habit h : sortedActive) addRow(list, row(h));
        if (active.isEmpty() && (!later.isEmpty() || !done.isEmpty())) {
            String msg;
            if (!later.isEmpty()) {
                Habit next = later.get(0);
                long[] at = nextAt(next, today);
                msg = "✨ All clear for now.\nNext: " + next.emoji + " " + next.name + (at != null ? " · " + whenLabel(at[0], at[1] == 1) : "");
            } else {
                msg = "✨ Today's plan is done.";
            }
            HabitUi.Card card = HabitUi.card(ctx);
            TextView t = HabitUi.text(ctx, msg, 15, HabitUi.sub());
            t.setGravity(Gravity.CENTER);
            card.addView(t);
            addRow(list, card);
        }
        if (!tasks.isEmpty()) {
            list.addView(HabitUi.section(ctx, "🏔  ONE-TIME TASKS"));
            ArrayList<Habit> sorted = new ArrayList<>(tasks);
            // Near deadlines first, no deadline last
            Collections.sort(sorted, (a, b) -> {
                boolean da = store.completedOn(a.id) != null, db = store.completedOn(b.id) != null;
                if (da != db) return da ? 1 : -1;
                String ka = a.repeat.deadline.isEmpty() ? "9999" : a.repeat.deadline;
                String kb = b.repeat.deadline.isEmpty() ? "9999" : b.repeat.deadline;
                int c = ka.compareTo(kb);
                return c != 0 ? c : Integer.compare(a.order, b.order);
            });
            for (Habit h : sorted) addRow(list, taskCard(h));
        }
        if (!later.isEmpty()) {
            laterBox = collapsible("LATER · " + later.size(), laterOpen, v -> laterOpen = v, box -> { for (Habit h : later) addRow(box, row(h)); });
        }
        if (!done.isEmpty()) {
            doneBox = collapsible("DONE · " + done.size(), doneOpen, v -> doneOpen = v, box -> { for (Habit h : done) addRow(box, row(h)); });
        }
        if (!autos.isEmpty()) {
            int ok = 0, bad = 0;
            for (Habit h : autos) {
                int s = store.status(h.id, today);
                if (s == Status.DONE) ok++;
                else if (s == Status.SKIPPED) bad++;
            }
            String title = "⚙️ AUTOMATIC · " + autos.size() + (ok > 0 ? "  ✓" + ok : "") + (bad > 0 ? "  ✕" + bad : "");
            ArrayList<Habit> sorted = new ArrayList<>(autos);
            Collections.sort(sorted, (a, b) -> {
                boolean ma = store.status(a.id, today) != Status.NONE, mb = store.status(b.id, today) != Status.NONE;
                if (ma != mb) return ma ? 1 : -1;
                return Integer.compare(a.order, b.order);
            });
            collapsible(title, autoOpen, v -> autoOpen = v, box -> { for (Habit h : sorted) addRow(box, row(h)); });
        }
        if (!otherDays.isEmpty()) {
            ArrayList<Habit> sorted = new ArrayList<>(otherDays);
            Collections.sort(sorted, (a, b) -> {
                LocalDate na = store.nextDue(a), nb = store.nextDue(b);
                if (na == null) return nb == null ? 0 : 1;
                if (nb == null) return -1;
                return na.compareTo(nb);
            });
            collapsible("OTHER DAYS · " + otherDays.size(), otherOpen, v -> otherOpen = v, box -> { for (Habit h : sorted) addRow(box, laterRow(h)); });
        }
    }

    /** Adds a row to an island; consecutive rows are separated by a thin divider */
    private void addRow(LinearLayout parent, View v) {
        if (parent.getChildCount() > 0 && parent.getChildAt(parent.getChildCount() - 1).getTag() == null) {
            parent.addView(HabitUi.divider(getContext(), 70));
        }
        parent.addView(v);
    }

    /** One-time task: name, deadline, measure progress and ✓. Measured tasks ask for the value when tapped. */
    private View taskCard(Habit h) {
        Context ctx = getContext();
        LocalDate today = HabitStore.today();
        LocalDate doneOn = store.completedOn(h.id);
        HabitUi.Card card = HabitUi.card(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        Cat cat = store.cat(h.cat);
        int catColor = cat != null ? cat.colorInt() : HabitUi.sub();
        TextView emoji = HabitUi.text(ctx, h.emoji, 22, HabitUi.text());
        emoji.setGravity(Gravity.CENTER);
        emoji.setBackground(HabitUi.oval(HabitUi.withAlpha(catColor, 0x2E)));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(44), dp(44));
        elp.rightMargin = dp(12);
        card.addView(emoji, elp);
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView nameView = HabitUi.text(ctx, h.name, 16, HabitUi.text(), true);
        nameView.setMaxLines(2);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(nameView);
        String dl = h.repeat.deadline;
        LocalDate dlDate = Dates.parse(dl);
        boolean overdue = !dl.isEmpty() && doneOn == null && dlDate != null && dlDate.isBefore(today);
        String subText = doneOn != null ? "🏆 Done · +" + h.plus + " ⭐" : !dl.isEmpty() ? "📅 " + Dates.shortDate(dl) + " · " + Dates.left(dl, today) : "+" + h.plus + " ⭐";
        TextView subView = HabitUi.text(ctx, subText, 13, overdue ? HabitUi.skip() : HabitUi.sub(), overdue);
        subView.setPadding(0, dp(2), 0, 0);
        body.addView(subView);
        if (h.measure != null) {
            Measure m = h.measure;
            TextView mv = HabitUi.text(ctx, Measure.fmt(m.from) + " → " + Measure.fmt(m.now) + " / " + Measure.fmt(m.to) + " " + m.unit, 13, HabitUi.text());
            mv.setPadding(0, dp(6), 0, dp(4));
            body.addView(mv);
            LinearLayout bar = new LinearLayout(ctx);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setBackground(HabitUi.roundRect(HabitUi.withAlpha(HabitUi.sub(), 0x30), HabitUi.dpf(4)));
            bar.setClipToOutline(true);
            float p = (float) m.progress();
            View fill = new View(ctx);
            fill.setBackground(HabitUi.roundRect(HabitUi.done(), HabitUi.dpf(4)));
            bar.addView(fill, new LinearLayout.LayoutParams(0, dp(6), Math.max(0.001f, p)));
            bar.addView(new View(ctx), new LinearLayout.LayoutParams(0, dp(6), Math.max(0.001f, 1 - p)));
            body.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
        }
        card.addView(body, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView check = HabitUi.text(ctx, "✓", 22, doneOn != null ? HabitUi.white() : HabitUi.sub(), true);
        check.setGravity(Gravity.CENTER);
        check.setBackground(doneOn != null ? HabitUi.oval(HabitUi.done()) : HabitUi.oval(0, HabitUi.line(), dp(2)));
        check.setOnClickListener(v -> {
            if (doneOn != null) {
                HabitUi.confirm(this, null, "Mark \"" + h.name + "\" as not done?", LocaleController.getString(R.string.OK), false, () -> store.setStatus(h.id, doneOn, Status.NONE));
                return;
            }
            HabitUi.haptic(check);
            taskDone(h, check, store.setStatus(h.id, today, Status.DONE));
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(52), dp(52));
        clp.leftMargin = dp(10);
        card.addView(check, clp);
        card.setOnClickListener(v -> {
            if (h.measure != null && doneOn == null) measureDialog(h, check);
            else presentFragment(new HabitDetailActivity(h.id));
        });
        return card;
    }

    private void taskDone(Habit h, View from, MarkResult r) {
        HabitUi.bounce(from);
        floatPoints(from, r.points);
        confetti.burstAt(from);
        confetti.burst(0, 0, true);
        if (r.goalReached != null) HabitUi.celebrate(this, confetti, h, r);
        else HabitUi.toastSuccess(this, "🏆 " + h.name + " — done!");
    }

    /** Current value (weight, distance…) */
    private void measureDialog(Habit h, View check) {
        Measure m = h.measure;
        if (m == null) return;
        HabitUi.textDialog(this, h.emoji + " Now, " + m.unit, "Start: " + Measure.fmt(m.from) + " · goal: " + Measure.fmt(m.to) + " " + m.unit,
            Measure.fmt(m.now), m.unit, true, s -> {
                try {
                    double v = Double.parseDouble(s.replace(',', '.'));
                    MarkResult r = store.setMeasure(h.id, v);
                    if (r != null) taskDone(h, check, r);
                } catch (Exception ignore) {}
            });
    }

    /**
     * Order: 1) exact times (pills, prayers) — nearest/overdue first; 2) rhythm-based due; 3) today's untimed;
     * 4) automatic; 5) optional (unscheduled).
     */
    private java.util.Comparator<Habit> urgency(LocalDate today) {
        HashMap<String, Integer> order = new HashMap<>();
        List<Cat> cs = store.cats();
        for (int i = 0; i < cs.size(); i++) order.put(cs.get(i).id, i);
        return (a, b) -> {
            long[] ra = rank(a, today), rb = rank(b, today);
            if (ra[0] != rb[0]) return Long.compare(ra[0], rb[0]);
            if (ra[1] != rb[1]) return Long.compare(ra[1], rb[1]);
            Integer oa = order.get(a.cat), ob = order.get(b.cat);
            int ia = oa == null ? Integer.MAX_VALUE : oa, ib = ob == null ? Integer.MAX_VALUE : ob;
            if (ia != ib) return Integer.compare(ia, ib);
            return Integer.compare(a.order, b.order);
        };
    }

    private long[] rank(Habit h, LocalDate today) {
        long[] next = nextAt(h, today);
        if (next != null && next[1] == 0) return new long[]{0, next[0]};
        if (next != null && h.daily()) return new long[]{1, next[0]};
        if (h.auto != null) return new long[]{3, Long.MAX_VALUE};
        if (h.daily()) return new long[]{2, Long.MAX_VALUE};
        if (next != null) return new long[]{4, next[0]};
        return new long[]{5, Long.MAX_VALUE};
    }

    private View row(Habit h) {
        Row row = new Row(h);
        rows.put(h.id, row);
        row.bind();
        return row.view;
    }

    private interface SetOpen { void set(boolean open); }
    private interface Fill { void fill(LinearLayout box); }

    /** Collapsible section; the rows inside are full rows — marks can be undone there too */
    private LinearLayout collapsible(String title, boolean open, SetOpen setOpen, Fill fill) {
        Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setVisibility(open ? View.VISIBLE : View.GONE);
        TextView header = HabitUi.section(ctx, title + "  " + (open ? "▴" : "▾"));
        header.setOnClickListener(v -> {
            HabitUi.haptic(v);
            setOpen.set(!open);
            refresh();
        });
        list.addView(header);
        fill.fill(box);
        list.addView(box);
        return box;
    }

    private View laterRow(Habit h) {
        Context ctx = getContext();
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(16), dp(12), dp(16), dp(12));
        l.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        l.addView(HabitUi.text(ctx, h.emoji + "  " + h.name, 15, HabitUi.text()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LocalDate next = store.nextDue(h);
        l.addView(HabitUi.text(ctx, next != null ? "next: " + Dates.shortDay(next, HabitStore.today()) : h.repeat.describe(), 13, HabitUi.sub()));
        l.setOnClickListener(v -> presentFragment(new HabitDetailActivity(h.id)));
        return l;
    }

    /** Points per day if everything is done (unscheduled habits — by their pace over the last 30 days) */
    private double expectedPerDay() {
        LocalDate today = HabitStore.today();
        List<Habit> habits = store.active();
        ArrayList<Points.Plan> plans = new ArrayList<>();
        for (Habit h : habits) plans.add(new Points.Plan(h.repeat, store.startOf(h), h.plus));
        double planned = Points.expectedDaily(plans, today);
        double free = 0;
        for (Habit h : habits) {
            if (h.daily() || h.once()) continue;
            int n = 0;
            for (LocalDate d : store.doneDates(h.id)) if (!d.isBefore(today.minusDays(29))) n++;
            free += n * h.plus;
        }
        return planned + free / 30.0;
    }

    private void refreshPoints() {
        LocalDate today = HabitStore.today();
        int balance = store.balance();
        animateBalance(balance);
        int todayEarned = store.earned(today, today);
        todayPoints.setText((todayEarned >= 0 ? "+" : "") + todayEarned + " today");
        todayPoints.setTextColor(todayEarned >= 0 ? HabitUi.done() : HabitUi.skip());
        todayPoints.setBackground(HabitUi.roundRect(HabitUi.withAlpha(todayEarned >= 0 ? HabitUi.done() : HabitUi.skip(), 0x22), HabitUi.dpf(12)));
        Goal goal = store.currentGoal();
        claimButton.setVisibility(View.GONE);
        if (goal == null) {
            goalTitle.setText("🎯 Set a goal");
            goalTitle.setTextColor(HabitUi.accent());
            goalNumbers.setText("›");
            goalBar.setVisibility(View.GONE);
            goalHint.setText("What are you collecting points for? E.g. 150 points → new headphones.");
            return;
        }
        goalTitle.setTextColor(HabitUi.text());
        goalTitle.setText(goal.emoji + " " + goal.title);
        goalNumbers.setText(fmt(Math.max(0, balance)) + " / " + fmt(goal.target));
        goalBar.setVisibility(View.VISIBLE);
        int done = Math.max(0, Math.min(goal.target, balance));
        ((LinearLayout.LayoutParams) goalFill.getLayoutParams()).weight = done;
        ((LinearLayout.LayoutParams) goalRest.getLayoutParams()).weight = Math.max(done == 0 ? 1f : 0f, goal.target - done);
        goalFill.requestLayout();
        int remaining = goal.target - balance;
        if (remaining <= 0) {
            goalHint.setText("🎉 Goal reached!");
            claimButton.setText("🎁  Claim reward");
            claimButton.setVisibility(View.VISIBLE);
            claimButton.setOnClickListener(v -> claim(goal));
            return;
        }
        // When: if everything is done, and at the pace of the last 14 days; today's unfinished ones count too
        int todayLeft = 0;
        for (Habit h : store.dueOn(today)) {
            int[] p = store.progress(h.id, today);
            if (store.status(h.id, today) != Status.SKIPPED) todayLeft += (p[1] - p[0]) * h.plus;
        }
        String ideal = Points.etaLabel(Points.etaWithToday(remaining, todayLeft, expectedPerDay()));
        double pace = store.earned(today.minusDays(13), today) / 14.0;
        Integer paceDays = Points.etaDays(remaining, pace);
        goalHint.setText(fmt(remaining) + " more points — " + ideal + " if you do everything" + (paceDays != null ? " · at the current pace " + Points.etaLabel(paceDays) : ""));
    }

    /** The balance counts up/down */
    private void animateBalance(int target) {
        Integer from = shownBalance;
        shownBalance = target;
        if (from == null || from == target) {
            balanceText.setText("⭐ " + fmt(target));
            return;
        }
        ValueAnimator a = ValueAnimator.ofInt(from, target);
        a.setDuration(600);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(an -> balanceText.setText("⭐ " + fmt((Integer) an.getAnimatedValue())));
        a.start();
    }

    private void claim(Goal goal) {
        showDialog(new AlertDialog.Builder(getContext())
            .setTitle(goal.emoji + " " + goal.title)
            .setMessage(fmt(goal.target) + " points will be spent and the reward marked as claimed. Treat yourself — you've earned it! 🎉")
            .setPositiveButton("Claim", (d, w) -> {
                if (store.claimGoal(goal.id)) {
                    confetti.burst(0, 0, true);
                    HabitUi.haptic(claimButton);
                    HabitUi.toastSuccess(this, "🎁 Enjoy! On to the next goal");
                }
            })
            .setNegativeButton("Later", null)
            .create());
    }

    /** "+10 ⭐" floats up from the button */
    private void floatPoints(View from, int points) {
        if (points == 0 || root == null) return;
        int[] loc = new int[2];
        from.getLocationInWindow(loc);
        int[] base = new int[2];
        root.getLocationInWindow(base);
        TextView label = HabitUi.text(getContext(), points > 0 ? "+" + points + " ⭐" : String.valueOf(points), 18, points > 0 ? HabitUi.fire() : HabitUi.skip(), true);
        root.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        label.setTranslationX(loc[0] - base[0] - dp(8));
        label.setTranslationY(loc[1] - base[1] - dp(8));
        label.animate().translationYBy(-HabitUi.dpf(64)).alpha(0f).setDuration(900)
            .setInterpolator(new DecelerateInterpolator())
            .withEndAction(() -> root.removeView(label)).start();
    }

    private void refreshProgress() {
        int[] p = progressCounts();
        int done = p[0], total = p[1];
        boolean all = total > 0 && done == total;
        refreshPoints();
        progressText.setText(total == 0 ? "No plan for today" : (all ? "🎉 " : "") + "Today " + done + " / " + total);
        progressText.setTextColor(all ? HabitUi.done() : HabitUi.sub());
        ((LinearLayout.LayoutParams) progressFill.getLayoutParams()).weight = done;
        ((LinearLayout.LayoutParams) progressRest.getLayoutParams()).weight = Math.max(done == 0 ? 1f : 0f, total - done);
        progressFill.requestLayout();
    }

    // endregion
}
