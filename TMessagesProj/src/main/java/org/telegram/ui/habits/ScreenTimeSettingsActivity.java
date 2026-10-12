package org.telegram.ui.habits;

import android.content.Intent;
import android.provider.Settings;
import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.nexo.ClipAccessibilityService;
import org.telegram.messenger.nexo.ScreenTime;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

/** FORK MOD (Nexo): screen-time permissions for app / sleep habits and full-screen blocking. */
public class ScreenTimeSettingsActivity extends HabitsSettingsBase {
    private static final int ID_USAGE = 1, ID_A11Y = 2;

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.adapter != null) listView.adapter.update(true);
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.HabitsScreenTime);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean usage = getContext() != null && ScreenTime.hasAccess(getContext());
        boolean a11y = getContext() != null && ClipAccessibilityService.isEnabled(getContext());
        items.add(UItem.asHeader("Permissions"));
        items.add(UItem.asButton(ID_USAGE, "Usage access", usage ? "Granted" : "Grant"));
        items.add(UItem.asButton(ID_A11Y, "Full-screen block (Accessibility)", a11y ? "Enabled" : "Enable"));
        items.add(UItem.asShadow("Usage access — app and sleep habits. Accessibility — full-screen blocking. Usage data never leaves the phone."));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (getContext() == null) return;
        try {
            if (item.id == ID_USAGE) ScreenTime.openAccessSettings(getContext());
            else if (item.id == ID_A11Y) getParentActivity().startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            HabitUi.toast(this, "Settings screen not found");
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
