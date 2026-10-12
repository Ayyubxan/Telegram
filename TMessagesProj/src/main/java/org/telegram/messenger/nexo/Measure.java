package org.telegram.messenger.nexo;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * FORK MOD (Nexo): numeric goal for a one-time task: from {@link #from} to {@link #to} ({@link #unit} — kg, km, books…).
 * {@link #now} — the last entered value. Can decrease (weight) or increase (distance).
 */
public class Measure {
    public String unit;
    public double from;
    public double to;
    public double now;

    public Measure(String unit, double from, double to, double now) {
        this.unit = unit; this.from = from; this.to = to; this.now = now;
    }

    public Measure copy() {
        return new Measure(unit, from, to, now);
    }

    /** 0..1 */
    public double progress() {
        if (to == from) return 1.0;
        return Math.max(0.0, Math.min(1.0, (now - from) / (to - from)));
    }

    public boolean reached() {
        return to < from ? now <= to : now >= to;
    }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("unit", unit).put("from", from).put("to", to).put("now", now);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public static Measure fromJson(JSONObject j) {
        if (j == null) return null;
        return new Measure(j.optString("unit"), j.optDouble("from", 0.0), j.optDouble("to", 0.0), j.optDouble("now", j.optDouble("from", 0.0)));
    }

    /** 80.0 → "80", 77.5 → "77.5" */
    public static String fmt(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format(Locale.US, "%.1f", v);
    }
}
