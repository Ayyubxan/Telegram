package org.telegram.ui.fork;

import android.app.Activity;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.gmail.GmailAuth;
import org.telegram.messenger.gmail.GmailStore;
import org.telegram.messenger.gmail.GmailSync;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.habits.HabitUi;
import org.telegram.ui.habits.HabitsSettingsBase;

import java.util.ArrayList;

/**
 * FORK MOD (gmail): Settings → Mods → Gmail. Connects the Google account (Play Services consent screen), shows the
 * sync state and the few knobs: on/off, poll interval, how many emails the first sync pulls.
 */
public class GmailSettingsActivity extends HabitsSettingsBase {

    private static final int ID_CONNECT = 1, ID_ENABLED = 2, ID_SYNC = 3, ID_INTERVAL = 4, ID_INITIAL = 5, ID_DISCONNECT = 6, ID_ACCOUNT = 7;
    private static final int[] INTERVALS = {1, 2, 5, 15};
    private static final int[] INITIAL = {20, 30, 50, 100};

    private final Runnable refresh = () -> {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    };

    @Override
    protected CharSequence getTitle() {
        return "Gmail";
    }

    @Override
    public void onResume() {
        super.onResume();
        GmailSync.addListener(refresh);
        refresh.run();
    }

    @Override
    public void onPause() {
        super.onPause();
        GmailSync.removeListener(refresh);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (!GmailStore.isConnected()) {
            items.add(UItem.asShadow("Emails arrive as chats: one chat per sender, with the subject and the message text. Replying sends an email back; archiving or reporting the chat archives or reports the sender in Gmail."));
            items.add(UItem.asButton(ID_CONNECT, "Connect Google account").accent());
            if (!GmailAuth.trace().isEmpty()) {
                items.add(UItem.asShadow("Last sign-in attempt:\n" + GmailAuth.trace()));
            }
            items.add(UItem.asShadow("A one-time Google sign-in in the browser. Google may warn that the app is not verified — open \"Advanced\" and continue; afterwards close the tab and return here. Nothing leaves the phone except Gmail API calls."));
            return;
        }
        items.add(UItem.asHeader("Account"));
        items.add(UItem.asButton(ID_ACCOUNT, "Signed in as", GmailStore.email()));
        if (GmailStore.needsAuth()) {
            items.add(UItem.asButton(ID_CONNECT, "Re-authorize").red());
            items.add(UItem.asShadow("Google dropped the permission (password change, long inactivity, or the Cloud project is still in \"Testing\"). Sign in again to resume."));
        }
        items.add(UItem.asHeader("Sync"));
        items.add(UItem.asSwitch(ID_ENABLED, "Fetch new emails").setChecked(GmailStore.isEnabled()));
        String state;
        String err = GmailStore.lastError();
        if (!TextUtils.isEmpty(err)) {
            state = err;
        } else if (GmailStore.lastSync() == 0) {
            state = "Not synced yet";
        } else {
            state = "Last sync " + LocaleController.formatDateTime(GmailStore.lastSync() / 1000, true);
        }
        items.add(UItem.asButton(ID_SYNC, "Sync now", state));
        items.add(UItem.asButton(ID_INTERVAL, "Check every", GmailStore.intervalMinutes() + " min"));
        items.add(UItem.asButton(ID_INITIAL, "First sync pulls", GmailStore.initialCount() + " newest inbox emails"));
        items.add(UItem.asShadow("Polling runs on an alarm; a shorter interval costs a little battery. The app also checks when the chat list is opened."));
        items.add(UItem.asButton(ID_DISCONNECT, "Disconnect").red());
        items.add(UItem.asShadow("Stops syncing and forgets the mapping. The sender chats already here stay until you delete them."));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_CONNECT:
                connect();
                break;
            case ID_ENABLED: {
                boolean v = !GmailStore.isEnabled();
                GmailStore.setEnabled(v);
                if (v) {
                    GmailSync.schedule(getContext());
                    GmailSync.sync("enabled", null);
                } else {
                    GmailSync.cancel(getContext());
                }
                refresh.run();
                break;
            }
            case ID_SYNC:
                HabitUi.toast(this, "Syncing…");
                GmailSync.sync("manual", null);
                break;
            case ID_INTERVAL: {
                GmailStore.setIntervalMinutes(next(INTERVALS, GmailStore.intervalMinutes()));
                GmailSync.schedule(getContext());
                refresh.run();
                break;
            }
            case ID_INITIAL:
                GmailStore.setInitialCount(next(INITIAL, GmailStore.initialCount()));
                refresh.run();
                break;
            case ID_DISCONNECT:
                HabitUi.confirm(this, "Disconnect Gmail?", "New emails stop arriving. Existing sender chats stay on this phone; replies in them will fail until you connect again.", "Disconnect", true, () -> {
                    GmailSync.disconnect(getContext());
                    refresh.run();
                });
                break;
        }
    }

    private static int next(int[] values, int current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                return values[(i + 1) % values.length];
            }
        }
        return values[0];
    }

    private void connect() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        if (!GmailAuth.isConfigured()) {
            HabitUi.toast(this, "This build has no OAuth client id (GMAIL_OAUTH_CLIENT_ID in local.properties)");
            return;
        }
        HabitUi.toast(this, "Sign in with Google in the browser, then come back here");
        GmailAuth.connectViaBrowser(activity, (accessToken, error) -> {
            if (error != null) {
                HabitUi.toast(this, "Sign-in failed: " + error);
            } else {
                finishConnect(accessToken);
            }
        });
    }

    private void finishConnect(String token) {
        HabitUi.toast(this, "Connecting…");
        GmailSync.connect(currentAccount, token, error -> {
            if (error != null) {
                HabitUi.toast(this, "Connection failed: " + error);
            } else {
                HabitUi.toastSuccess(this, "Connected · " + GmailStore.email());
            }
            refresh.run();
        });
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
