package org.telegram.messenger.nexo;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Pair;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/**
 * FORK MOD (Nexo): auto mode: notices "Copy" being tapped on the phone and opens the invisible window for a moment
 * to send the clipboard to the Mac. No text or screen content is stored anywhere — it only watches for the
 * "Copy" button being pressed and the system clipboard overlay appearing.
 */
public class ClipAccessibilityService extends AccessibilityService {
    private static final String[] COPY_WORDS = {
        "copy", "копировать", "скопировать", "nusxa", "nusxalash", "kopyala", "kopieren", "copiar", "copier",
        "复制", "複製", "コピー", "복사",
    };

    public static boolean isEnabled(Context ctx) {
        String enabled = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(ctx, ClipAccessibilityService.class);
        for (String s : enabled.split(":")) {
            if (me.equals(ComponentName.unflattenFromString(s))) return true;
        }
        return false;
    }

    private static boolean looksLikeCopy(CharSequence s) {
        if (s == null) return false;
        String t = s.toString().trim().toLowerCase(Locale.ROOT);
        if (t.length() > 24) return false;
        for (String w : COPY_WORDS) if (t.startsWith(w)) return true;
        return false;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastTrigger = 0L;
    private final Runnable launch = this::launchReader;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();
        if (pkg.equals(getPackageName())) return;
        boolean copy = false;
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_CLICKED: {
                copy = looksLikeCopy(event.getContentDescription());
                if (!copy) {
                    List<CharSequence> texts = event.getText();
                    if (texts != null) {
                        for (CharSequence t : texts) {
                            if (looksLikeCopy(t)) {
                                copy = true;
                                break;
                            }
                        }
                    }
                }
                break;
            }
            // Android 13+: the system shows the clipboard overlay at the bottom on every copy
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
            case AccessibilityEvent.TYPE_WINDOWS_CHANGED:
                copy = "com.android.systemui".equals(pkg) && isClipboardOverlay(event);
                break;
            default:
                break;
        }
        if (BuildVars.LOGS_ENABLED && copy) FileLog.d("NexoA11y: copy detected in " + pkg);
        if (!copy) return;
        // Give the app a moment to write to the clipboard
        main.removeCallbacks(launch);
        main.postDelayed(launch, 250);
    }

    /** Is the SystemUI window the clipboard overlay — only view IDs are inspected (not text). */
    private boolean isClipboardOverlay(AccessibilityEvent event) {
        CharSequence cls = event.getClassName();
        if (cls != null && cls.toString().toLowerCase(Locale.ROOT).contains("clipboard")) return true;
        AccessibilityNodeInfo root;
        try {
            root = event.getSource();
        } catch (Exception e) {
            root = null;
        }
        if (root == null) return false;
        ArrayDeque<Pair<AccessibilityNodeInfo, Integer>> queue = new ArrayDeque<>();
        queue.add(new Pair<>(root, 0));
        while (!queue.isEmpty()) {
            Pair<AccessibilityNodeInfo, Integer> e = queue.removeFirst();
            AccessibilityNodeInfo node = e.first;
            int depth = e.second;
            String id = node.getViewIdResourceName();
            if (id != null && id.toLowerCase(Locale.ROOT).contains("clipboard")) return true;
            if (depth < 3) {
                int n = Math.min(node.getChildCount(), 12);
                for (int i = 0; i < n; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.add(new Pair<>(child, depth + 1));
                }
            }
        }
        return false;
    }

    private void launchReader() {
        SyncEngine engine = Nexo.engine();
        if (!engine.isPaired() || !engine.prefs.isSyncEnabled() || !engine.prefs.isAutoModeEnabled()) return;
        long now = SystemClock.elapsedRealtime();
        // Ignore the clipboard overlay that appears when we ourselves write the text that came from the Mac
        if (now - engine.getLastAppliedAt() < 2000 || now - lastTrigger < 700) return;
        lastTrigger = now;
        try {
            startActivity(
                new Intent(this, SendClipboardActivity.class)
                    .putExtra(SendClipboardActivity.EXTRA_AUTO, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            );
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onInterrupt() {}
}
