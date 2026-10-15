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
            Webhooks.flush(context);
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }
}
