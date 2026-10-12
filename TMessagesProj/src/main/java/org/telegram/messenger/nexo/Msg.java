package org.telegram.messenger.nexo;

import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/** FORK MOD (Nexo): the encrypted message. Field names are identical to `Message` on the Mac. */
public class Msg {
    public final String t;
    public final String from;
    public final String name;
    public final String os;
    public final String mid;
    public final long ts;
    public final String text;
    /** File name */
    public final String file;
    public final String mime;
    /** Image ("clip") or file ("file") bytes, base64 in JSON */
    public final byte[] data;

    public Msg(String t, String from, String name, String os, String mid, long ts, String text, String file, String mime, byte[] data) {
        this.t = t;
        this.from = from;
        this.name = name;
        this.os = os;
        this.mid = mid;
        this.ts = ts;
        this.text = text;
        this.file = file;
        this.mime = mime;
        this.data = data;
    }

    public Msg(String t, String from) {
        this(t, from, null, null, null, 0, null, null, null, null);
    }

    public byte[] toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("t", t);
            o.put("from", from);
            if (name != null) o.put("name", name);
            if (os != null) o.put("os", os);
            if (mid != null) o.put("mid", mid);
            if (ts > 0) o.put("ts", ts);
            if (text != null) o.put("text", text);
            if (file != null) o.put("file", file);
            if (mime != null) o.put("mime", mime);
            if (data != null) o.put("data", Base64.encodeToString(data, Base64.NO_WRAP));
            return o.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String opt(JSONObject o, String k) throws Exception {
        return o.has(k) && !o.isNull(k) ? o.getString(k) : null;
    }

    public static Msg parse(byte[] bytes) {
        try {
            JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            String data = opt(o, "data");
            return new Msg(
                o.getString("t"),
                o.getString("from"),
                opt(o, "name"),
                opt(o, "os"),
                opt(o, "mid"),
                o.optLong("ts"),
                opt(o, "text"),
                opt(o, "file"),
                opt(o, "mime"),
                data == null ? null : Base64.decode(data, Base64.DEFAULT)
            );
        } catch (Exception e) {
            return null;
        }
    }
}
