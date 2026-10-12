package org.telegram.messenger.nexo;

/**
 * FORK MOD (Nexo): a habit. {@link #repeat} — when it is due (unscheduled habits are only marked when they happen).
 * {@link #cat} — category id or "". {@link #auto} — automatic habit (app usage, sleep, steps…).
 * Points: +{@link #plus} when done, −{@link #minus} when skipped (per time slot for multi-time habits).
 */
public class Habit {
    public static final int DEFAULT_PLUS = 1;
    public static final int DEFAULT_MINUS = 2;
    /** One-time task is a big deal — larger default reward */
    public static final int ONCE_PLUS = 20;

    public String id;
    public String name;
    public String emoji;
    public String cat;
    public Repeat repeat;
    public int order;
    public boolean del;
    public long u;
    public Auto auto;
    public int plus;
    public int minus;
    /** Numeric goal of a one-time task (80 → 75 kg) */
    public Measure measure;

    public Habit(String id, String name, String emoji, String cat, Repeat repeat, int order, boolean del, long u, Auto auto, int plus, int minus, Measure measure) {
        this.id = id; this.name = name; this.emoji = emoji; this.cat = cat; this.repeat = repeat; this.order = order; this.del = del; this.u = u;
        this.auto = auto; this.plus = plus; this.minus = minus; this.measure = measure;
    }

    public Habit copy() {
        return new Habit(id, name, emoji, cat, repeat.copy(), order, del, u, auto == null ? null : auto.copy(), plus, minus, measure == null ? null : measure.copy());
    }

    public boolean once() {
        return repeat.once();
    }

    /** Expected habit (has streak, ✕ and rewards); false — unscheduled */
    public boolean daily() {
        return repeat.expected();
    }
}
