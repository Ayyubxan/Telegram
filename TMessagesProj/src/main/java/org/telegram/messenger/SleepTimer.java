package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;

// FORK MOD (sleep timer): stop music playback after a chosen number of minutes.
// Runs on the main-thread handler; the music foreground service keeps the process alive
// until it fires.
public class SleepTimer {

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable pending;
    private static long endTime;

    public static void schedule(int minutes) {
        cancel();
        if (minutes <= 0) {
            return;
        }
        final long delay = minutes * 60_000L;
        endTime = System.currentTimeMillis() + delay;
        pending = () -> {
            pending = null;
            endTime = 0;
            try {
                MediaController.getInstance().cleanupPlayer(true, true);
            } catch (Exception e) {
                FileLog.e(e);
            }
        };
        handler.postDelayed(pending, delay);
    }

    public static void cancel() {
        if (pending != null) {
            handler.removeCallbacks(pending);
            pending = null;
        }
        endTime = 0;
    }

    public static boolean isActive() {
        return pending != null;
    }

    public static long getEndTime() {
        return endTime;
    }
}
