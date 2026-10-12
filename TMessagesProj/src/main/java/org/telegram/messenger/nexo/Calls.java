package org.telegram.messenger.nexo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CallLog;

import androidx.core.content.ContextCompat;

/** FORK MOD (Nexo): call log — did we talk to the contact for at least {@code minSec} seconds between from and to */
public class Calls {
    public static boolean hasPermission(Context ctx) {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean talked(Context ctx, String phone, long from, long to, int minSec) {
        if (!hasPermission(ctx)) return false;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls.NUMBER, CallLog.Calls.DURATION},
                CallLog.Calls.DATE + " >= ? AND " + CallLog.Calls.DATE + " < ? AND " + CallLog.Calls.DURATION + " >= ?",
                new String[]{String.valueOf(from), String.valueOf(to), String.valueOf(Math.max(1, minSec))}, null);
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
            return false;
        }
        if (c == null) return false;
        try {
            while (c.moveToNext()) {
                String n = c.getString(0);
                if (AutoRules.samePhone(n == null ? "" : n, phone)) return true;
            }
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        } finally {
            c.close();
        }
        return false;
    }
}
