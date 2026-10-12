package org.telegram.messenger.nexo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** FORK MOD (Nexo): starts the sync service after a reboot or an app update and re-schedules the reminders. */
public class NexoBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            SyncService.start(context);
            // When the time or the time zone changes the reminders are re-set to the right time too
            HabitReminder.schedule(context);
            HabitAlarms.schedule(context);
            if (Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())) PrayerTravel.notify(context);
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }
}
