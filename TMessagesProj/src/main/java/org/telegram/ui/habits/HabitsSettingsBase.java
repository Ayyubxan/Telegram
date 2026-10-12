package org.telegram.ui.habits;

import android.content.Context;
import android.view.View;

import org.telegram.ui.Components.UniversalFragment;

/** FORK MOD (Nexo): settings page in the current Telegram style — rounded islands and an adaptive action bar */
public abstract class HabitsSettingsBase extends UniversalFragment {
    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        listView.adapter.setApplyBackground(false);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        return v;
    }
}
