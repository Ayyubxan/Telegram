package org.telegram.messenger.gmail;

import java.util.Locale;

/**
 * FORK MOD (gmail): id space of the synthetic "sender" dialogs.
 * <p>
 * Every email sender is a fake {@code TLRPC.User} whose id lives in [2^56, 2^57): positive (so every user-dialog
 * code path applies), far above any real Telegram id, and below the encrypted (bit 62) and folder (bit 61) markers
 * that {@code DialogObject} checks. The emails themselves are ordinary {@code messages_v2} rows of that dialog with
 * ids counted up from {@link #MID_BASE}: positive, so reply / forward / copy work like for a real message, and above
 * any real message id this account will ever see.
 */
public final class GmailIds {
    public static final long BASE = 1L << 56;
    private static final long LIMIT = 1L << 57;
    private static final long MASK = (1L << 55) - 1;
    /** First local message id; ids grow by one per email */
    public static final int MID_BASE = 2_000_000_000;

    private GmailIds() {}

    public static boolean isGmailDialog(long dialogId) {
        return dialogId >= BASE && dialogId < LIMIT;
    }

    public static boolean isGmailMessageId(int mid) {
        return mid >= MID_BASE;
    }

    /** Stable dialog id of a sender address (case-insensitive) */
    public static long senderId(String email) {
        String key = normalize(email);
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < key.length(); i++) {
            h ^= key.charAt(i);
            h *= 0x100000001b3L;
        }
        long id = BASE + (h & MASK);
        if (id == BASE) {
            id++;
        }
        return id;
    }

    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.US);
    }
}
