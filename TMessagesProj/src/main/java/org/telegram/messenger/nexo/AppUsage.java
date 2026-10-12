package org.telegram.messenger.nexo;

import java.util.ArrayList;
import java.util.List;

/** FORK MOD (Nexo): one day's usage of an app */
public class AppUsage {
    public final int minutes;
    public final boolean opened;
    public final List<long[]> intervals;

    public AppUsage(int minutes, boolean opened) {
        this(minutes, opened, new ArrayList<>());
    }

    public AppUsage(int minutes, boolean opened, List<long[]> intervals) {
        this.minutes = minutes;
        this.opened = opened;
        this.intervals = intervals;
    }
}
