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
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.R;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** FORK MOD (Nexo): evening reminder — if daily habits are not marked today, one tap in the notification marks ✓. */
public class HabitReminder {
    private static final String CHANNEL_ID = "habit_reminders";
    private static final int NOTIFICATION_ID = 2001;

    public static void schedule(Context ctx) {
        AlarmManager am = ContextCompat.getSystemService(ctx, AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = alarmIntent(ctx);
        am.cancel(pi);
        NexoPrefs prefs = Nexo.prefs();
        if (!prefs.isHabitReminder()) return;
        int m = prefs.getHabitReminderMinutes();
        LocalDateTime at = LocalDate.now().atTime(m / 60, m % 60);
        if (!at.isAfter(LocalDateTime.now())) at = at.plusDays(1);
        long ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        // Android delays a plain alarm by up to an hour — the reminder needs an exact time
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setWindow(AlarmManager.RTC_WAKEUP, ms, 10 * 60_000L, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, ms, pi);
            }
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
            try { am.setWindow(AlarmManager.RTC_WAKEUP, ms, 10 * 60_000L, pi); } catch (Exception ignore) {}
        }
    }

    private static PendingIntent alarmIntent(Context ctx) {
        return PendingIntent.getBroadcast(
            ctx, 0, new Intent(ctx, HabitReceiver.class).setAction(HabitReceiver.ACTION_REMIND),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static void show(Context ctx) {
        show(ctx, false);
    }

    /** {@code update} — marked from the notification: only refreshes the existing notification */
    public static void show(Context ctx, boolean update) {
        NotificationManager nm = ContextCompat.getSystemService(ctx, NotificationManager.class);
        if (nm == null) return;
        HabitStore store = Nexo.store();
        LocalDate today = HabitStore.today();
        ArrayList<Habit> left = new ArrayList<>();
        for (Habit h : store.dueOn(today)) if (store.status(h.id, today) == Status.NONE) left.add(h);
        if (left.isEmpty()) {
            nm.cancel(NOTIFICATION_ID);
            if (update) Toast.makeText(ctx, "🎉 Everything marked for today!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Habit reminders", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Habit atRisk = null;
        int atRiskStreak = 0;
        for (Habit h : left) {
            int s = store.streak(h.id);
            if (s > atRiskStreak) { atRiskStreak = s; atRisk = h; }
        }
        StringBuilder sb = new StringBuilder();
        for (Habit h : left) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(h.emoji).append(' ').append(h.name);
        }
        if (atRisk != null) sb.append("\n🔥 ").append(atRisk.name).append(": ").append(atRiskStreak).append("-day streak — don't break it!");
        String text = sb.toString();
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL_ID) : new Notification.Builder(ctx))
            .setSmallIcon(R.drawable.notification)
            .setContentTitle("Not marked today: " + left.size())
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(Nexo.openHabitsPending(ctx))
            .setAutoCancel(true)
            .setOnlyAlertOnce(update);
        List<Habit> top = left.subList(0, Math.min(3, left.size()));
        for (Habit h : top) {
            Intent done = new Intent(ctx, HabitReceiver.class)
                .setAction(HabitReceiver.ACTION_DONE)
                .setData(Uri.parse("nexo-habit://done/" + h.id))
                .putExtra(HabitReceiver.EXTRA_ID, h.id);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, h.id.hashCode(), done, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            // int-icon constructor: the Icon variant is API 23+ and the fork's minSdk is 21
            b.addAction(new Notification.Action.Builder(0, "✓ " + h.name, pi).build());
        }
        nm.notify(NOTIFICATION_ID, b.build());
    }
}
