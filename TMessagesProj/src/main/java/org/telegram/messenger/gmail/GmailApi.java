package org.telegram.messenger.gmail;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** FORK MOD (gmail): thin Gmail REST client (users/me), plain HttpURLConnection + org.json. */
public final class GmailApi {

    private static final String BASE = "https://gmail.googleapis.com/gmail/v1/users/me/";
    private static final String UPLOAD = "https://gmail.googleapis.com/upload/gmail/v1/users/me/";

    public static final class HttpError extends IOException {
        public final int code;
        public final String body;

        HttpError(int code, String body) {
            super("HTTP " + code + (body == null ? "" : ": " + shortBody(body)));
            this.code = code;
            this.body = body;
        }

        private static String shortBody(String s) {
            s = s.replace('\n', ' ');
            return s.length() > 300 ? s.substring(0, 300) + "…" : s;
        }
    }

    private GmailApi() {}

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static byte[] raw(String method, String url, byte[] body, String contentType, String token) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "TelegramFork-Gmail/1.0");
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", contentType);
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] data = readAll(in);
            if (code >= 400) {
                throw new HttpError(code, new String(data, StandardCharsets.UTF_8));
            }
            return data;
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = i.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        }
    }

    private static JSONObject json(String method, String url, JSONObject body, String token) throws IOException {
        byte[] data = raw(method, url, body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8", token);
        if (data.length == 0) {
            return new JSONObject();
        }
        try {
            return new JSONObject(new String(data, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("Bad JSON from Gmail", e);
        }
    }

    public static JSONObject profile(String token) throws IOException {
        return json("GET", BASE + "profile", null, token);
    }

    /** messages.list — ids only */
    public static JSONObject listMessages(String token, String query, int maxResults, String pageToken) throws IOException {
        StringBuilder u = new StringBuilder(BASE).append("messages?maxResults=").append(maxResults);
        if (query != null) {
            u.append("&q=").append(enc(query));
        }
        if (pageToken != null) {
            u.append("&pageToken=").append(enc(pageToken));
        }
        return json("GET", u.toString(), null, token);
    }

    /** messages.get; format = full | metadata | minimal | raw */
    public static JSONObject getMessage(String token, String id, String format) throws IOException {
        return json("GET", BASE + "messages/" + enc(id) + "?format=" + format, null, token);
    }

    public static byte[] getAttachment(String token, String messageId, String attachmentId) throws IOException {
        JSONObject o = json("GET", BASE + "messages/" + enc(messageId) + "/attachments/" + enc(attachmentId), null, token);
        return Base64.decode(o.optString("data", ""), Base64.URL_SAFE);
    }

    /** history.list restricted to one label and message additions; the caller pages with nextPageToken */
    public static JSONObject history(String token, String startHistoryId, String labelId, String pageToken) throws IOException {
        StringBuilder u = new StringBuilder(BASE).append("history?startHistoryId=").append(enc(startHistoryId))
                .append("&historyTypes=messageAdded&historyTypes=labelAdded&historyTypes=labelRemoved&maxResults=500");
        if (labelId != null) {
            u.append("&labelId=").append(enc(labelId));
        }
        if (pageToken != null) {
            u.append("&pageToken=").append(enc(pageToken));
        }
        return json("GET", u.toString(), null, token);
    }

    public static JSONObject modify(String token, String id, List<String> add, List<String> remove) throws IOException {
        JSONObject body = new JSONObject();
        try {
            if (add != null && !add.isEmpty()) {
                body.put("addLabelIds", new JSONArray(add));
            }
            if (remove != null && !remove.isEmpty()) {
                body.put("removeLabelIds", new JSONArray(remove));
            }
        } catch (JSONException ignore) {}
        return json("POST", BASE + "messages/" + enc(id) + "/modify", body, token);
    }

    /** messages.batchModify (up to 1000 ids) */
    public static void batchModify(String token, List<String> ids, List<String> add, List<String> remove) throws IOException {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("ids", new JSONArray(ids));
            if (add != null && !add.isEmpty()) {
                body.put("addLabelIds", new JSONArray(add));
            }
            if (remove != null && !remove.isEmpty()) {
                body.put("removeLabelIds", new JSONArray(remove));
            }
        } catch (JSONException ignore) {}
        json("POST", BASE + "messages/batchModify", body, token);
    }

    public static JSONObject trash(String token, String id) throws IOException {
        return json("POST", BASE + "messages/" + enc(id) + "/trash", null, token);
    }

    /** messages.send with a complete RFC 822 message (media upload, so large attachments are fine) */
    public static JSONObject send(String token, byte[] rfc822, String threadId) throws IOException {
        String url = UPLOAD + "messages/send?uploadType=media";
        // threadId travels in the metadata part of a multipart upload; the media upload has no body for it, so use
        // the simple upload and, when there is a thread, the JSON endpoint with the raw field instead.
        if (threadId == null) {
            byte[] data = raw("POST", url, rfc822, "message/rfc822", token);
            try {
                return new JSONObject(new String(data, StandardCharsets.UTF_8));
            } catch (JSONException e) {
                throw new IOException("Bad JSON from Gmail", e);
            }
        }
        JSONObject body = new JSONObject();
        try {
            body.put("raw", Base64.encodeToString(rfc822, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
            body.put("threadId", threadId);
        } catch (JSONException ignore) {}
        return json("POST", BASE + "messages/send", body, token);
    }

    static void log(String s) {
        FileLog.d("[gmail] " + s);
    }
}
