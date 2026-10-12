package org.telegram.messenger.nexo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.R;

/** FORK MOD (Nexo): travelling — when the phone's time zone changes, remind to refresh the prayer times */
public class PrayerTravel {
    public static final String CHANNEL_ID = "habit_reminders";
    private static final int NOTIFICATION_ID = 2002;

    public static void notify(Context ctx) {
        boolean any = false;
        for (Habit h : Nexo.store().active()) {
            for (String t : h.repeat.times) if (Repeat.isPrayer(t)) { any = true; break; }
            if (any) break;
        }
        if (!any) return;
        String text = PrayerTimes.isAuto()
            ? "Open Habits to fetch the prayer times for the new place."
            : "Prayer times are still for " + PrayerTimes.city().name + ". Settings → Habits → Prayer times → City → \"My location\".";
        NotificationManager nm = ContextCompat.getSystemService(ctx, NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Habit reminders", NotificationManager.IMPORTANCE_DEFAULT));
        }
        nm.notify(NOTIFICATION_ID, (Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL_ID) : new Notification.Builder(ctx))
            .setSmallIcon(R.drawable.notification)
            .setContentTitle("🕌 Time zone changed")
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(Nexo.openHabitsPending(ctx))
            .setAutoCancel(true)
            .build());
    }
}
