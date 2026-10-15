package org.telegram.messenger.nexo;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LockedChats;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * FORK MOD: export / import file of what the fork adds on top of Telegram — module settings (Mac pairing, webhooks…),
 * the locked-chat list, and per account the messages anti-delete kept (deleted by the other side / edit history), which
 * exist nowhere else. Telegram's own chats are in the cloud and are not included. Optional AES passphrase ({@link ForkCrypto}).
 */
public class ForkBackup {
    public static final String ENVELOPE = "telegram-fork-backup";
    public static final int VERSION = 4;

    /** Result of reading a file */
    public static class Summary {
        public int lockedChats, settings, keptMessages, accounts;
    }

    /** Builds the file (background thread); {@code done} gets the bytes on the UI thread, or null on failure */
    public static void export(Utilities.Callback<byte[]> done) {
        Utilities.globalQueue.postRunnable(() -> {
            byte[] out = null;
            try {
                JSONArray accounts = new JSONArray();
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    UserConfig uc = UserConfig.getInstance(a);
                    if (!uc.isClientActivated()) continue;
                    accounts.put(new JSONObject().put("userId", uc.getClientUserId()).put("kept", KeptMessages.export(a)));
                }
                JSONObject o = new JSONObject()
                    .put("fork", ENVELOPE)
                    .put("v", VERSION)
                    .put("exportedAt", System.currentTimeMillis())
                    .put("device", Nexo.engine().deviceName)
                    .put("prefs", Nexo.prefs().exportJson())
                    .put("lockedChats", new JSONArray(LockedChats.all()))
                    .put("accounts", accounts);
                byte[] plain = o.toString().getBytes(StandardCharsets.UTF_8);
                String pass = Nexo.prefs().getBackupPassphrase();
                out = pass.isEmpty() ? plain : ForkCrypto.encrypt(plain, pass);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final byte[] result = out;
            AndroidUtilities.runOnUIThread(() -> done.run(result));
        });
    }

    public static String fileName() {
        return "telegram-fork-" + LocalDate.now() + (Nexo.prefs().isBackupEncrypted() ? ForkCrypto.EXT : ".json");
    }

    /** Plain JSON bytes of a file: decrypts with the stored passphrase if needed; null when encrypted and the passphrase does not fit */
    public static byte[] open(byte[] bytes) {
        if (!ForkCrypto.isEncrypted(bytes)) return bytes;
        return ForkCrypto.decrypt(bytes, Nexo.prefs().getBackupPassphrase());
    }

    private static JSONObject envelope(byte[] bytes) {
        try {
            JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            return ENVELOPE.equals(o.optString("fork")) ? o : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** What the file holds, or null if it is not a fork backup */
    public static Summary summary(byte[] bytes) {
        JSONObject o = envelope(bytes);
        if (o == null) return null;
        Summary s = new Summary();
        JSONArray locked = o.optJSONArray("lockedChats");
        JSONObject prefs = o.optJSONObject("prefs");
        s.lockedChats = locked == null ? 0 : locked.length();
        s.settings = prefs == null ? 0 : prefs.length();
        JSONArray acc = o.optJSONArray("accounts");
        if (acc != null) for (int i = 0; i < acc.length(); i++) {
            JSONObject a = acc.optJSONObject(i);
            JSONArray k = a == null ? null : a.optJSONArray("kept");
            if (k != null) { s.keptMessages += k.length(); s.accounts++; }
        }
        return s;
    }

    /** Restores settings, locked chats and kept messages (for accounts logged in here); {@code done} gets the number of messages restored, -1 on failure */
    public static void restore(byte[] bytes, Utilities.Callback<Integer> done) {
        Utilities.globalQueue.postRunnable(() -> {
            int n = -1;
            JSONObject o = envelope(bytes);
            if (o != null) {
                try {
                    JSONObject prefs = o.optJSONObject("prefs");
                    if (prefs != null) Nexo.prefs().importJson(prefs);
                    JSONArray locked = o.optJSONArray("lockedChats");
                    if (locked != null) {
                        final JSONArray l = locked;
                        AndroidUtilities.runOnUIThread(() -> { for (int i = 0; i < l.length(); i++) LockedChats.setLocked(l.optLong(i), true); });
                    }
                    n = 0;
                    JSONArray acc = o.optJSONArray("accounts");
                    if (acc != null) for (int i = 0; i < acc.length(); i++) {
                        JSONObject a = acc.optJSONObject(i);
                        if (a == null) continue;
                        long userId = a.optLong("userId");
                        for (int k = 0; k < UserConfig.MAX_ACCOUNT_COUNT; k++) {
                            UserConfig uc = UserConfig.getInstance(k);
                            if (uc.isClientActivated() && uc.getClientUserId() == userId) n += KeptMessages.restore(k, a.optJSONArray("kept"));
                        }
                    }
                    Nexo.engine().postApplySettings();
                } catch (Throwable e) {
                    FileLog.e(e);
                    n = -1;
                }
            }
            final int result = n;
            AndroidUtilities.runOnUIThread(() -> done.run(result));
        });
    }
}
