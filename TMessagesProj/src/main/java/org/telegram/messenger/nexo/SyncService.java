package org.telegram.messenger.nexo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.List;

/** FORK MOD (Nexo): foreground service that runs permanently in the background and keeps every channel alive. */
public class SyncService extends Service {
    /** Must not collide with Telegram's own ids: NotificationsController uses currentAccount + 1 (so 1 is account 0's message notification) */
    private static final int NOTIFICATION_ID = 7101;
    /** Hidden channel (the app cannot lower the importance of an existing channel, hence the own id) */
    private static final String CHANNEL_ID = "sync_hidden";
    /** Visible (silent) channel for the next task; without a task — the hidden channel */
    private static final String TASK_CHANNEL_ID = "now_task";

    private static volatile boolean isRunning = false;

    public static boolean isRunning() {
        return isRunning;
    }

    public static void start(Context ctx) {
        SyncEngine engine = Nexo.engine();
        if (!engine.isPaired() || !engine.prefs.isSyncEnabled()) return;
        try {
            Intent intent = new Intent(ctx, SyncService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent);
            else ctx.startService(intent);
        } catch (Exception e) {
            // Android 12+: a FGS cannot always be started from the background
            FileLog.e(e);
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, SyncService.class));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private SyncEngine engine;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String lastNotificationText = "";
    private String lastPosted = "";
    private boolean locationReceiverRegistered = false;

    private final Runnable notifyRunnable = this::updateNotification;
    /** Every minute: "25 min left" refreshes, due habits move into the queue */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateNotification();
            main.postDelayed(this, 60_000);
        }
    };
    private final Runnable listener = () -> {
        main.removeCallbacks(notifyRunnable);
        main.postDelayed(notifyRunnable, 500);
    };

    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (PrayerTimes.isAuto() && PrayerTimes.canBackground(context)) PrayerTimes.refreshLocation(context);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        engine = Nexo.engine();
        isRunning = true;
        createChannel();
        goForeground();
        engine.addListener(listener);
        engine.habits.addListener(listener);
        main.postDelayed(tick, 60_000);
        engine.activate();
        // Travelling: prayer times when location gets enabled (if the background location permission exists)
        try {
            IntentFilter filter = new IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(locationReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(locationReceiver, filter);
            }
            locationReceiverRegistered = true;
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (!engine.isPaired() || !engine.prefs.isSyncEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        engine.postApplySettings();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        engine.removeListener(listener);
        engine.habits.removeListener(listener);
        main.removeCallbacks(tick);
        engine.deactivate();
        if (locationReceiverRegistered) {
            try {
                unregisterReceiver(locationReceiver);
            } catch (Exception ignore) {
            }
            locationReceiverRegistered = false;
        }
        main.removeCallbacks(notifyRunnable);
        super.onDestroy();
    }

    private void goForeground() {
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * A foreground service cannot run without a notification, but when the channel is turned off
     * the notification is invisible — while the service keeps running in the background.
     */
    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Background sync", NotificationManager.IMPORTANCE_NONE);
        channel.setDescription("Shows that Mac sync is running in the background (hidden by default)");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
        NotificationChannel task = new NotificationChannel(TASK_CHANNEL_ID, "Next task", NotificationManager.IMPORTANCE_LOW);
        task.setDescription("The nearest habit in the persistent notification: ✓ / ✕ in one tap");
        task.setShowBadge(false);
        task.setSound(null, null);
        task.enableVibration(false);
        nm.createNotificationChannel(task);
    }

    private String statusLine() {
        List<String> parts = new ArrayList<>();
        if (!engine.lan.connectedPeers().isEmpty()) parts.add("Wi-Fi");
        if (!engine.ble.connectedPeers().isEmpty()) parts.add("Bluetooth");
        if (engine.internet.isConnected()) parts.add("Internet");
        if (parts.isEmpty()) return "Waiting for connection…";
        StringBuilder sb = new StringBuilder("Connected: ");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private void updateNotification() {
        if (!isRunning) return;
        Notification n = buildNotification();
        if (lastNotificationText.equals(lastPosted)) return;
        lastPosted = lastNotificationText;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NOTIFICATION_ID, n);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private PendingIntent receiver(String action, Habit h, String time, int code) {
        Intent intent = new Intent(this, HabitReceiver.class).setAction(action)
            .putExtra(HabitReceiver.EXTRA_ID, h.id)
            .putExtra(HabitReceiver.EXTRA_DATE, HabitStore.today().toString())
            .putExtra(HabitReceiver.EXTRA_TIME, time != null ? time : "");
        return PendingIntent.getBroadcast(this, code, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The next task (with ✓ / ✕) or — when there is none — the connection status */
    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        PendingIntent open = Nexo.openHabitsPending(this);
        PendingIntent send = PendingIntent.getActivity(
            this, 1,
            new Intent(this, SendClipboardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        HabitStore store = engine.habits;
        NowTask.State st = NowTask.state(store);
        NowTask.Pick p = st.pick;
        String summary = (st.total > 0 ? "Today " + st.done + " / " + st.total + " · " : "") + "⭐ " + store.balance();
        String title;
        String text;
        String channel;
        if (p != null) {
            channel = TASK_CHANNEL_ID;
            String label;
            if (p.time != null) {
                label = " · " + Repeat.plainLabel(p.time);
            } else if (!p.habit.repeat.times.isEmpty()) {
                label = " · " + Repeat.plainLabel(p.habit.repeat.times.get(0));
            } else {
                label = "";
            }
            title = p.habit.emoji + " " + p.habit.name + label;
            text = (p.at != null ? NowTask.left(p.at) + " · " : "") + summary;
        } else if (st.total > 0 && st.done >= st.total) {
            channel = TASK_CHANNEL_ID;
            title = "🎉 Everything done today";
            text = summary;
        } else {
            channel = store.isEmpty() ? CHANNEL_ID : TASK_CHANNEL_ID;
            title = "Nexo";
            text = statusLine();
        }
        lastNotificationText = channel + "|" + title + "|" + text + "|" + (p != null ? p.habit.id : null) + "|" + (p != null ? p.time : null);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MIN);
        if (p != null) {
            // int-icon constructor: the Icon variant is API 23+ and the fork's minSdk is 21
            b.addAction(new Notification.Action.Builder(0, "✓ Done", receiver(HabitReceiver.ACTION_SLOT_DONE, p.habit, p.time, 10)).build());
            if (p.habit.daily()) {
                b.addAction(new Notification.Action.Builder(0, "✕ Skipped", receiver(HabitReceiver.ACTION_SLOT_SKIP, p.habit, p.time, 11)).build());
            }
        }
        b.addAction(new Notification.Action.Builder(0, "Send to Mac", send).build());
        return b.build();
    }
}
