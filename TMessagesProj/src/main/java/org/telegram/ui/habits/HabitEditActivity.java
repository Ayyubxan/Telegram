package org.telegram.ui.habits;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.nexo.Auto;
import org.telegram.messenger.nexo.Calls;
import org.telegram.messenger.nexo.Cat;
import org.telegram.messenger.nexo.ClipAccessibilityService;
import org.telegram.messenger.nexo.Dates;
import org.telegram.messenger.nexo.Habit;
import org.telegram.messenger.nexo.HabitStore;
import org.telegram.messenger.nexo.Health;
import org.telegram.messenger.nexo.Measure;
import org.telegram.messenger.nexo.Nexo;
import org.telegram.messenger.nexo.Places;
import org.telegram.messenger.nexo.PrayerTimes;
import org.telegram.messenger.nexo.Repeat;
import org.telegram.messenger.nexo.ScreenTime;
import org.telegram.messenger.nexo.Steps;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SectionsScrollView;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FORK MOD (Nexo): add / edit a habit. The main screen shows short rows ("Repeat — Every day"),
 * details open in small dialogs. Save with the ✓ in the action bar.
 */
@SuppressLint("SetTextI18n")
public class HabitEditActivity extends BaseFragment {
    private static final int MENU_SAVE = 1;
    private static final String MANUAL = "manual";
    private static final String ONCE = Repeat.ONCE;
    private static final int REQ_PERM = 61;
    private static final int REQ_CONTACT = 62;
    private static final int REQ_HEALTH = 63;

    /** Automatic kinds: key, label */
    private static final String[][] AUTO_KINDS = {
        {Auto.APP, "📱 App"}, {Auto.SLEEP, "😴 Sleep"}, {Auto.STEPS, "👟 Steps"}, {Auto.PLACE, "📍 Place"},
        {Auto.UNLOCKS, "🔓 Unlocks"}, {Auto.MORNING, "🌅 Phone-free morning"}, {Auto.CALL, "📞 Call"},
        {Auto.WORKOUT, "🏋️ Workout"},
    };

    private final String editId;
    private HabitStore store;
    private Habit existing;
    private HabitUi.Form f;
    private LinearLayout body;
    private TextView emojiView;
    private EditTextBoldCursor name;
    private TextView nameError;
    private boolean dirty;

    // State
    private String chosenCat = "";
    private String emoji = "";
    private String kind = MANUAL;
    private String freq = Repeat.DAILY;
    private int everyDays = 1;
    private int everyHours = 12;
    private String firstHour = "08:00";
    private final HashSet<Integer> weekDays = new HashSet<>();
    private int mday = 1;
    private String ydate = "01-01";
    private final ArrayList<String> times = new ArrayList<>();
    private boolean remind = true;
    private String appPkg = "";
    private String appName = "";
    private String appMode = Auto.LIMIT;
    private int appMinutes = 60;
    private boolean block = true;
    private boolean escape = true;
    private String winFrom = "";
    private String winTo = "";
    private String sleepMode = Auto.BEDTIME;
    private String sleepTime = "23:30";
    private int sleepMinutes = 7 * 60;
    private int unlockMax = 50;
    private int morningMin = 30;
    private int stepGoal = 8000;
    private String placeName = "";
    private double placeLat = 0, placeLon = 0;
    private int placeRadius = 150;
    private String placeFrom = "", placeTo = "";
    private boolean locating;
    private String callName = "", callPhone = "";
    private int callMin = 1;
    /** Steps and sleep source: phone or watch (Health Connect) */
    private String src = "";
    private int workoutMin = 30;
    private int plus = Habit.DEFAULT_PLUS;
    private int minus = Habit.DEFAULT_MINUS;
    private String deadline = "";
    private boolean measureOn;
    private String unit = "kg";
    private double mFrom = 0, mTo = 0;
    private Double mNow;

    private final Runnable healthListener = () -> { if (fragmentView != null) render(); };

    public HabitEditActivity(String editId) {
        this.editId = editId;
    }

    @Override
    public boolean onFragmentCreate() {
        store = Nexo.store();
        existing = editId == null ? null : store.habit(editId);
        load();
        return super.onFragmentCreate();
    }

    private void load() {
        LocalDate today = LocalDate.now();
        Habit h = existing;
        Repeat old = h == null ? null : h.repeat;
        List<Cat> cats = store.cats();
        chosenCat = h != null ? h.cat : cats.isEmpty() ? "" : cats.get(0).id;
        emoji = h != null ? h.emoji : "";
        freq = old != null ? old.freq : Repeat.DAILY;
        if (old != null && Repeat.DAILY.equals(old.freq)) everyDays = old.every;
        if (old != null && Repeat.HOURLY.equals(old.freq)) {
            everyHours = old.every;
            firstHour = old.times.isEmpty() ? "08:00" : old.times.get(0);
        }
        if (old != null && !old.days.isEmpty()) weekDays.addAll(old.days);
        else weekDays.add(today.getDayOfWeek().getValue());
        mday = old != null && Repeat.MONTHLY.equals(old.freq) ? old.mday : today.getDayOfMonth();
        ydate = old != null && Repeat.YEARLY.equals(old.freq) ? old.ydate : String.format(Locale.US, "%02d-%02d", today.getMonthValue(), today.getDayOfMonth());
        if (old != null && !Repeat.HOURLY.equals(old.freq)) times.addAll(old.times);
        remind = old == null || old.remind;
        if (h != null) {
            plus = h.plus;
            minus = h.minus > 0 || h.repeat.canLose() ? h.minus : Habit.DEFAULT_MINUS;
            if (h.once()) {
                kind = ONCE;
                deadline = h.repeat.deadline;
            }
            if (h.measure != null) {
                measureOn = true;
                unit = h.measure.unit;
                mFrom = h.measure.from;
                mTo = h.measure.to;
                mNow = h.measure.now;
            }
            Auto a = h.auto;
            if (a != null) {
                kind = a.kind;
                src = a.src;
                switch (a.kind) {
                    case Auto.WORKOUT: workoutMin = a.minutes; break;
                    case Auto.UNLOCKS: unlockMax = a.minutes; break;
                    case Auto.MORNING: morningMin = a.minutes; break;
                    case Auto.STEPS: stepGoal = a.minutes; break;
                    case Auto.PLACE:
                        placeName = a.app; placeLat = a.lat; placeLon = a.lon; placeRadius = a.radius; placeFrom = a.from; placeTo = a.to;
                        break;
                    case Auto.CALL: callName = a.app; callPhone = a.phone; callMin = a.minutes; break;
                    case Auto.APP:
                        appPkg = a.pkg; appName = a.app; appMode = a.mode; appMinutes = a.minutes; block = a.block; escape = a.escape; winFrom = a.from; winTo = a.to;
                        break;
                    case Auto.SLEEP:
                        sleepMode = a.mode;
                        if (!a.time.isEmpty()) sleepTime = a.time;
                        if (Auto.DURATION.equals(a.mode)) sleepMinutes = a.minutes;
                        break;
                }
            }
        }
    }

    private void changed() {
        dirty = true;
        render();
    }

    @Override
    public View createView(Context context) {
        f = new HabitUi.Form(context);
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_SAVE, R.drawable.ic_ab_done).setContentDescription(LocaleController.getString(R.string.Save));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) close();
                else if (id == MENU_SAVE) save();
            }
        });
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(HabitUi.bg());
        LinearLayout[] c = new LinearLayout[1];
        SectionsScrollView scroll = HabitUi.page(context, c);
        LinearLayout content = c[0];
        content.setPadding(dp(12), dp(12), dp(12), dp(48));
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(scroll);
        buildHeader(content);
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        content.addView(body);
        fragmentView = root;
        render();
        if (existing == null) HabitUi.focusWithKeyboard(name);
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        Health.addListener(healthListener);
        if (getContext() != null) Health.refresh(getContext());
        // Coming back from system settings with a permission granted — refresh the banner
        if (fragmentView != null) render();
    }

    @Override
    public void onPause() {
        super.onPause();
        Health.removeListener(healthListener);
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!dirty) return true;
        if (invoked) close();
        return false;
    }

    // region Header: emoji + name

    private void buildHeader(LinearLayout content) {
        Context ctx = getContext();
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(16), dp(12));
        emojiView = EmojiPickerSheet.button(this, emoji.isEmpty() ? "🙂" : emoji, 56, e -> {
            emoji = e;
            dirty = true;
        });
        if (emoji.isEmpty()) emojiView.setTextColor(HabitUi.sub());
        name = HabitUi.input(ctx, "Name");
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20);
        if (existing != null) {
            name.setText(existing.name);
            name.setSelection(name.length());
        }
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                dirty = true;
                nameError.setVisibility(View.GONE);
            }
        });
        card.addView(emojiView, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(14);
        card.addView(name, nlp);
        content.addView(card);
        nameError = f.text("Enter a name", 13, HabitUi.skip(), false);
        nameError.setPadding(dp(16), dp(6), 0, 0);
        nameError.setVisibility(View.GONE);
        content.addView(HabitUi.notSection(nameError));
    }

    // endregion

    private boolean isAuto() {
        return !MANUAL.equals(kind) && !ONCE.equals(kind);
    }

    /** Kind and its groups — redrawn on every change (the name field stays) */
    private void render() {
        if (fragmentView == null) return;
        actionBar.setTitle(existing == null ? (ONCE.equals(kind) ? "New task" : LocaleController.getString(R.string.HabitsNew)) : (ONCE.equals(kind) ? "Edit task" : LocaleController.getString(R.string.HabitsEdit)));
        body.removeAllViews();
        body.addView(f.spacer(16));
        body.addView(f.segmented(new String[][]{{MANUAL, "Habit"}, {ONCE, "One-time"}, {"auto", "Automatic"}}, isAuto() ? "auto" : kind, key -> {
            boolean wasOnce = ONCE.equals(kind);
            kind = "auto".equals(key) ? Auto.APP : key;
            // One-time tasks are big — larger default reward
            if (ONCE.equals(kind) && plus == Habit.DEFAULT_PLUS) plus = Habit.ONCE_PLUS;
            if (wasOnce && !ONCE.equals(kind) && plus == Habit.ONCE_PLUS) plus = Habit.DEFAULT_PLUS;
            changed();
        }));
        if (isAuto()) {
            HabitUi.FlowLayout chips = HabitUi.notSection(new HabitUi.FlowLayout(getContext(), dp(8)));
            chips.setPadding(dp(4), dp(12), dp(4), 0);
            for (String[] k : AUTO_KINDS) {
                final String key = k[0];
                boolean on = key.equals(kind);
                chips.addView(HabitUi.chip(getContext(), k[1], on, () -> {
                    if (on) return;
                    kind = key;
                    changed();
                }));
            }
            body.addView(chips);
            Object[] banner = permissionBanner();
            if (banner != null) {
                body.addView(f.spacer(12));
                body.addView(f.banner((String) banner[0], (Runnable) banner[1]));
            }
        }
        switch (kind) {
            case Auto.APP: renderApp(); break;
            case Auto.SLEEP: renderSleep(); break;
            case Auto.UNLOCKS: renderUnlocks(); break;
            case Auto.MORNING: renderMorning(); break;
            case Auto.STEPS: renderSteps(); break;
            case Auto.PLACE: renderPlace(); break;
            case Auto.CALL: renderCall(); break;
            case Auto.WORKOUT: renderWorkout(); break;
            case ONCE: renderOnce(); break;
            default: renderManual(); break;
        }
        // Points: given when done, taken when skipped (no loss for unscheduled habits)
        HabitUi.Form.Group points = f.new Group("Points", body);
        points.add(f.stepperRow("When done", () -> "+" + plus + " ⭐", d -> { plus = pointStep(plus, d); dirty = true; }));
        boolean canLose = currentRepeat().canLose();
        if (canLose) points.add(f.stepperRow(ONCE.equals(kind) ? "Deadline missed" : "When skipped", () -> "−" + minus, d -> { minus = pointStep(minus, d); dirty = true; }));

        // Common: category (and schedule for automatic kinds)
        HabitUi.Form.Group other = f.new Group("Other", body);
        if (isAuto()) other.add(f.new Row("Repeat", this::freqDialog).value(freqSummary()).view);
        Cat cat = store.cat(chosenCat);
        other.add(f.new Row("Category", this::catPicker).value(cat != null ? cat.emoji + " " + cat.name : "Other").view);

        if (existing != null) {
            body.addView(f.spacer(24));
            HabitUi.Form.Group danger = f.new Group(null, body);
            HabitUi.Form.Row del = f.new Row(LocaleController.getString(R.string.HabitsDelete), () ->
                HabitUi.confirm(this, null, "Delete \"" + existing.name + "\" and its history?", LocaleController.getString(R.string.Delete), true, () -> {
                    // The detail screen below notices the missing habit on resume and closes itself
                    store.deleteHabit(existing.id);
                    dirty = false;
                    AndroidUtilities.hideKeyboard(name);
                    finishFragment();
                }));
            del.titleColor(HabitUi.skip());
            del.titleView.setGravity(android.view.Gravity.CENTER);
            danger.add(del.view);
        }
    }

    /** 1 by 1 up to 10, then by 5 */
    private int pointStep(int v, int d) {
        int s = (d > 0 ? v : v - 1) < 10 ? 1 : 5;
        return Math.max(0, Math.min(500, v + d * s));
    }

    /** One-time: deadline (optional) and a numeric goal (80 → 75 kg) */
    private void renderOnce() {
        HabitUi.Form.Group g = f.new Group("Task", body);
        g.add(f.new Row("Deadline", this::deadlineDialog).value(deadline.isEmpty() ? "None" : Dates.shortDate(deadline)).view);
        g.add(f.new SwitchRow("Track with a number", measureOn, on -> { measureOn = on; changed(); }).sub("Weight, distance, number of books…").view);
        if (measureOn) {
            HabitUi.Form.Group m = f.new Group("Measure", body);
            m.add(f.new Row("Unit", () -> HabitUi.textDialog(this, "Unit", null, unit, "kg, km, books…", false, s -> { unit = s.isEmpty() ? "kg" : s; changed(); })).value(unit).view);
            m.add(f.new Row("Now", () -> HabitUi.textDialog(this, "Now", null, Measure.fmt(mFrom), unit, true, s -> {
                Double v = parseDouble(s);
                if (v != null) { mFrom = v; if (mNow == null || existing == null) mNow = v; }
                changed();
            })).value(Measure.fmt(mFrom) + " " + unit).view);
            m.add(f.new Row("Goal", () -> HabitUi.textDialog(this, "Goal", null, Measure.fmt(mTo), unit, true, s -> {
                Double v = parseDouble(s);
                if (v != null) mTo = v;
                changed();
            })).value(Measure.fmt(mTo) + " " + unit).view);
        } else {
        }
    }

    private static Double parseDouble(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (Exception e) {
            return null;
        }
    }

    private void deadlineDialog() {
        LocalDate d = Dates.parse(deadline);
        if (d == null) d = LocalDate.now().plusMonths(1);
        LocalDate now = LocalDate.now();
        AlertDialog.Builder b = AlertsCreator.createDatePickerDialog(getContext(), 0, 10, d.getYear() - now.getYear(), d.getDayOfMonth(), d.getMonthValue() - 1, d.getYear(), "Deadline", true,
            (year, month, dayOfMonth) -> { deadline = LocalDate.of(year, month + 1, dayOfMonth).toString(); changed(); });
        if (b == null) return;
        if (!deadline.isEmpty()) b.setNeutralButton("No deadline", (dlg, w) -> { deadline = ""; changed(); });
        showDialog(b.create());
    }

    private void renderManual() {
        HabitUi.Form.Group g = f.new Group("Schedule", body);
        g.add(f.new Row("Repeat", this::freqDialog).value(freqSummary()).view);
        if (!Repeat.HOURLY.equals(freq) && !Repeat.FREE.equals(freq)) {
            ArrayList<String> labels = new ArrayList<>();
            for (String t : times) labels.add(Repeat.plainLabel(t));
            g.add(f.new Row("Time", this::timesDialog).value(times.isEmpty() ? "None" : TextUtils.join(", ", labels)).view);
        }
        if (Repeat.HOURLY.equals(freq) || !times.isEmpty()) {
            g.add(f.new SwitchRow("Remind on time", remind, on -> { remind = on; dirty = true; }).sub("Notification at every time: ✓ / ✕ / snooze 10 min").view);
        }
    }

    private void renderApp() {
        HabitUi.Form.Group g = f.new Group("App", body);
        HabitUi.Form.Row appRow = f.new Row("App", this::appPicker).value(appPkg.isEmpty() ? "Choose" : appName);
        if (appPkg.isEmpty()) appRow.valueColor(HabitUi.accent());
        g.add(appRow.view);
        if (!Auto.ALL.equals(appPkg)) {
            g.add(f.segmentedRow(new String[][]{{Auto.LIMIT, "Limit"}, {Auto.AVOID, "Avoid"}, {Auto.USE, "Use"}}, appMode, key -> {
                appMode = key;
                if (Auto.USE.equals(key) && appMinutes == 60) appMinutes = 0;
                if (Auto.LIMIT.equals(key) && appMinutes == 0) appMinutes = 60;
                changed();
            }));
        }
        if (Auto.LIMIT.equals(appMode)) {
            g.add(f.stepperRow("At most per day", () -> Auto.duration(appMinutes), d -> { appMinutes = step(appMinutes, d, 5, 600); dirty = true; }));
        } else if (Auto.USE.equals(appMode)) {
            g.add(f.stepperRow("At least per day", () -> appMinutes == 0 ? "just open" : Auto.duration(appMinutes), d -> { appMinutes = step(appMinutes, d, 0, 600); dirty = true; }));
        } else {
            g.add(f.new Row("When", this::windowDialog).value(winFrom.isEmpty() ? "All day" : winFrom + " – " + winTo).view);
        }
        if (!Auto.USE.equals(appMode)) {
            HabitUi.Form.Group b = f.new Group("Block", body);
            b.add(f.new SwitchRow("Full-screen block", block, on -> { block = on; changed(); }).sub(Auto.LIMIT.equals(appMode) ? "The app won't open once the limit is over" : "When the app is opened").view);
            b.add(f.new SwitchRow("\"Open anyway\"", escape, on -> { escape = on; dirty = true; }).sub("Emergency: hold 3 seconds, counts as ✕").enabled(block).view);
            if (block && !ClipAccessibilityService.isEnabled(getContext())) {
                body.addView(f.spacer(10));
                body.addView(f.banner("For blocking: Accessibility → \"Telegram auto-send and app blocking\"", () -> openSettings(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)));
            }
        }
    }

    private void openSettings(String action) {
        try {
            getParentActivity().startActivity(new Intent(action));
        } catch (Exception e) {
            HabitUi.toast(this, "Settings screen not found");
        }
    }

    private void renderSleep() {
        HabitUi.Form.Group g = f.new Group("Sleep", body);
        sourceRow(g);
        g.add(f.segmentedRow(new String[][]{{Auto.BEDTIME, "Bedtime"}, {Auto.WAKE, "Wake up"}, {Auto.DURATION, "Duration"}}, sleepMode, key -> {
            sleepMode = key;
            if (Auto.WAKE.equals(key) && "23:30".equals(sleepTime)) sleepTime = "07:00";
            if (Auto.BEDTIME.equals(key) && "07:00".equals(sleepTime)) sleepTime = "23:30";
            changed();
        }));
        if (Auto.DURATION.equals(sleepMode)) {
            g.add(f.stepperRow("At least", () -> Auto.duration(sleepMinutes), d -> { sleepMinutes = Math.max(180, Math.min(720, sleepMinutes + d * 15)); dirty = true; }));
        } else {
            g.add(f.new Row(Auto.BEDTIME.equals(sleepMode) ? "In bed by" : "Up by", () -> pickTime(sleepTime, t -> { sleepTime = t; changed(); })).value(sleepTime).view);
        }
    }

    private boolean usesHealth() {
        return Auto.WORKOUT.equals(kind) || ((Auto.STEPS.equals(kind) || Auto.SLEEP.equals(kind)) && Auto.HEALTH.equals(src));
    }

    /** Missing permission for the kind — banner text and the fix */
    private Object[] permissionBanner() {
        Context ctx = getContext();
        if (usesHealth()) {
            if (Health.needsInstall(ctx)) return new Object[]{"Health Connect is not installed — tap", (Runnable) () -> { try { getParentActivity().startActivity(Health.installIntent()); } catch (Exception ignore) {} }};
            if (!Health.available(ctx)) return new Object[]{"Health Connect does not work on this phone", (Runnable) () -> {}};
            if (!Health.isConnected()) return new Object[]{"Connect to the watch data (Health Connect) — tap", (Runnable) () -> Health.request(getParentActivity(), REQ_HEALTH)};
            return null;
        }
        if ((Auto.APP.equals(kind) || Auto.SLEEP.equals(kind) || Auto.UNLOCKS.equals(kind) || Auto.MORNING.equals(kind)) && !ScreenTime.hasAccess(ctx)) {
            return new Object[]{"Screen-time access is needed — tap", (Runnable) () -> ScreenTime.openAccessSettings(ctx)};
        }
        if (Auto.STEPS.equals(kind) && !Steps.hasSensor(ctx)) return new Object[]{"This phone has no step counter", (Runnable) () -> {}};
        if (Auto.STEPS.equals(kind) && !Steps.hasPermission(ctx)) {
            return new Object[]{"Physical-activity permission is needed — tap", (Runnable) () -> requestPerms(new String[]{Steps.PERMISSION})};
        }
        if (Auto.PLACE.equals(kind) && !Places.hasPermission(ctx)) {
            return new Object[]{"Precise location permission is needed — tap", (Runnable) () -> requestPerms(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})};
        }
        if (Auto.PLACE.equals(kind) && !Places.hasBackground(ctx)) {
            return new Object[]{"To work while the app is closed: Location → \"Allow all the time\"", (Runnable) () -> requestPerms(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION})};
        }
        if (Auto.CALL.equals(kind) && !Calls.hasPermission(ctx)) {
            return new Object[]{"Call-log permission is needed — tap", (Runnable) () -> requestPerms(new String[]{Manifest.permission.READ_CALL_LOG})};
        }
        return null;
    }

    private void requestPerms(String[] perms) {
        if (getParentActivity() != null && Build.VERSION.SDK_INT >= 23) getParentActivity().requestPermissions(perms, REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_HEALTH && getContext() != null) Health.refresh(getContext(), true);
        render();
    }

    private void renderUnlocks() {
        HabitUi.Form.Group g = f.new Group("Unlocks", body);
        g.add(f.stepperRow("At most per day", () -> unlockMax + " times", d -> { unlockMax = Math.max(5, Math.min(300, unlockMax + d * 5)); dirty = true; }));
        if (ScreenTime.hasAccess(getContext())) body.addView(f.helper("So far today: " + ScreenTime.unlocks(getContext(), LocalDate.now()) + " times"));
    }

    private void renderMorning() {
        HabitUi.Form.Group g = f.new Group("Morning", body);
        g.add(f.stepperRow("After waking", () -> Auto.duration(morningMin), d -> { morningMin = Math.max(10, Math.min(180, morningMin + d * 5)); dirty = true; }));
    }

    /** Source choice (steps, sleep) */
    private void sourceRow(HabitUi.Form.Group g) {
        g.add(f.segmentedRow(new String[][]{{"", "📱 Phone"}, {Auto.HEALTH, "⌚ Watch"}}, src, key -> { src = key; changed(); }));
    }

    private void renderWorkout() {
        HabitUi.Form.Group g = f.new Group("Workout", body);
        g.add(f.stepperRow("At least per day", () -> Auto.duration(workoutMin), d -> { workoutMin = step(workoutMin, d, 5, 300); dirty = true; }));
    }

    private void renderSteps() {
        HabitUi.Form.Group g = f.new Group("Steps", body);
        sourceRow(g);
        g.add(f.stepperRow("At least per day", () -> Auto.num(stepGoal), d -> { stepGoal = Math.max(500, Math.min(40_000, stepGoal + d * 500)); dirty = true; }));
        if (Auto.HEALTH.equals(src)) {
            Health.Day day = Health.day(LocalDate.now());
            if (day != null && day.steps != null) body.addView(f.helper("Today on the watch: " + day.steps + " steps"));
        } else if (Steps.hasPermission(getContext()) && Steps.hasSensor(getContext())) {
            int today = Steps.on(LocalDate.now());
        }
    }

    private void renderPlace() {
        HabitUi.Form.Group g = f.new Group("Place", body);
        HabitUi.Form.Row loc = f.new Row("Location", this::placeDialog)
            .value(locating ? "Locating…" : placeLat == 0 && placeLon == 0 ? "Choose" : String.format(Locale.US, "%.4f, %.4f", placeLat, placeLon));
        if (placeLat == 0 && !locating) loc.valueColor(HabitUi.accent());
        g.add(loc.view);
        g.add(f.stepperRow("Radius", () -> placeRadius + " m", d -> { placeRadius = Math.max(50, Math.min(1000, placeRadius + d * 50)); dirty = true; }));
        g.add(f.new Row("When", this::placeWindowDialog).value(placeFrom.isEmpty() ? "All day" : Repeat.plainLabel(placeFrom) + " – " + Repeat.plainLabel(placeTo)).view);
    }

    private void renderCall() {
        HabitUi.Form.Group g = f.new Group("Call", body);
        HabitUi.Form.Row contact = f.new Row("Contact", this::pickContact).value(!callName.isEmpty() ? callName : !callPhone.isEmpty() ? callPhone : "Choose");
        if (callPhone.isEmpty()) contact.valueColor(HabitUi.accent());
        g.add(contact.view);
        g.add(f.stepperRow("At least", () -> callMin == 0 ? "any" : Auto.duration(callMin), d -> { callMin = Math.max(0, Math.min(60, callMin + d)); dirty = true; }));
    }

    private void placeDialog() {
        showDialog(new AlertDialog.Builder(getContext()).setTitle("Location")
            .setItems(new CharSequence[]{"📍 My current location", "✏️ Enter coordinates"}, (d, i) -> { if (i == 0) locateHere(); else coordsDialog(); })
            .create());
    }

    @SuppressLint("MissingPermission")
    private void locateHere() {
        Context ctx = getContext();
        if (!Places.hasPermission(ctx)) {
            requestPerms(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
            return;
        }
        LocationManager lm = androidx.core.content.ContextCompat.getSystemService(ctx, LocationManager.class);
        if (lm == null) return;
        locating = true;
        render();
        String provider = Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                lm.getCurrentLocation(provider, null, ctx.getMainExecutor(), this::located);
            } catch (Exception e) {
                located(lastKnown(lm, provider));
            }
        } else {
            located(lastKnown(lm, provider));
        }
    }

    @SuppressLint("MissingPermission")
    private static Location lastKnown(LocationManager lm, String provider) {
        try {
            return lm.getLastKnownLocation(provider);
        } catch (Exception e) {
            return null;
        }
    }

    private void located(Location loc) {
        locating = false;
        if (loc == null) {
            HabitUi.toast(this, "Location not found — is GPS on?");
        } else {
            placeLat = loc.getLatitude();
            placeLon = loc.getLongitude();
            dirty = true;
        }
        render();
    }

    private void coordsDialog() {
        HabitUi.textDialog(this, "Coordinates", "Long-press the place in Google Maps and paste the coordinates here.",
            placeLat != 0 ? placeLat + ", " + placeLon : "", "41.3111, 69.2797", false, text -> {
                ArrayList<Double> nums = new ArrayList<>();
                Matcher m = Pattern.compile("-?\\d+(\\.\\d+)?").matcher(text);
                while (m.find()) nums.add(Double.parseDouble(m.group()));
                if (nums.size() >= 2 && Math.abs(nums.get(0)) <= 90 && Math.abs(nums.get(1)) <= 180) {
                    placeLat = nums.get(0);
                    placeLon = nums.get(1);
                    changed();
                } else {
                    HabitUi.toast(this, "Invalid coordinates");
                }
            });
    }

    /** Place window: all day, a time range or a prayer (−10 … +60 min) */
    private void placeWindowDialog() {
        Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(4));
        final String[] from = {placeFrom};
        final String[] to = {placeTo};
        LinearLayout seg = new LinearLayout(ctx);
        seg.setOrientation(LinearLayout.VERTICAL);
        LinearLayout rows = new LinearLayout(ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        Runnable draw = new Runnable() {
            @Override
            public void run() {
                String mode = from[0].isEmpty() ? "all" : Repeat.isPrayer(from[0]) ? "prayer" : "range";
                seg.removeAllViews();
                seg.addView(f.segmented(new String[][]{{"all", "All day"}, {"range", "Range"}, {"prayer", "Prayer"}}, mode, key -> {
                    if ("all".equals(key)) { from[0] = ""; to[0] = ""; }
                    else if ("range".equals(key)) { from[0] = "06:00"; to[0] = "09:00"; }
                    else { from[0] = Repeat.prayer("fajr", -10); to[0] = Repeat.prayer("fajr", 60); }
                    run();
                }));
                rows.removeAllViews();
                if ("range".equals(mode)) {
                    rows.addView(f.new Row("From", () -> pickTime(from[0], t -> { from[0] = t; run(); })).value(from[0]).view);
                    rows.addView(f.new Row("To", () -> pickTime(to[0], t -> { to[0] = t; run(); })).value(to[0]).view);
                } else if ("prayer".equals(mode)) {
                    Repeat.Anchor a = Repeat.anchor(from[0]);
                    String chosen = a == null ? null : a.key;
                    HabitUi.FlowLayout list = new HabitUi.FlowLayout(ctx, dp(8));
                    list.setPadding(0, dp(12), 0, 0);
                    for (Map.Entry<String, String> e : PrayerTimes.NAMES.entrySet()) {
                        final String k = e.getKey();
                        list.addView(HabitUi.chip(ctx, e.getValue(), k.equals(chosen), () -> { from[0] = Repeat.prayer(k, -10); to[0] = Repeat.prayer(k, 60); run(); }));
                    }
                    rows.addView(list);
                    rows.addView(f.helper("From 10 minutes before the adhan to 1 hour after."));
                }
            }
        };
        draw.run();
        box.addView(seg);
        box.addView(rows);
        showDialog(new AlertDialog.Builder(ctx).setTitle("When").setView(box)
            .setPositiveButton(LocaleController.getString(R.string.Done), (d, w) -> { placeFrom = from[0]; placeTo = to[0]; changed(); })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null).create());
    }

    private void pickContact() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_CONTACT);
        } catch (Exception e) {
            HabitUi.toast(this, "Contacts app not found");
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_HEALTH) {
            if (getContext() != null) Health.refresh(getContext(), true);
            return;
        }
        Uri uri = data == null ? null : data.getData();
        if (requestCode != REQ_CONTACT || resultCode != android.app.Activity.RESULT_OK || uri == null) return;
        try (Cursor c = getContext().getContentResolver().query(uri, new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                callPhone = c.getString(0) == null ? "" : c.getString(0);
                callName = c.getString(1) == null ? "" : c.getString(1);
            }
        } catch (Exception ignore) {}
        changed();
    }

    /** 5-minute steps up to an hour, then 15 */
    private static int step(int v, int d, int min, int max) {
        int s = (d > 0 ? v : v - 1) < 60 ? 5 : 15;
        return Math.max(min, Math.min(max, v + d * s));
    }

    private interface OnTime {
        void pick(String hhmm);
    }

    private void pickTime(String initial, OnTime onPick) {
        Integer m = Repeat.minutes(initial);
        int minutes = m == null ? 9 * 60 : m;
        showDialog(AlertsCreator.createTimePickerDialog(getContext(), "Time", minutes, 0, 24 * 60, picked -> onPick.pick(Repeat.hhmm(picked))));
    }

    // region Dialogs

    private Repeat currentRepeat() {
        Repeat r = new Repeat();
        r.start = existing != null ? existing.repeat.start : "";
        if (ONCE.equals(kind)) {
            r.freq = Repeat.ONCE;
            r.deadline = deadline;
            return r;
        }
        r.freq = freq;
        r.every = Repeat.DAILY.equals(freq) ? everyDays : Repeat.HOURLY.equals(freq) ? everyHours : 1;
        if (Repeat.WEEKLY.equals(freq)) {
            r.days = new ArrayList<>(weekDays);
            Collections.sort(r.days);
        }
        r.mday = mday;
        r.ydate = ydate;
        if (Repeat.HOURLY.equals(freq)) r.times = Repeat.hourlyTimes(firstHour, everyHours);
        else if (Repeat.FREE.equals(freq) || !MANUAL.equals(kind)) r.times = new ArrayList<>();
        else {
            r.times = new ArrayList<>(times);
            Repeat.sortTimes(r.times);
        }
        r.remind = remind;
        return r;
    }

    private String freqSummary() {
        Repeat r = currentRepeat();
        String base = r.describeBase();
        return Repeat.HOURLY.equals(freq) ? base + " · " + TextUtils.join(", ", r.times) : base;
    }

    /** Repeat: kind plus its setting, in one dialog */
    private void freqDialog() {
        Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(4));
        LinearLayout options = new LinearLayout(ctx);
        options.setOrientation(LinearLayout.VERTICAL);
        HabitUi.FlowLayout kinds = new HabitUi.FlowLayout(ctx, dp(8));
        ArrayList<String[]> labels = new ArrayList<>();
        for (String[] l : Repeat.LABELS) {
            if (!MANUAL.equals(kind) && (Repeat.HOURLY.equals(l[0]) || Repeat.FREE.equals(l[0]))) continue;
            labels.add(l);
        }
        Runnable draw = new Runnable() {
            @Override
            public void run() {
                kinds.removeAllViews();
                for (String[] l : labels) {
                    final String k = l[0];
                    kinds.addView(HabitUi.chip(ctx, l[1], k.equals(freq), () -> { freq = k; run(); }));
                }
                options.removeAllViews();
                switch (freq) {
                    case Repeat.DAILY:
                        options.addView(f.stepperRow("Every", () -> everyDays + (everyDays == 1 ? " day" : " days"), d -> everyDays = Math.max(1, Math.min(30, everyDays + d))));
                        break;
                    case Repeat.WEEKLY: {
                        LinearLayout days = new LinearLayout(ctx);
                        days.setOrientation(LinearLayout.HORIZONTAL);
                        days.setPadding(0, dp(12), 0, 0);
                        for (int d = 1; d <= 7; d++) {
                            final int day = d;
                            boolean on = weekDays.contains(day);
                            TextView t = HabitUi.text(ctx, Dates.WEEKDAYS_SHORT[day - 1], 13, on ? HabitUi.white() : HabitUi.text());
                            t.setGravity(Gravity.CENTER);
                            t.setBackground(on ? HabitUi.oval(HabitUi.accent()) : HabitUi.oval(0, HabitUi.line(), dp(1)));
                            t.setOnClickListener(v -> {
                                HabitUi.haptic(v);
                                if (on && weekDays.size() > 1) weekDays.remove(day); else weekDays.add(day);
                                run();
                            });
                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
                            lp.rightMargin = dp(4);
                            days.addView(t, lp);
                        }
                        options.addView(days);
                        break;
                    }
                    case Repeat.MONTHLY:
                        options.addView(f.stepperRow("Day of month", () -> Dates.ordinal(mday), d -> mday = Math.max(1, Math.min(31, mday + d))));
                        break;
                    case Repeat.YEARLY: {
                        int[] md = currentRepeat().ydateParts();
                        options.addView(f.new Row("Date", () -> {
                            int[] cur = currentRepeat().ydateParts();
                            AlertDialog.Builder b = AlertsCreator.createDatePickerDialog(ctx, 0, 0, 0, cur[1], cur[0] - 1, LocalDate.now().getYear(), "Date", false,
                                (year, month, dayOfMonth) -> { ydate = String.format(Locale.US, "%02d-%02d", month + 1, dayOfMonth); run(); });
                            if (b != null) showDialog(b.create());
                        }).value(md[1] + " " + Dates.MONTHS[md[0] - 1]).view);
                        break;
                    }
                    case Repeat.HOURLY:
                        options.addView(f.stepperRow("Every", () -> everyHours + (everyHours == 1 ? " hour" : " hours"), d -> everyHours = Math.max(1, Math.min(23, everyHours + d))));
                        options.addView(f.new Row("First at", () -> pickTime(firstHour, t -> { firstHour = t; run(); })).value(firstHour).view);
                        options.addView(f.helper("Per day: " + TextUtils.join(" · ", Repeat.hourlyTimes(firstHour, everyHours))));
                        break;
                    default:
                        options.addView(f.helper("Only the days it happens are marked — never counted as skipped."));
                        break;
                }
            }
        };
        draw.run();
        box.addView(kinds);
        box.addView(options);
        showDialog(new AlertDialog.Builder(ctx).setTitle("Repeat").setView(box)
            .setPositiveButton(LocaleController.getString(R.string.Done), (d, w) -> changed())
            .setOnCancelListener(d -> changed()).create());
    }

    /** Times: a clock time or bound to a prayer; each is marked separately */
    private void timesDialog() {
        Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(4));
        HabitUi.FlowLayout list = new HabitUi.FlowLayout(ctx, dp(8));
        Runnable draw = new Runnable() {
            @Override
            public void run() {
                list.removeAllViews();
                Repeat.sortTimes(times);
                for (String t : times) {
                    TextView chip = HabitUi.chip(ctx, (Repeat.isPrayer(t) ? Repeat.label(t) : t) + "   ✕", true, () -> { times.remove(t); run(); });
                    chip.setContentDescription(Repeat.label(t) + " — remove");
                    list.addView(chip);
                }
                if (times.isEmpty()) list.addView(HabitUi.text(ctx, "No times yet", 14, HabitUi.sub()));
            }
        };
        draw.run();
        box.addView(list);
        box.addView(f.spacer(12));
        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView addClock = HabitUi.pill(ctx, "＋ Time", HabitUi.accent(), () -> {
            String last = "09:00";
            for (String t : times) if (!Repeat.isPrayer(t)) last = t;
            pickTime(last, t -> { if (!times.contains(t)) times.add(t); draw.run(); });
        });
        TextView addPrayer = HabitUi.pill(ctx, "＋ Prayer", HabitUi.accent(), () -> PrayerPicker.show(this, picked -> {
            for (String t : picked) if (!times.contains(t)) times.add(t);
            draw.run();
        }));
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        l1.rightMargin = dp(8);
        actions.addView(addClock, l1);
        actions.addView(addPrayer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(actions);
        TextView help = f.helper("With several times (pills, prayers) each one is marked separately.");
        help.setPadding(0, dp(10), 0, 0);
        box.addView(help);
        showDialog(new AlertDialog.Builder(ctx).setTitle("Time").setView(box)
            .setPositiveButton(LocaleController.getString(R.string.Done), (d, w) -> changed())
            .setOnCancelListener(d -> changed()).create());
    }

    private void catPicker() {
        List<Cat> cats = store.cats();
        CharSequence[] items = new CharSequence[cats.size() + 2];
        for (int i = 0; i < cats.size(); i++) items[i] = cats.get(i).emoji + "  " + cats.get(i).name;
        items[cats.size()] = "Other";
        items[cats.size() + 1] = "＋ New category";
        showDialog(new AlertDialog.Builder(getContext()).setTitle("Category")
            .setItems(items, (d, i) -> {
                if (i < cats.size()) { chosenCat = cats.get(i).id; changed(); }
                else if (i == cats.size()) { chosenCat = ""; changed(); }
                else HabitUi.catDialog(this, null, null, (n, e, c) -> { chosenCat = store.addCat(n, e, c).id; changed(); });
            }).create());
    }

    private void appPicker() {
        Context ctx = getContext();
        ArrayList<ScreenTime.AppInfo> apps = new ArrayList<>();
        apps.add(new ScreenTime.AppInfo(Auto.ALL, "Total screen time", null));
        apps.addAll(ScreenTime.launcherApps(ctx));
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        final AlertDialog[] dialog = new AlertDialog[1];
        for (ScreenTime.AppInfo app : apps) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(52));
            row.setPadding(dp(20), dp(6), dp(20), dp(6));
            row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            ImageView icon = new ImageView(ctx);
            if (app.icon != null) icon.setImageDrawable(app.icon);
            else {
                icon.setImageResource(R.drawable.msg_stats);
                icon.setColorFilter(HabitUi.accent());
            }
            row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
            TextView label = HabitUi.text(ctx, app.label, 16, HabitUi.text());
            label.setPadding(dp(14), 0, 0, 0);
            row.addView(label);
            row.setOnClickListener(v -> {
                appPkg = app.pkg;
                appName = app.label;
                if (Auto.ALL.equals(appPkg)) appMode = Auto.LIMIT;
                if (dialog[0] != null) dialog[0].dismiss();
                changed();
            });
            box.addView(row);
        }
        ScrollView sv = new ScrollView(ctx);
        sv.addView(box);
        dialog[0] = new AlertDialog.Builder(ctx).setTitle("App").setView(sv).setNegativeButton(LocaleController.getString(R.string.Cancel), null).create();
        showDialog(dialog[0]);
    }

    /** Avoid: all day or a window (may cross midnight) */
    private void windowDialog() {
        Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(4));
        final String[] from = {winFrom};
        final String[] to = {winTo};
        LinearLayout seg = new LinearLayout(ctx);
        seg.setOrientation(LinearLayout.VERTICAL);
        LinearLayout rows = new LinearLayout(ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        Runnable draw = new Runnable() {
            @Override
            public void run() {
                seg.removeAllViews();
                seg.addView(f.segmented(new String[][]{{"all", "All day"}, {"range", "Range"}}, from[0].isEmpty() ? "all" : "range", key -> {
                    if ("all".equals(key)) { from[0] = ""; to[0] = ""; } else { from[0] = "23:00"; to[0] = "07:00"; }
                    run();
                }));
                rows.removeAllViews();
                if (!from[0].isEmpty()) {
                    rows.addView(f.new Row("From", () -> pickTime(from[0], t -> { from[0] = t; run(); })).value(from[0]).view);
                    rows.addView(f.new Row("To", () -> pickTime(to[0], t -> { to[0] = t; run(); })).value(to[0]).view);
                    rows.addView(f.helper("May cross midnight (23:00 – 07:00)."));
                }
            }
        };
        draw.run();
        box.addView(seg);
        box.addView(rows);
        showDialog(new AlertDialog.Builder(ctx).setTitle("When to avoid").setView(box)
            .setPositiveButton(LocaleController.getString(R.string.Done), (d, w) -> { winFrom = from[0]; winTo = to[0]; changed(); })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null).create());
    }

    // endregion

    private void save() {
        Auto auto = null;
        switch (kind) {
            case Auto.APP: {
                if (appPkg.isEmpty()) {
                    HabitUi.toast(this, "Choose an app");
                    appPicker();
                    return;
                }
                auto = new Auto(Auto.APP, appMode);
                auto.pkg = appPkg;
                auto.app = appName;
                auto.minutes = Auto.AVOID.equals(appMode) ? 0 : appMinutes;
                auto.block = block;
                auto.from = Auto.AVOID.equals(appMode) ? winFrom : "";
                auto.to = Auto.AVOID.equals(appMode) ? winTo : "";
                auto.escape = escape;
                break;
            }
            case Auto.SLEEP:
                auto = new Auto(Auto.SLEEP, sleepMode);
                auto.minutes = Auto.DURATION.equals(sleepMode) ? sleepMinutes : 0;
                auto.time = Auto.DURATION.equals(sleepMode) ? "" : sleepTime;
                auto.src = src;
                break;
            case Auto.WORKOUT:
                auto = new Auto(Auto.WORKOUT, Auto.USE);
                auto.minutes = workoutMin;
                auto.src = Auto.HEALTH;
                break;
            case Auto.UNLOCKS:
                auto = new Auto(Auto.UNLOCKS, Auto.LIMIT);
                auto.minutes = unlockMax;
                break;
            case Auto.MORNING:
                auto = new Auto(Auto.MORNING, Auto.AVOID);
                auto.minutes = morningMin;
                break;
            case Auto.STEPS:
                auto = new Auto(Auto.STEPS, Auto.USE);
                auto.minutes = stepGoal;
                auto.src = src;
                break;
            case Auto.PLACE: {
                if (placeLat == 0 && placeLon == 0) {
                    HabitUi.toast(this, "Choose a place");
                    placeDialog();
                    return;
                }
                String label = !placeName.isEmpty() ? placeName : !name.getText().toString().trim().isEmpty() ? name.getText().toString().trim() : "Place";
                auto = new Auto(Auto.PLACE, Auto.USE);
                auto.app = label;
                auto.lat = placeLat;
                auto.lon = placeLon;
                auto.radius = placeRadius;
                auto.from = placeFrom;
                auto.to = placeTo;
                break;
            }
            case Auto.CALL: {
                if (callPhone.isEmpty()) {
                    HabitUi.toast(this, "Choose a contact");
                    pickContact();
                    return;
                }
                auto = new Auto(Auto.CALL, Auto.USE);
                auto.app = callName.isEmpty() ? callPhone : callName;
                auto.minutes = callMin;
                auto.phone = callPhone;
                break;
            }
            default:
                break;
        }
        if (auto != null && Auto.PLACE.equals(auto.kind) && Repeat.isPrayer(auto.from)) PrayerTimes.ensure(getContext());
        // No name — derive it from the rule ("YouTube ≤ 1 h")
        String n = name.getText().toString().trim();
        if (n.isEmpty() && auto != null) {
            String d = auto.describe();
            int sp = d.indexOf(' ');
            n = sp >= 0 ? d.substring(sp + 1) : d;
        }
        if (n.isEmpty()) {
            nameError.setVisibility(View.VISIBLE);
            HabitUi.focusWithKeyboard(name);
            return;
        }
        String fallback;
        if (auto == null) {
            Cat cat = store.cat(chosenCat);
            fallback = ONCE.equals(kind) ? "🏔" : cat != null ? cat.emoji : "✅";
        } else {
            switch (auto.kind) {
                case Auto.APP: fallback = Auto.AVOID.equals(auto.mode) ? "🚫" : "📱"; break;
                case Auto.SLEEP: fallback = "😴"; break;
                case Auto.UNLOCKS: fallback = "🔓"; break;
                case Auto.MORNING: fallback = "🌅"; break;
                case Auto.STEPS: fallback = "👟"; break;
                case Auto.PLACE: fallback = "📍"; break;
                case Auto.CALL: fallback = "📞"; break;
                case Auto.WORKOUT: fallback = "🏋️"; break;
                default: fallback = "✅"; break;
            }
        }
        String e = emoji.trim().isEmpty() ? fallback : emoji.trim();
        Repeat r = currentRepeat();
        int lose = r.canLose() ? minus : 0;
        Measure measure = ONCE.equals(kind) && measureOn ? new Measure(unit, mFrom, mTo, mNow != null ? mNow : mFrom) : null;
        if (measure != null && measure.from == measure.to) {
            HabitUi.toast(this, "The current and goal values are the same");
            return;
        }
        if (existing == null) {
            store.addHabit(n, e, chosenCat, r, auto, plus, lose, measure);
        } else {
            Habit h = existing.copy();
            h.name = n; h.emoji = e; h.cat = chosenCat; h.repeat = r; h.auto = auto; h.plus = plus; h.minus = lose; h.measure = measure;
            store.updateHabit(h);
        }
        dirty = false;
        AndroidUtilities.hideKeyboard(name);
        finishFragment();
    }

    /** Unsaved changes — ask */
    private void close() {
        if (!dirty) {
            finishFragment();
            return;
        }
        showDialog(new AlertDialog.Builder(getContext())
            .setMessage("Discard changes?")
            .setPositiveButton("Discard", (d, w) -> { dirty = false; finishFragment(); })
            .setNegativeButton("Keep editing", null)
            .create());
    }
}
