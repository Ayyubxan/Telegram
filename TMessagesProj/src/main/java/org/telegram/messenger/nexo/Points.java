package org.telegram.messenger.nexo;

import java.time.LocalDate;
import java.util.List;

/** FORK MOD (Nexo): point arithmetic (no Android dependencies) */
public class Points {
    /** One habit: schedule, start day and points per completion */
    public static class Plan {
        public final Repeat repeat;
        public final LocalDate first;
        public final int plus;

        public Plan(Repeat repeat, LocalDate first, int plus) {
            this.repeat = repeat; this.first = first; this.plus = plus;
        }
    }

    /** Average points per day if everything is done: due occurrences in the next {@code days} × slots × points */
    public static double expectedDaily(List<Plan> plans, LocalDate today, int days) {
        double total = 0;
        for (Plan p : plans) {
            if (!p.repeat.expected() || p.plus <= 0) continue;
            int slots = p.repeat.slotted() ? p.repeat.times.size() : 1;
            int due = 0;
            for (int i = 0; i < days; i++) if (p.repeat.isDue(today.plusDays(i), p.first)) due++;
            total += (double) due * slots * p.plus;
        }
        return total / days;
    }

    public static double expectedDaily(List<Plan> plans, LocalDate today) {
        return expectedDaily(plans, today, 28);
    }

    /** Reached with today's remaining habits ({@code todayLeft} points) — 0 (today); otherwise days starting tomorrow */
    public static Integer etaWithToday(int remaining, int todayLeft, double perDay) {
        return remaining <= todayLeft ? Integer.valueOf(0) : etaDays(remaining - todayLeft, perDay);
    }

    /** Days until the goal: 0 — reached; null — unreachable at this pace */
    public static Integer etaDays(int remaining, double perDay) {
        if (remaining <= 0) return 0;
        if (perDay <= 0.0) return null;
        return (int) Math.ceil(remaining / perDay);
    }

    public static String etaLabel(Integer days) {
        if (days == null) return "—";
        if (days == 0) return "today";
        if (days == 1) return "tomorrow";
        if (days <= 13) return "in ~" + days + " days";
        if (days <= 59) return "in ~" + ((days + 3) / 7) + " weeks";
        return "in ~" + ((days + 15) / 30) + " months";
    }
}
