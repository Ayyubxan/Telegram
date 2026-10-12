package org.telegram.ui.habits;

import android.content.Intent;
import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.nexo.Auto;
import org.telegram.messenger.nexo.Health;
import org.telegram.messenger.nexo.Repeat;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;

/** FORK MOD (Nexo): watch data via Health Connect — status, connect, today's values. */
public class HealthSettingsActivity extends HabitsSettingsBase {
    private static final int ID_CONNECT = 1, ID_BACKGROUND = 2, ID_SETTINGS = 3, ID_INSTALL = 4;
    private static final int REQ_HEALTH = 52;
    private final Runnable listener = () -> { if (listView != null && listView.adapter != null) listView.adapter.update(true); };

    @Override
    public void onResume() {
        super.onResume();
        Health.addListener(listener);
        if (getContext() != null) Health.refresh(getContext(), true);
        listener.run();
    }

    @Override
    public void onPause() {
        super.onPause();
        Health.removeListener(listener);
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.HabitsWatch);
    }

    private static String hm(long ms) {
        ZonedDateTime z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault());
        return Repeat.hhmm(z.getHour() * 60 + z.getMinute());
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (getContext() == null) return;
        if (Health.needsInstall(getContext())) {
            items.add(UItem.asButton(ID_INSTALL, "Health Connect is not installed — install from Play").accent());
            return;
        }
        if (!Health.available(getContext())) {
            items.add(UItem.asShadow("Health Connect does not work on this phone (Android 14 or newer is required)."));
            return;
        }
        boolean ok = Health.isConnected();
        items.add(UItem.asHeader("Connection"));
        items.add(UItem.asButton(ID_CONNECT, "Read data (steps, sleep, workouts)", ok ? "Connected" : "Connect"));
        if (ok) items.add(UItem.asButton(ID_BACKGROUND, "Read in the background", Health.hasBackground() ? "Allowed" : "Allow"));
        items.add(UItem.asButton(ID_SETTINGS, "Health Connect settings"));
        if (ok) {
            items.add(UItem.asShadow(null));
            items.add(UItem.asHeader("Today"));
            Health.Day today = Health.day(LocalDate.now());
            items.add(UItem.asButton(10, "👟 Steps", today != null && today.steps != null ? Auto.num(today.steps) : "—"));
            items.add(UItem.asButton(11, "😴 Sleep", today != null && today.sleep != null ? hm(today.sleep.start) + " → " + hm(today.sleep.end) + " · " + Auto.duration(today.sleep.minutes()) : "—"));
            items.add(UItem.asButton(12, "🏋️ Workout", today != null && today.exercise != null ? Auto.duration(today.exercise) : "—"));
            if (Health.getLastSync() > 0) items.add(UItem.asShadow("Updated: " + hm(Health.getLastSync())));
        }
        items.add(UItem.asShadow("Mi Fitness: Profile → Health Connect → enable steps, sleep and workouts."));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        try {
            if (item.id == ID_CONNECT || item.id == ID_BACKGROUND) Health.request(getParentActivity(), REQ_HEALTH);
            else if (item.id == ID_SETTINGS) getParentActivity().startActivity(Health.settingsIntent());
            else if (item.id == ID_INSTALL) getParentActivity().startActivity(Health.installIntent());
        } catch (Exception e) {
            HabitUi.toast(this, "Could not open Health Connect");
        }
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_HEALTH && getContext() != null) Health.refresh(getContext(), true);
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_HEALTH && getContext() != null) Health.refresh(getContext(), true);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
