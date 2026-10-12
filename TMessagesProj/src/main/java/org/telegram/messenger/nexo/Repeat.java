package org.telegram.messenger.nexo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * FORK MOD (Nexo): when a habit is due. Same JSON as the Mac `HabitRepeat`.
 * {@link #times} — exact times ("HH:mm" or "@fajr+10"). With two or more, every time is marked separately (pills: 08:00, 20:00).
 * For hourly habits {@link #times} are generated from the first time and {@link #every} hours.
 */
public class Repeat {
    public static final String DAILY = "daily";
    public static final String WEEKLY = "weekly";
    public static final String MONTHLY = "monthly";
    public static final String YEARLY = "yearly";
    public static final String HOURLY = "hourly";
    public static final String FREE = "free";
    /** One-time task: stays in the list until done (lose 5 kg, climb a mountain) */
    public static final String ONCE = "once";

    public static final String[][] LABELS = {
        {DAILY, "Daily"}, {WEEKLY, "Weekly"}, {MONTHLY, "Monthly"},
        {YEARLY, "Yearly"}, {HOURLY, "Hourly"}, {FREE, "No schedule"},
    };

    public String freq = DAILY;
    /** Daily — every N days, hourly — every N hours */
    public int every = 1;
    /** Weekly: 1 = Monday … 7 = Sunday */
    public ArrayList<Integer> days = new ArrayList<>();
    /** Monthly: day of month (last day if the month is shorter) */
    public int mday = 1;
    /** Yearly: "MM-dd" */
    public String ydate = "01-01";
    /** Start date "yyyy-MM-dd" ("every N days" counts from it) */
    public String start = "";
    public ArrayList<String> times = new ArrayList<>();
    /** Notification at every time */
    public boolean remind = true;
    /** One-time task deadline "yyyy-MM-dd" ("" — none) */
    public String deadline = "";

    public Repeat() {}

    public Repeat(String freq) {
        this.freq = freq;
    }

    public Repeat copy() {
        Repeat r = new Repeat();
        r.freq = freq; r.every = every; r.days = new ArrayList<>(days); r.mday = mday; r.ydate = ydate; r.start = start;
        r.times = new ArrayList<>(times); r.remind = remind; r.deadline = deadline;
        return r;
    }

    public static class Anchor {
        public final String key;
        public final int offset;
        Anchor(String key, int offset) { this.key = key; this.offset = offset; }
    }

    /** Hourly: from the first time every N hours within the day (12 h, 08:00 → 08:00, 20:00) */
    public static ArrayList<String> hourlyTimes(String first, int every) {
        ArrayList<String> out = new ArrayList<>();
        Integer start = minutes(first);
        if (start == null) return out;
        int step = Math.max(1, Math.min(24, every)) * 60;
        for (int m = start; m < 24 * 60; m += step) out.add(hhmm(m));
        return out;
    }

    /** "@asr-15" — time bound to a prayer: prayer key and minute offset */
    public static boolean isPrayer(String t) {
        return t != null && t.startsWith("@");
    }

    public static Anchor anchor(String t) {
        if (!isPrayer(t)) return null;
        String body = t.substring(1);
        int i = -1;
        for (int k = 0; k < body.length(); k++) {
            char c = body.charAt(k);
            if (c == '+' || c == '-') { i = k; break; }
        }
        String key = i < 0 ? body : body.substring(0, i);
        int off = 0;
        if (i >= 0) {
            try { off = Integer.parseInt(body.substring(i)); } catch (Exception ignore) {}
        }
        return PrayerTimes.NAMES.containsKey(key) ? new Anchor(key, off) : null;
    }

    public static String prayer(String key, int offset) {
        return "@" + key + (offset > 0 ? "+" + offset : offset < 0 ? String.valueOf(offset) : "");
    }

    /** Real minute of the day on that date. Null if the prayer schedule is not loaded yet. */
    public static Integer resolve(String t, LocalDate date) {
        Anchor a = anchor(t);
        if (a == null) return minutes(t);
        Integer m = PrayerTimes.minute(Nexo.ctx(), date, a.key);
        if (m == null) return null;
        return Math.max(0, Math.min(24 * 60 - 1, m + a.offset));
    }

    /** "🕌 Fajr +10 min" or "08:00" */
    public static String label(String t) {
        Anchor a = anchor(t);
        if (a == null) return t;
        String shift = a.offset > 0 ? " +" + a.offset + " min" : a.offset < 0 ? " −" + (-a.offset) + " min" : "";
        return "🕌 " + PrayerTimes.NAMES.get(a.key) + shift;
    }

    /** Label without the mosque emoji: "Fajr +10 min" */
    public static String plainLabel(String t) {
        String s = label(t);
        return s.startsWith("🕌 ") ? s.substring(2) : s;
    }

    /** Short pill text: "Fajr 04:56", "Asr-15 15:54" or "08:00" */
    public static String shortLabel(String t, LocalDate date) {
        Anchor a = anchor(t);
        if (a == null) return t;
        String name = PrayerTimes.NAMES.get(a.key) + (a.offset > 0 ? "+" + a.offset : a.offset < 0 ? String.valueOf(a.offset) : "");
        Integer m = resolve(t, date);
        return m == null ? name : name + " " + hhmm(m);
    }

    private static final HashMap<String, Integer> PRAYER_ORDER = new HashMap<>();
    static {
        PRAYER_ORDER.put("fajr", 300); PRAYER_ORDER.put("sunrise", 380); PRAYER_ORDER.put("dhuhr", 740);
        PRAYER_ORDER.put("asr", 960); PRAYER_ORDER.put("maghrib", 1080); PRAYER_ORDER.put("isha", 1170);
    }

    /** Sort key: approximate position for prayers (fajr — morning, isha — night) */
    public static int sortKey(String t) {
        Anchor a = anchor(t);
        if (a != null) {
            Integer base = PRAYER_ORDER.get(a.key);
            return (base == null ? 0 : base) + a.offset;
        }
        Integer m = minutes(t);
        return m == null ? 0 : m;
    }

    public static void sortTimes(List<String> times) {
        Collections.sort(times, (x, y) -> Integer.compare(sortKey(x), sortKey(y)));
    }

    public static Integer minutes(String t) {
        if (t == null) return null;
        String[] p = t.split(":");
        if (p.length != 2) return null;
        try {
            return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
        } catch (Exception e) {
            return null;
        }
    }

    public static String hhmm(int min) {
        return String.format(Locale.US, "%02d:%02d", min / 60, min % 60);
    }

    public static Repeat fromJson(JSONObject j, boolean legacyDaily) {
        if (j == null) return new Repeat(legacyDaily ? DAILY : FREE);
        Repeat r = new Repeat();
        r.freq = j.optString("freq", DAILY);
        r.every = Math.max(1, j.optInt("every", 1));
        JSONArray d = j.optJSONArray("days");
        if (d != null) for (int i = 0; i < d.length(); i++) r.days.add(d.optInt(i));
        r.mday = j.optInt("mday", 1);
        r.ydate = j.optString("ydate", "01-01");
        r.start = j.optString("start");
        JSONArray t = j.optJSONArray("times");
        if (t != null) for (int i = 0; i < t.length(); i++) r.times.add(t.optString(i));
        sortTimes(r.times);
        r.remind = j.optBoolean("remind", true);
        r.deadline = j.optString("deadline");
        return r;
    }

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject()
                .put("freq", freq).put("every", every).put("days", new JSONArray(days)).put("mday", mday)
                .put("ydate", ydate).put("start", start).put("times", new JSONArray(times)).put("remind", remind);
            if (!deadline.isEmpty()) o.put("deadline", deadline);
            return o;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public boolean slotted() { return times.size() >= 2; }
    public boolean expected() { return !FREE.equals(freq) && !ONCE.equals(freq); }
    public boolean once() { return ONCE.equals(freq); }
    /** Can lose points: an expected habit or a one-time task with a deadline */
    public boolean canLose() { return expected() || (once() && !deadline.isEmpty()); }

    /** Due on that date ({@code first} — the day the habit started; before it — no) */
    public boolean isDue(LocalDate date, LocalDate first) {
        if (date.isBefore(first)) return false;
        switch (freq) {
            case DAILY: return every <= 1 || (date.toEpochDay() - first.toEpochDay()) % every == 0;
            case HOURLY: return true;
            case WEEKLY: return days.isEmpty() || days.contains(date.getDayOfWeek().getValue());
            case MONTHLY: return date.getDayOfMonth() == Math.min(mday, date.lengthOfMonth());
            case YEARLY: {
                int[] md = ydateParts();
                return date.getMonthValue() == md[0] && date.getDayOfMonth() == Math.min(md[1], date.lengthOfMonth());
            }
            default: return false;
        }
    }

    public int[] ydateParts() {
        int m = 1, d = 1;
        try {
            String[] p = ydate.split("-");
            m = Integer.parseInt(p[0]);
            d = Integer.parseInt(p[1]);
        } catch (Exception ignore) {}
        return new int[]{m, d};
    }

    /** "Every day · 09:00", "Mon, Wed, Fri", "Every 12 hours · 08:00, 20:00" */
    public String describe() {
        String base = describeBase();
        if (times.isEmpty()) return base;
        StringBuilder sb = new StringBuilder(base).append(" · ");
        for (int i = 0; i < times.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(label(times.get(i)));
        }
        return sb.toString();
    }

    /** Schedule only, without the times */
    public String describeBase() {
        switch (freq) {
            case DAILY: return every <= 1 ? "Every day" : "Every " + every + " days";
            case WEEKLY: {
                if (days.isEmpty() || days.size() == 7) return "Every day";
                ArrayList<Integer> sorted = new ArrayList<>(days);
                Collections.sort(sorted);
                StringBuilder sb = new StringBuilder();
                for (int d : sorted) { if (sb.length() > 0) sb.append(", "); sb.append(Dates.WEEKDAYS_SHORT[d - 1]); }
                return sb.toString();
            }
            case MONTHLY: return "Monthly on the " + Dates.ordinal(mday);
            case YEARLY: {
                int[] md = ydateParts();
                return "Yearly on " + md[1] + " " + Dates.MONTHS_SHORT[md[0] - 1];
            }
            case HOURLY: return "Every " + every + (every == 1 ? " hour" : " hours");
            case ONCE: return deadline.isEmpty() ? "One-time" : "One-time · by " + Dates.shortDate(deadline);
            default: return "No schedule";
        }
    }

    /** Streak unit: "day" for daily, otherwise the number of occurrences */
    public String unit() {
        if (MONTHLY.equals(freq)) return "month";
        if (YEARLY.equals(freq)) return "year";
        if (HOURLY.equals(freq) || (DAILY.equals(freq) && every <= 1)) return "day";
        return "time";
    }

    public String units(int n) {
        String u = unit();
        return n == 1 ? u : u + "s";
    }
}
