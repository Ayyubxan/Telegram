package org.telegram.ui.fork;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import org.telegram.messenger.finance.Finance;
import org.telegram.messenger.nexo.Nexo;
import org.telegram.ui.LaunchActivity;

/**
 * FORK MOD: target of the extra launcher icons (Habits, Finance). Runs in its own throw-away task and starts
 * {@link LaunchActivity} with the tab action, so the real task always receives a fresh intent (a dead process
 * re-created from recents would otherwise get the task's old root intent).
 */
public class TabLaunchActivity extends Activity {
    public static final String EXTRA_NONCE = "fork_nonce";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String cls = getIntent() != null && getIntent().getComponent() != null ? getIntent().getComponent().getClassName() : "";
        String action = cls.endsWith(".FinanceIcon") ? Finance.ACTION_OPEN_FINANCE : Nexo.ACTION_OPEN_HABITS;
        Intent i = new Intent(this, LaunchActivity.class)
            .setAction(action)
            .putExtra(EXTRA_NONCE, System.currentTimeMillis())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
        overridePendingTransition(0, 0);
    }
}
