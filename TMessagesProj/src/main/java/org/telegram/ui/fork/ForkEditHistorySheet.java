package org.telegram.ui.fork;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.habits.HabitUi;

import java.util.ArrayList;

/** FORK MOD (edit history): all versions of an edited message in a sheet — current first, then older ones; tap a version to copy it */
public class ForkEditHistorySheet {
    public static void show(BaseFragment f, MessageObject msg, Theme.ResourcesProvider rp) {
        if (f == null || f.getContext() == null || msg == null || msg.messageOwner == null) return;
        Context ctx = f.getContext();
        ArrayList<String> texts = msg.messageOwner.forkEditHistory == null ? new ArrayList<>() : msg.messageOwner.forkEditHistory;
        ArrayList<Integer> dates = msg.messageOwner.forkEditHistoryDates == null ? new ArrayList<>() : msg.messageOwner.forkEditHistoryDates;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(16));
        TextView title = HabitUi.text(ctx, "Edit history", 20, Theme.getColor(Theme.key_dialogTextBlack, rp), true);
        box.addView(title);
        TextView sub = HabitUi.text(ctx, texts.size() + (texts.size() == 1 ? " previous version" : " previous versions") + " · tap to copy", 13, Theme.getColor(Theme.key_dialogTextGray2, rp));
        sub.setPadding(0, dp(2), 0, dp(12));
        box.addView(sub);

        BottomSheet[] sheet = new BottomSheet[1];
        // current version first
        String current = msg.messageOwner.message == null ? "" : msg.messageOwner.message;
        int curDate = msg.messageOwner.edit_date != 0 ? msg.messageOwner.edit_date : msg.messageOwner.date;
        box.addView(version(ctx, rp, "Current · " + LocaleController.getInstance().getFormatterDayMonth().format(curDate * 1000L) + " " + LocaleController.getInstance().getFormatterDay().format(curDate * 1000L), current, true, sheet, f));
        for (int i = texts.size() - 1; i >= 0; i--) {
            String t = texts.get(i) == null ? "" : texts.get(i);
            int d = i < dates.size() ? dates.get(i) : 0;
            String when = d == 0 ? "Earlier" : LocaleController.getInstance().getFormatterDayMonth().format(d * 1000L) + " " + LocaleController.getInstance().getFormatterDay().format(d * 1000L);
            box.addView(version(ctx, rp, "Version " + (i + 1) + " · until " + when, t, false, sheet, f));
        }
        ScrollView sv = new ScrollView(ctx);
        sv.addView(box, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        BottomSheet.Builder b = new BottomSheet.Builder(ctx, false, rp);
        b.setCustomView(sv);
        sheet[0] = b.create();
        f.showDialog(sheet[0]);
    }

    private static View version(Context ctx, Theme.ResourcesProvider rp, String label, String text, boolean current, BottomSheet[] sheet, BaseFragment f) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(10), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        l.setLayoutParams(lp);
        int bg = Theme.getColor(current ? Theme.key_chat_outBubble : Theme.key_chat_inBubble, rp);
        l.setBackground(HabitUi.roundRect(bg, HabitUi.dpf(14)));
        TextView lab = HabitUi.text(ctx, label, 12, Theme.getColor(current ? Theme.key_chat_outTimeText : Theme.key_chat_inTimeText, rp), true);
        l.addView(lab);
        TextView body = HabitUi.text(ctx, text.isEmpty() ? "(empty)" : text, 15, Theme.getColor(current ? Theme.key_chat_messageTextOut : Theme.key_chat_messageTextIn, rp));
        body.setPadding(0, dp(4), 0, 0);
        body.setTextIsSelectable(false);
        l.addView(body);
        l.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(text);
            if (sheet[0] != null) sheet[0].dismiss();
            BulletinFactory.of(f).createCopyBulletin(LocaleController.getString(org.telegram.messenger.R.string.TextCopied)).show();
        });
        return l;
    }
}
