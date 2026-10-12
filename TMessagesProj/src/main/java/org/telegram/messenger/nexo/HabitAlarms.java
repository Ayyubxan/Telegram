package org.telegram.messenger.nexo;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.R;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * FORK MOD (Nexo): habits with exact times (pills at 08:00 and 20:00, work at 09:00…): a notification at every time.
 * One alarm — for the nearest time; when it fires all habits of that minute are shown and the next one is set.
 */
public class HabitAlarms {
    private static final String CHANNEL_ID = "habit_times";
    public static final int SNOOZE_MIN = 10;

    private static ZoneId zone() {
        return ZoneId.systemDefault();
    }

    /** Finds the nearest upcoming time and sets the alarm. Called after every change. */
    public static void schedule(Context ctx) {
        AlarmManager am = ContextCompat.getSystemService(ctx, AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(
            ctx, 100, new Intent(ctx, HabitReceiver.class).setAction(HabitReceiver.ACTION_FIRE),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        LocalDateTime next = nextEvent();
        if (next == null) {
            am.cancel(pi);
            return;
        }
        setExact(am, next.atZone(zone()).toInstant().toEpochMilli(), pi);
    }

    private static void setExact(AlarmManager am, long at, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
            try { am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi); } catch (Exception ignore) {}
        }
    }

    private static LocalDateTime nextEvent() {
        HabitStore store = Nexo.store();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime best = null;
        for (Habit h : store.active()) {
            if (!h.daily() || !h.repeat.remind || h.repeat.times.isEmpty()) continue;
            LocalDate day = now.toLocalDate();
            for (int round = 0; round < 2; round++) {
                // Today's times are over — the next occurrence
                LocalDate due = store.nextDue(h, day);
                if (due == null) break;
                ArrayList<LocalDateTime> ts = new ArrayList<>();
                for (String time : h.repeat.times) {
                    Integer m = Repeat.resolve(time, due);
                    if (m != null) ts.add(due.atStartOfDay().plusMinutes(m));
                }
                Collections.sort(ts);
                LocalDateTime t = null;
                for (LocalDateTime x : ts) if (x.isAfter(now)) { t = x; break; }
                if (t != null) {
                    if (best == null || t.isBefore(best)) best = t;
                    break;
                }
                day = due.plusDays(1);
            }
        }
        return best;
    }

    /** The alarm fired: notifications for the not yet marked times of this minute */
    public static void fire(Context ctx) {
        HabitStore store = Nexo.store();
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        int minute = now.getHour() * 60 + now.getMinute();
        for (Habit h : store.dueOn(today)) {
            if (!h.repeat.remind) continue;
            for (String t : h.repeat.times) {
                Integer m = Repeat.resolve(t, today);
                if (m == null) continue;
                // Even if the alarm is a bit late (Doze) — the last 5 minutes
                if (m >= minute - 5 && m <= minute && pending(h, today, t)) notify(ctx, h, today, t);
            }
        }
        schedule(ctx);
    }

    private static boolean pending(Habit h, LocalDate date, String time) {
        HabitStore store = Nexo.store();
        int s = h.repeat.slotted() ? store.slotStatus(h.id, date, time) : store.status(h.id, date);
        return s == Status.NONE;
    }

    private static int notificationId(String habitId, LocalDate date, String time) {
        return (habitId + "|" + date + "|" + time).hashCode();
    }

    private static Notification.Action action(Context ctx, Habit h, LocalDate date, String time, String action, String label) {
        PendingIntent pi = PendingIntent.getBroadcast(
            ctx, (action + "|" + h.id + "|" + date + "|" + time).hashCode(),
            new Intent(ctx, HabitReceiver.class).setAction(action)
                .setData(Uri.parse("nexo-habit://" + action + "/" + h.id + "/" + date + "/" + time))
                .putExtra(HabitReceiver.EXTRA_ID, h.id)
                .putExtra(HabitReceiver.EXTRA_DATE, date.toString())
                .putExtra(HabitReceiver.EXTRA_TIME, time),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        // int-icon constructor: the Icon variant is API 23+ and the fork's minSdk is 21
        return new Notification.Action.Builder(0, label, pi).build();
    }

    public static void notify(Context ctx, Habit h, LocalDate date, String time) {
        NotificationManager nm = ContextCompat.getSystemService(ctx, NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Habit times (pills, tasks)", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("For habits with an exact time");
            nm.createNotificationChannel(ch);
        }
        HabitStore store = Nexo.store();
        String text;
        if (h.repeat.slotted()) {
            int[] p = store.progress(h.id, date);
            text = "Today: " + p[0] + " / " + p[1];
        } else if (store.streak(h.id) > 0) {
            int streak = store.streak(h.id);
            text = "🔥 " + streak + " " + h.repeat.units(streak) + " in a row — don't break it!";
        } else {
            text = h.repeat.describe();
        }
        Integer minute = Repeat.resolve(time, date);
        long when = date.atStartOfDay().plusMinutes(minute == null ? 0 : minute).atZone(zone()).toInstant().toEpochMilli();
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL_ID) : new Notification.Builder(ctx))
            .setSmallIcon(R.drawable.notification)
            .setContentTitle(h.emoji + " " + h.name + " · " + Repeat.shortLabel(time, date))
            .setContentText(text)
            .setContentIntent(Nexo.openHabitsPending(ctx))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setWhen(when)
            .setShowWhen(true)
            .setAutoCancel(true)
            .addAction(action(ctx, h, date, time, HabitReceiver.ACTION_SLOT_DONE, "✓ Done"));
        if (h.daily()) b.addAction(action(ctx, h, date, time, HabitReceiver.ACTION_SLOT_SKIP, "✕ Skip"));
        b.addAction(action(ctx, h, date, time, HabitReceiver.ACTION_SNOOZE, "⏰ " + SNOOZE_MIN + " min"));
        nm.notify(notificationId(h.id, date, time), b.build());
    }

    /** Closes the notification and shows it again after {@link #SNOOZE_MIN} minutes */
    public static void snooze(Context ctx, String habitId, LocalDate date, String time) {
        NotificationManager nm = ContextCompat.getSystemService(ctx, NotificationManager.class);
        if (nm != null) nm.cancel(notificationId(habitId, date, time));
        PendingIntent pi = PendingIntent.getBroadcast(
            ctx, ("snooze|" + habitId + "|" + date + "|" + time).hashCode(),
            new Intent(ctx, HabitReceiver.class).setAction(HabitReceiver.ACTION_RENOTIFY)
                .setData(Uri.parse("nexo-habit://renotify/" + habitId + "/" + date + "/" + time))
                .putExtra(HabitReceiver.EXTRA_ID, habitId)
                .putExtra(HabitReceiver.EXTRA_DATE, date.toString())
                .putExtra(HabitReceiver.EXTRA_TIME, time),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = ContextCompat.getSystemService(ctx, AlarmManager.class);
        if (am == null) return;
        setExact(am, Instant.now().plusSeconds(SNOOZE_MIN * 60L).toEpochMilli(), pi);
    }

    /** The snoozed notification comes back — if still not marked */
    public static void renotify(Context ctx, String habitId, LocalDate date, String time) {
        Habit h = Nexo.store().habit(habitId);
        if (h == null) return;
        if (pending(h, date, time)) notify(ctx, h, date, time);
    }

    /** Closes the notification(s) of the marked time(s). {@code time} null — all times of that day. */
    public static void cancel(Context ctx, String habitId, LocalDate date, String time) {
        NotificationManager nm = ContextCompat.getSystemService(ctx, NotificationManager.class);
        if (nm == null) return;
        List<String> times;
        if (time != null) {
            times = Collections.singletonList(time);
        } else {
            Habit h = Nexo.store().habit(habitId);
            times = h == null ? Collections.<String>emptyList() : h.repeat.times;
        }
        for (String t : times) nm.cancel(notificationId(habitId, date, t));
    }
}
