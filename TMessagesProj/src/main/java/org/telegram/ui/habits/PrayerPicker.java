package org.telegram.ui.habits;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.nexo.PrayerTimes;
import org.telegram.messenger.nexo.Repeat;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * FORK MOD (Nexo): bind a habit time to prayers: the prayers (several — each marked separately) and
 * "on time / N min before / after". Result — times like "@fajr+10".
 */
public class PrayerPicker {
    public interface OnPick {
        void onPick(List<String> times);
    }

    public static void show(BaseFragment f, OnPick onPick) {
        Context ctx = f.getContext();
        LinkedHashSet<String> chosen = new LinkedHashSet<>();
        final int[] offset = {0};
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        String city = PrayerTimes.city().name;
        LocalDate today = LocalDate.now();
        HabitUi.FlowLayout list = new HabitUi.FlowLayout(ctx, dp(8));
        Runnable draw = new Runnable() {
            @Override
            public void run() {
                list.removeAllViews();
                for (Map.Entry<String, String> e : PrayerTimes.NAMES.entrySet()) {
                    final String k = e.getKey();
                    boolean on = chosen.contains(k);
                    Integer m = PrayerTimes.minute(ctx, today, k);
                    list.addView(HabitUi.chip(ctx, e.getValue() + (m != null ? " " + Repeat.hhmm(m) : ""), on, () -> {
                        if (on) chosen.remove(k); else chosen.add(k);
                        run();
                    }));
                }
            }
        };
        draw.run();
        TextView hint = HabitUi.text(ctx, "Today, " + city + " (change in Settings). Several prayers — each is marked separately.", 13, HabitUi.sub());
        hint.setPadding(0, 0, 0, dp(10));
        box.addView(hint);
        box.addView(list);

        TextView offsetText = HabitUi.text(ctx, "", 16, HabitUi.text());
        offsetText.setGravity(Gravity.CENTER);
        offsetText.setMinWidth(dp(150));
        Runnable drawOffset = () -> offsetText.setText(offset[0] == 0 ? "on time" : offset[0] < 0 ? (-offset[0]) + " min before" : offset[0] + " min after");
        drawOffset.run();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(16), 0, 0);
        row.addView(btn(ctx, "−", -5, offset, drawOffset), new LinearLayout.LayoutParams(dp(40), dp(40)));
        row.addView(offsetText);
        row.addView(btn(ctx, "+", 5, offset, drawOffset), new LinearLayout.LayoutParams(dp(40), dp(40)));
        box.addView(row);

        // Schedule may still be loading — redraw when the times arrive
        Runnable loaded = draw;
        PrayerTimes.addListener(loaded);
        f.showDialog(new AlertDialog.Builder(ctx)
            .setTitle("🕌 Bind to prayer")
            .setView(box)
            .setPositiveButton(LocaleController.getString(R.string.Add), (d, w) -> {
                ArrayList<String> out = new ArrayList<>();
                for (String k : chosen) out.add(Repeat.prayer(k, offset[0]));
                onPick.onPick(out);
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setOnDismissListener(d -> PrayerTimes.removeListener(loaded))
            .create());
    }

    private static TextView btn(Context ctx, String label, int d, int[] offset, Runnable drawOffset) {
        TextView t = HabitUi.text(ctx, label, 20, HabitUi.text());
        t.setGravity(Gravity.CENTER);
        t.setBackground(HabitUi.oval(0, HabitUi.line(), dp(2)));
        t.setOnClickListener(v -> {
            HabitUi.haptic(v);
            offset[0] = Math.max(-120, Math.min(120, offset[0] + d));
            drawOffset.run();
        });
        return t;
    }
}
