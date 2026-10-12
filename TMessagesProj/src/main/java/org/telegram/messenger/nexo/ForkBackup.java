package org.telegram.messenger.nexo;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LockedChats;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * FORK MOD: backup of everything the fork adds on top of Telegram — module settings (Mac pairing,
 * reminders, prayer city…) and the locked-chat list. Telegram's own data is not included.
 * The file is sent daily to a chosen Telegram chat at a chosen time (when the device is online); local copies are
 * kept for a few days by {@link LocalBackup}.
 */
public class ForkBackup {
    public static final String ENVELOPE = "telegram-fork-backup";
    public static final int VERSION = 3;
    private static final int ALARM_REQUEST = 400;

    /** Full backup JSON */
    public static byte[] payload() {
        try {
            JSONObject o = new JSONObject()
                .put("fork", ENVELOPE)
                .put("v", VERSION)
                .put("exportedAt", System.currentTimeMillis())
                .put("device", Nexo.engine().deviceName)
                .put("prefs", Nexo.prefs().exportJson())
                .put("lockedChats", new JSONArray(LockedChats.all()));
            return o.toString(2).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            FileLog.e(e);
            return "{}".getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Bytes to write to a file: encrypted when a passphrase is set, plain JSON otherwise */
    public static byte[] fileBytes() {
        byte[] plain = payload();
        String pass = Nexo.prefs().getBackupPassphrase();
        if (pass.isEmpty()) return plain;
        try {
            return ForkCrypto.encrypt(plain, pass);
        } catch (Exception e) {
            FileLog.e(e);
            return plain;
        }
    }

    public static String fileName() {
        return "telegram-backup-" + LocalDate.now() + (Nexo.prefs().isBackupEncrypted() ? ForkCrypto.EXT : ".json");
    }

    /** Plain JSON bytes of a file: decrypts with the stored passphrase if needed; null when encrypted and the passphrase does not fit */
    public static byte[] open(byte[] bytes) {
        if (!ForkCrypto.isEncrypted(bytes)) return bytes;
        return ForkCrypto.decrypt(bytes, Nexo.prefs().getBackupPassphrase());
    }

    /** {locked chats, settings} counts, or null if the bytes are not a fork backup */
    public static int[] summary(byte[] bytes) {
        JSONObject o = envelope(bytes);
        if (o == null) return null;
        JSONArray locked = o.optJSONArray("lockedChats");
        JSONObject prefs = o.optJSONObject("prefs");
        return new int[]{locked == null ? 0 : locked.length(), prefs == null ? 0 : prefs.length()};
    }

    private static JSONObject envelope(byte[] bytes) {
        try {
            JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            return ENVELOPE.equals(o.optString("fork")) ? o : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Restores: settings and locked chats are applied. */
    public static boolean restore(byte[] bytes) {
        JSONObject o = envelope(bytes);
        if (o == null) return false;
        try {
            JSONObject prefs = o.optJSONObject("prefs");
            if (prefs != null) Nexo.prefs().importJson(prefs);
            JSONArray locked = o.optJSONArray("lockedChats");
            if (locked != null) {
                for (int i = 0; i < locked.length(); i++) LockedChats.setLocked(locked.getLong(i), true);
            }
            Nexo.engine().postApplySettings();
            schedule(Nexo.ctx());
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    // region Telegram

    public static boolean isConfigured() {
        return Nexo.prefs().getBackupDialogId() != 0;
    }

    private static boolean isOnline() {
        return ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState() == ConnectionsManager.ConnectionStateConnected;
    }

    /** Daily alarm at the chosen time (ForkBackupReceiver); checks also run on app start */
    public static void schedule(Context ctx) {
        AlarmManager am = ContextCompat.getSystemService(ctx, AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, ALARM_REQUEST,
            new Intent(ctx, ForkBackupReceiver.class).setAction(ForkBackupReceiver.ACTION_BACKUP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (!isConfigured()) {
            am.cancel(pi);
            return;
        }
        int m = Nexo.prefs().getBackupMinutes();
        LocalDateTime at = LocalDate.now().atTime(m / 60, m % 60);
        if (!at.isAfter(LocalDateTime.now())) at = at.plusDays(1);
        long ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi);
    }

    /** Sends today's backup if the chosen time has passed, it was not sent today yet and the device is online */
    public static void checkDue(Context ctx) {
        if (!isConfigured()) return;
        NexoPrefs p = Nexo.prefs();
        int m = p.getBackupMinutes();
        LocalDateTime due = LocalDate.now().atTime(m / 60, m % 60);
        if (LocalDateTime.now().isBefore(due)) {
            schedule(ctx);
            return;
        }
        long last = p.getLastTelegramBackupAt();
        LocalDate lastDay = last == 0 ? null : Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault()).toLocalDate();
        if (lastDay != null && !lastDay.isBefore(LocalDate.now())) {
            schedule(ctx);
            return;
        }
        if (!isOnline()) return; // retried by the next alarm / app start
        sendNow(ctx);
        schedule(ctx);
    }

    /** Writes the file into the cache and sends it as a document to the chosen chat. Returns false if not configured. */
    public static boolean sendNow(Context ctx) {
        long dialogId = Nexo.prefs().getBackupDialogId();
        if (dialogId == 0) return false;
        try {
            File dir = new File(AndroidUtilities.getCacheDir(), "fork_backup");
            dir.mkdirs();
            boolean enc = Nexo.prefs().isBackupEncrypted();
            File file = new File(dir, fileName());
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(fileBytes()); }
            String caption = "Telegram fork backup · " + Dates.longDate(LocalDate.now()) + (enc ? " · encrypted" : "")
                + "\nSettings and locked chats. Restore: Settings → Mods → Restore from file.";
            AndroidUtilities.runOnUIThread(() -> SendMessagesHelper.prepareSendingDocument(
                AccountInstance.getInstance(UserConfig.selectedAccount), file.getAbsolutePath(), file.getAbsolutePath(), null,
                caption, enc ? "application/octet-stream" : "application/json", dialogId, null, null, null, null, null, true, 0, null, null, false));
            Nexo.prefs().setLastTelegramBackupAt(System.currentTimeMillis());
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    // endregion
}
