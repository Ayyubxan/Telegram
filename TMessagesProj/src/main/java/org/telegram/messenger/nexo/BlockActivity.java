package org.telegram.messenger.nexo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;

import java.time.LocalDate;

/**
 * FORK MOD (Nexo): full-screen block. "Go home" is the main way out. "Open anyway" needs a 3-second hold
 * (for emergencies), allows the app for the day and counts as ✕.
 */
@SuppressLint({"SetTextI18n", "ClickableViewAccessibility"})
public class BlockActivity extends Activity {
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_PKG = "pkg";
    private static final long HOLD_MS = 3000L;
    private static final int BG = 0xFF0B0D10;
    private static final String ANYWAY = "Open anyway (hold for 3 seconds)";

    private long holdStart;
    private TextView anyway;
    private Habit habit;
    private String blockedPkg;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long left = HOLD_MS - (System.currentTimeMillis() - holdStart);
            if (left <= 0) {
                openAnyway();
            } else {
                anyway.setText("Hold on… " + ((left + 999) / 1000));
                anyway.postDelayed(this, 100);
            }
        }
    };

    private TextView text(String s, float size, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(size);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER);
        if (bold) tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        return tv;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HabitStore store = Nexo.store();
        String id = getIntent().getStringExtra(EXTRA_ID);
        final Habit h = id == null ? null : store.habit(id);
        final Auto a = h == null ? null : h.auto;
        if (a == null) {
            finish();
            return;
        }
        habit = h;
        int streak = store.streak(h.id);
        // Blocked app: for the total limit — the one opened now
        String pkg = getIntent().getStringExtra(EXTRA_PKG);
        if (pkg == null) pkg = a.pkg;
        blockedPkg = pkg;
        String appName;
        if (a.isTotal()) {
            try {
                PackageManager pm = getPackageManager();
                appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Exception e) {
                appName = pkg;
            }
        } else {
            appName = a.app;
        }
        int used = (a.isTotal() ? ScreenTime.total(this, LocalDate.now()) : ScreenTime.usage(this, a.pkg, LocalDate.now())).minutes;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(BG);
        root.setPadding(AndroidUtilities.dp(32), AndroidUtilities.dp(32), AndroidUtilities.dp(32), AndroidUtilities.dp(32));

        root.addView(text(Auto.AVOID.equals(a.mode) ? "🚫" : "⏳", 64f, 0xFFFFFFFF, false));
        TextView title = text(appName, 28f, 0xFFFFFFFF, true);
        title.setPadding(0, AndroidUtilities.dp(16), 0, AndroidUtilities.dp(8));
        root.addView(title);
        String message;
        if (Auto.AVOID.equals(a.mode) && a.hasWindow()) {
            message = "You agreed not to open this app between " + a.from + " and " + a.to + ".";
        } else if (Auto.AVOID.equals(a.mode)) {
            message = "You agreed not to open this app today.";
        } else if (a.isTotal()) {
            message = "Today's total screen time is over: " + Auto.duration(used) + " / " + Auto.duration(a.minutes);
        } else {
            message = "Today's limit is over: " + Auto.duration(used) + " / " + Auto.duration(a.minutes);
        }
        root.addView(text(message, 17f, 0xCCFFFFFF, false));
        if (streak > 0) {
            TextView s = text("🔥 " + h.name + ": " + streak + " " + h.repeat.units(streak) + " in a row. Don't break the chain!", 16f, 0xFFF97316, true);
            s.setPadding(0, AndroidUtilities.dp(24), 0, 0);
            root.addView(s);
        }
        TextView home = text("Go home", 18f, BG, true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF22C55E);
        bg.setCornerRadius(AndroidUtilities.dp(28));
        home.setBackground(bg);
        home.setPadding(AndroidUtilities.dp(32), AndroidUtilities.dp(16), AndroidUtilities.dp(32), AndroidUtilities.dp(16));
        home.setOnClickListener(v -> goHome());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = AndroidUtilities.dp(48);
        root.addView(home, lp);

        anyway = text(ANYWAY, 14f, 0x88FFFFFF, false);
        anyway.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(20), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        anyway.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    holdStart = System.currentTimeMillis();
                    v.post(tick);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.removeCallbacks(tick);
                    anyway.setText(ANYWAY);
                    break;
            }
            return true;
        });
        // "Open anyway" disabled — there is no way out
        if (a.escape) root.addView(anyway);
        setContentView(root);
        // Dark background — light status bar icons
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(0);
        }
    }

    /** Another app got blocked — the screen with the new data */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        recreate();
    }

    private void goHome() {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
        finish();
    }

    /** Emergency: allowed for today, the day is ✕ (the agreement is broken) */
    private void openAnyway() {
        Habit h = habit;
        Auto a = h == null ? null : h.auto;
        if (a == null) {
            finish();
            return;
        }
        AppGuard.allow(blockedPkg != null ? blockedPkg : a.pkg);
        HabitStore store = Nexo.store();
        LocalDate today = LocalDate.now();
        if (store.status(h.id, today) == Status.NONE) store.setStatus(h.id, today, Status.SKIPPED);
        finish();
    }

    @Override
    public void onBackPressed() {
        goHome();
    }
}
