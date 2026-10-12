package org.telegram.messenger.nexo;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FORK MOD (Nexo): full-screen block when a limit is over or an "avoid" app is opened. The foreground app
 * comes from the accessibility service (it has to be enabled).
 */
public class AppGuard {
    private static final long WATCH_MS = 30_000L;
    /** Overlays (system UI, permission dialog, keyboard) — the app underneath did not change */
    private static final Set<String> TRANSIENT = new HashSet<>(Arrays.asList(
        "com.android.systemui", "com.google.android.permissioncontroller", "com.android.permissioncontroller"));

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static String current;
    private static Context app;
    /** "Open anyway" — apps allowed for the day */
    private static final HashMap<String, LocalDate> allowed = new HashMap<>();

    public static void allow(String pkg) {
        allowed.put(pkg, LocalDate.now());
    }

    /**
     * While a watched app is in front we re-check every {@link #WATCH_MS}: the limit may run out during this
     * session, or Android may not send a new event after an overlay closes — we don't rely on an exact time.
     */
    private static final Runnable watch = new Runnable() {
        @Override
        public void run() {
            String pkg = current;
            if (pkg == null) return;
            if (check(pkg)) main.postDelayed(this, WATCH_MS);
        }
    };

    public static void onForeground(Context ctx, String pkg) {
        if (pkg == null || TRANSIENT.contains(pkg) || pkg.contains("inputmethod")) return;
        app = ctx.getApplicationContext();
        if (pkg.equals(current)) return;
        current = pkg;
        main.removeCallbacks(watch);
        if (check(pkg)) main.postDelayed(watch, WATCH_MS);
    }

    private static List<Habit> rules(String pkg) {
        HabitStore store = Nexo.store();
        LocalDate today = LocalDate.now();
        // Total limit: everything except the essential apps (phone, SMS, camera, Settings)
        Set<String> essential = null;
        ArrayList<Habit> out = new ArrayList<>();
        for (Habit h : AutoMarker.autoHabits()) {
            Auto a = h.auto;
            if (!a.blocks()) continue;
            boolean match;
            if (a.pkg.equals(pkg)) {
                match = true;
            } else if (a.isTotal()) {
                if (essential == null) essential = ScreenTime.essential(app);
                match = !essential.contains(pkg);
            } else {
                match = false;
            }
            if (match && store.isDue(h, today)) out.add(h);
        }
        return out;
    }

    /** Blocks, or returns whether watching should continue (true) */
    private static boolean check(String pkg) {
        if (app == null) return false;
        if (pkg.equals(app.getPackageName()) || LocalDate.now().equals(allowed.get(pkg))) return false;
        List<Habit> rules = rules(pkg);
        if (rules.isEmpty()) return false;
        AutoMarker.run(app, true);
        LocalDate today = LocalDate.now();
        LocalTime lt = LocalTime.now();
        int now = lt.getHour() * 60 + lt.getMinute();
        for (Habit h : rules) {
            Auto a = h.auto;
            boolean hit;
            if (Auto.AVOID.equals(a.mode)) {
                // Windowed avoid: only inside the window; outside we keep watching (the window may start)
                hit = AutoRules.inWindow(a, now);
            } else if (a.isTotal()) {
                hit = ScreenTime.total(app, today).minutes >= a.minutes;
            } else {
                hit = ScreenTime.usage(app, pkg, today).minutes >= a.minutes;
            }
            if (hit) {
                block(h, pkg);
                return false;
            }
        }
        return true;
    }

    private static void block(Habit h, String pkg) {
        current = null;
        try {
            app.startActivity(new Intent(app, BlockActivity.class)
                .putExtra(BlockActivity.EXTRA_ID, h.id)
                .putExtra(BlockActivity.EXTRA_PKG, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }
}
