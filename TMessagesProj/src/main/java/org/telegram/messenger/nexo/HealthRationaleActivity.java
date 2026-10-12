package org.telegram.messenger.nexo;

import android.app.Activity;
import android.os.Bundle;

/** FORK MOD (Nexo): Health Connect "privacy policy" link — opens the Habits health settings page */
public class HealthRationaleActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            startActivity(Nexo.openIntent(this, Nexo.ACTION_OPEN_HABITS_HEALTH));
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
        finish();
    }
}
