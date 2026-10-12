package org.telegram.messenger.nexo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * FORK MOD (Nexo): prayer times — Aladhan API (https://aladhan.com), monthly schedule by coordinates.
 * Not requested by city name: the API silently substitutes an unknown name with another city.
 * The schedule is stored on the phone — reminders work offline.
 */
public class PrayerTimes {
    /** Key (stored in habit times as "@fajr+10") → English name */
    public static final LinkedHashMap<String, String> NAMES = new LinkedHashMap<>();
    private static final HashMap<String, String> API_FIELDS = new HashMap<>();
    static {
        NAMES.put("fajr", "Fajr"); NAMES.put("sunrise", "Sunrise"); NAMES.put("dhuhr", "Dhuhr");
        NAMES.put("asr", "Asr"); NAMES.put("maghrib", "Maghrib"); NAMES.put("isha", "Isha");
        API_FIELDS.put("fajr", "Fajr"); API_FIELDS.put("sunrise", "Sunrise"); API_FIELDS.put("dhuhr", "Dhuhr");
        API_FIELDS.put("asr", "Asr"); API_FIELDS.put("maghrib", "Maghrib"); API_FIELDS.put("isha", "Isha");
    }

    public static class City {
        public final String name;
        public final double lat;
        public final double lon;
        public City(String name, double lat, double lon) { this.name = name; this.lat = lat; this.lon = lon; }
    }

    public static final List<City> CITIES = Arrays.asList(
        new City("Tashkent", 41.311, 69.240), new City("Samarkand", 39.655, 66.960), new City("Bukhara", 39.768, 64.421),
        new City("Andijan", 40.783, 72.350), new City("Namangan", 40.998, 71.672), new City("Fergana", 40.384, 71.784),
        new City("Kokand", 40.529, 70.943), new City("Karshi", 38.861, 65.790), new City("Termez", 37.224, 67.278),
        new City("Jizzakh", 40.116, 67.842), new City("Gulistan", 40.490, 68.785), new City("Navoi", 40.084, 65.379),
        new City("Urgench", 41.550, 60.631), new City("Khiva", 41.378, 60.364), new City("Nukus", 42.460, 59.603)
    );

    /** Phone location instead of a city — correct times when travelling */
    public static final String AUTO = "auto";

    /** Aladhan: 1 — Hanafi (later Asr), 0 — Shafi */
    public static final int[] SCHOOL_IDS = {1, 0};
    public static final String[] SCHOOL_NAMES = {"Hanafi", "Shafi"};

    public static String schoolName(int id) {
        for (int i = 0; i < SCHOOL_IDS.length; i++) if (SCHOOL_IDS[i] == id) return SCHOOL_NAMES[i];
        return SCHOOL_NAMES[0];
    }

    private static final int METHOD = 3; // Muslim World League

    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    public static void addListener(Runnable l) { listeners.add(l); }
    public static void removeListener(Runnable l) { listeners.remove(l); }

    /** "yyyy-MM-dd" → (key → minute) */
    private static final HashMap<String, Map<String, Integer>> days = new HashMap<>();
    private static volatile String loadedFor;
    private static volatile boolean loading;
    public static volatile String lastError;
    private static final Object lock = new Object();

    private static File file(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), "prayer_times.json");
    }

    /** Settings (place + school) — when they change the schedule is fetched again */
    private static String settingsKey() {
        City c = city();
        return String.format(Locale.US, "%.2f,%.2f|%d", c.lat, c.lon, Nexo.prefs().getPrayerSchool());
    }

    public static boolean isAuto() {
        return AUTO.equals(Nexo.prefs().getPrayerCity());
    }

    private static boolean hasLocation() {
        NexoPrefs p = Nexo.prefs();
        return p.getPrayerLat() != 0.0 || p.getPrayerLon() != 0.0;
    }

    public static City city() {
        NexoPrefs p = Nexo.prefs();
        if (AUTO.equals(p.getPrayerCity())) {
            String place = p.getPrayerPlace();
            return new City(place == null ? "My location" : place, p.getPrayerLat(), p.getPrayerLon());
        }
        for (City c : CITIES) if (c.name.equals(p.getPrayerCity())) return c;
        return CITIES.get(0);
    }

    /** Can the location be read while the app is closed (Android 10+: "Allow all the time") */
    public static boolean canBackground(Context ctx) {
        boolean coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return coarse && (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED);
    }

    /** Location providers toggled — refresh if the app is open or background permission exists */
    public static final BroadcastReceiver providersReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isAuto()) refreshLocation(context);
        }
    };

    /**
     * "My location": approximate (network) location. Moved more than 25 km — the place is updated and the schedule
     * reloaded. Called only while the app is open (no background location requests).
     */
    @SuppressLint("MissingPermission")
    public static void refreshLocation(Context ctx) {
        if (!isAuto()) return;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            lastError = "Location permission not granted";
            return;
        }
        LocationManager lm = ContextCompat.getSystemService(ctx, LocationManager.class);
        if (lm == null) return;
        Location last = null;
        for (String p : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (last == null || l.getTime() > last.getTime())) last = l;
            } catch (Exception ignore) {}
        }
        final Location lastKnown = last;
        // Last location older than 3 hours — ask for a fresh one (a stale place misleads when travelling)
        if (last != null && System.currentTimeMillis() - last.getTime() < 3 * 3600_000L) {
            useLocation(ctx, last);
        } else if (Build.VERSION.SDK_INT >= 30) {
            // First available: fused (Google) → network → GPS (coarse permission gives a rough GPS fix too)
            ArrayList<String> providers = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 31) providers.add(LocationManager.FUSED_PROVIDER);
            providers.add(LocationManager.NETWORK_PROVIDER);
            providers.add(LocationManager.GPS_PROVIDER);
            String provider = null;
            for (String p : providers) {
                try { if (lm.isProviderEnabled(p)) { provider = p; break; } } catch (Exception ignore) {}
            }
            if (provider == null) {
                useLocation(ctx, lastKnown);
            } else {
                try {
                    lm.getCurrentLocation(provider, null, ctx.getMainExecutor(), loc -> useLocation(ctx, loc != null ? loc : lastKnown));
                } catch (Exception e) {
                    useLocation(ctx, lastKnown);
                }
            }
        } else {
            useLocation(ctx, last);
        }
    }

    private static void useLocation(Context ctx, Location loc) {
        if (loc == null) return;
        NexoPrefs p = Nexo.prefs();
        float[] moved = new float[1];
        Location.distanceBetween(p.getPrayerLat(), p.getPrayerLon(), loc.getLatitude(), loc.getLongitude(), moved);
        if (hasLocation() && moved[0] < 25_000) return;
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            // Place name is only for display; coordinates if not found
            String place = null;
            try {
                List<Address> list = new Geocoder(app).getFromLocation(loc.getLatitude(), loc.getLongitude(), 1);
                if (list != null && !list.isEmpty()) {
                    Address a = list.get(0);
                    String locality = a.getLocality() != null ? a.getLocality() : a.getSubAdminArea() != null ? a.getSubAdminArea() : a.getAdminArea();
                    StringBuilder sb = new StringBuilder();
                    if (locality != null) sb.append(locality);
                    if (a.getCountryName() != null) { if (sb.length() > 0) sb.append(", "); sb.append(a.getCountryName()); }
                    if (sb.length() > 0) place = sb.toString();
                }
            } catch (Exception ignore) {}
            if (place == null) place = String.format(Locale.US, "%.2f, %.2f", loc.getLatitude(), loc.getLongitude());
            p.setPrayerLat(loc.getLatitude());
            p.setPrayerLon(loc.getLongitude());
            p.setPrayerPlace(place);
            reset(app);
        }, "nexo-place").start();
    }

    private static void load(Context ctx) {
        synchronized (lock) {
            if (settingsKey().equals(loadedFor)) return;
            days.clear();
            try {
                File f = file(ctx);
                if (f.exists()) {
                    byte[] bytes = new byte[(int) f.length()];
                    try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                        int off = 0;
                        while (off < bytes.length) { int n = in.read(bytes, off, bytes.length - off); if (n < 0) break; off += n; }
                    }
                    JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                    if (settingsKey().equals(o.optString("settings"))) {
                        JSONObject d = o.getJSONObject("days");
                        Iterator<String> it = d.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            JSONObject t = d.getJSONObject(k);
                            HashMap<String, Integer> m = new HashMap<>();
                            for (String key : NAMES.keySet()) m.put(key, t.getInt(key));
                            days.put(k, m);
                        }
                    }
                }
            } catch (Exception ignore) {}
            loadedFor = settingsKey();
        }
    }

    /** Prayer minute of the day for that date. Not in the schedule — null, and fetched in the background. */
    public static Integer minute(Context ctx, LocalDate date, String key) {
        load(ctx);
        Integer m;
        synchronized (lock) {
            Map<String, Integer> d = days.get(date.toString());
            m = d == null ? null : d.get(key);
        }
        if (m == null) ensure(ctx);
        return m;
    }

    public static void ensure(Context ctx) {
        ensure(ctx, false);
    }

    /** Makes sure this and next month's schedule exist (fetches in the background otherwise) */
    public static void ensure(Context ctx, boolean force) {
        load(ctx);
        YearMonth now = YearMonth.now();
        if (isAuto() && !hasLocation()) {
            lastError = "Location not determined yet";
            return;
        }
        ArrayList<YearMonth> need = new ArrayList<>();
        for (YearMonth m : new YearMonth[]{now, now.plusMonths(1)}) {
            boolean missing;
            synchronized (lock) {
                missing = days.get(m.atDay(1).toString()) == null || days.get(m.atEndOfMonth().toString()) == null;
            }
            if (force || missing) need.add(m);
        }
        if (need.isEmpty() || loading) return;
        loading = true;
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                for (YearMonth m : need) fetch(app, m);
                lastError = null;
            } catch (Exception e) {
                lastError = "No internet or the server did not respond";
            } finally {
                loading = false;
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                for (Runnable l : listeners) l.run();
                HabitAlarms.schedule(app);
            });
        }, "nexo-prayer").start();
    }

    /** Settings changed: the old schedule is invalid */
    public static void reset(Context ctx) {
        synchronized (lock) {
            days.clear();
            loadedFor = settingsKey();
        }
        file(ctx).delete();
        ensure(ctx, true);
    }

    private static void fetch(Context ctx, YearMonth month) throws Exception {
        City c = city();
        int school = Nexo.prefs().getPrayerSchool();
        URL url = new URL("https://api.aladhan.com/v1/calendar/" + month.getYear() + "/" + month.getMonthValue()
            + "?latitude=" + c.lat + "&longitude=" + c.lon + "&method=" + METHOD + "&school=" + school);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);
        String body;
        try {
            if (conn.getResponseCode() != 200) throw new IllegalStateException("HTTP " + conn.getResponseCode());
            StringBuilder sb = new StringBuilder();
            try (InputStreamReader r = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            }
            body = sb.toString();
        } finally {
            conn.disconnect();
        }
        org.json.JSONArray data = new JSONObject(body).getJSONArray("data");
        HashMap<String, Map<String, Integer>> parsed = new HashMap<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject day = data.getJSONObject(i);
            // "09-10-2026" → "2026-10-09"
            String[] dmy = day.getJSONObject("date").getJSONObject("gregorian").getString("date").split("-");
            JSONObject t = day.getJSONObject("timings");
            HashMap<String, Integer> m = new HashMap<>();
            for (Map.Entry<String, String> e : API_FIELDS.entrySet()) {
                // "04:47 (+05)" → 287
                String s = t.getString(e.getValue());
                Integer min = Repeat.minutes(s.length() > 5 ? s.substring(0, 5) : s);
                m.put(e.getKey(), min == null ? 0 : min);
            }
            parsed.put(dmy[2] + "-" + dmy[1] + "-" + dmy[0], m);
        }
        synchronized (lock) {
            days.putAll(parsed);
            // Keep from last month on
            String keep = YearMonth.now().minusMonths(1).atDay(1).toString();
            Iterator<String> it = days.keySet().iterator();
            while (it.hasNext()) if (it.next().compareTo(keep) < 0) it.remove();
            JSONObject out = new JSONObject();
            for (Map.Entry<String, Map<String, Integer>> e : days.entrySet()) out.put(e.getKey(), new JSONObject(e.getValue()));
            byte[] bytes = new JSONObject().put("settings", settingsKey()).put("days", out).toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fo = new FileOutputStream(file(ctx))) { fo.write(bytes); }
        }
    }

    /** "Fajr 04:56 · Dhuhr 12:10 …" — for the settings screen; null while loading */
    public static String todayLine(Context ctx) {
        LocalDate today = LocalDate.now();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : NAMES.entrySet()) {
            Integer m = minute(ctx, today, e.getKey());
            if (m == null) return null;
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(e.getValue()).append(' ').append(Repeat.hhmm(m));
        }
        return sb.toString();
    }
}
