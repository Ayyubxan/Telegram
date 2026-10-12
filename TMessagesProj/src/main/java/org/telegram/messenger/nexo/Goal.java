package org.telegram.messenger.nexo;

/** FORK MOD (Nexo): reward goal — when {@link #target} points are collected, the reward can be claimed. {@link #claimed} — claim time or null. */
public class Goal {
    public String id;
    public String title;
    public String emoji;
    public int target;
    public Long claimed;
    public int order;
    public boolean del;
    public long u;

    public Goal(String id, String title, String emoji, int target, Long claimed, int order, boolean del, long u) {
        this.id = id; this.title = title; this.emoji = emoji; this.target = target; this.claimed = claimed; this.order = order; this.del = del; this.u = u;
    }

    public Goal copy() {
        return new Goal(id, title, emoji, target, claimed, order, del, u);
    }
}
