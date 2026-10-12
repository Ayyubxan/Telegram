package org.telegram.messenger.nexo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * FORK MOD (Nexo): habits data — same JSON as the Mac HabitStore.
 * Every record carries `u` (modification time, ms): on sync the later writer wins.
 * Deletion is a record too (`del: true`), otherwise another device would bring it back.
 */
public class HabitStore {
    public static final List<Integer> MILESTONES = Arrays.asList(3, 7, 14, 21, 30, 50, 75, 100, 150, 200, 365);
    /** Unscheduled habit: total count (the first one is a celebration too) */
    public static final List<Integer> COUNT_MILESTONES = Arrays.asList(1, 5, 10, 25, 50, 75, 100, 150, 200, 300, 500, 1000);

    public static LocalDate today() {
        return LocalDate.now();
    }

    private static class Mark {
        final int s;
        final long u;
        Mark(int s, long u) { this.s = s; this.u = u; }
    }

    private final Context app;
    private final File file;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final LinkedHashMap<String, Cat> cats = new LinkedHashMap<>();
    private final LinkedHashMap<String, Habit> habits = new LinkedHashMap<>();
    /** "habitId|yyyy-MM-dd" or, for multi-time habits, "habitId|yyyy-MM-dd|HH:mm" → status */
    private final HashMap<String, Mark> marks = new HashMap<>();
    private final LinkedHashMap<String, Goal> goals = new LinkedHashMap<>();

    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Local change — the delta to send to other devices */
    public volatile Consumer<byte[]> onLocalChange;

    public HabitStore(Context ctx) {
        app = ctx.getApplicationContext();
        file = new File(app.getFilesDir(), "habits.json");
        load();
    }

    public void addListener(Runnable l) { listeners.add(l); }
    public void removeListener(Runnable l) { listeners.remove(l); }

    private void changed() {
        main.post(() -> {
            for (Runnable l : listeners) l.run();
            HabitWidget.refresh(app);
            HabitAlarms.schedule(app);
            HabitBackup.schedule(app);
            AutoMarker.schedule(app);
        });
    }

    // region Reading

    public synchronized List<Cat> cats() {
        ArrayList<Cat> out = new ArrayList<>();
        for (Cat c : cats.values()) if (!c.del) out.add(c);
        Collections.sort(out, (a, b) -> a.order != b.order ? Integer.compare(a.order, b.order) : a.name.toLowerCase().compareTo(b.name.toLowerCase()));
        return out;
    }

    /** Deleted or missing category — null (the habit shows under "Other") */
    public synchronized Cat cat(String id) {
        Cat c = cats.get(id);
        return c == null || c.del ? null : c;
    }

    public synchronized List<Habit> active() {
        HashMap<String, Integer> order = new HashMap<>();
        List<Cat> cs = cats();
        for (int i = 0; i < cs.size(); i++) order.put(cs.get(i).id, i);
        ArrayList<Habit> out = new ArrayList<>();
        for (Habit h : habits.values()) if (!h.del) out.add(h);
        Collections.sort(out, (a, b) -> {
            Integer oa = order.get(a.cat), ob = order.get(b.cat);
            int ia = oa == null ? Integer.MAX_VALUE : oa, ib = ob == null ? Integer.MAX_VALUE : ob;
            if (ia != ib) return Integer.compare(ia, ib);
            if (a.order != b.order) return Integer.compare(a.order, b.order);
            return a.name.toLowerCase().compareTo(b.name.toLowerCase());
        });
        return out;
    }

    /** First mark of the habit */
    public synchronized LocalDate firstDate(String habitId) {
        String best = null;
        String prefix = habitId + "|";
        for (Map.Entry<String, Mark> e : marks.entrySet()) {
            if (e.getValue().s == Status.NONE || !e.getKey().startsWith(prefix)) continue;
            String d = e.getKey().split("\\|")[1];
            if (best == null || d.compareTo(best) < 0) best = d;
        }
        return best == null ? null : LocalDate.parse(best);
    }

    /** Day the habit started: the set start or the earliest mark (past days can be marked too) */
    public synchronized LocalDate startOf(Habit h) {
        LocalDate set = Dates.parse(h.repeat.start);
        LocalDate first = firstDate(h.id);
        LocalDate min = null;
        if (set != null) min = set;
        if (first != null && (min == null || first.isBefore(min))) min = first;
        return min == null ? today() : min;
    }

    public boolean isDue(Habit h, LocalDate date) {
        return h.repeat.isDue(date, startOf(h));
    }

    /** Habits due on that date (unscheduled ones excluded) */
    public List<Habit> dueOn(LocalDate date) {
        ArrayList<Habit> out = new ArrayList<>();
        for (Habit h : active()) if (isDue(h, date)) out.add(h);
        return out;
    }

    /** Next occurrence from {@code from} (within a year) */
    public LocalDate nextDue(Habit h, LocalDate from) {
        if (!h.daily()) return null;
        LocalDate first = startOf(h);
        for (long i = 0; i <= 400; i++) {
            LocalDate d = from.plusDays(i);
            if (h.repeat.isDue(d, first)) return d;
        }
        return null;
    }

    public LocalDate nextDue(Habit h) {
        return nextDue(h, today());
    }

    public synchronized Habit habit(String id) {
        Habit h = habits.get(id);
        return h == null || h.del ? null : h;
    }

    /** Day a one-time task was completed (null — not yet) */
    public synchronized LocalDate completedOn(String habitId) {
        String best = null;
        String prefix = habitId + "|";
        for (Map.Entry<String, Mark> e : marks.entrySet()) {
            if (e.getValue().s != Status.DONE || !e.getKey().startsWith(prefix)) continue;
            String d = e.getKey().split("\\|")[1];
            if (best == null || d.compareTo(best) < 0) best = d;
        }
        return best == null ? null : LocalDate.parse(best);
    }

    /** One-time tasks on the home screen: open ones and those completed today */
    public List<Habit> openTasks(LocalDate today) {
        ArrayList<Habit> out = new ArrayList<>();
        for (Habit h : active()) {
            if (!h.once()) continue;
            LocalDate done = completedOn(h.id);
            if (done == null || done.equals(today)) out.add(h);
        }
        return out;
    }

    public synchronized int slotStatus(String habitId, LocalDate date, String time) {
        Mark m = marks.get(habitId + "|" + date + "|" + time);
        return m == null ? Status.NONE : m.s;
    }

    /** Day status. Multi-time habit: all ✓ — DONE, any empty — NONE, otherwise (some skipped) — SKIPPED. */
    public synchronized int status(String habitId, LocalDate date) {
        Habit h = habits.get(habitId);
        if (h == null || !h.repeat.slotted()) {
            Mark m = marks.get(habitId + "|" + date);
            return m == null ? Status.NONE : m.s;
        }
        boolean allDone = true, anyNone = false;
        for (String t : h.repeat.times) {
            int s = slotStatus(habitId, date, t);
            if (s != Status.DONE) allDone = false;
            if (s == Status.NONE) anyNone = true;
        }
        if (allDone) return Status.DONE;
        if (anyNone) return Status.NONE;
        return Status.SKIPPED;
    }

    /** {done, total} — number of times for multi-time habits, 0/1 otherwise */
    public synchronized int[] progress(String habitId, LocalDate date) {
        Habit h = habits.get(habitId);
        if (h == null) return new int[]{0, 1};
        if (!h.repeat.slotted()) return new int[]{status(habitId, date) == Status.DONE ? 1 : 0, 1};
        int done = 0;
        for (String t : h.repeat.times) if (slotStatus(habitId, date, t) == Status.DONE) done++;
        return new int[]{done, h.repeat.times.size()};
    }

    /** Rhythm of an untimed habit: the usual interval and the next expected time (epoch ms) */
    public static class Rhythm {
        public final long intervalMs;
        public final long lastAt;
        Rhythm(long intervalMs, long lastAt) { this.intervalMs = intervalMs; this.lastAt = lastAt; }
        public long nextAt() { return lastAt + intervalMs; }
    }

    /**
     * Median of the gaps between marks (last 10 gaps). E.g. every ~25 h → the next one 25 h after the last.
     * Median: one skipped day (48 h) doesn't ruin the result. Only marks made on the same day count — marks
     * filled in from the calendar later don't have a real time. At least 3 marks (2 gaps) are needed.
     */
    public synchronized Rhythm rhythm(String habitId) {
        ZoneId zone = ZoneId.systemDefault();
        ArrayList<Long> times = new ArrayList<>();
        String prefix = habitId + "|";
        for (Map.Entry<String, Mark> e : marks.entrySet()) {
            String k = e.getKey();
            Mark m = e.getValue();
            if (m.s != Status.DONE || !k.startsWith(prefix)) continue;
            int bars = 0;
            for (char c : k.toCharArray()) if (c == '|') bars++;
            if (bars != 1) continue;
            String day = k.substring(k.indexOf('|') + 1);
            if (!Instant.ofEpochMilli(m.u).atZone(zone).toLocalDate().toString().equals(day)) continue;
            times.add(m.u);
        }
        Collections.sort(times);
        while (times.size() > 11) times.remove(0);
        if (times.size() < 3) return null;
        ArrayList<Long> gaps = new ArrayList<>();
        for (int i = 1; i < times.size(); i++) {
            long g = times.get(i) - times.get(i - 1);
            if (g > 60 * 60_000L) gaps.add(g);
        }
        if (gaps.size() < 2) return null;
        Collections.sort(gaps);
        long median = gaps.size() % 2 == 1 ? gaps.get(gaps.size() / 2) : (gaps.get(gaps.size() / 2 - 1) + gaps.get(gaps.size() / 2)) / 2;
        return new Rhythm(median, times.get(times.size() - 1));
    }

    // region Points and goals

    public int pointsOf(Habit h, int s) {
        if (s == Status.DONE) return h.plus;
        if (s == Status.SKIPPED) return -h.minus;
        return 0;
    }

    /**
     * Points (within from..to): from marks plus unmarked past days counted as ✕.
     * Points found for deleted habits are not lost.
     */
    public synchronized int earned(LocalDate from, LocalDate to, String habitId) {
        String f = from == null ? null : from.toString();
        String t = to == null ? null : to.toString();
        int marked = 0;
        for (Map.Entry<String, Mark> e : marks.entrySet()) {
            String[] p = e.getKey().split("\\|");
            Habit h = habits.get(p[0]);
            if (h == null) continue;
            if (habitId != null && !h.id.equals(habitId)) continue;
            if ((f != null && p[1].compareTo(f) < 0) || (t != null && p[1].compareTo(t) > 0)) continue;
            marked += pointsOf(h, e.getValue().s);
        }
        int missed = 0;
        for (Habit h : habits.values()) {
            if (habitId != null && !h.id.equals(habitId)) continue;
            missed += missed(h, from, to) * h.minus;
        }
        return marked - missed;
    }

    public int earned() {
        return earned(null, null, null);
    }

    public int earned(LocalDate from, LocalDate to) {
        return earned(from, to, null);
    }

    /**
     * Number of unmarked occurrences (per time slot for multi-time habits). Only finished days (today doesn't count),
     * from the habit's start day; for deleted habits — until the deletion day.
     */
    public synchronized int missed(Habit h, LocalDate from, LocalDate to) {
        if (h.minus == 0) return 0;
        if (h.once()) {
            // Deadline passed without completion — once
            LocalDate dl = Dates.parse(h.repeat.deadline);
            if (dl == null) return 0;
            if (!dl.isBefore(today()) || (from != null && dl.isBefore(from)) || (to != null && dl.isAfter(to))) return 0;
            LocalDate done = completedOn(h.id);
            return done == null || done.isAfter(dl) ? 1 : 0;
        }
        if (!h.repeat.expected()) return 0;
        LocalDate first = startOf(h);
        LocalDate end = today().minusDays(1);
        if (h.del) {
            LocalDate delDay = Instant.ofEpochMilli(h.u).atZone(ZoneId.systemDefault()).toLocalDate().minusDays(1);
            if (delDay.isBefore(end)) end = delDay;
        }
        if (to != null && to.isBefore(end)) end = to;
        LocalDate d = from != null && from.isAfter(first) ? from : first;
        ArrayList<String> slots = new ArrayList<>();
        if (h.repeat.slotted()) for (String t : h.repeat.times) slots.add("|" + t);
        else slots.add("");
        int n = 0;
        while (!d.isAfter(end)) {
            if (h.repeat.isDue(d, first)) {
                for (String s : slots) {
                    Mark m = marks.get(h.id + "|" + d + s);
                    if (m == null || m.s == Status.NONE) n++;
                }
            }
            d = d.plusDays(1);
        }
        return n;
    }

    /** Balance: earned − spent on claimed rewards */
    public synchronized int balance() {
        int spent = 0;
        for (Goal g : goals.values()) if (!g.del && g.claimed != null) spent += g.target;
        return earned() - spent;
    }

    public synchronized List<Goal> goals() {
        ArrayList<Goal> out = new ArrayList<>();
        for (Goal g : goals.values()) if (!g.del) out.add(g);
        Collections.sort(out, (a, b) -> {
            boolean ca = a.claimed != null, cb = b.claimed != null;
            if (ca != cb) return ca ? 1 : -1;
            return Integer.compare(a.order, b.order);
        });
        return out;
    }

    /** The next (unclaimed) goal */
    public Goal currentGoal() {
        for (Goal g : goals()) if (g.claimed == null) return g;
        return null;
    }

    public void addGoal(String title, String emoji, int target) {
        Goal g;
        synchronized (this) {
            int order = -1;
            for (Goal x : goals.values()) order = Math.max(order, x.order);
            g = new Goal(UUID.randomUUID().toString(), title.trim(), emoji, target, null, order + 1, false, now());
            goals.put(g.id, g);
        }
        commit(delta(null, null, null, Collections.singletonList(g)));
    }

    public void updateGoal(Goal g) {
        Goal updated;
        synchronized (this) {
            Goal prev = goals.get(g.id);
            updated = g.copy();
            updated.u = stamp(prev == null ? null : prev.u);
            goals.put(g.id, updated);
        }
        commit(delta(null, null, null, Collections.singletonList(updated)));
    }

    public void deleteGoal(String id) {
        Goal g;
        synchronized (this) {
            g = goals.get(id);
            if (g == null) return;
        }
        Goal c = g.copy();
        c.del = true;
        updateGoal(c);
    }

    /** Claim the reward: the points are spent, the next goal starts */
    public boolean claimGoal(String id) {
        Goal g;
        synchronized (this) {
            g = goals.get(id);
            if (g == null || g.claimed != null || balance() < g.target) return false;
        }
        Goal c = g.copy();
        c.claimed = now();
        updateGoal(c);
        return true;
    }

    // endregion

    public synchronized boolean isEmpty() {
        for (Habit h : habits.values()) if (!h.del) return false;
        return true;
    }

    /** Fully completed days (ascending) */
    public synchronized List<LocalDate> doneDates(String habitId) {
        ArrayList<String> days = new ArrayList<>();
        String prefix = habitId + "|";
        for (String k : marks.keySet()) {
            if (!k.startsWith(prefix)) continue;
            String d = k.split("\\|")[1];
            if (!days.contains(d)) days.add(d);
        }
        ArrayList<LocalDate> out = new ArrayList<>();
        for (String d : days) {
            LocalDate date = LocalDate.parse(d);
            if (status(habitId, date) == Status.DONE) out.add(date);
        }
        Collections.sort(out);
        return out;
    }

    /** The closest occurrence going back from {@code day} (null — before the habit started) */
    private LocalDate prevDue(Habit h, LocalDate day, LocalDate first) {
        LocalDate d = day;
        while (!d.isBefore(first)) {
            if (h.repeat.isDue(d, first)) return d;
            d = d.minusDays(1);
        }
        return null;
    }

    /**
     * Consecutive completed occurrences: days for daily, the chosen days for weekly, months for monthly…
     * If today's occurrence isn't marked yet the chain isn't broken — count from the previous one.
     */
    public synchronized int streak(String habitId, LocalDate today) {
        Habit h = habits.get(habitId);
        if (h == null || !h.daily()) return 0;
        LocalDate first = startOf(h);
        LocalDate d = prevDue(h, today, first);
        if (d == null) return 0;
        if (d.equals(today)) {
            int s = status(habitId, today);
            if (s == Status.SKIPPED) return 0;
            if (s == Status.NONE) {
                d = prevDue(h, today.minusDays(1), first);
                if (d == null) return 0;
            }
        }
        int n = 0;
        while (status(habitId, d) == Status.DONE) {
            n++;
            d = prevDue(h, d.minusDays(1), first);
            if (d == null) break;
        }
        return n;
    }

    public int streak(String habitId) {
        return streak(habitId, today());
    }

    public synchronized int bestStreak(String habitId) {
        Habit h = habits.get(habitId);
        if (h == null || !h.daily()) return 0;
        LocalDate first = startOf(h);
        int best = 0, run = 0;
        LocalDate d = first;
        LocalDate today = today();
        while (!d.isAfter(today)) {
            if (h.repeat.isDue(d, first)) {
                int s = status(habitId, d);
                if (s == Status.DONE) run++;
                else if (s == Status.NONE) { if (!d.equals(today)) run = 0; }
                else run = 0;
                best = Math.max(best, run);
            }
            d = d.plusDays(1);
        }
        return best;
    }

    /** Percentage of occurrences completed in the last {@code days} days */
    public synchronized int completion(String habitId, int days, LocalDate today) {
        Habit h = habits.get(habitId);
        if (h == null) return 0;
        LocalDate first = startOf(h);
        int due = 0, done = 0;
        for (int i = 0; i < days; i++) {
            LocalDate d = today.minusDays(i);
            if (!h.repeat.isDue(d, first)) continue;
            due++;
            if (status(habitId, d) == Status.DONE) done++;
        }
        return due == 0 ? 0 : done * 100 / due;
    }

    public int completion(String habitId, int days) {
        return completion(habitId, days, today());
    }

    // endregion

    // region Writing

    public Cat addCat(String name, String emoji, String color) {
        Cat c;
        synchronized (this) {
            int order = -1;
            for (Cat x : cats.values()) order = Math.max(order, x.order);
            c = new Cat(UUID.randomUUID().toString(), name.trim(), emoji, color, order + 1, false, now());
            cats.put(c.id, c);
        }
        commit(delta(Collections.singletonList(c), null, null, null));
        return c;
    }

    public void updateCat(Cat c) {
        Cat updated;
        synchronized (this) {
            Cat prev = cats.get(c.id);
            updated = c.copy();
            updated.u = stamp(prev == null ? null : prev.u);
            cats.put(c.id, updated);
        }
        commit(delta(Collections.singletonList(updated), null, null, null));
    }

    /** Habits are not deleted — they move under "Other" */
    public void deleteCat(String id) {
        Cat c;
        synchronized (this) {
            c = cats.get(id);
            if (c == null) return;
        }
        Cat d = c.copy();
        d.del = true;
        updateCat(d);
    }

    public Habit addHabit(String name, String emoji, String cat, Repeat repeat, Auto auto, int plus, int minus, Measure measure) {
        Habit h;
        synchronized (this) {
            int order = -1;
            for (Habit x : habits.values()) if (x.cat.equals(cat)) order = Math.max(order, x.order);
            Repeat r = repeat.copy();
            if (r.start.isEmpty()) r.start = today().toString();
            h = new Habit(UUID.randomUUID().toString(), name.trim(), emoji, cat, r, order + 1, false, now(), auto, plus, r.canLose() ? minus : 0, measure);
            habits.put(h.id, h);
        }
        commit(delta(null, Collections.singletonList(h), null, null));
        return h;
    }

    /** Current value of a measured task; when the goal is reached — done (result for the celebration) */
    public MarkResult setMeasure(String habitId, double value) {
        Habit h = habit(habitId);
        if (h == null || h.measure == null) return null;
        Habit c = h.copy();
        c.measure.now = value;
        updateHabit(c);
        boolean reached = c.measure.reached();
        if (reached && completedOn(habitId) == null) return setStatus(habitId, today(), Status.DONE);
        return null;
    }

    public void updateHabit(Habit h) {
        Habit updated;
        synchronized (this) {
            Habit prev = habits.get(h.id);
            updated = h.copy();
            updated.u = stamp(prev == null ? null : prev.u);
            habits.put(h.id, updated);
        }
        commit(delta(null, Collections.singletonList(updated), null, null));
    }

    public void deleteHabit(String id) {
        Habit h;
        synchronized (this) {
            h = habits.get(id);
            if (h == null) return;
        }
        Habit d = h.copy();
        d.del = true;
        updateHabit(d);
    }

    /** Marks the day. Multi-time habit: all times at once. */
    public MarkResult setStatus(String habitId, LocalDate date, int s) {
        ArrayList<String> times = new ArrayList<>();
        synchronized (this) {
            Habit h = habits.get(habitId);
            if (h != null && h.repeat.slotted()) times.addAll(h.repeat.times);
        }
        if (times.isEmpty()) times.add(null);
        return mark(habitId, date, times, s);
    }

    /** Marks one time slot of a multi-time habit (e.g. the 20:00 pill) */
    public MarkResult setSlot(String habitId, LocalDate date, String time, int s) {
        ArrayList<String> times = new ArrayList<>();
        times.add(time);
        return mark(habitId, date, times, s);
    }

    private MarkResult mark(String habitId, LocalDate date, List<String> times, int s) {
        int before, streak, balanceBefore, balanceAfter;
        boolean free;
        long u;
        synchronized (this) {
            balanceBefore = balance();
            long prev = 0;
            for (String t : times) {
                Mark m = marks.get(key(habitId, date, t));
                if (m != null) prev = Math.max(prev, m.u);
            }
            u = stamp(prev);
            // Unscheduled habit has no streak — the total count is the milestone
            Habit h = habits.get(habitId);
            free = h != null && !h.daily() && !h.once();
            before = free ? doneDates(habitId).size() : streak(habitId);
            for (String t : times) marks.put(key(habitId, date, t), new Mark(s, u));
            streak = free ? doneDates(habitId).size() : streak(habitId);
            balanceAfter = balance();
        }
        ArrayList<JSONObject> markJson = new ArrayList<>();
        try {
            for (String t : times) {
                JSONObject j = new JSONObject().put("h", habitId).put("d", date.toString()).put("s", s).put("u", u);
                if (t != null) j.put("t", t);
                markJson.add(j);
            }
        } catch (Exception ignore) {}
        // The notification shown for the marked time is no longer needed
        if (s != Status.NONE) for (String t : times) HabitAlarms.cancel(app, habitId, date, t);
        commit(delta(null, null, markJson, null));
        Integer milestone = null;
        for (int m : free ? COUNT_MILESTONES : MILESTONES) if (m > before && m <= streak) { milestone = m; break; }
        // Did this mark reach the next goal
        Goal goal = currentGoal();
        if (goal != null && !(balanceBefore < goal.target && balanceAfter >= goal.target)) goal = null;
        return new MarkResult(free ? 0 : streak, milestone, balanceAfter - balanceBefore, goal, free);
    }

    private static String key(String habitId, LocalDate date, String t) {
        return t == null ? habitId + "|" + date : habitId + "|" + date + "|" + t;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /**
     * Modification time: now, but always after the version of the record we know. Even if the other device's clock
     * is ahead (or the change came from it), the edit made here wins — otherwise "later wins" would discard it.
     */
    private static long stamp(Long previous) {
        return Math.max(now(), (previous == null ? 0 : previous) + 1);
    }

    private void commit(JSONObject delta) {
        save();
        changed();
        Consumer<byte[]> sink = onLocalChange;
        if (sink != null) sink.accept(delta.toString().getBytes(StandardCharsets.UTF_8));
    }

    // endregion

    // region JSON, persistence and sync

    private static JSONObject catJson(Cat c) throws Exception {
        return new JSONObject().put("id", c.id).put("name", c.name).put("emoji", c.emoji).put("color", c.color)
            .put("order", c.order).put("del", c.del).put("u", c.u);
    }

    // "daily" — for old versions that don't know schedules
    private static JSONObject habitJson(Habit h) throws Exception {
        JSONObject o = new JSONObject()
            .put("id", h.id).put("name", h.name).put("emoji", h.emoji).put("cat", h.cat)
            .put("daily", h.daily()).put("repeat", h.repeat.toJson())
            .put("order", h.order).put("del", h.del).put("u", h.u)
            .put("plus", h.plus).put("minus", h.minus);
        if (h.auto != null) o.put("auto", h.auto.toJson());
        if (h.measure != null) o.put("measure", h.measure.toJson());
        return o;
    }

    private static JSONObject goalJson(Goal g) throws Exception {
        return new JSONObject().put("id", g.id).put("title", g.title).put("emoji", g.emoji).put("target", g.target)
            .put("claimed", g.claimed == null ? JSONObject.NULL : g.claimed).put("order", g.order).put("del", g.del).put("u", g.u);
    }

    private static JSONObject delta(List<Cat> cats, List<Habit> habits, List<JSONObject> marks, List<Goal> goals) {
        try {
            JSONArray c = new JSONArray(), h = new JSONArray(), m = new JSONArray(), g = new JSONArray();
            if (cats != null) for (Cat x : cats) c.put(catJson(x));
            if (habits != null) for (Habit x : habits) h.put(habitJson(x));
            if (marks != null) for (JSONObject x : marks) m.put(x);
            if (goals != null) for (Goal x : goals) g.put(goalJson(x));
            return new JSONObject().put("cats", c).put("habits", h).put("marks", m).put("goals", g);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public synchronized byte[] fullJson() {
        try {
            JSONArray c = new JSONArray(), h = new JSONArray(), m = new JSONArray(), g = new JSONArray();
            for (Cat x : cats.values()) c.put(catJson(x));
            for (Habit x : habits.values()) h.put(habitJson(x));
            for (Map.Entry<String, Mark> e : marks.entrySet()) {
                String[] p = e.getKey().split("\\|");
                JSONObject j = new JSONObject().put("h", p[0]).put("d", p[1]).put("s", e.getValue().s).put("u", e.getValue().u);
                if (p.length > 2) j.put("t", p[2]);
                m.put(j);
            }
            for (Goal x : goals.values()) g.put(goalJson(x));
            return new JSONObject().put("cats", c).put("habits", h).put("marks", m).put("goals", g).toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "{}".getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Backup file: full state plus when and where it was exported */
    public byte[] backupJson(String deviceName) {
        try {
            return new JSONObject()
                .put("nexo", "habits-backup")
                .put("v", 1)
                .put("exportedAt", now())
                .put("device", deviceName)
                .put("data", new JSONObject(new String(fullJson(), StandardCharsets.UTF_8)))
                .toString(2).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return fullJson();
        }
    }

    /** {habits, marks} counts in the backup (shown before restoring); null if the file is not a backup */
    public int[] backupSummary(byte[] bytes) {
        try {
            JSONObject data = unwrap(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
            JSONArray habits = data.optJSONArray("habits");
            if (habits == null) return null;
            int alive = 0;
            for (int i = 0; i < habits.length(); i++) if (!habits.getJSONObject(i).optBoolean("del")) alive++;
            JSONArray marks = data.optJSONArray("marks");
            return new int[]{alive, marks == null ? 0 : marks.length()};
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject unwrap(JSONObject o) throws Exception {
        return "habits-backup".equals(o.optString("nexo")) ? o.getJSONObject("data") : o;
    }

    /** Restore from a backup: merges with the existing data (the newer version of every record wins) — nothing is lost. The result is sent to other devices too. */
    public boolean importBackup(byte[] bytes) {
        JSONObject o;
        try {
            o = unwrap(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return false;
        }
        synchronized (this) { apply(o); }
        save();
        changed();
        Consumer<byte[]> sink = onLocalChange;
        if (sink != null) sink.accept(fullJson());
        return true;
    }

    /** Adds the state received from another device. The newer version of every record wins. */
    public void merge(byte[] bytes) {
        JSONObject o;
        try {
            o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return;
        }
        boolean any;
        synchronized (this) { any = apply(o); }
        if (any) {
            save();
            changed();
        }
    }

    /** true — something changed */
    private boolean apply(JSONObject o) {
        boolean any = false;
        JSONArray arr = o.optJSONArray("cats");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject j = arr.getJSONObject(i);
                Cat c = new Cat(j.getString("id"), j.optString("name"), j.optString("emoji", "📁"), j.optString("color", "#64748B"),
                    j.optInt("order"), j.optBoolean("del"), j.optLong("u"));
                Cat prev = cats.get(c.id);
                if (prev == null || prev.u < c.u) { cats.put(c.id, c); any = true; }
            } catch (Exception ignore) {}
        }
        arr = o.optJSONArray("habits");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject j = arr.getJSONObject(i);
                Repeat repeat = Repeat.fromJson(j.optJSONObject("repeat"), j.optBoolean("daily", true));
                Habit h = new Habit(j.getString("id"), j.optString("name"), j.optString("emoji", "✅"), j.optString("cat"),
                    repeat, j.optInt("order"), j.optBoolean("del"), j.optLong("u"), Auto.fromJson(j.optJSONObject("auto")),
                    j.optInt("plus", Habit.DEFAULT_PLUS), j.optInt("minus", repeat.canLose() ? Habit.DEFAULT_MINUS : 0),
                    Measure.fromJson(j.optJSONObject("measure")));
                Habit prev = habits.get(h.id);
                if (prev == null || prev.u < h.u) { habits.put(h.id, h); any = true; }
            } catch (Exception ignore) {}
        }
        arr = o.optJSONArray("marks");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject j = arr.getJSONObject(i);
                String t = j.optString("t");
                String key = j.getString("h") + "|" + j.getString("d") + (t.isEmpty() ? "" : "|" + t);
                long u = j.optLong("u");
                Mark prev = marks.get(key);
                if (prev == null || prev.u < u) { marks.put(key, new Mark(j.optInt("s"), u)); any = true; }
            } catch (Exception ignore) {}
        }
        arr = o.optJSONArray("goals");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject j = arr.getJSONObject(i);
                Goal g = new Goal(j.getString("id"), j.optString("title"), j.optString("emoji", "🎁"), j.optInt("target"),
                    j.isNull("claimed") ? null : j.optLong("claimed"), j.optInt("order"), j.optBoolean("del"), j.optLong("u"));
                Goal prev = goals.get(g.id);
                if (prev == null || prev.u < g.u) { goals.put(g.id, g); any = true; }
            } catch (Exception ignore) {}
        }
        migrateLegacyCats();
        return any;
    }

    /** Category records for the old fixed "sport", "work"… ids: u = 1 so any user edit wins (the Mac does the same) */
    private void migrateLegacyCats() {
        for (Habit h : habits.values()) {
            String[] legacy = Cat.LEGACY.get(h.cat);
            if (legacy == null || cats.containsKey(h.cat)) continue;
            int index = new ArrayList<>(Cat.LEGACY.keySet()).indexOf(h.cat);
            cats.put(h.cat, new Cat(h.cat, legacy[0], legacy[1], legacy[2], index, false, 1));
        }
    }

    private synchronized void load() {
        try {
            if (!file.exists()) return;
            byte[] bytes = new byte[(int) file.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int off = 0;
                while (off < bytes.length) {
                    int n = in.read(bytes, off, bytes.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            apply(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
        } catch (Exception ignore) {}
    }

    private void save() {
        byte[] bytes = fullJson();
        synchronized (file) {
            try {
                File tmp = new File(file.getParentFile(), "habits.json.tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(bytes); }
                tmp.renameTo(file);
            } catch (Exception ignore) {}
        }
    }

    // endregion
}
