package org.telegram.messenger.nexo;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * FORK MOD (Nexo): marks app / sleep / steps / place / call habits automatically from screen time and sensors
 * (today and yesterday). A manual mark always wins: an already marked day is never touched.
 */
public class AutoMarker {
    private static long lastRun;

    public static List<Habit> autoHabits() {
        ArrayList<Habit> out = new ArrayList<>();
        for (Habit h : Nexo.store().active()) if (h.auto != null) out.add(h);
        return out;
    }

    /** Without {@code force} — at most once a minute (accessibility events come often) */
    public static void run(Context ctx, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastRun < 60_000) return;
        lastRun = now;
        List<Habit> habits = autoHabits();
        if (habits.isEmpty()) return;
        boolean usage = ScreenTime.hasAccess(ctx);
        boolean anyPlace = false, anyHealth = false;
        for (Habit h : habits) {
            if (Auto.PLACE.equals(h.auto.kind)) anyPlace = true;
            if (h.auto.fromHealth()) anyHealth = true;
        }
        if (anyPlace) Places.poll(ctx);
        if (anyHealth) Health.refresh(ctx);
        HabitStore store = Nexo.store();
        LocalDate today = LocalDate.now();
        for (Habit h : habits) {
            Auto a = h.auto;
            if (a == null) continue;
            if (a.needsUsage() && !usage) continue;
            for (LocalDate date : Arrays.asList(today.minusDays(1), today)) {
                if (!store.isDue(h, date) || store.status(h.id, date) != Status.NONE) continue;
                boolean dayOver = date.isBefore(today);
                long dayStart = ScreenTime.dayStart(date);
                Integer result;
                try {
                    result = evaluate(ctx, h, a, date, dayOver, dayStart, now);
                } catch (Exception e) {
                    org.telegram.messenger.FileLog.e(e);
                    result = null;
                }
                if (result == null) continue;
                store.setStatus(h.id, date, result);
            }
        }
    }

    private static Integer evaluate(Context ctx, Habit h, Auto a, LocalDate date, boolean dayOver, long dayStart, long now) {
        switch (a.kind) {
            case Auto.APP:
                return AutoRules.app(a, a.isTotal() ? ScreenTime.total(ctx, date) : ScreenTime.usage(ctx, a.pkg, date), dayOver, dayStart);
            case Auto.SLEEP: {
                Sleep s;
                if (a.fromHealth()) {
                    Health.Day d = Health.day(date);
                    s = d == null ? null : d.sleep;
                } else {
                    s = ScreenTime.sleep(ctx, date);
                }
                return AutoRules.sleep(a, s, dayStart);
            }
            case Auto.UNLOCKS:
                return AutoRules.unlocks(a, ScreenTime.unlocks(ctx, date), dayOver);
            case Auto.MORNING:
                return AutoRules.morning(a, ScreenTime.wake(ctx, date), ScreenTime.morningUsage(ctx, date), now);
            case Auto.STEPS: {
                // From the watch: to mark yesterday ✕ the watch must have had time to sync
                if (a.fromHealth()) {
                    Health.Day d = Health.day(date);
                    if (d == null || d.steps == null) return null;
                    return AutoRules.steps(a, d.steps, dayOver && Health.settled(date));
                }
                if (!Steps.hasPermission(ctx)) return null;
                return AutoRules.steps(a, Steps.on(date), dayOver);
            }
            case Auto.WORKOUT: {
                Health.Day d = Health.day(date);
                if (d == null || d.exercise == null) return null;
                return AutoRules.steps(a, d.exercise, dayOver && Health.settled(date));
            }
            case Auto.PLACE: {
                long[] w = Places.window(a, date);
                // Prayer times not loaded yet
                if (w != null && w.length == 0) return null;
                if (!Places.hasPermission(ctx)) return null;
                return AutoRules.place(Places.visits(h.id), w, dayStart, dayOver ? dayStart + 24 * 3600_000L : now);
            }
            case Auto.CALL: {
                if (!Calls.hasPermission(ctx)) return null;
                // Weekly/monthly: any day after the previous occurrence counts
                HabitStore store = Nexo.store();
                LocalDate first = store.startOf(h);
                LocalDate prev = null;
                for (long i = 1; i <= 400; i++) {
                    LocalDate d = date.minusDays(i);
                    if (d.isBefore(first)) break;
                    if (store.isDue(h, d)) { prev = d; break; }
                }
                long from = ScreenTime.dayStart(prev != null ? prev.plusDays(1) : first);
                return AutoRules.call(Calls.talked(ctx, a.phone, from, dayStart + 24 * 3600_000L, a.minutes * 60), dayOver);
            }
            default:
                return null;
        }
    }

    /** Every ~30 minutes (after midnight yesterday's limits ✓, in the morning the sleep) */
    public static void schedule(Context ctx) {
        AlarmManager am = ContextCompat.getSystemService(ctx, AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(
            ctx, 300, new Intent(ctx, HabitReceiver.class).setAction(HabitReceiver.ACTION_AUTO),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            Steps.sync(ctx);
            Places.sync(ctx);
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
        if (autoHabits().isEmpty()) {
            am.cancel(pi);
            return;
        }
        am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + AlarmManager.INTERVAL_HALF_HOUR, AlarmManager.INTERVAL_HALF_HOUR, pi);
    }
}
