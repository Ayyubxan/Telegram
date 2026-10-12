package org.telegram.messenger.nexo;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * FORK MOD (Nexo): automatically marked habit: app usage, sleep (night without the phone), steps, place, calls, Mac…
 * Same JSON as the Mac `HabitAuto`. No Android dependencies — the rules live in {@link AutoRules}.
 */
public class Auto {
    public static final String APP = "app";
    public static final String SLEEP = "sleep";
    public static final String LIMIT = "limit";
    public static final String AVOID = "avoid";
    public static final String USE = "use";
    public static final String BEDTIME = "bedtime";
    public static final String WAKE = "wake";
    public static final String DURATION = "duration";
    /** Phone unlocks ≤ minutes */
    public static final String UNLOCKS = "unlocks";
    /** Phone-free for N minutes after waking */
    public static final String MORNING = "morning";
    /** At least N steps a day */
    public static final String STEPS = "steps";
    /** Visiting a place (within from–to, can be bound to a prayer) */
    public static final String PLACE = "place";
    /** Talking to a contact for at least N minutes (any day between occurrences) */
    public static final String CALL = "call";
    /** Workout (Health Connect): at least N minutes a day */
    public static final String WORKOUT = "workout";
    public static final String HEALTH = "health";
    public static final Set<String> KINDS = new HashSet<>(Arrays.asList(APP, SLEEP, UNLOCKS, MORNING, STEPS, PLACE, CALL, WORKOUT));
    /** Short screen-ons after waking that don't count (turning off the alarm) */
    public static final int MORNING_GRACE_MIN = 2;
    /** pkg = ALL — total screen time (all apps) */
    public static final String ALL = "*";

    /** "app" | "sleep" | … */
    public String kind;
    /** App: package and display name */
    public String pkg = "";
    public String app = "";
    /** App: "limit" | "avoid" | "use"; sleep: "bedtime" | "wake" | "duration" */
    public String mode;
    /** limit — at most, use — at least (0 — just open), duration — at least (minutes) */
    public int minutes;
    /** Sleep: bed/wake time "HH:mm" */
    public String time = "";
    /** Full-screen block when the limit is over or the "avoid" app is opened */
    public boolean block = true;
    /** Avoid window "HH:mm" — empty means all day. May cross midnight (23:00–07:00). */
    public String from = "";
    public String to = "";
    /** "Open anyway" on the block screen (hold 3 s) — when off there is no way out */
    public boolean escape = true;
    /** Place: center and radius (m); {@link #app} — place name */
    public double lat;
    public double lon;
    public int radius;
    /** Call: phone number; {@link #app} — contact name */
    public String phone = "";
    /** Source: "" — phone (screen / step counter), {@link #HEALTH} — Health Connect (watch) */
    public String src = "";

    public Auto(String kind, String mode) {
        this.kind = kind;
        this.mode = mode;
    }

    public Auto copy() {
        Auto a = new Auto(kind, mode);
        a.pkg = pkg; a.app = app; a.minutes = minutes; a.time = time; a.block = block; a.from = from; a.to = to; a.escape = escape;
        a.lat = lat; a.lon = lon; a.radius = radius; a.phone = phone; a.src = src;
        return a;
    }

    public static Auto fromJson(JSONObject j) {
        if (j == null) return null;
        Auto a = new Auto(j.optString("kind"), j.optString("mode"));
        a.pkg = j.optString("pkg");
        a.app = j.optString("app");
        a.minutes = j.optInt("minutes");
        a.time = j.optString("time");
        a.block = j.optBoolean("block", true);
        a.from = j.optString("from");
        a.to = j.optString("to");
        a.escape = j.optBoolean("escape", true);
        a.lat = j.optDouble("lat", 0.0);
        a.lon = j.optDouble("lon", 0.0);
        a.radius = j.optInt("radius");
        a.phone = j.optString("phone");
        a.src = j.optString("src");
        return KINDS.contains(a.kind) ? a : null;
    }

    /** "45 min", "2 h", "1 h 30 min" */
    public static String duration(int min) {
        if (min < 60) return min + " min";
        if (min % 60 == 0) return (min / 60) + " h";
        return (min / 60) + " h " + (min % 60) + " min";
    }

    public static String num(int n) {
        return String.format(Locale.US, "%,d", n).replace(',', ' ');
    }

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject().put("kind", kind).put("pkg", pkg).put("app", app).put("mode", mode)
                .put("minutes", minutes).put("time", time).put("block", block)
                .put("from", from).put("to", to).put("escape", escape);
            if (PLACE.equals(kind)) o.put("lat", lat).put("lon", lon).put("radius", radius);
            if (!phone.isEmpty()) o.put("phone", phone);
            if (!src.isEmpty()) o.put("src", src);
            return o;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public String describe() {
        switch (kind) {
            case APP:
                if (LIMIT.equals(mode)) return "📱 " + app + " ≤ " + duration(minutes);
                if (AVOID.equals(mode)) return hasWindow() ? "📱 " + from + "–" + to + " no " + app : "📱 no " + app;
                return minutes > 0 ? "📱 " + app + " ≥ " + duration(minutes) : "📱 open " + app;
            case UNLOCKS: return "🔓 ≤ " + minutes + " unlocks a day";
            case MORNING: return "🌅 " + duration(minutes) + " phone-free after waking";
            case STEPS: return "👟 " + num(minutes) + " steps a day";
            case PLACE: return "📍 " + app + (hasWindow() ? " · " + Repeat.plainLabel(from) + "–" + Repeat.plainLabel(to) : "");
            case CALL: return "📞 " + app + (minutes > 0 ? " · ≥ " + duration(minutes) : "");
            case WORKOUT: return "🏋️ ≥ " + duration(minutes) + " workout a day";
            default:
                if (BEDTIME.equals(mode)) return "😴 in bed by " + time;
                if (WAKE.equals(mode)) return "⏰ up by " + time;
                return "😴 sleep ≥ " + duration(minutes);
        }
    }

    public boolean hasWindow() { return !from.isEmpty() && !to.isEmpty() && !from.equals(to); }
    public boolean isTotal() { return ALL.equals(pkg); }
    /** Needs the usage-access permission */
    public boolean needsUsage() { return APP.equals(kind) || (SLEEP.equals(kind) && !fromHealth()) || UNLOCKS.equals(kind) || MORNING.equals(kind); }
    public boolean fromHealth() { return HEALTH.equals(src) || WORKOUT.equals(kind); }
    /** Needs blocking (limit / avoid) */
    public boolean blocks() { return APP.equals(kind) && block && (LIMIT.equals(mode) || AVOID.equals(mode)); }
}
