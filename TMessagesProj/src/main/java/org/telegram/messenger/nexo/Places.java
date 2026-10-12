package org.telegram.messenger.nexo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FORK MOD (Nexo): places — LocationManager proximity alerts (no Play services) plus the last known location on
 * every check. Visits (enter, exit) are stored per place and compared with the time window.
 */
public class Places {
    public static final String EXTRA_ENTERING = LocationManager.KEY_PROXIMITY_ENTERING;
    private static final long KEEP_MS = 3 * 24 * 3600_000L;
    private static SharedPreferences prefs;
    private static long lastAsk;

    private static synchronized SharedPreferences prefs() {
        if (prefs == null) prefs = Nexo.ctx().getSharedPreferences("places", Context.MODE_PRIVATE);
        return prefs;
    }

    public static boolean hasPermission(Context ctx) {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasBackground(Context ctx) {
        return Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private static List<Habit> habits() {
        ArrayList<Habit> out = new ArrayList<>();
        for (Habit h : AutoMarker.autoHabits()) {
            if (Auto.PLACE.equals(h.auto.kind) && h.auto.radius > 0) out.add(h);
        }
        return out;
    }

    private static PendingIntent intent(Context ctx, String id) {
        return PendingIntent.getBroadcast(
            ctx, 0,
            new Intent(ctx, HabitReceiver.class).setAction(HabitReceiver.ACTION_PLACE)
                .setData(Uri.parse("nexo-place://" + id)).putExtra(HabitReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Matches the proximity alerts to the current habits (removes the deleted ones) */
    @SuppressLint("MissingPermission")
    public static void sync(Context ctx) {
        LocationManager lm = ContextCompat.getSystemService(ctx, LocationManager.class);
        if (lm == null) return;
        List<Habit> list = habits();
        Set<String> old = prefs().getStringSet("registered", Collections.<String>emptySet());
        for (String id : old) {
            boolean still = false;
            for (Habit h : list) if (h.id.equals(id)) { still = true; break; }
            if (!still) {
                try { lm.removeProximityAlert(intent(ctx, id)); } catch (Exception ignore) {}
            }
        }
        if (!hasPermission(ctx)) {
            prefs().edit().putStringSet("registered", new HashSet<String>()).apply();
            return;
        }
        HashSet<String> ids = new HashSet<>();
        for (Habit h : list) {
            Auto a = h.auto;
            try { lm.addProximityAlert(a.lat, a.lon, (float) a.radius, -1, intent(ctx, h.id)); } catch (Exception ignore) {}
            ids.add(h.id);
        }
        prefs().edit().putStringSet("registered", ids).apply();
    }

    public static synchronized List<long[]> visits(String id) {
        JSONArray arr;
        try {
            arr = new JSONArray(prefs().getString(id, "[]"));
        } catch (Exception e) {
            arr = new JSONArray();
        }
        ArrayList<long[]> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONArray v = arr.getJSONArray(i);
                out.add(new long[]{v.getLong(0), v.getLong(1)});
            } catch (Exception ignore) {}
        }
        return out;
    }

    public static boolean update(String id, boolean inside) {
        return update(id, inside, System.currentTimeMillis());
    }

    /** Entered (true) or left (false) the place */
    public static synchronized boolean update(String id, boolean inside, long at) {
        ArrayList<long[]> list = new ArrayList<>();
        for (long[] v : visits(id)) if (v[1] == 0L || at - v[1] < KEEP_MS) list.add(v);
        long[] last = list.isEmpty() ? null : list.get(list.size() - 1);
        long[] open = last != null && last[1] == 0L ? last : null;
        boolean changed;
        if (inside && open == null) {
            list.add(new long[]{at, 0L});
            changed = true;
        } else if (!inside && open != null) {
            open[1] = Math.max(at, open[0]);
            changed = true;
        } else {
            changed = false;
        }
        if (changed) {
            JSONArray arr = new JSONArray();
            for (long[] v : list) {
                JSONArray a = new JSONArray();
                a.put(v[0]);
                a.put(v[1]);
                arr.put(a);
            }
            prefs().edit().putString(id, arr.toString()).apply();
        }
        return changed;
    }

    /** The place's window for that day (epoch ms). No window — null; prayer times not loaded yet — empty array */
    public static long[] window(Auto a, LocalDate date) {
        if (!a.hasWindow()) return null;
        Integer f = Repeat.resolve(a.from, date);
        if (f == null) return new long[0];
        Integer t = Repeat.resolve(a.to, date);
        if (t == null) return new long[0];
        long start = ScreenTime.dayStart(date);
        return new long[]{start + f * 60_000L, start + (t > f ? t : 24 * 60) * 60_000L};
    }

    /** Updates the visits from the last known location; now and then asks for a fresh one too */
    @SuppressLint("MissingPermission")
    public static void poll(final Context ctx) {
        List<Habit> list = habits();
        if (list.isEmpty() || !hasPermission(ctx)) return;
        LocationManager lm = ContextCompat.getSystemService(ctx, LocationManager.class);
        if (lm == null) return;
        ArrayList<String> providers = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) providers.add(LocationManager.FUSED_PROVIDER);
        providers.add(LocationManager.GPS_PROVIDER);
        providers.add(LocationManager.NETWORK_PROVIDER);
        Location best = null;
        for (String p : providers) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Exception ignore) {}
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < 20 * 60_000L) apply(best);
        long now = System.currentTimeMillis();
        if (Build.VERSION.SDK_INT >= 30 && now - lastAsk > 10 * 60_000L) {
            lastAsk = now;
            String provider = null;
            for (String p : providers) {
                boolean enabled = false;
                try { enabled = lm.isProviderEnabled(p); } catch (Exception ignore) {}
                if (enabled) { provider = p; break; }
            }
            if (provider == null) return;
            try {
                lm.getCurrentLocation(provider, null, ctx.getMainExecutor(), loc -> {
                    if (loc != null && apply(loc)) AutoMarker.run(ctx, true);
                });
            } catch (Exception e) {
                org.telegram.messenger.FileLog.e(e);
            }
        }
    }

    /** True if any place state changed */
    private static boolean apply(Location loc) {
        boolean any = false;
        for (Habit h : habits()) {
            Auto a = h.auto;
            double d = AutoRules.distance(loc.getLatitude(), loc.getLongitude(), a.lat, a.lon);
            // Even with poor accuracy — inside when close to the center
            boolean inside = d <= a.radius + Math.min((double) loc.getAccuracy(), 100.0);
            if (update(h.id, inside, loc.getTime())) any = true;
        }
        return any;
    }
}
