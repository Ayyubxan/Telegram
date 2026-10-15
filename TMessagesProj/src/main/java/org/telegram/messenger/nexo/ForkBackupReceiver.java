package org.telegram.messenger.nexo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.telegram.messenger.FileLog;

/** FORK MOD: alarm receiver — webhook retries (the old daily-backup action now only flushes webhooks too) */
public class ForkBackupReceiver extends BroadcastReceiver {
    public static final String ACTION_BACKUP = "org.telegram.messenger.fork.BACKUP";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            Webhooks.flush(ctx);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
