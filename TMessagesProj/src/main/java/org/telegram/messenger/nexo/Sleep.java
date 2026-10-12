package org.telegram.messenger.nexo;

/** FORK MOD (Nexo): one night's sleep (epoch ms) */
public class Sleep {
    public final long start;
    public final long end;

    public Sleep(long start, long end) {
        this.start = start;
        this.end = end;
    }

    public int minutes() {
        return (int) ((end - start) / 60_000);
    }
}
