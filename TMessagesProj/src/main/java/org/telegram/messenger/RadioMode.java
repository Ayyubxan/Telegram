package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

// FORK MOD (radio mode): PER-CHAT. For each chat where it's enabled, every incoming voice
// message auto-plays (at max volume) and outgoing messages are sent silently. The set of
// enabled chats is persisted, so it survives app restarts.
public class RadioMode {

    private static final Set<Long> chats = new HashSet<>();
    private static boolean loaded;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("fork_radio_mode", Context.MODE_PRIVATE);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Set<String> stored = prefs().getStringSet("chats", null);
            if (stored != null) {
                for (String s : stored) {
                    try {
                        chats.add(Long.parseLong(s));
                    } catch (Exception ignore) {
                    }
                }
            }
        } catch (Exception ignore) {
        }
    }

    private static void save() {
        Set<String> out = new HashSet<>();
        for (Long id : chats) {
            out.add(String.valueOf(id));
        }
        prefs().edit().putStringSet("chats", out).apply();
    }

    public static boolean isEnabled(long dialogId) {
        ensureLoaded();
        return chats.contains(dialogId);
    }

    public static void setEnabled(long dialogId, boolean value) {
        ensureLoaded();
        if (value) {
            chats.add(dialogId);
        } else {
            chats.remove(dialogId);
        }
        save();
    }

    public static void toggle(long dialogId) {
        setEnabled(dialogId, !isEnabled(dialogId));
    }
}
