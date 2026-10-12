package org.telegram.ui.habits;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.nexo.HabitReminder;
import org.telegram.messenger.nexo.Nexo;
import org.telegram.messenger.nexo.NexoPrefs;
import org.telegram.messenger.nexo.Repeat;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

/** FORK MOD (Nexo): evening reminder for unmarked habits. */
public class HabitReminderActivity extends HabitsSettingsBase {
    private static final int ID_ENABLED = 1, ID_TIME = 2;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.HabitsEveningReminder);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        NexoPrefs prefs = Nexo.prefs();
        items.add(UItem.asCheck(ID_ENABLED, LocaleController.getString(R.string.HabitsEveningReminder)).setChecked(prefs.isHabitReminder()));
        if (prefs.isHabitReminder()) items.add(UItem.asButton(ID_TIME, "Time", Repeat.hhmm(prefs.getHabitReminderMinutes())));
        items.add(UItem.asShadow("Reminds about habits not marked today. Timed habits are reminded at their time."));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        NexoPrefs prefs = Nexo.prefs();
        if (item.id == ID_ENABLED) {
            boolean on = !prefs.isHabitReminder();
            prefs.setHabitReminder(on);
            HabitReminder.schedule(getContext());
            if (on && Build.VERSION.SDK_INT >= 33 && getParentActivity() != null
                && getParentActivity().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                getParentActivity().requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
            listView.adapter.update(true);
        } else if (item.id == ID_TIME) {
            showDialog(AlertsCreator.createTimePickerDialog(getContext(), "Time", prefs.getHabitReminderMinutes(), 0, 24 * 60, minutes -> {
                prefs.setHabitReminderMinutes(minutes);
                HabitReminder.schedule(getContext());
                listView.adapter.update(true);
            }));
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
