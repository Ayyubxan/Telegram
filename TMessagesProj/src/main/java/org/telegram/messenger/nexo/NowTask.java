package org.telegram.messenger.nexo;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;

/**
 * FORK MOD (Nexo): the "current task" of the persistent notification — the first item of the home screen order:
 * 1) timed habits that are due now/soon (within an hour) or recently overdue (up to 3 h) — earliest first;
 * 2) today's untimed habits; 3) timed habits later today. Automatic habits are excluded.
 */
public class NowTask {
    private static final long LEAD_MS = 60 * 60_000L;
    private static final long OVERDUE_MS = 3 * 3600_000L;

    /** {@link #time} — the slot to mark ("20:00" or "@fajr") for multi-time habits, {@link #at} — epoch ms */
    public static class Pick {
        public final Habit habit;
        public final String time;
        public final Long at;
        Pick(Habit habit, String time, Long at) { this.habit = habit; this.time = time; this.at = at; }
    }

    public static class State {
        public final Pick pick;
        public final int done;
        public final int total;
        State(Pick pick, int done, int total) { this.pick = pick; this.done = done; this.total = total; }
    }

    public static State state(HabitStore store) {
        return state(store, System.currentTimeMillis());
    }

    public static State state(HabitStore store, long now) {
        LocalDate today = LocalDate.now();
        long dayStart = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        int done = 0, total = 0;
        ArrayList<Pick> timed = new ArrayList<>();
        ArrayList<Pick> untimed = new ArrayList<>();
        for (Habit h : store.dueOn(today)) {
            int[] p = store.progress(h.id, today);
            done += p[0];
            total += p[1];
            if (h.auto != null || store.status(h.id, today) != Status.NONE) continue;
            String slot = null;
            if (h.repeat.slotted()) {
                for (String t : h.repeat.times) if (store.slotStatus(h.id, today, t) == Status.NONE) { slot = t; break; }
            } else if (!h.repeat.times.isEmpty()) {
                slot = h.repeat.times.get(0);
            }
            Integer minute = slot == null ? null : Repeat.resolve(slot, today);
            if (minute != null) timed.add(new Pick(h, h.repeat.slotted() ? slot : null, dayStart + minute * 60_000L));
            else if (h.repeat.times.isEmpty()) untimed.add(new Pick(h, null, null));
        }
        Collections.sort(timed, (a, b) -> Long.compare(a.at, b.at));
        Pick pick = null;
        for (Pick p : timed) if (p.at >= now - OVERDUE_MS && p.at <= now + LEAD_MS) { pick = p; break; }
        if (pick == null && !untimed.isEmpty()) pick = untimed.get(0);
        if (pick == null) for (Pick p : timed) if (p.at > now) { pick = p; break; }
        return new State(pick, done, total);
    }

    /** "25 min left", "due now", "15 min ago", "2 h 10 min left" */
    public static String left(long at) {
        return left(at, System.currentTimeMillis());
    }

    public static String left(long at, long now) {
        int min = (int) ((at - now) / 60_000L);
        if (min >= -1 && min <= 1) return "due now";
        if (min > 0) return Auto.duration(min) + " left";
        return Auto.duration(-min) + " ago";
    }
}
