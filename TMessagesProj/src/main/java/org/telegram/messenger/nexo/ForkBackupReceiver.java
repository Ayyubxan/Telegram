package org.telegram.messenger.nexo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.telegram.messenger.FileLog;

/** FORK MOD: the daily backup alarm — sends the fork backup to the chosen chat (once online) and writes the local copy */
public class ForkBackupReceiver extends BroadcastReceiver {
    public static final String ACTION_BACKUP = "org.telegram.messenger.fork.BACKUP";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            ForkBackup.checkDue(ctx);
            LocalBackup.schedule(ctx);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
