package org.telegram.messenger.nexo;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * FORK MOD (Nexo): steps from the phone's step counter (total since boot). While the app process is alive
 * (sync service) we listen and accumulate per day. Steps between yesterday's last reading and today's first
 * are written to today (usually no steps at night).
 */
public class Steps {
    public static final String PERMISSION = "android.permission.ACTIVITY_RECOGNITION";
    private static final long KEEP_DAYS = 14L;
    private static boolean listening;
    private static SharedPreferences prefs;

    private static final SensorEventListener listener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            try {
                int before = on(LocalDate.now());
                record((long) e.values[0]);
                // The goal may have been reached — mark right away
                int now = on(LocalDate.now());
                boolean reached = false;
                for (Habit h : AutoMarker.autoHabits()) {
                    Auto a = h.auto;
                    if (a == null || !Auto.STEPS.equals(a.kind)) continue;
                    if (before < a.minutes && now >= a.minutes) { reached = true; break; }
                }
                if (reached) AutoMarker.run(Nexo.ctx(), true);
            } catch (Exception ex) {
                org.telegram.messenger.FileLog.e(ex);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private static synchronized SharedPreferences prefs() {
        if (prefs == null) prefs = Nexo.ctx().getSharedPreferences("steps", Context.MODE_PRIVATE);
        return prefs;
    }

    public static boolean hasSensor(Context ctx) {
        SensorManager sm = ContextCompat.getSystemService(ctx, SensorManager.class);
        return sm != null && sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null;
    }

    public static boolean hasPermission(Context ctx) {
        return Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Starts listening when there is a steps habit, otherwise stops */
    public static void sync(Context ctx) {
        boolean need = false;
        for (Habit h : AutoMarker.autoHabits()) {
            if (Auto.STEPS.equals(h.auto.kind) && !h.auto.fromHealth()) { need = true; break; }
        }
        need = need && hasPermission(ctx);
        SensorManager sm = ContextCompat.getSystemService(ctx, SensorManager.class);
        if (sm == null) return;
        Sensor sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (need && !listening && sensor != null) {
            // Batches up to 1 minute — easy on the battery
            listening = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000);
        } else if (!need && listening) {
            sm.unregisterListener(listener);
            listening = false;
        }
    }

    public static synchronized int on(LocalDate date) {
        return days().optInt(date.toString());
    }

    private static JSONObject days() {
        try {
            return new JSONObject(prefs().getString("days", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static synchronized void record(long raw) {
        try {
            LocalDate today = LocalDate.now();
            long last = prefs().getLong("last", -1);
            JSONObject days = days();
            // After a reboot the counter starts from zero
            long delta;
            if (last < 0) delta = 0;
            else if (raw >= last) delta = raw - last;
            else delta = raw;
            String key = today.toString();
            days.put(key, days.optInt(key) + (int) delta);
            String oldest = today.minusDays(KEEP_DAYS).toString();
            ArrayList<String> old = new ArrayList<>();
            Iterator<String> it = days.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.compareTo(oldest) < 0) old.add(k);
            }
            for (String k : old) days.remove(k);
            prefs().edit().putLong("last", raw).putString("days", days.toString()).apply();
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }
}
