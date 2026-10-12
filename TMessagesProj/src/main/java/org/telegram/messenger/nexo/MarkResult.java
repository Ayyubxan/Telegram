package org.telegram.messenger.nexo;

/**
 * FORK MOD (Nexo): result of marking a habit — for celebrations: new streak, milestone, point change and reached goal.
 * {@link #total} — for unscheduled habits {@link #milestone} is the total count (10th, 25th…), not a streak.
 */
public class MarkResult {
    public final int streak;
    public final Integer milestone;
    public final int points;
    public final Goal goalReached;
    public final boolean total;

    public MarkResult(int streak, Integer milestone, int points, Goal goalReached, boolean total) {
        this.streak = streak;
        this.milestone = milestone;
        this.points = points;
        this.goalReached = goalReached;
        this.total = total;
    }
}
