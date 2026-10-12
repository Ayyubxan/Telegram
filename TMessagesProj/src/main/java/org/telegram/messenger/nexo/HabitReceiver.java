package org.telegram.messenger.nexo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import java.time.LocalDate;

/** FORK MOD (Nexo): the widget, the reminder notification and the alarms all land here. */
public class HabitReceiver extends BroadcastReceiver {
    public static final String ACTION_TOGGLE = "org.telegram.messenger.nexo.TOGGLE";
    public static final String ACTION_DONE = "org.telegram.messenger.nexo.DONE";
    public static final String ACTION_REMIND = "org.telegram.messenger.nexo.REMIND";
    public static final String ACTION_FIRE = "org.telegram.messenger.nexo.FIRE";
    public static final String ACTION_AUTO = "org.telegram.messenger.nexo.AUTO";
    public static final String ACTION_PLACE = "org.telegram.messenger.nexo.PLACE";
    public static final String ACTION_SLOT_DONE = "org.telegram.messenger.nexo.SLOT_DONE";
    public static final String ACTION_SLOT_SKIP = "org.telegram.messenger.nexo.SLOT_SKIP";
    public static final String ACTION_SNOOZE = "org.telegram.messenger.nexo.SNOOZE";
    public static final String ACTION_RENOTIFY = "org.telegram.messenger.nexo.RENOTIFY";
    public static final String ACTION_BACKUP = "org.telegram.messenger.nexo.BACKUP";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_DATE = "date";
    public static final String EXTRA_TIME = "time";

    private static LocalDate dateOf(Intent intent, LocalDate fallback) {
        String s = intent.getStringExtra(EXTRA_DATE);
        LocalDate d = s == null ? null : Dates.parse(s);
        return d == null ? fallback : d;
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            handle(ctx, intent);
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }

    private void handle(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        HabitStore store = Nexo.store();
        LocalDate today = HabitStore.today();
        switch (action) {
            case ACTION_REMIND: {
                HabitReminder.schedule(ctx);
                HabitWidget.refresh(ctx);
                HabitReminder.show(ctx);
                break;
            }
            case ACTION_TOGGLE: {
                String id = intent.getStringExtra(EXTRA_ID);
                Habit h = id == null ? null : store.habit(id);
                if (h == null) return;
                if (h.repeat.slotted()) {
                    // In the widget: ✓ the next time slot; all done — clear the day
                    String next = null;
                    for (String t : h.repeat.times) {
                        if (store.slotStatus(h.id, today, t) == Status.NONE) { next = t; break; }
                    }
                    if (next == null) {
                        store.setStatus(h.id, today, Status.NONE);
                    } else {
                        feedback(ctx, h, store.setSlot(h.id, today, next, Status.DONE), next);
                    }
                    return;
                }
                int next;
                switch (store.status(h.id, today)) {
                    case Status.NONE: next = Status.DONE; break;
                    case Status.DONE: next = h.daily() ? Status.SKIPPED : Status.NONE; break;
                    default: next = Status.NONE; break;
                }
                MarkResult r = store.setStatus(h.id, today, next);
                if (next == Status.DONE) feedback(ctx, h, r, null);
                break;
            }
            case ACTION_FIRE:
                HabitAlarms.fire(ctx);
                break;
            case ACTION_BACKUP:
                ForkBackup.checkDue(ctx);
                break;
            case ACTION_AUTO:
                ForkBackup.checkDue(ctx);
                AutoMarker.run(ctx, true);
                break;
            case ACTION_PLACE: {
                String id = intent.getStringExtra(EXTRA_ID);
                if (id == null) return;
                if (intent.hasExtra(Places.EXTRA_ENTERING)) Places.update(id, intent.getBooleanExtra(Places.EXTRA_ENTERING, false));
                AutoMarker.run(ctx, true);
                break;
            }
            case ACTION_SLOT_DONE:
            case ACTION_SLOT_SKIP: {
                String id = intent.getStringExtra(EXTRA_ID);
                Habit h = id == null ? null : store.habit(id);
                if (h == null) return;
                LocalDate date = dateOf(intent, today);
                // Empty time — a plain (untimed or single-time) habit: the whole day
                String time = intent.getStringExtra(EXTRA_TIME);
                if (time != null && time.isEmpty()) time = null;
                int s = ACTION_SLOT_DONE.equals(action) ? Status.DONE : Status.SKIPPED;
                MarkResult r = h.repeat.slotted() && time != null ? store.setSlot(h.id, date, time, s) : store.setStatus(h.id, date, s);
                if (s == Status.DONE) feedback(ctx, h, r, h.repeat.slotted() ? time : null);
                break;
            }
            case ACTION_SNOOZE: {
                String id = intent.getStringExtra(EXTRA_ID);
                String time = intent.getStringExtra(EXTRA_TIME);
                if (id == null || time == null) return;
                HabitAlarms.snooze(ctx, id, dateOf(intent, today), time);
                Toast.makeText(ctx, "⏰ I'll remind you in " + HabitAlarms.SNOOZE_MIN + " minutes", Toast.LENGTH_SHORT).show();
                break;
            }
            case ACTION_RENOTIFY: {
                String id = intent.getStringExtra(EXTRA_ID);
                String time = intent.getStringExtra(EXTRA_TIME);
                if (id == null || time == null) return;
                HabitAlarms.renotify(ctx, id, dateOf(intent, today), time);
                break;
            }
            case ACTION_DONE: {
                String id = intent.getStringExtra(EXTRA_ID);
                Habit h = id == null ? null : store.habit(id);
                if (h == null) return;
                MarkResult r = store.setStatus(h.id, today, Status.DONE);
                feedback(ctx, h, r, null);
                HabitReminder.show(ctx, true);
                break;
            }
            default:
                break;
        }
    }

    private static void feedback(Context ctx, Habit h, MarkResult r, String time) {
        String name = time != null ? h.name + " (" + time + ")" : h.name;
        String msg;
        if (r.goalReached != null) {
            msg = "🎉 Goal reached: " + r.goalReached.emoji + " " + r.goalReached.title + "!";
        } else if (r.milestone != null && r.total) {
            msg = "🎉 " + h.name + ": " + Dates.ordinal(r.milestone) + " time!";
        } else if (r.milestone != null) {
            msg = "🔥 " + h.name + ": " + r.milestone + " " + h.repeat.units(r.milestone) + " in a row!";
        } else if (r.streak > 1) {
            msg = "✓ " + name + " · 🔥 " + r.streak + (r.points > 0 ? " · +" + r.points + " ⭐" : "");
        } else {
            msg = "✓ " + name + (r.points > 0 ? " · +" + r.points + " ⭐" : "");
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }
}
