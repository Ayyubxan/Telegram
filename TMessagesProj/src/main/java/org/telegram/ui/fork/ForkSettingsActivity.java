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
import org.telegram.messenger.nexo.LocalBackup;
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
    private static final int ID_BACKUP_CHAT = 1, ID_BACKUP_TIME = 2, ID_BACKUP_NOW = 3, ID_RESTORE = 4, ID_LAST = 5, ID_ENCRYPT = 6;
    private static final int ID_LOCKED_BASE = 1000;
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

        // Telegram backup
        items.add(UItem.asHeader(LocaleController.getString(R.string.ForkBackup)));
        long dialogId = prefs.getBackupDialogId();
        items.add(UItem.asButton(ID_BACKUP_CHAT, LocaleController.getString(R.string.ForkBackupChat), dialogId == 0 ? LocaleController.getString(R.string.ForkBackupNotSet) : peerName(dialogId)));
        items.add(UItem.asButton(ID_BACKUP_TIME, LocaleController.getString(R.string.ForkBackupTime), String.format(java.util.Locale.US, "%02d:%02d", prefs.getBackupMinutes() / 60, prefs.getBackupMinutes() % 60)));
        long last = prefs.getLastTelegramBackupAt();
        items.add(UItem.asButton(ID_LAST, LocaleController.getString(R.string.ForkBackupLast), last == 0 ? "never" : Dates.daysAgo(java.time.temporal.ChronoUnit.DAYS.between(Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault()).toLocalDate(), LocalDate.now()))));
        items.add(UItem.asButton(ID_ENCRYPT, "Encryption", prefs.isBackupEncrypted() ? "On · passphrase set" : "Off · plain JSON"));
        items.add(UItem.asButton(ID_BACKUP_NOW, LocaleController.getString(R.string.ForkBackupNow)).accent());
        items.add(UItem.asButton(ID_RESTORE, LocaleController.getString(R.string.ForkBackupRestore)));
        items.add(UItem.asShadow(LocaleController.formatString(R.string.ForkBackupInfo, LocalBackup.KEEP_DAYS)));
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
        switch (item.id) {
            case ID_BACKUP_CHAT: {
                Bundle args = new Bundle();
                args.putBoolean("onlySelect", true);
                args.putBoolean("checkCanWrite", true);
                args.putBoolean("allowGlobalSearch", false);
                args.putBoolean("resetDelegate", false);
                DialogsActivity activity = new DialogsActivity(args);
                activity.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
                    if (!dids.isEmpty()) {
                        prefs.setBackupDialogId(dids.get(0).dialogId);
                        ForkBackup.schedule(getContext());
                    }
                    fragment.finishFragment();
                    listView.adapter.update(true);
                    return true;
                });
                presentFragment(activity);
                break;
            }
            case ID_BACKUP_TIME:
                showDialog(AlertsCreator.createTimePickerDialog(getContext(), LocaleController.getString(R.string.ForkBackupTime), prefs.getBackupMinutes(), 0, 24 * 60, minutes -> {
                    prefs.setBackupMinutes(minutes);
                    ForkBackup.schedule(getContext());
                    listView.adapter.update(true);
                }));
                break;
            case ID_BACKUP_NOW:
                if (!ForkBackup.isConfigured()) {
                    HabitUi.toast(this, LocaleController.getString(R.string.ForkBackupChooseChat));
                } else if (ForkBackup.sendNow(getContext())) {
                    HabitUi.toastSuccess(this, LocaleController.getString(R.string.ForkBackupSent));
                    listView.adapter.update(true);
                }
                break;
            case ID_ENCRYPT:
                if (prefs.isBackupEncrypted()) {
                    HabitUi.confirm(this, "Backup encryption", "Backups are encrypted with your passphrase (AES-256). Turn it off? New backups will be plain JSON again.", "Turn off", true, () -> {
                        prefs.setBackupPassphrase("");
                        listView.adapter.update(true);
                    });
                } else {
                    HabitUi.textDialog(this, "Backup passphrase", "Backups sent to Telegram and local copies will be unreadable without it. Remember it — there is no recovery. Only this phone keeps it; a new phone will ask for it on restore.", "", "Passphrase", false, s -> {
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
                if (!Nexo.prefs().isBackupEncrypted()) Nexo.prefs().setBackupPassphrase(s); // keep it for the daily backups on this phone
                restoreBytes(p);
            });
            return;
        }
        restoreBytes(bytes);
    }

    private void restoreBytes(byte[] bytes) {
        int[] summary = ForkBackup.summary(bytes);
        if (summary == null) {
            HabitUi.toast(this, LocaleController.getString(R.string.ForkBackupInvalid));
            return;
        }
        final byte[] data2 = bytes;
        HabitUi.confirm(this, LocaleController.getString(R.string.ForkBackupRestoreTitle), LocaleController.formatString(R.string.ForkBackupRestoreText, summary[0], summary[1]),
            LocaleController.getString(R.string.ForkBackupRestoreButton), false, () -> {
                boolean ok = ForkBackup.restore(data2);
                HabitUi.toastSuccess(this, ok ? "✓ " + LocaleController.getString(R.string.ForkBackupRestored) : LocaleController.getString(R.string.ForkBackupInvalid));
                listView.adapter.update(true);
            });
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
