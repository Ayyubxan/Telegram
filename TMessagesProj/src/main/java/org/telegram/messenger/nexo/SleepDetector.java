package org.telegram.messenger.nexo;

import android.util.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * FORK MOD (Nexo): guesses sleep from the screen-off time. Window: yesterday 18:00 — today 14:00.
 * Short screen-ons at night (up to {@link #AWAKE_MERGE_MS}) don't split the sleep.
 * The longest off piece (at least {@link #MIN_SLEEP_MS}) is the sleep. An unfinished piece (screen still off) doesn't count.
 */
public class SleepDetector {
    public static final long AWAKE_MERGE_MS = 5 * 60_000L;
    public static final long MIN_SLEEP_MS = 3 * 3600_000L;

    /**
     * {@code events} — (time, screen on?) sorted. {@code screenOnAtStart} — screen state at the window start.
     * {@code now} — current time: if the window ends after it, the last piece is still ongoing.
     */
    public static Sleep detect(List<Pair<Long, Boolean>> events, long from, long to, boolean screenOnAtStart, long now) {
        ArrayList<long[]> on = new ArrayList<>();
        Long since = screenOnAtStart ? from : null;
        boolean anyInWindow = false;
        for (Pair<Long, Boolean> e : events) {
            long t = e.first;
            if (t < from || t > to) continue;
            anyInWindow = true;
            if (e.second && since == null) since = t;
            if (!e.second && since != null) {
                on.add(new long[]{since, t});
                since = null;
            }
        }
        long end = Math.min(to, now);
        if (since != null) on.add(new long[]{since, end});
        // Short screen-ons (checking the clock at night) don't split the sleep
        ArrayList<long[]> real = new ArrayList<>();
        for (long[] iv : on) if (iv[1] - iv[0] > AWAKE_MERGE_MS) real.add(iv);
        // Off pieces = gaps between on pieces. Sleep only starts at a real "screen off": the gap at the window
        // start (evening without the phone) is not sleep.
        Sleep best = null;
        Long cursor = null;
        for (long[] iv : real) {
            if (cursor != null) {
                long gap = iv[0] - cursor;
                if (gap >= MIN_SLEEP_MS && (best == null || gap > best.minutes() * 60_000L)) best = new Sleep(cursor, iv[0]);
            }
            cursor = cursor == null ? iv[1] : Math.max(cursor, iv[1]);
        }
        // Last piece: only if the window is over (you may still be asleep)
        if (cursor != null && now >= to) {
            long gap = to - cursor;
            if (gap >= MIN_SLEEP_MS && (best == null || gap > best.minutes() * 60_000L)) best = new Sleep(cursor, to);
        }
        // No screen events at all in the window — no data (phone was off), not "20 hours of sleep"
        return anyInWindow ? best : null;
    }
}
