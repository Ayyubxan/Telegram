package org.telegram.messenger.nexo;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Process;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.util.Pair;

import androidx.core.content.ContextCompat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FORK MOD (Nexo): screen time via Android's "Usage access" permission.
 * How long an app was used, whether it was opened, and screen on/off times for sleep detection.
 */
public class ScreenTime {

    public static class AppInfo {
        public final String pkg;
        public final String label;
        public final Drawable icon;

        public AppInfo(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private interface Include {
        boolean test(String pkg);
    }

    private static ZoneId zone() {
        return ZoneId.systemDefault();
    }

    public static long dayStart(LocalDate date) {
        return date.atStartOfDay(zone()).toInstant().toEpochMilli();
    }

    public static boolean hasAccess(Context ctx) {
        try {
            AppOpsManager ops = ContextCompat.getSystemService(ctx, AppOpsManager.class);
            if (ops == null) return false;
            int mode;
            if (Build.VERSION.SDK_INT >= 29) {
                mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
            } else {
                mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
            return false;
        }
    }

    public static void openAccessSettings(Context ctx) {
        try {
            ctx.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }

    /** How long the app was in the foreground that day (so far), whether it was opened and in which intervals */
    public static AppUsage usage(Context ctx, final String pkg, LocalDate date) {
        AppUsage u = perApp(ctx, date, new Include() {
            @Override
            public boolean test(String p) {
                return p.equals(pkg);
            }
        }).get(pkg);
        return u != null ? u : new AppUsage(0, false);
    }

    /** Total screen time: all apps except the launcher / this app / system panels */
    public static AppUsage total(Context ctx, LocalDate date) {
        final Set<String> skip = notCounted(ctx);
        Map<String, AppUsage> all = perApp(ctx, date, new Include() {
            @Override
            public boolean test(String p) {
                return !skip.contains(p);
            }
        });
        int minutes = 0;
        boolean opened = false;
        ArrayList<long[]> intervals = new ArrayList<>();
        for (AppUsage u : all.values()) {
            minutes += u.minutes;
            if (u.opened) opened = true;
            intervals.addAll(u.intervals);
        }
        return new AppUsage(minutes, opened, intervals);
    }

    private static Map<String, AppUsage> perApp(Context ctx, LocalDate date, Include include) {
        if (!hasAccess(ctx)) return Collections.emptyMap();
        long from = dayStart(date);
        long to = Math.min(dayStart(date.plusDays(1)), System.currentTimeMillis());
        if (to <= from) return Collections.emptyMap();
        UsageStatsManager usm = ContextCompat.getSystemService(ctx, UsageStatsManager.class);
        if (usm == null) return Collections.emptyMap();
        UsageEvents events = usm.queryEvents(from, to);
        if (events == null) return Collections.emptyMap();
        UsageEvents.Event e = new UsageEvents.Event();
        HashMap<String, Long> since = new HashMap<>();
        HashSet<String> seen = new HashSet<>();
        HashMap<String, List<long[]>> intervals = new HashMap<>();
        HashSet<String> opened = new HashSet<>();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            String pkg = e.getPackageName();
            if (pkg == null || !include.test(pkg)) continue;
            int type = e.getEventType();
            if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                opened.add(pkg);
                if (!since.containsKey(pkg)) since.put(pkg, e.getTimeStamp());
            } else if (type == UsageEvents.Event.ACTIVITY_PAUSED || type == UsageEvents.Event.ACTIVITY_STOPPED) {
                // Opened before midnight — count from the start of the day
                Long start = since.remove(pkg);
                if (start == null && !seen.contains(pkg)) start = from;
                if (start != null && e.getTimeStamp() > start) {
                    List<long[]> list = intervals.get(pkg);
                    if (list == null) { list = new ArrayList<>(); intervals.put(pkg, list); }
                    list.add(new long[]{start, e.getTimeStamp()});
                }
            } else {
                continue;
            }
            seen.add(pkg);
        }
        for (Map.Entry<String, Long> en : since.entrySet()) {
            List<long[]> list = intervals.get(en.getKey());
            if (list == null) { list = new ArrayList<>(); intervals.put(en.getKey(), list); }
            list.add(new long[]{en.getValue(), to});
        }
        HashSet<String> keys = new HashSet<>(intervals.keySet());
        keys.addAll(opened);
        HashMap<String, AppUsage> out = new HashMap<>();
        for (String pkg : keys) {
            List<long[]> list = intervals.get(pkg);
            if (list == null) list = new ArrayList<>();
            long ms = 0;
            for (long[] iv : list) ms += iv[1] - iv[0];
            out.put(pkg, new AppUsage((int) (ms / 60_000), opened.contains(pkg), list));
        }
        return out;
    }

    private static String resolvePkg(PackageManager pm, Intent i) {
        try {
            ResolveInfo ri = pm.resolveActivity(i, 0);
            return ri == null || ri.activityInfo == null ? null : ri.activityInfo.packageName;
        } catch (Exception e) {
            return null;
        }
    }

    /** Not counted in the total: launcher, this app, system UI, keyboard */
    private static Set<String> notCounted(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        HashSet<String> out = new HashSet<>();
        String home = resolvePkg(pm, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        if (home != null) out.add(home);
        out.add(ctx.getPackageName());
        out.add("com.android.systemui");
        out.add("com.google.android.permissioncontroller");
        out.add("com.android.permissioncontroller");
        out.add("android");
        try {
            String ime = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime != null) {
                int slash = ime.indexOf('/');
                out.add(slash >= 0 ? ime.substring(0, slash) : ime);
            }
        } catch (Exception ignore) {}
        return out;
    }

    /** Not blocked even when the total limit is over: phone, SMS, camera, Settings (+ the uncounted ones) */
    public static Set<String> essential(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> out = notCounted(ctx);
        if (Build.VERSION.SDK_INT >= 23) { // getDefaultDialerPackage is API 23+ (NoSuchMethodError is not an Exception)
            try {
                TelecomManager tm = ContextCompat.getSystemService(ctx, TelecomManager.class);
                String dialer = tm == null ? null : tm.getDefaultDialerPackage();
                if (dialer != null) out.add(dialer);
            } catch (Exception ignore) {}
        }
        try {
            String sms = Telephony.Sms.getDefaultSmsPackage(ctx);
            if (sms != null) out.add(sms);
        } catch (Exception ignore) {}
        String camera = resolvePkg(pm, new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
        if (camera != null) out.add(camera);
        out.add("com.android.settings");
        out.add("com.android.phone");
        return out;
    }

    /**
     * Sleep that ended on {@code date}: the longest stretch without the phone between yesterday 18:00 and today 14:00.
     * Android 9+ (screen on/off events).
     */
    public static Sleep sleep(Context ctx, LocalDate date) {
        if (Build.VERSION.SDK_INT < 28 || !hasAccess(ctx)) return null;
        long from = dayStart(date.minusDays(1)) + 18 * 3600_000L;
        long to = dayStart(date) + 14 * 3600_000L;
        UsageStatsManager usm = ContextCompat.getSystemService(ctx, UsageStatsManager.class);
        if (usm == null) return null;
        // Read a bit earlier to know the state at the window start
        UsageEvents events = usm.queryEvents(from - 6 * 3600_000L, Math.min(to, System.currentTimeMillis()));
        if (events == null) return null;
        UsageEvents.Event e = new UsageEvents.Event();
        ArrayList<Pair<Long, Boolean>> list = new ArrayList<>();
        boolean onAtStart = false;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            boolean on;
            int type = e.getEventType();
            if (type == UsageEvents.Event.SCREEN_INTERACTIVE) on = true;
            else if (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE) on = false;
            else continue;
            if (e.getTimeStamp() < from) onAtStart = on;
            else list.add(new Pair<>(e.getTimeStamp(), on));
        }
        return SleepDetector.detect(list, from, to, onAtStart, System.currentTimeMillis());
    }

    /** How many times the phone was unlocked that day. Without a lock screen — screen-on count. */
    public static int unlocks(Context ctx, LocalDate date) {
        if (Build.VERSION.SDK_INT < 28 || !hasAccess(ctx)) return 0;
        long from = dayStart(date);
        long to = Math.min(dayStart(date.plusDays(1)), System.currentTimeMillis());
        if (to <= from) return 0;
        UsageStatsManager usm = ContextCompat.getSystemService(ctx, UsageStatsManager.class);
        if (usm == null) return 0;
        UsageEvents events = usm.queryEvents(from, to);
        if (events == null) return 0;
        UsageEvents.Event e = new UsageEvents.Event();
        int unlocks = 0;
        int screenOn = 0;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            int type = e.getEventType();
            if (type == UsageEvents.Event.KEYGUARD_HIDDEN) unlocks++;
            else if (type == UsageEvents.Event.SCREEN_INTERACTIVE) screenOn++;
        }
        return unlocks > 0 ? unlocks : screenOn;
    }

    /** Wake-up time on {@code date}: the first screen-on after the night's sleep (alarm counts too) */
    public static Long wake(Context ctx, LocalDate date) {
        Sleep s = sleep(ctx, date);
        if (s == null) return null;
        UsageStatsManager usm = ContextCompat.getSystemService(ctx, UsageStatsManager.class);
        if (usm == null) return s.end;
        UsageEvents events = usm.queryEvents(s.start + 60_000L, s.end + 1);
        if (events == null) return s.end;
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.SCREEN_INTERACTIVE) return e.getTimeStamp();
        }
        return s.end;
    }

    /** Morning app intervals: like the total, but without the alarm/clock app */
    public static List<long[]> morningUsage(Context ctx, LocalDate date) {
        final Set<String> skip = notCounted(ctx);
        String clock = resolvePkg(ctx.getPackageManager(), new Intent(AlarmClock.ACTION_SHOW_ALARMS));
        if (clock != null) skip.add(clock);
        skip.add("com.google.android.deskclock");
        skip.add("com.android.deskclock");
        Map<String, AppUsage> all = perApp(ctx, date, new Include() {
            @Override
            public boolean test(String p) {
                return !skip.contains(p);
            }
        });
        ArrayList<long[]> out = new ArrayList<>();
        for (AppUsage u : all.values()) out.addAll(u.intervals);
        return out;
    }

    /** Apps on the home screen (alphabetical) */
    public static List<AppInfo> launcherApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        ArrayList<AppInfo> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        List<ResolveInfo> list;
        try {
            list = pm.queryIntentActivities(intent, 0);
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
            return out;
        }
        for (ResolveInfo ri : list) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || !seen.add(pkg)) continue;
            Drawable icon = null;
            try {
                icon = ri.loadIcon(pm);
            } catch (Exception ignore) {}
            CharSequence label = ri.loadLabel(pm);
            out.add(new AppInfo(pkg, label == null ? pkg : label.toString(), icon));
        }
        Collections.sort(out, (a, b) -> a.label.toLowerCase().compareTo(b.label.toLowerCase()));
        return out;
    }
}
