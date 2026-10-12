package org.telegram.messenger.nexo;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;

import org.telegram.messenger.R;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** FORK MOD (Nexo): home screen widget — every habit is marked with one tap (empty → ✓ → ✕ → empty). */
public class HabitWidget extends AppWidgetProvider {
    private static final int MAX_ITEMS = 8;

    public static void refresh(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            if (mgr == null) return;
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, HabitWidget.class));
            if (ids != null && ids.length > 0) update(ctx, mgr, ids);
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }

    /** Minute of the first pending time slot (or the first time for single-time habits), null if none */
    private static Integer time(HabitStore store, Habit h, LocalDate today) {
        for (String t : h.repeat.times) {
            if (!h.repeat.slotted() || store.slotStatus(h.id, today, t) == Status.NONE) return Repeat.resolve(t, today);
        }
        return null;
    }

    private static void update(Context ctx, AppWidgetManager mgr, int[] ids) {
        final HabitStore store = Nexo.store();
        final LocalDate today = HabitStore.today();
        // Like the home page: unfinished first, the nearest time on top; untimed dailies, then optional ones
        List<Habit> due = store.dueOn(today);
        ArrayList<Habit> habits = new ArrayList<>(due);
        for (Habit h : store.active()) if (!h.daily() && !h.once()) habits.add(h);
        Collections.sort(habits, (x, y) -> {
            int mx = store.status(x.id, today) != Status.NONE ? 1 : 0;
            int my = store.status(y.id, today) != Status.NONE ? 1 : 0;
            if (mx != my) return Integer.compare(mx, my);
            Integer tx = time(store, x, today), ty = time(store, y, today);
            int gx = !x.daily() ? 2 : tx != null ? 0 : 1;
            int gy = !y.daily() ? 2 : ty != null ? 0 : 1;
            if (gx != gy) return Integer.compare(gx, gy);
            return Integer.compare(tx == null ? Integer.MAX_VALUE : tx, ty == null ? Integer.MAX_VALUE : ty);
        });
        if (habits.size() > MAX_ITEMS) habits = new ArrayList<>(habits.subList(0, MAX_ITEMS));
        int done = 0;
        for (Habit h : due) if (store.status(h.id, today) == Status.DONE) done++;

        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.habits_widget);
        views.setTextViewText(R.id.habits_widget_progress, due.isEmpty() ? "" : done + " / " + due.size());
        views.setTextViewText(R.id.habits_widget_title, !due.isEmpty() && done == due.size() ? "Habits 🎉" : "Habits");
        views.setOnClickPendingIntent(R.id.habits_widget_header, Nexo.openHabitsPending(ctx));
        views.removeAllViews(R.id.habits_widget_items);
        views.setViewVisibility(R.id.habits_widget_empty, habits.isEmpty() ? View.VISIBLE : View.GONE);
        for (Habit h : habits) {
            RemoteViews item = new RemoteViews(ctx.getPackageName(), R.layout.habits_widget_item);
            int s = store.status(h.id, today);
            item.setTextViewText(R.id.habits_widget_emoji, h.emoji);
            item.setTextViewText(R.id.habits_widget_name, h.name);
            int streak = h.daily() ? store.streak(h.id) : 0;
            item.setTextViewText(R.id.habits_widget_streak, streak > 0 ? "🔥" + streak : "");
            int[] p = store.progress(h.id, today);
            String state;
            if (s == Status.DONE) state = "✓";
            else if (s == Status.SKIPPED) state = "✕";
            else if (h.repeat.slotted() && p[0] > 0) state = p[0] + "/" + p[1]; // timed habit (pills): how many taken
            else state = "";
            item.setTextViewText(R.id.habits_widget_state, state);
            int bg;
            if (s == Status.DONE) bg = R.drawable.habits_widget_state_done;
            else if (s == Status.SKIPPED) bg = R.drawable.habits_widget_state_skip;
            else bg = R.drawable.habits_widget_state_none;
            item.setInt(R.id.habits_widget_state, "setBackgroundResource", bg);
            Intent tap = new Intent(ctx, HabitReceiver.class)
                .setAction(HabitReceiver.ACTION_TOGGLE)
                .setData(Uri.parse("nexo-habit://toggle/" + h.id))
                .putExtra(HabitReceiver.EXTRA_ID, h.id);
            item.setOnClickPendingIntent(R.id.habits_widget_item, PendingIntent.getBroadcast(
                ctx, h.id.hashCode(), tap, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            views.addView(R.id.habits_widget_items, item);
        }
        mgr.updateAppWidget(ids, views);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        try {
            update(context, appWidgetManager, appWidgetIds);
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }
}
