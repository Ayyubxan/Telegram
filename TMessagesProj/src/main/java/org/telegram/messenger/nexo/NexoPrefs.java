package org.telegram.messenger.nexo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** FORK MOD (Nexo): settings of the Mac-sync module and the fork backup (SharedPreferences "nexo"). */
public class NexoPrefs {
    private final SharedPreferences p;
    public final String deviceId;

    public NexoPrefs(Context context) {
        p = context.getApplicationContext().getSharedPreferences("nexo", Context.MODE_PRIVATE);
        String id = p.getString("deviceId", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            p.edit().putString("deviceId", id).apply();
        }
        deviceId = id;
    }

    public String getKeyB64() { return p.getString("key", null); }
    public void setKeyB64(String v) { p.edit().putString("key", v).apply(); }

    public String getMacName() { return p.getString("macName", null); }
    public void setMacName(String v) { p.edit().putString("macName", v).apply(); }

    /** "ip:port" list — fallback when Bonjour does not work */
    public List<String> getLanHints() {
        ArrayList<String> out = new ArrayList<>();
        for (String s : p.getString("lanHints", "").split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    public void setLanHints(List<String> v) {
        ArrayList<String> distinct = new ArrayList<>();
        for (String s : v) if (!distinct.contains(s)) distinct.add(s);
        while (distinct.size() > 6) distinct.remove(distinct.size() - 1);
        p.edit().putString("lanHints", join(distinct)).apply();
    }

    public void addLanHint(String hint) {
        ArrayList<String> list = new ArrayList<>();
        list.add(hint);
        for (String s : getLanHints()) if (!s.equals(hint)) list.add(s);
        setLanHints(list);
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) { if (sb.length() > 0) sb.append(','); sb.append(s); }
        return sb.toString();
    }

    public boolean isSyncEnabled() { return p.getBoolean("syncEnabled", true); }
    public void setSyncEnabled(boolean v) { p.edit().putBoolean("syncEnabled", v).apply(); }

    public boolean isLanEnabled() { return p.getBoolean("lanEnabled", true); }
    public void setLanEnabled(boolean v) { p.edit().putBoolean("lanEnabled", v).apply(); }

    public boolean isBleEnabled() { return p.getBoolean("bleEnabled", true); }
    public void setBleEnabled(boolean v) { p.edit().putBoolean("bleEnabled", v).apply(); }

    public boolean isInternetEnabled() { return p.getBoolean("internetEnabled", true); }
    public void setInternetEnabled(boolean v) { p.edit().putBoolean("internetEnabled", v).apply(); }

    /** Also sync clipboard images */
    public boolean isImagesEnabled() { return p.getBoolean("imagesEnabled", true); }
    public void setImagesEnabled(boolean v) { p.edit().putBoolean("imagesEnabled", v).apply(); }


    /** Last manual/auto backup time (ms), 0 — never */
    public long getLastBackupAt() { return p.getLong("lastBackupAt", 0); }
    public void setLastBackupAt(long v) { p.edit().putLong("lastBackupAt", v).apply(); }


    /** Mac sync: send every copied text automatically (accessibility service) */
    public boolean isAutoModeEnabled() { return p.getBoolean("autoMode", true); }
    public void setAutoModeEnabled(boolean v) { p.edit().putBoolean("autoMode", v).apply(); }

    public String getRelayServer() { String s = p.getString("relayServer", null); return s == null ? "https://ntfy.sh" : s; }
    public void setRelayServer(String v) { p.edit().putString("relayServer", v).apply(); }

    public String relayLastId(String topic) { return p.getString("relayLastId." + topic, null); }
    public void setRelayLastId(String topic, String id) { p.edit().putString("relayLastId." + topic, id).apply(); }

    public Map<String, String> getKnownPeers() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(p.getString("knownPeers", "{}"));
            Iterator<String> it = o.keys();
            while (it.hasNext()) { String k = it.next(); out.put(k, o.getString(k)); }
        } catch (Exception ignore) {}
        return out;
    }

    public void setKnownPeers(Map<String, String> v) {
        p.edit().putString("knownPeers", new JSONObject(v).toString()).apply();
    }

    // region Telegram backup

    /** Chat the daily backup file is sent to (0 — not set) */
    public long getBackupDialogId() { return p.getLong("backupDialogId", 0); }
    public void setBackupDialogId(long v) { p.edit().putLong("backupDialogId", v).apply(); }

    /** Backup time: minutes from midnight (default 03:00) */
    public int getBackupMinutes() { return p.getInt("backupMinutes", 3 * 60); }
    public void setBackupMinutes(int v) { p.edit().putInt("backupMinutes", v).apply(); }

    /** Backup encryption passphrase ("" — plain JSON). Kept in app-private prefs; excluded from exportJson. */
    public String getBackupPassphrase() { return p.getString("backupPassphrase", ""); }
    public void setBackupPassphrase(String s) { p.edit().putString("backupPassphrase", s == null ? "" : s).apply(); }
    public boolean isBackupEncrypted() { return !getBackupPassphrase().isEmpty(); }

    public long getLastTelegramBackupAt() { return p.getLong("lastTelegramBackupAt", 0); }
    public void setLastTelegramBackupAt(long v) { p.edit().putLong("lastTelegramBackupAt", v).apply(); }

    /** All settings as JSON (device id and relay cursors are per device and are left out) */
    public JSONObject exportJson() {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            String k = e.getKey();
            if ("deviceId".equals(k) || k.startsWith("relayLastId.") || "lastTelegramBackupAt".equals(k) || "lastBackupAt".equals(k) || "backupPassphrase".equals(k)) continue;
            try { o.put(k, e.getValue()); } catch (Exception ignore) {}
        }
        return o;
    }

    public void importJson(JSONObject o) {
        SharedPreferences.Editor ed = p.edit();
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            if ("deviceId".equals(k)) continue;
            Object v = o.opt(k);
            if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) ed.putInt(k, (Integer) v);
            else if (v instanceof Long) ed.putLong(k, (Long) v);
            else if (v instanceof Double) ed.putFloat(k, ((Double) v).floatValue());
            else if (v instanceof String) ed.putString(k, (String) v);
        }
        ed.apply();
    }

    // endregion
}
