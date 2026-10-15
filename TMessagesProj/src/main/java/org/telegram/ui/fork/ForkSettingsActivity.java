package org.telegram.ui.fork;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LockedChats;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.nexo.Dates;
import org.telegram.messenger.nexo.ForkBackup;
import org.telegram.messenger.nexo.Nexo;
import org.telegram.messenger.nexo.NexoPrefs;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.habits.HabitUi;
import org.telegram.ui.habits.HabitsSettingsBase;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * FORK MOD: Settings → Mods — the fork's own settings: locked chats (visible only while they are revealed)
 * and the Telegram backup of the fork's data (module settings, locked chats).
 */
public class ForkSettingsActivity extends HabitsSettingsBase {
    private static final int ID_EXPORT = 3, ID_RESTORE = 4, ID_ENCRYPT = 6;
    private static final int REQ_EXPORT = 43;
    private byte[] pendingExport;
    private static final int ID_HOOK_ADD = 7;
    private static final int ID_LOCKED_BASE = 1000, ID_HOOK_BASE = 3000;
    private static final int REQ_IMPORT = 42;
    private final ArrayList<Long> lockedShown = new ArrayList<>();

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ForkMods);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.adapter != null) listView.adapter.update(true);
    }

    private TLObject peer(long dialogId) {
        MessagesController mc = getMessagesController();
        return dialogId > 0 ? mc.getUser(dialogId) : mc.getChat(-dialogId);
    }

    private String peerName(long dialogId) {
        if (dialogId == getUserConfig().getClientUserId()) return LocaleController.getString(R.string.SavedMessages);
        TLObject obj = peer(dialogId);
        if (obj instanceof TLRPC.User) return UserObject.getUserName((TLRPC.User) obj);
        if (obj instanceof TLRPC.Chat) return ((TLRPC.Chat) obj).title;
        return String.valueOf(dialogId);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        NexoPrefs prefs = Nexo.prefs();
        // Locked chats — only while revealed (long-press the lock icon in Chats)
        lockedShown.clear();
        if (LockedChats.isHidden()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.ForkLockedChats) + ": " + LocaleController.getString(R.string.ForkLockedChatsHidden)));
        } else {
            List<Long> ids = LockedChats.all();
            lockedShown.addAll(ids);
            if (ids.isEmpty()) {
                items.add(UItem.asShadow(LocaleController.getString(R.string.ForkLockedChats) + ": " + LocaleController.getString(R.string.ForkLockedChatsEmpty)));
            } else {
                items.add(UItem.asHeader(LocaleController.getString(R.string.ForkLockedChats)));
                for (int i = 0; i < ids.size(); i++) {
                    TLObject obj = peer(ids.get(i));
                    if (obj != null) items.add(UItem.asProfileCell(obj).setId(ID_LOCKED_BASE + i));
                    else items.add(UItem.asButton(ID_LOCKED_BASE + i, String.valueOf(ids.get(i))));
                }
                items.add(UItem.asShadow(LocaleController.getString(R.string.ForkLockedChatsInfo)));
            }
        }

        // Webhooks
        items.add(UItem.asHeader("Webhooks"));
        java.util.List<org.telegram.messenger.nexo.Webhooks.Hook> hooks = org.telegram.messenger.nexo.Webhooks.all();
        for (int i = 0; i < hooks.size(); i++) {
            org.telegram.messenger.nexo.Webhooks.Hook h = hooks.get(i);
            int waiting = org.telegram.messenger.nexo.Webhooks.pending(h.id);
            String sub = (h.enabled ? "" : "Paused · ") + h.target + (waiting > 0 ? " · " + waiting + " waiting" : h.failed > 0 ? " · " + h.failed + " failed" : "");
            items.add(UItem.asButton(ID_HOOK_BASE + i, h.name.isEmpty() ? "Webhook" : h.name, sub));
        }
        items.add(UItem.asButton(ID_HOOK_ADD, "Add webhook").accent());
        items.add(UItem.asShadow("New messages of a chat are posted to an https:// address or handed to another app, with retries until delivered."));

        // Export / import
        items.add(UItem.asHeader("Export & import"));
        items.add(UItem.asButton(ID_EXPORT, "Export to file").accent());
        items.add(UItem.asButton(ID_RESTORE, "Import from file"));
        items.add(UItem.asButton(ID_ENCRYPT, "Encryption", prefs.isBackupEncrypted() ? "On · passphrase set" : "Off · plain JSON"));
        items.add(UItem.asShadow("One file with the fork's settings (Mac pairing, webhooks), locked chats, and the messages anti-delete kept — deleted by the other side or edited — which exist only on this phone. Import merges: nothing here is removed."));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        NexoPrefs prefs = Nexo.prefs();
        if (item.id >= ID_LOCKED_BASE && item.id - ID_LOCKED_BASE < lockedShown.size()) {
            long id = lockedShown.get(item.id - ID_LOCKED_BASE);
            HabitUi.confirm(this, null, LocaleController.formatString(R.string.ForkUnlockChatConfirm, peerName(id)), LocaleController.getString(R.string.ForkUnlock), true, () -> {
                LockedChats.setLocked(id, false);
                listView.adapter.update(true);
            });
            return;
        }
        if (item.id >= ID_HOOK_BASE && item.id - ID_HOOK_BASE < org.telegram.messenger.nexo.Webhooks.all().size()) {
            presentFragment(new WebhookActivity(org.telegram.messenger.nexo.Webhooks.all().get(item.id - ID_HOOK_BASE)));
            return;
        }
        switch (item.id) {
            case ID_HOOK_ADD:
                presentFragment(new WebhookActivity(null));
                break;
            case ID_EXPORT:
                HabitUi.toast(this, "Preparing…");
                ForkBackup.export(bytes -> {
                    if (bytes == null) { HabitUi.toast(this, "Export failed"); return; }
                    pendingExport = bytes;
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(prefs.isBackupEncrypted() ? "application/octet-stream" : "application/json").putExtra(Intent.EXTRA_TITLE, ForkBackup.fileName()), REQ_EXPORT);
                });
                break;
            case ID_ENCRYPT:
                if (prefs.isBackupEncrypted()) {
                    HabitUi.confirm(this, "Backup encryption", "Exports are encrypted with your passphrase (AES-256). Turn it off? New exports will be plain JSON.", "Turn off", true, () -> {
                        prefs.setBackupPassphrase("");
                        listView.adapter.update(true);
                    });
                } else {
                    HabitUi.textDialog(this, "Backup passphrase", "Exported files will be unreadable without it. Remember it — there is no recovery. A new phone will ask for it on import.", "", "Passphrase", false, s -> {
                        if (s.length() < 4) { HabitUi.toast(this, "At least 4 characters"); return; }
                        prefs.setBackupPassphrase(s);
                        listView.adapter.update(true);
                        HabitUi.toastSuccess(this, "Encryption on");
                    });
                }
                break;
            case ID_RESTORE:
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT);
                break;
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_EXPORT) {
            byte[] bytes = pendingExport;
            pendingExport = null;
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null || bytes == null || getContext() == null) return;
            try (java.io.OutputStream os = getContext().getContentResolver().openOutputStream(data.getData(), "wt")) {
                os.write(bytes);
                HabitUi.toastSuccess(this, "Exported");
            } catch (Exception e) {
                HabitUi.toast(this, "Could not write the file");
            }
            return;
        }
        if (requestCode != REQ_IMPORT || resultCode != Activity.RESULT_OK || data == null || data.getData() == null || getContext() == null) return;
        Uri uri = data.getData();
        byte[] bytes = null;
        try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            bytes = bo.toByteArray();
        } catch (Exception ignore) {}
        if (bytes == null) {
            HabitUi.toast(this, LocaleController.getString(R.string.ForkBackupInvalid));
            return;
        }
        if (org.telegram.messenger.nexo.ForkCrypto.isEncrypted(bytes)) {
            byte[] plain = ForkBackup.open(bytes);
            if (plain != null) { restoreBytes(plain); return; }
            final byte[] enc = bytes;
            HabitUi.textDialog(this, "Encrypted backup", "Enter the passphrase this backup was made with.", "", "Passphrase", false, s -> {
                byte[] p = org.telegram.messenger.nexo.ForkCrypto.decrypt(enc, s);
                if (p == null) { HabitUi.toast(this, "Wrong passphrase"); return; }
                if (!Nexo.prefs().isBackupEncrypted()) Nexo.prefs().setBackupPassphrase(s); // keep it for later exports on this phone
                restoreBytes(p);
            });
            return;
        }
        restoreBytes(bytes);
    }

    private void restoreBytes(byte[] bytes) {
        ForkBackup.Summary sm = ForkBackup.summary(bytes);
        if (sm == null) {
            HabitUi.toast(this, LocaleController.getString(R.string.ForkBackupInvalid));
            return;
        }
        final byte[] data2 = bytes;
        String text = sm.settings + " settings, " + sm.lockedChats + " locked chats, " + sm.keptMessages + " kept messages.\nNothing on this phone is removed — missing messages are added back, edit histories are merged.";
        HabitUi.confirm(this, "Import this file?", text, "Import", false, () -> ForkBackup.restore(data2, n -> {
            if (n < 0) HabitUi.toast(this, LocaleController.getString(R.string.ForkBackupInvalid));
            else HabitUi.toastSuccess(this, "Imported · " + n + (n == 1 ? " message" : " messages") + " restored");
            listView.adapter.update(true);
        }));
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
