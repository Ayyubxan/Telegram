package org.telegram.ui.fork;

import android.os.Bundle;
import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.nexo.Nexo;
import org.telegram.messenger.nexo.Webhooks;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.habits.HabitUi;
import org.telegram.ui.habits.HabitsSettingsBase;

import java.util.ArrayList;

/** FORK MOD: one webhook — chat, target (https URL or app:<package>/<receiver>), secret, options, stats, test, delete */
public class WebhookActivity extends HabitsSettingsBase {
    private static final int ID_CHAT = 1, ID_TARGET = 2, ID_SECRET = 3, ID_ENABLED = 4, ID_OUTGOING = 5, ID_TEST = 6, ID_RETRY = 7, ID_CLEAR = 8, ID_DELETE = 9;
    private final Webhooks.Hook hook;
    private final boolean isNew;

    public WebhookActivity(Webhooks.Hook existing) {
        this.hook = existing != null ? existing : new Webhooks.Hook();
        this.isNew = existing == null;
    }

    @Override
    protected CharSequence getTitle() {
        return isNew ? "New webhook" : (hook.name.isEmpty() ? "Webhook" : hook.name);
    }

    private final Runnable changed = () -> { if (listView != null && listView.adapter != null) listView.adapter.update(true); };

    @Override
    public boolean onFragmentCreate() {
        Nexo.addListener(changed);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        Nexo.removeListener(changed);
        super.onFragmentDestroy();
    }

    private void save() {
        Webhooks.save(hook);
        listView.adapter.update(true);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Source"));
        items.add(UItem.asButton(ID_CHAT, "Chat", hook.dialogId == 0 ? "Choose" : hook.name));
        items.add(UItem.asCheck(ID_OUTGOING, "Include my own messages").setChecked(hook.outgoing));
        items.add(UItem.asShadow("Every new message of the chat is sent as JSON: id, message_id, date, chat_id, chat, from, text, media, reply_to."));

        items.add(UItem.asHeader("Target"));
        items.add(UItem.asButton(ID_TARGET, "Address", hook.target.isEmpty() ? "Set" : hook.target));
        items.add(UItem.asButton(ID_SECRET, "Secret", hook.secret.isEmpty() ? "None" : "••••••"));
        items.add(UItem.asShadow("https://… — HTTP POST with the JSON body and the secret in the X-Webhook-Secret header.\napp:<package>/<receiver> — a broadcast to that Android app (action " + Webhooks.APP_ACTION + ", extras json, hook, secret). The app must answer setResultCode(RESULT_OK), otherwise the message is retried."));

        items.add(UItem.asHeader("Delivery"));
        items.add(UItem.asCheck(ID_ENABLED, "Enabled").setChecked(hook.enabled));
        int pending = isNew ? 0 : Webhooks.pending(hook.id);
        String stats = hook.sent + " sent" + (hook.failed > 0 ? " · " + hook.failed + " failed" : "") + (pending > 0 ? " · " + pending + " waiting" : "");
        items.add(UItem.asButton(ID_RETRY, "Status", stats));
        if (!hook.lastError.isEmpty()) items.add(UItem.asShadow("Last error: " + hook.lastError));
        items.add(UItem.asButton(ID_TEST, "Send a test message").accent());
        if (pending > 0) items.add(UItem.asButton(ID_CLEAR, "Drop " + pending + " waiting"));
        items.add(UItem.asShadow("Failed deliveries are kept and retried with growing delays (1 min → 6 h, up to " + Webhooks.MAX_ATTEMPTS + " attempts), also after a reboot."));

        if (!isNew) items.add(UItem.asButton(ID_DELETE, LocaleController.getString(R.string.Delete)).red());
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_CHAT: {
                Bundle args = new Bundle();
                args.putBoolean("onlySelect", true);
                args.putBoolean("allowGlobalSearch", false);
                args.putBoolean("resetDelegate", false);
                DialogsActivity activity = new DialogsActivity(args);
                activity.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
                    if (!dids.isEmpty()) {
                        hook.dialogId = dids.get(0).dialogId;
                        hook.name = Webhooks.chatTitle(getCurrentAccount(), hook.dialogId);
                        if (!hook.target.isEmpty()) save(); else listView.adapter.update(true);
                    }
                    fragment.finishFragment();
                    return true;
                });
                presentFragment(activity);
                break;
            }
            case ID_TARGET:
                HabitUi.textDialog(this, "Address", "https://example.com/hook  or  app:com.example.app/.HookReceiver", hook.target, "https://", false, s -> {
                    String t = s.trim();
                    if (t.isEmpty()) return;
                    if (!t.startsWith("https://") && !t.startsWith("http://") && !t.startsWith("app:")) { HabitUi.toast(this, "Must start with https:// or app:"); return; }
                    hook.target = t;
                    if (hook.dialogId != 0) save(); else listView.adapter.update(true);
                });
                break;
            case ID_SECRET:
                HabitUi.textDialog(this, "Secret", "Sent as the X-Webhook-Secret header (or the secret extra). Leave empty for none.", hook.secret, "Secret", false, s -> {
                    hook.secret = s.trim();
                    if (!isNew) save(); else listView.adapter.update(true);
                });
                break;
            case ID_ENABLED:
                hook.enabled = !hook.enabled;
                if (!isNew) save(); else listView.adapter.update(true);
                break;
            case ID_OUTGOING:
                hook.outgoing = !hook.outgoing;
                if (!isNew) save(); else listView.adapter.update(true);
                break;
            case ID_TEST:
                if (hook.target.isEmpty()) { HabitUi.toast(this, "Set the address first"); return; }
                HabitUi.toast(this, "Sending…");
                Webhooks.test(getContext(), hook, err -> {
                    if (err == null) HabitUi.toastSuccess(this, "Delivered"); else HabitUi.toast(this, "Failed: " + err);
                });
                break;
            case ID_RETRY:
                Webhooks.flush(getContext());
                HabitUi.toast(this, "Retrying now");
                break;
            case ID_CLEAR:
                HabitUi.confirm(this, null, "Drop the waiting messages of this webhook?", "Drop", true, () -> { Webhooks.clearQueue(hook.id); listView.adapter.update(true); });
                break;
            case ID_DELETE:
                HabitUi.confirm(this, null, "Delete this webhook?", LocaleController.getString(R.string.Delete), true, () -> { Webhooks.delete(hook.id); finishFragment(); });
                break;
        }
    }

    @Override
    public void onPause() {
        // a new hook is saved once it has both a chat and an address (leaving the screen in any way)
        if (isNew && hook.dialogId != 0 && !hook.target.isEmpty() && Webhooks.get(hook.id == null ? "" : hook.id) == null) Webhooks.save(hook);
        super.onPause();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
