package org.telegram.ui.habits;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.nexo.Nexo;
import org.telegram.messenger.nexo.NexoPrefs;
import org.telegram.messenger.nexo.PrayerTimes;
import org.telegram.messenger.nexo.Repeat;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;

/** FORK MOD (Nexo): prayer times — city (or phone location) and school. */
public class PrayerSettingsActivity extends HabitsSettingsBase {
    private static final int ID_CITY = 1, ID_SCHOOL = 2, ID_BACKGROUND = 3;
    private static final int REQ_LOCATION = 51;
    private final Runnable listener = () -> { if (listView != null && listView.adapter != null) listView.adapter.update(true); };

    @Override
    public void onResume() {
        super.onResume();
        PrayerTimes.addListener(listener);
        listener.run();
    }

    @Override
    public void onPause() {
        super.onPause();
        PrayerTimes.removeListener(listener);
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.HabitsPrayerTimes);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        NexoPrefs prefs = Nexo.prefs();
        items.add(UItem.asHeader("Place and method"));
        items.add(UItem.asButton(ID_CITY, "City", PrayerTimes.city().name));
        items.add(UItem.asButton(ID_SCHOOL, "School", PrayerTimes.schoolName(prefs.getPrayerSchool())));
        if (PrayerTimes.isAuto() && Build.VERSION.SDK_INT >= 29 && getContext() != null && !PrayerTimes.canBackground(getContext())) {
            items.add(UItem.asButton(ID_BACKGROUND, "Update while the app is closed", "Allow all the time").accent());
        }
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Today"));
        LocalDate today = LocalDate.now();
        boolean all = true;
        ArrayList<UItem> rows = new ArrayList<>();
        int i = 100;
        for (Map.Entry<String, String> e : PrayerTimes.NAMES.entrySet()) {
            Integer m = getContext() == null ? null : PrayerTimes.minute(getContext(), today, e.getKey());
            if (m == null) { all = false; break; }
            rows.add(UItem.asButton(i++, e.getValue(), Repeat.hhmm(m)));
        }
        if (all) items.addAll(rows);
        else items.add(UItem.asButton(99, PrayerTimes.lastError != null ? "⚠️ " + PrayerTimes.lastError : "Loading the schedule…"));
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        NexoPrefs prefs = Nexo.prefs();
        if (item.id == ID_CITY) {
            pickCity();
        } else if (item.id == ID_SCHOOL) {
            CharSequence[] names = new CharSequence[PrayerTimes.SCHOOL_NAMES.length];
            System.arraycopy(PrayerTimes.SCHOOL_NAMES, 0, names, 0, names.length);
            showDialog(new AlertDialog.Builder(getContext()).setTitle("School").setItems(names, (d, i) -> {
                prefs.setPrayerSchool(PrayerTimes.SCHOOL_IDS[i]);
                PrayerTimes.reset(getContext());
                listView.adapter.update(true);
            }).create());
        } else if (item.id == ID_BACKGROUND) {
            if (getParentActivity() != null && Build.VERSION.SDK_INT >= 29) getParentActivity().requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_LOCATION);
        }
    }

    private void pickCity() {
        NexoPrefs prefs = Nexo.prefs();
        CharSequence[] items = new CharSequence[PrayerTimes.CITIES.size() + 1];
        items[0] = "📍 My location (also while travelling)";
        for (int i = 0; i < PrayerTimes.CITIES.size(); i++) items[i + 1] = PrayerTimes.CITIES.get(i).name;
        showDialog(new AlertDialog.Builder(getContext()).setTitle("City").setItems(items, (d, i) -> {
            if (i == 0) {
                prefs.setPrayerCity(PrayerTimes.AUTO);
                if (getParentActivity() != null && getParentActivity().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    PrayerTimes.refreshLocation(getContext());
                } else if (getParentActivity() != null) {
                    getParentActivity().requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
                }
            } else {
                prefs.setPrayerCity(PrayerTimes.CITIES.get(i - 1).name);
                PrayerTimes.reset(getContext());
            }
            listView.adapter.update(true);
        }).create());
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_LOCATION && getContext() != null) PrayerTimes.refreshLocation(getContext());
        if (listView != null && listView.adapter != null) listView.adapter.update(true);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
