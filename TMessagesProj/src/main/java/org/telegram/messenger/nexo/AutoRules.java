package org.telegram.messenger.nexo;

import java.util.ArrayList;
import java.util.List;

/**
 * FORK MOD (Nexo): pure rules. Result: DONE / SKIPPED, or null — not decided yet (day not over, sleep not detected).
 * {@code dayOver} — whether the evaluated day is over (true for yesterday).
 */
public class AutoRules {
    /** {@code dayStart} — start of the evaluated day; intervals in {@code u} are within that day */
    public static Integer app(Auto a, AppUsage u, boolean dayOver, long dayStart) {
        if (Auto.LIMIT.equals(a.mode)) {
            // Over the limit — ✕ immediately; day ends under the limit — ✓
            if (u.minutes > a.minutes) return Status.SKIPPED;
            return dayOver ? Status.DONE : null;
        }
        if (Auto.AVOID.equals(a.mode)) {
            boolean broke;
            if (a.hasWindow()) {
                broke = false;
                List<long[]> ws = windows(a, dayStart);
                for (long[] iv : u.intervals) {
                    for (long[] w : ws) if (iv[0] < w[1] && w[0] < iv[1]) { broke = true; break; }
                    if (broke) break;
                }
            } else {
                broke = u.opened;
            }
            if (broke) return Status.SKIPPED;
            return dayOver ? Status.DONE : null;
        }
        // Use: ✓ as soon as the condition is met; ✕ if the day ends without it
        boolean met = a.minutes > 0 ? u.minutes >= a.minutes : u.opened;
        if (met) return Status.DONE;
        return dayOver ? Status.SKIPPED : null;
    }

    /** At most {@link Auto#minutes} times: ✕ when exceeded, ✓ when the day ends under it */
    public static Integer unlocks(Auto a, int count, boolean dayOver) {
        if (count > a.minutes) return Status.SKIPPED;
        return dayOver ? Status.DONE : null;
    }

    /** At least {@link Auto#minutes} (steps, workout minutes): ✓ as soon as reached, ✕ if the day ends short */
    public static Integer steps(Auto a, int steps, boolean dayOver) {
        if (steps >= a.minutes) return Status.DONE;
        return dayOver ? Status.SKIPPED : null;
    }

    /**
     * Phone-free for {@link Auto#minutes} after {@code wake}: more than {@link Auto#MORNING_GRACE_MIN} minutes of app
     * usage ({@code used} — screen intervals) → ✕; clean until the end → ✓. {@code wake} null — not awake yet.
     */
    public static Integer morning(Auto a, Long wake, List<long[]> used, long now) {
        if (wake == null) return null;
        long end = wake + a.minutes * 60_000L;
        long ms = 0;
        for (long[] iv : used) ms += Math.max(0L, Math.min(Math.min(iv[1], end), now) - Math.max(iv[0], wake));
        if (ms > Auto.MORNING_GRACE_MIN * 60_000L) return Status.SKIPPED;
        if (now >= end) return Status.DONE;
        return null;
    }

    /**
     * Place: {@code visits} (enter, exit; exit 0 — still there) intersecting {@code window} → ✓.
     * Window (or day) over without a visit → ✕. {@code window} null — the whole day.
     */
    public static Integer place(List<long[]> visits, long[] window, long dayStart, long now) {
        long[] w = window != null ? window : new long[]{dayStart, dayStart + 24 * 3600_000L};
        boolean hit = false;
        for (long[] v : visits) {
            long exit = v[1] == 0L ? now : v[1];
            if (v[0] < w[1] && w[0] < exit && v[0] <= now) { hit = true; break; }
        }
        if (hit) return Status.DONE;
        return now >= w[1] ? Status.SKIPPED : null;
    }

    /** Call: found within the period → ✓, period (the due day) over → ✕ */
    public static Integer call(boolean found, boolean dayOver) {
        if (found) return Status.DONE;
        return dayOver ? Status.SKIPPED : null;
    }

    /** Distance between two points (m) */
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double sLat = Math.sin(dLat / 2), sLon = Math.sin(dLon / 2);
        double h = sLat * sLat + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sLon * sLon;
        return 2 * r * Math.asin(Math.sqrt(h));
    }

    /** Compare phone numbers by the last 9 digits (+998 90… and 90… are the same) */
    public static boolean samePhone(String a, String b) {
        String x = digits(a), y = digits(b);
        if (x.length() > 9) x = x.substring(x.length() - 9);
        if (y.length() > 9) y = y.substring(y.length() - 9);
        return x.length() >= 7 && x.equals(y);
    }

    private static String digits(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) if (Character.isDigit(c)) sb.append(c);
        return sb.toString();
    }

    /** Window pieces for the day: 09:00–17:00 → one; 23:00–07:00 → [00:00–07:00) and [23:00–24:00) */
    public static List<long[]> windows(Auto a, long dayStart) {
        ArrayList<long[]> out = new ArrayList<>();
        Integer f = Repeat.minutes(a.from);
        Integer t = Repeat.minutes(a.to);
        if (f == null || t == null) return out;
        if (f < t) {
            out.add(new long[]{dayStart + f * 60_000L, dayStart + t * 60_000L});
        } else {
            out.add(new long[]{dayStart, dayStart + t * 60_000L});
            out.add(new long[]{dayStart + f * 60_000L, dayStart + 24 * 60 * 60_000L});
        }
        return out;
    }

    /** Is the minute of the day inside the window */
    public static boolean inWindow(Auto a, int minuteOfDay) {
        if (!a.hasWindow()) return true;
        Integer f = Repeat.minutes(a.from);
        Integer t = Repeat.minutes(a.to);
        if (f == null || t == null) return true;
        return f < t ? (minuteOfDay >= f && minuteOfDay < t) : (minuteOfDay >= f || minuteOfDay < t);
    }

    /**
     * Sleep. {@code dayStart} — start of the day (00:00, epoch ms). A bedtime after 12:00 belongs to the previous
     * evening (23:30), earlier — to this day's night (00:30).
     */
    public static Integer sleep(Auto a, Sleep s, long dayStart) {
        if (s == null) return null;
        Integer t = Repeat.minutes(a.time);
        if (Auto.BEDTIME.equals(a.mode)) {
            if (t == null) return null;
            long target = dayStart + (t >= 12 * 60 ? t - 24 * 60 : t) * 60_000L;
            return s.start <= target ? Status.DONE : Status.SKIPPED;
        }
        if (Auto.WAKE.equals(a.mode)) {
            if (t == null) return null;
            return s.end <= dayStart + t * 60_000L ? Status.DONE : Status.SKIPPED;
        }
        return s.minutes() >= a.minutes ? Status.DONE : Status.SKIPPED;
    }
}
