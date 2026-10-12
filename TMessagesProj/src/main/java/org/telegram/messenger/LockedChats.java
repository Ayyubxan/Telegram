package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

// FORK MOD: "Locked chats" — hide selected chats from the dialog list and suppress their
// notifications/calls while the lock is engaged. The set of locked dialog ids is persisted;
// the "hidden" (locked) state is runtime-only and defaults to true on each app start so the
// chats re-lock for privacy whenever the app is reopened.
public class LockedChats {

    private static final Set<Long> locked = new HashSet<>();
    private static boolean loaded;
    private static boolean hidden = true; // true = locked chats are currently hidden/suppressed

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("fork_locked_chats", Context.MODE_PRIVATE);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Set<String> stored = prefs().getStringSet("ids", null);
            if (stored != null) {
                for (String s : stored) {
                    try {
                        locked.add(Long.parseLong(s));
                    } catch (Exception ignore) {
                    }
                }
            }
        } catch (Exception ignore) {
        }
    }

    private static void save() {
        Set<String> out = new HashSet<>();
        for (Long id : locked) {
            out.add(String.valueOf(id));
        }
        prefs().edit().putStringSet("ids", out).apply();
    }

    public static boolean isLocked(long dialogId) {
        ensureLoaded();
        return locked.contains(dialogId);
    }

    public static boolean isEmpty() {
        ensureLoaded();
        return locked.isEmpty();
    }

    /** All locked dialog ids (for the settings list and backups) */
    public static java.util.List<Long> all() {
        ensureLoaded();
        return new java.util.ArrayList<>(locked);
    }

    public static void setLocked(long dialogId, boolean value) {
        ensureLoaded();
        if (value) {
            locked.add(dialogId);
        } else {
            locked.remove(dialogId);
        }
        save();
    }

    public static void toggleLocked(long dialogId) {
        setLocked(dialogId, !isLocked(dialogId));
    }

    public static boolean isHidden() {
        return hidden;
    }

    public static void setHidden(boolean value) {
        hidden = value;
    }

    public static void toggleHidden() {
        hidden = !hidden;
    }

    // A locked chat is suppressed (hidden from list, no notifications, no calls) only while the
    // lock is engaged.
    public static boolean isSuppressed(long dialogId) {
        return hidden && isLocked(dialogId);
    }

    // Same check for the TL objects that search / hints / share lists carry around.
    public static boolean isSuppressedObject(Object obj) {
        if (!hidden || obj == null) {
            return false;
        }
        if (obj instanceof TLRPC.User) {
            return isLocked(((TLRPC.User) obj).id);
        } else if (obj instanceof TLRPC.Chat) {
            return isLocked(-((TLRPC.Chat) obj).id);
        } else if (obj instanceof TLRPC.Dialog) {
            return isLocked(((TLRPC.Dialog) obj).id);
        } else if (obj instanceof TLRPC.TL_topPeer) {
            return isSuppressedPeer(((TLRPC.TL_topPeer) obj).peer);
        } else if (obj instanceof TLRPC.Peer) {
            return isSuppressedPeer((TLRPC.Peer) obj);
        }
        return false;
    }

    public static boolean isSuppressedPeer(TLRPC.Peer peer) {
        if (!hidden || peer == null) {
            return false;
        }
        if (peer.user_id != 0) {
            return isLocked(peer.user_id);
        } else if (peer.channel_id != 0) {
            return isLocked(-peer.channel_id);
        } else if (peer.chat_id != 0) {
            return isLocked(-peer.chat_id);
        }
        return false;
    }
}
