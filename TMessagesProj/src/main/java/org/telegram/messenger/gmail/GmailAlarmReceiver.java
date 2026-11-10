package org.telegram.messenger.gmail;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.telegram.messenger.ApplicationLoader;

/** FORK MOD (gmail): the poll alarm; keeps the process alive (goAsync) until the sync finished */
public class GmailAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        ApplicationLoader.postInitApplication();
        final PendingResult pr = goAsync();
        GmailSync.schedule(context);
        GmailSync.sync("alarm", () -> {
            try {
                pr.finish();
            } catch (Throwable ignore) {}
        });
    }
}
