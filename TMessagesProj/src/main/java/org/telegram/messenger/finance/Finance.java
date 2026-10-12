package org.telegram.messenger.finance;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * FORK MOD (Finance): entry point of the finance module (expense tracker + wealth tracker + debts).
 * One SQLite database ({@link FinanceDb}), exchange rates ({@link Rates}) and a tiny preference store.
 */
public class Finance {
    public static final String ACTION_OPEN_FINANCE = "org.telegram.messenger.OPEN_FINANCE";

    private static volatile FinanceDb db;
    private static volatile boolean inited;
    private static final List<Runnable> listeners = new ArrayList<>();
    private static final SecureRandom random = new SecureRandom();
    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    public static Context ctx() {
        return ApplicationLoader.applicationContext;
    }

    public static FinanceDb db() {
        if (db == null) {
            synchronized (Finance.class) {
                if (db == null) db = new FinanceDb(ctx());
            }
        }
        return db;
    }

    public static SharedPreferences prefs() {
        return ctx().getSharedPreferences("finance", Context.MODE_PRIVATE);
    }

    /** Base (display) currency, UZS by default */
    public static String base() {
        return prefs().getString("base", "UZS");
    }

    /** Display currency for an entity (null → the global base) */
    public static String baseFor(String entityId) {
        if (entityId != null) {
            Entity e = db().entity(entityId);
            if (e != null && e.currency != null && !e.currency.isEmpty()) return e.currency;
        }
        return base();
    }

    public static void setBase(String code) {
        prefs().edit().putString("base", code).apply();
        notifyChanged();
    }

    /** Called once from Nexo.init(): opens the DB, seeds defaults and refreshes stale rates */
    public static void init(Context ctx) {
        if (inited) return;
        inited = true;
        try {
            db().seedDefaults();
            Rates.refreshIfStale(null);
            BotImport.init();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static String newId() {
        StringBuilder sb = new StringBuilder(Long.toString(System.currentTimeMillis(), 36));
        for (int i = 0; i < 4; i++) sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        return sb.toString();
    }

    // region Change listeners (UI thread)

    public static void addListener(Runnable r) {
        synchronized (listeners) { if (!listeners.contains(r)) listeners.add(r); }
    }

    public static void removeListener(Runnable r) {
        synchronized (listeners) { listeners.remove(r); }
    }

    public static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            List<Runnable> copy;
            synchronized (listeners) { copy = new ArrayList<>(listeners); }
            for (Runnable r : copy) {
                try { r.run(); } catch (Throwable e) { FileLog.e(e); }
            }
        });
    }

    // endregion
}
