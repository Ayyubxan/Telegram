package org.telegram.ui.finance;

import android.view.View;

import org.telegram.messenger.finance.BotImport;
import org.telegram.messenger.finance.BotParser;
import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.finance.FinanceDb;
import org.telegram.messenger.finance.ImportRule;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.habits.HabitUi;
import org.telegram.ui.habits.HabitsSettingsBase;

import java.util.ArrayList;
import java.util.List;

/** FORK MOD (Finance): chats whose messages are turned into transactions (bank bots, business bots) */
public class ImportRulesActivity extends HabitsSettingsBase {
    private static final int ID_ADD = -1;
    private static final int ID_INBOX = -2;
    private final Runnable listener = this::update;
    private final ArrayList<ImportRule> shown = new ArrayList<>();

    private void update() {
        if (listView != null && listView.adapter != null) listView.adapter.update(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        Finance.addListener(listener);
        update();
    }

    @Override
    public void onPause() {
        super.onPause();
        Finance.removeListener(listener);
    }

    @Override
    protected CharSequence getTitle() {
        return "Bot import";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        FinanceDb db = Finance.db();
        int pending = BotImport.pendingCount();
        items.add(UItem.asButton(ID_INBOX, "Inbox", pending == 0 ? "empty" : pending + " to review"));
        items.add(UItem.asShadow("Messages from the chats below are parsed into transactions. New ones wait in the Inbox unless a chat is set to add them automatically."));
        items.add(UItem.asHeader("Chats"));
        shown.clear();
        List<ImportRule> rules = db.rules();
        for (ImportRule r : rules) {
            items.add(UItem.asButton(shown.size(), (r.enabled ? "" : "⏸ ") + r.name, r.auto ? "auto" : "review"));
            shown.add(r);
        }
        items.add(UItem.asButton(ID_ADD, "＋  Add chat").accent());
        items.add(UItem.asShadow(rules.isEmpty() ? "Pick the bank bot or the business bot that sends you payment messages. The last message is used as a sample to set up the fields." : null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ADD) {
            FinanceUi.pickDialog(this, dialogId -> {
                FinanceDb db = Finance.db();
                ImportRule existing = db.ruleForDialog(dialogId);
                if (existing != null) { org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> presentFragment(new ImportRuleActivity(existing.id)), 350); return; }
                ImportRule r = new ImportRule();
                r.id = Finance.newId();
                r.dialogId = dialogId;
                r.name = FinanceUi.dialogName(this, dialogId);
                String sample = BotImport.lastMessageText(getCurrentAccount(), dialogId);
                if (sample != null) {
                    r.sample = sample;
                    r.mapping.addAll(BotParser.guess(sample));
                }
                List<org.telegram.messenger.finance.Account> accs = db.accounts(false);
                if (!accs.isEmpty()) r.account = accs.get(0).id;
                db.saveRule(r);
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> presentFragment(new ImportRuleActivity(r.id)), 350);
            });
        } else if (item.id == ID_INBOX) {
            presentFragment(new InboxActivity());
        } else if (item.id >= 0 && item.id < shown.size()) {
            presentFragment(new ImportRuleActivity(shown.get(item.id).id));
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id >= 0 && item.id < shown.size()) {
            ImportRule r = shown.get(item.id);
            HabitUi.confirm(this, null, "Remove \"" + r.name + "\" from import?", "Remove", true, () -> Finance.db().deleteRule(r.id));
            return true;
        }
        return false;
    }
}
