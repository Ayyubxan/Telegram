package org.telegram.ui.habits;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EmojiView;
import org.telegram.ui.Components.LayoutHelper;

import java.text.BreakIterator;

/** FORK MOD (Nexo): pick one emoji with Telegram's own emoji keyboard ({@link EmojiView}) in a bottom sheet. */
public class EmojiPickerSheet {
    public interface OnPick {
        void onPick(String emoji);
    }

    public static void show(BaseFragment f, String current, OnPick onPick) {
        Context ctx = f.getContext();
        if (ctx == null) return;
        if (f.getParentActivity() != null && f.getParentActivity().getCurrentFocus() != null) {
            AndroidUtilities.hideKeyboard(f.getParentActivity().getCurrentFocus());
        }
        final BottomSheet[] sheet = new BottomSheet[1];
        FrameLayout container = new FrameLayout(ctx);
        container.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        EmojiView emojiView = new EmojiView(f, false, false, false, ctx, true, null, container, true, f.getResourceProvider(), false);
        emojiView.setDelegate(new EmojiView.EmojiViewDelegate() {
            @Override
            public void onEmojiSelected(String emoji) {
                String e = firstGrapheme(emoji == null ? "" : emoji.trim());
                if (!e.isEmpty()) onPick.onPick(e);
                if (sheet[0] != null) sheet[0].dismiss();
            }

            @Override
            public boolean onBackspace() {
                return false;
            }
        });
        int height = Math.max(dp(320), Math.min(dp(480), AndroidUtilities.displaySize.y / 2));
        container.addView(emojiView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0, Gravity.FILL));
        emojiView.getLayoutParams().height = height;
        BottomSheet.Builder b = new BottomSheet.Builder(ctx, false, f.getResourceProvider());
        b.setApplyTopPadding(false);
        b.setApplyBottomPadding(false);
        b.setCustomView(container);
        sheet[0] = b.create();
        f.showDialog(sheet[0]);
        emojiView.onOpen(true, false);
    }

    /** "👨‍👩‍👧 Family" → "👨‍👩‍👧" (with ZWJ and skin-tone modifiers) */
    public static String firstGrapheme(String s) {
        if (s.isEmpty()) return s;
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(s);
        int end = it.next();
        return s.substring(0, Math.max(1, end));
    }

    /** Round emoji button that opens the picker; the chosen emoji is reported through {@code onPick} */
    public static TextView button(BaseFragment f, String initial, int size, OnPick onPick) {
        TextView t = HabitUi.text(f.getContext(), initial, size >= 56 ? 26 : 22, HabitUi.text());
        t.setGravity(Gravity.CENTER);
        t.setBackground(HabitUi.oval(HabitUi.bg()));
        t.setOnClickListener(v -> show(f, t.getText().toString(), e -> {
            t.setText(e);
            onPick.onPick(e);
        }));
        return t;
    }
}
