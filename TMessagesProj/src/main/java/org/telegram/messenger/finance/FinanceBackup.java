package org.telegram.messenger.finance;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;

/** FORK MOD (Finance): the finance part of the fork backup — every table, merged back "newest row wins" */
public class FinanceBackup {
    public static JSONObject export() {
        JSONObject o = new JSONObject();
        try {
            o.put("base", Finance.base());
            for (String t : FinanceDb.TABLES) o.put(t, Finance.db().dump(t));
        } catch (Exception e) {
            FileLog.e(e);
        }
        return o;
    }

    /** Returns {transactions, accounts} counts in the backup, or null */
    public static int[] summary(JSONObject o) {
        if (o == null || !o.has("tx")) return null;
        int tx = 0, acc = 0;
        org.json.JSONArray a = o.optJSONArray("tx");
        if (a != null) for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null && a.optJSONObject(i).optInt("del") == 0) tx++;
        a = o.optJSONArray("account");
        if (a != null) for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null && a.optJSONObject(i).optInt("del") == 0) acc++;
        return new int[]{tx, acc};
    }

    public static boolean restore(JSONObject o) {
        if (o == null) return false;
        try {
            for (String t : FinanceDb.TABLES) Finance.db().merge(t, o.optJSONArray(t));
            String base = o.optString("base", null);
            if (base != null && !base.isEmpty()) Finance.prefs().edit().putString("base", base).apply();
            Finance.notifyChanged();
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }
}
