package org.telegram.messenger.nexo;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.health.connect.AggregateRecordsRequest;
import android.health.connect.AggregateRecordsResponse;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.HealthPermissions;
import android.health.connect.ReadRecordsRequestUsingFilters;
import android.health.connect.ReadRecordsResponse;
import android.health.connect.TimeInstantRangeFilter;
import android.health.connect.datatypes.ExerciseSessionRecord;
import android.health.connect.datatypes.SleepSessionRecord;
import android.health.connect.datatypes.StepsRecord;
import android.net.Uri;
import android.os.Build;
import android.os.OutcomeReceiver;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * FORK MOD (Nexo): Health Connect — reads steps, sleep and workouts written by the watch/band app (Mi Fitness etc.)
 * through the platform API (Android 14+). Reads are async; results are cached per day and habits are evaluated
 * from the cache. Read-only, nothing is written.
 */
public class Health {
    public static final String[] PERMISSIONS = {
        HealthPermissions.READ_STEPS,
        HealthPermissions.READ_SLEEP,
        HealthPermissions.READ_EXERCISE,
    };
    /** Reading while the app is closed (Android 15+) */
    public static final String BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND";
    private static final long STALE_MS = 15 * 60_000L;

    private static SharedPreferences prefs;
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static volatile boolean running;
    private static volatile long lastTry;
    /** Callbacks of the Health Connect calls run here (the reading thread is blocked waiting for them) */
    private static ExecutorService callbacks;

    public static class Day {
        public final Integer steps;
        public final Sleep sleep;
        public final Integer exercise;
        public final long fetched;

        public Day(Integer steps, Sleep sleep, Integer exercise, long fetched) {
            this.steps = steps;
            this.sleep = sleep;
            this.exercise = exercise;
            this.fetched = fetched;
        }
    }

    private static synchronized SharedPreferences prefs() {
        if (prefs == null) prefs = Nexo.ctx().getSharedPreferences("health", Context.MODE_PRIVATE);
        return prefs;
    }

    private static synchronized Executor callbacks() {
        if (callbacks == null) callbacks = Executors.newSingleThreadExecutor();
        return callbacks;
    }

    public static void addListener(Runnable l) { listeners.add(l); }
    public static void removeListener(Runnable l) { listeners.remove(l); }

    public static boolean available(Context ctx) {
        if (Build.VERSION.SDK_INT < 34) return false;
        try {
            return ctx.getSystemService(Context.HEALTHCONNECT_SERVICE) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Health Connect is not available on this device (Android 14+ without the module) */
    public static boolean needsInstall(Context ctx) {
        if (Build.VERSION.SDK_INT < 34) return false;
        return !available(ctx);
    }

    /** Permissions granted at the last check (cached for synchronous use) */
    public static boolean isConnected() { return prefs().getBoolean("granted", false); }
    public static boolean hasBackground() { return prefs().getBoolean("background", false); }
    public static long getLastSync() { return prefs().getLong("fetched", 0); }

    /**
     * Asks for the permissions. On Android 14+ Health Connect permissions are ordinary runtime permissions —
     * {@link Activity#requestPermissions}; the result comes back with {@code requestCode}.
     */
    public static void request(Activity a, int requestCode) {
        if (Build.VERSION.SDK_INT >= 34) {
            ArrayList<String> list = new ArrayList<>(Arrays.asList(PERMISSIONS));
            if (Build.VERSION.SDK_INT >= 35) list.add(BACKGROUND);
            try {
                a.requestPermissions(list.toArray(new String[0]), requestCode);
            } catch (Exception e) {
                org.telegram.messenger.FileLog.e(e);
                try { a.startActivity(settingsIntent()); } catch (Exception ignore) {}
            }
        } else {
            try { a.startActivity(installIntent()); } catch (Exception e) { org.telegram.messenger.FileLog.e(e); }
        }
    }

    public static Intent installIntent() {
        return new Intent(Intent.ACTION_VIEW,
            Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"));
    }

    /** Health Connect → this app's permissions page */
    public static Intent settingsIntent() {
        Intent i = new Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS");
        i.putExtra(Intent.EXTRA_PACKAGE_NAME, Nexo.ctx().getPackageName());
        return i;
    }

    public static Day day(LocalDate date) {
        JSONObject j;
        try {
            j = new JSONObject(prefs().getString("days", "{}")).optJSONObject(date.toString());
        } catch (Exception e) {
            return null;
        }
        if (j == null) return null;
        int steps = j.optInt("steps", -1);
        int exercise = j.optInt("exercise", -1);
        Sleep sleep = j.has("sleepStart") ? new Sleep(j.optLong("sleepStart"), j.optLong("sleepEnd")) : null;
        return new Day(steps >= 0 ? steps : null, sleep, exercise >= 0 ? exercise : null, j.optLong("fetched"));
    }

    /**
     * Is the day settled: before marking yesterday ✕ the watch app must have had time to sync —
     * we trust data read after 10:00 of the next day.
     */
    public static boolean settled(LocalDate date) {
        Day d = day(date);
        if (d == null) return false;
        return d.fetched >= ScreenTime.dayStart(date.plusDays(1)) + 10 * 3600_000L;
    }

    public static void refresh(Context ctx) {
        refresh(ctx, false);
    }

    /** When the cache is stale (or {@code force}) — reads in the background, then re-evaluates the habits */
    public static void refresh(Context ctx, boolean force) {
        if (running || !available(ctx)) return;
        // A failed attempt counts too — otherwise an error would retry endlessly
        if (!force && System.currentTimeMillis() - lastTry < STALE_MS) return;
        lastTry = System.currentTimeMillis();
        running = true;
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                boolean ok = true;
                for (String p : PERMISSIONS) {
                    if (app.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
                }
                boolean bg = Build.VERSION.SDK_INT >= 35 && app.checkSelfPermission(BACKGROUND) == PackageManager.PERMISSION_GRANTED;
                prefs().edit().putBoolean("granted", ok).putBoolean("background", bg).apply();
                if (ok) Api34.read(app);
            } catch (Throwable e) {
                // No background read permission or Health Connect busy — next time
                org.telegram.messenger.FileLog.e(e);
            } finally {
                running = false;
                AndroidUtilities.runOnUIThread(() -> {
                    for (Runnable l : listeners) {
                        try { l.run(); } catch (Exception e) { org.telegram.messenger.FileLog.e(e); }
                    }
                    AutoMarker.run(app, true);
                });
            }
        }, "nexo-health");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Everything that touches android.health.connect lives here so the class is only loaded on Android 14+ */
    @TargetApi(34)
    private static class Api34 {
        private interface Call<R> {
            void run(OutcomeReceiver<R, HealthConnectException> cb);
        }

        @SuppressWarnings("unchecked")
        private static <R> R await(Call<R> call) throws Exception {
            final CountDownLatch latch = new CountDownLatch(1);
            final Object[] result = new Object[1];
            final Throwable[] error = new Throwable[1];
            call.run(new OutcomeReceiver<R, HealthConnectException>() {
                @Override
                public void onResult(R r) {
                    result[0] = r;
                    latch.countDown();
                }

                @Override
                public void onError(HealthConnectException e) {
                    error[0] = e;
                    latch.countDown();
                }
            });
            if (!latch.await(60, TimeUnit.SECONDS)) throw new TimeoutException("Health Connect timeout");
            if (error[0] != null) throw new Exception(error[0]);
            return (R) result[0];
        }

        private static TimeInstantRangeFilter range(Instant from, Instant to) {
            return new TimeInstantRangeFilter.Builder().setStartTime(from).setEndTime(to).build();
        }

        private static <T extends android.health.connect.datatypes.Record> List<T> readAll(final HealthConnectManager hc, final Class<T> type, final TimeInstantRangeFilter range) throws Exception {
            ArrayList<T> out = new ArrayList<>();
            long token = -1;
            do {
                final long pageToken = token;
                ReadRecordsResponse<T> page = await(new Call<ReadRecordsResponse<T>>() {
                    @Override
                    public void run(OutcomeReceiver<ReadRecordsResponse<T>, HealthConnectException> cb) {
                        ReadRecordsRequestUsingFilters.Builder<T> b = new ReadRecordsRequestUsingFilters.Builder<T>(type).setTimeRangeFilter(range);
                        if (pageToken >= 0) b.setPageToken(pageToken);
                        hc.readRecords(b.build(), callbacks(), cb);
                    }
                });
                if (page == null) break;
                out.addAll(page.getRecords());
                token = page.getNextPageToken();
            } while (token >= 0);
            return out;
        }

        static void read(Context app) throws Exception {
            final HealthConnectManager hc = (HealthConnectManager) app.getSystemService(Context.HEALTHCONNECT_SERVICE);
            if (hc == null) return;
            long now = System.currentTimeMillis();
            LocalDate today = LocalDate.now();
            JSONObject days;
            try {
                days = new JSONObject(prefs().getString("days", "{}"));
            } catch (Exception e) {
                days = new JSONObject();
            }
            for (LocalDate date : Arrays.asList(today.minusDays(1), today)) {
                final Instant from = Instant.ofEpochMilli(ScreenTime.dayStart(date));
                final Instant to = Instant.ofEpochMilli(Math.min(ScreenTime.dayStart(date.plusDays(1)), now));
                JSONObject j = new JSONObject().put("fetched", now);
                // Steps: with several sources Health Connect removes the duplicates itself
                final TimeInstantRangeFilter range = range(from, to);
                AggregateRecordsResponse<Long> aggResp = await(new Call<AggregateRecordsResponse<Long>>() {
                    @Override
                    public void run(OutcomeReceiver<AggregateRecordsResponse<Long>, HealthConnectException> cb) {
                        hc.aggregate(new AggregateRecordsRequest.Builder<Long>(range).addAggregationType(StepsRecord.STEPS_COUNT_TOTAL).build(), callbacks(), cb);
                    }
                });
                Long aggValue = aggResp == null ? null : aggResp.get(StepsRecord.STEPS_COUNT_TOTAL);
                long agg = aggValue == null ? 0L : aggValue;
                // A source missing from the priority list is left out of the total — count each source separately
                // and take the largest (phone and watch record the same steps — summing would double them)
                HashMap<String, Long> perSource = new HashMap<>();
                for (StepsRecord r : readAll(hc, StepsRecord.class, range)) {
                    String src = r.getMetadata().getDataOrigin().getPackageName();
                    if (src == null) src = "";
                    Long prev = perSource.get(src);
                    perSource.put(src, (prev == null ? 0L : prev) + r.getCount());
                }
                long maxSource = 0L;
                for (Long v : perSource.values()) if (v > maxSource) maxSource = v;
                j.put("steps", (int) Math.max(agg, maxSource));
                // Sleep: the longest session between yesterday 18:00 and today 14:00
                TimeInstantRangeFilter night = range(from.minusSeconds(6 * 3600), from.plusSeconds(14 * 3600));
                SleepSessionRecord longest = null;
                long longestMs = -1;
                for (SleepSessionRecord s : readAll(hc, SleepSessionRecord.class, night)) {
                    long len = s.getEndTime().toEpochMilli() - s.getStartTime().toEpochMilli();
                    if (len > longestMs) { longestMs = len; longest = s; }
                }
                if (longest != null) {
                    j.put("sleepStart", longest.getStartTime().toEpochMilli()).put("sleepEnd", longest.getEndTime().toEpochMilli());
                }
                // Workouts: the part inside the day (minutes)
                long exMin = 0;
                for (ExerciseSessionRecord ex : readAll(hc, ExerciseSessionRecord.class, range)) {
                    long end = Math.min(ex.getEndTime().toEpochMilli(), to.toEpochMilli());
                    long start = Math.max(ex.getStartTime().toEpochMilli(), from.toEpochMilli());
                    exMin += Math.max(0L, end - start) / 60_000L;
                }
                j.put("exercise", (int) exMin);
                days.put(date.toString(), j);
            }
            String oldest = today.minusDays(14).toString();
            ArrayList<String> old = new ArrayList<>();
            Iterator<String> it = days.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.compareTo(oldest) < 0) old.add(k);
            }
            for (String k : old) days.remove(k);
            prefs().edit().putString("days", days.toString()).putLong("fetched", now).apply();
        }
    }
}
