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
        SharedPreferences.Editor ed = null;
        for (String k : DEAD) if (p.contains(k)) { if (ed == null) ed = p.edit(); ed.remove(k); }
        if (ed != null) ed.apply();
    }

    /** Keys of removed features (daily backup, Finance/Habits bridges): dropped on start, never exported or imported */
    private static final String[] DEAD = {"lastTelegramBackupAt", "lastBackupAt", "backupDialogId", "backupMinutes", "financeDialogs"};

    private static boolean local(String k) {
        if ("deviceId".equals(k) || k.startsWith("relayLastId.") || "backupPassphrase".equals(k)) return true;
        for (String d : DEAD) if (d.equals(k)) return true;
        return false;
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




    /** Mac sync: send every copied text automatically (accessibility service) */
    public boolean isAutoModeEnabled() { return p.getBoolean("autoMode", true); }
    public void setAutoModeEnabled(boolean v) { p.edit().putBoolean("autoMode", v).apply(); }

    /** Webhooks: JSON array of hooks (see Webhooks.Hook) */
    public String getWebhooks() { return p.getString("webhooks", "[]"); }
    public void setWebhooks(String json) { p.edit().putString("webhooks", json).apply(); }

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

    // region Fork backup (export / import file)

    /** Backup encryption passphrase ("" — plain JSON). Kept in app-private prefs; excluded from exportJson. */
    public String getBackupPassphrase() { return p.getString("backupPassphrase", ""); }
    public void setBackupPassphrase(String s) { p.edit().putString("backupPassphrase", s == null ? "" : s).apply(); }
    public boolean isBackupEncrypted() { return !getBackupPassphrase().isEmpty(); }


    /** All settings as JSON (device id and relay cursors are per device and are left out) */
    public JSONObject exportJson() {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            String k = e.getKey();
            if (local(k)) continue;
            try { o.put(k, e.getValue()); } catch (Exception ignore) {}
        }
        return o;
    }

    public void importJson(JSONObject o) {
        SharedPreferences.Editor ed = p.edit();
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (local(k)) continue;
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
