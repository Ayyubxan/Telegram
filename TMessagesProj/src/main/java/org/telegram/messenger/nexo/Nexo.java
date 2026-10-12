package org.telegram.messenger.nexo;

import android.content.Context;
import android.content.Intent;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.LaunchActivity;

/**
 * FORK MOD (Nexo): entry point of the Mac-sync module (merged from the Nexo app) and the fork backup.
 * Holds the single {@link SyncEngine} (which owns {@link NexoPrefs}).
 */
public class Nexo {
    /** Intent actions understood by LaunchActivity (FORK MOD) */
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

    public static NexoPrefs prefs() {
        return engine().prefs;
    }

    /** Called once from ApplicationLoader.postInitApplication(): backups, Mac sync service */
    public static void init(Context ctx) {
        if (scheduled) return;
        scheduled = true;
        try {
            engine();
            ForkBackup.schedule(ctx);
            ForkBackup.checkDue(ctx);
            SyncService.start(ctx);
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }

    /** Intent that opens the Mac clipboard settings */
    public static Intent openIntent(Context ctx, String action) {
        return new Intent(ctx, LaunchActivity.class).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

}
