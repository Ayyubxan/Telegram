package org.telegram.messenger.nexo;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.LaunchActivity;

/**
 * FORK MOD (Nexo): entry point of the habits / Mac-sync module merged from the Nexo app.
 * Holds the single {@link SyncEngine} (which owns the {@link HabitStore} and {@link NexoPrefs}).
 */
public class Nexo {
    /** Intent actions understood by LaunchActivity (FORK MOD) */
    public static final String ACTION_OPEN_HABITS = "org.telegram.messenger.OPEN_HABITS";
    public static final String ACTION_OPEN_HABITS_HEALTH = "org.telegram.messenger.OPEN_HABITS_HEALTH";
    public static final String ACTION_OPEN_MAC_SYNC = "org.telegram.messenger.OPEN_MAC_SYNC";

    private static volatile SyncEngine engine;
    private static volatile boolean scheduled;

    public static Context ctx() {
        return ApplicationLoader.applicationContext;
    }

    public static SyncEngine engine() {
        if (engine == null) {
            synchronized (Nexo.class) {
                if (engine == null) {
                    engine = new SyncEngine(ctx());
                }
            }
        }
        return engine;
    }

    public static HabitStore store() {
        return engine().habits;
    }

    public static NexoPrefs prefs() {
        return engine().prefs;
    }

    /** Called once from ApplicationLoader.postInitApplication(): schedule reminders, alarms, backups, auto-marking */
    public static void init(Context ctx) {
        if (scheduled) return;
        scheduled = true;
        try {
            engine();
            HabitReminder.schedule(ctx);
            HabitAlarms.schedule(ctx);
            HabitBackup.schedule(ctx);
            AutoMarker.schedule(ctx);
            ForkBackup.schedule(ctx);
            ForkBackup.checkDue(ctx);
            org.telegram.messenger.finance.Finance.init(ctx); // FORK MOD (Finance)
            if (needsPrayerTimes()) PrayerTimes.ensure(ctx);
            SyncService.start(ctx);
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }

    public static boolean needsPrayerTimes() {
        for (Habit h : store().active()) {
            for (String t : h.repeat.times) if (Repeat.isPrayer(t)) return true;
            if (h.auto != null && Repeat.isPrayer(h.auto.from)) return true;
        }
        return false;
    }

    /** Intent that opens the app on the Habits tab */
    public static Intent openIntent(Context ctx, String action) {
        return new Intent(ctx, LaunchActivity.class).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    public static PendingIntent openHabitsPending(Context ctx) {
        return PendingIntent.getActivity(ctx, 7001, openIntent(ctx, ACTION_OPEN_HABITS), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
