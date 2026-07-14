package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

// FORK MOD (love effect): remembers which "Sevaman" messages have already triggered the
// celebration so each one fires exactly once, ever (persisted across opens and restarts).
public class LoveEffect {

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("fork_love_effect", Context.MODE_PRIVATE);
    }

    private static String key(long dialogId, int messageId) {
        return dialogId + "_" + messageId;
    }

    public static boolean wasShown(long dialogId, int messageId) {
        return prefs().getStringSet("shown", Collections.emptySet()).contains(key(dialogId, messageId));
    }

    public static void markShown(long dialogId, int messageId) {
        Set<String> set = new HashSet<>(prefs().getStringSet("shown", Collections.emptySet()));
        set.add(key(dialogId, messageId));
        prefs().edit().putStringSet("shown", set).apply();
    }
}
