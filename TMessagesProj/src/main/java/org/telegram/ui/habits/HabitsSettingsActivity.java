package org.telegram.ui.habits;

import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.nexo.Dates;
import org.telegram.messenger.nexo.HabitStore;
import org.telegram.messenger.nexo.Health;
import org.telegram.messenger.nexo.Nexo;
import org.telegram.messenger.nexo.NexoPrefs;
import org.telegram.messenger.nexo.PrayerTimes;
import org.telegram.messenger.nexo.Repeat;
import org.telegram.messenger.nexo.ScreenTime;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;

/** FORK MOD (Nexo): Settings → Habits — all habit-related settings grouped in sections. */
public class HabitsSettingsActivity extends HabitsSettingsBase {
    private static final int ID_GOALS = 1, ID_CATS = 2, ID_REMINDER = 3, ID_PRAYER = 4, ID_SCREEN = 5, ID_HEALTH = 6, ID_MAC = 7, ID_BACKUP = 8, ID_OPEN = 9;
    private final Runnable listener = this::update;

    private void update() {
        if (listView != null && listView.adapter != null) listView.adapter.update(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        Nexo.store().addListener(listener);
        PrayerTimes.addListener(listener);
        Health.addListener(listener);
        if (getContext() != null) Health.refresh(getContext());
        update();
    }

    @Override
    public void onPause() {
        super.onPause();
        Nexo.store().removeListener(listener);
        PrayerTimes.removeListener(listener);
        Health.removeListener(listener);
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.Habits);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        HabitStore store = Nexo.store();
        NexoPrefs prefs = Nexo.prefs();
        items.add(UItem.asHeader(LocaleController.getString(R.string.Habits)));
        items.add(UItem.asButton(ID_GOALS, R.drawable.msg_premium_liststar, LocaleController.getString(R.string.HabitsRewards), "⭐ " + store.balance()));
        items.add(UItem.asButton(ID_CATS, R.drawable.msg_folders, LocaleController.getString(R.string.HabitsCategories), String.valueOf(store.cats().size())));
        items.add(UItem.asButton(ID_REMINDER, R.drawable.msg_notifications, LocaleController.getString(R.string.HabitsEveningReminder), prefs.isHabitReminder() ? Repeat.hhmm(prefs.getHabitReminderMinutes()) : LocaleController.getString(R.string.HabitsOff)));
        items.add(UItem.asButton(ID_PRAYER, R.drawable.msg_calendar2, LocaleController.getString(R.string.HabitsPrayerTimes), PrayerTimes.city().name + " · " + PrayerTimes.schoolName(prefs.getPrayerSchool())));
        boolean ready = getContext() != null && ScreenTime.hasAccess(getContext());
        items.add(UItem.asButton(ID_SCREEN, R.drawable.msg_stats, LocaleController.getString(R.string.HabitsScreenTime), ready ? LocaleController.getString(R.string.HabitsReady) : LocaleController.getString(R.string.HabitsPermissionNeeded)));
        String health;
        if (getContext() == null || !Health.available(getContext())) health = Health.needsInstall(getContext()) ? "Install" : "Unavailable";
        else health = Health.isConnected() ? LocaleController.getString(R.string.HabitsConnected) : LocaleController.getString(R.string.HabitsNotConnected);
        items.add(UItem.asButton(ID_HEALTH, R.drawable.msg_fave, LocaleController.getString(R.string.HabitsWatch), health));
        items.add(UItem.asShadow(null));
        items.add(UItem.asShadow(LocaleController.getString(R.string.HabitsWidgetInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_GOALS: presentFragment(new HabitGoalsActivity()); break;
            case ID_CATS: presentFragment(new HabitCategoriesActivity()); break;
            case ID_REMINDER: presentFragment(new HabitReminderActivity()); break;
            case ID_PRAYER: presentFragment(new PrayerSettingsActivity()); break;
            case ID_SCREEN: presentFragment(new ScreenTimeSettingsActivity()); break;
            case ID_HEALTH: presentFragment(new HealthSettingsActivity()); break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
