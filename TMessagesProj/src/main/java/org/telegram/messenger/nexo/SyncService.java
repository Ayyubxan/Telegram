package org.telegram.messenger.nexo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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

    private final Runnable notifyRunnable = this::updateNotification;
    /** Every minute: notification text refresh */
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
        main.postDelayed(tick, 60_000);
        engine.activate();
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
        main.removeCallbacks(tick);
        engine.deactivate();
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
        nm.deleteNotificationChannel(TASK_CHANNEL_ID); // the old habits channel
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

    /** The connection status, with a "Send to Mac" action */
    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 2, Nexo.openIntent(this, Nexo.ACTION_OPEN_MAC_SYNC), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent send = PendingIntent.getActivity(
            this, 1,
            new Intent(this, SendClipboardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        String title = "Mac clipboard";
        String text = statusLine();
        lastNotificationText = CHANNEL_ID + "|" + title + "|" + text;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MIN);
        b.addAction(new Notification.Action.Builder(0, "Send to Mac", send).build());
        return b.build();
    }
}
