package org.telegram.ui.finance;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.finance.Finance;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SectionsScrollView;
import org.telegram.ui.habits.HabitUi;

/** FORK MOD (Finance): a stacked island-style page that rebuilds itself when finance data changes */
public abstract class FinancePage extends BaseFragment {
    protected FrameLayout root;
    protected SectionsScrollView scroll;
    protected LinearLayout content;
    protected boolean closing, pendingRefresh;
    /** Rebuilding the island content while the navigation layout animates a transition crashed on some devices (alpha dispatch
     *  over a child list being replaced) — so refreshes are deferred until the animation ends. */
    protected final Runnable listener = () -> {
        if (closing || fragmentView == null || content == null) return;
        if (parentLayout != null && parentLayout.isTransitionAnimationInProgress()) { pendingRefresh = true; return; }
        refresh();
    };

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (pendingRefresh && !closing && fragmentView != null && content != null) {
            pendingRefresh = false;
            refresh();
        }
    }

    @Override
    public void finishFragment() {
        closing = true;
        super.finishFragment();
    }

    @Override
    public void removeSelfFromStack() {
        closing = true;
        super.removeSelfFromStack();
    }

    protected abstract CharSequence title();

    protected void buildMenu(ActionBarMenu menu) {}

    protected void onMenuClick(int id) {}

    protected abstract void refresh();

    protected int bottomPaddingDp() {
        return 48;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(title());
        buildMenu(actionBar.createMenu());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else onMenuClick(id);
            }
        });
        root = new FrameLayout(context);
        root.setBackgroundColor(HabitUi.bg());
        LinearLayout[] c = new LinearLayout[1];
        scroll = HabitUi.page(context, c);
        content = c[0];
        content.setPadding(dp(12), dp(12), dp(12), dp(bottomPaddingDp()));
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(scroll);
        fragmentView = root;
        refresh();
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        Finance.addListener(listener);
        if (content != null) refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        Finance.removeListener(listener);
    }
}
