package org.telegram.messenger.gmail;

import android.graphics.Typeface;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.QuoteSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FORK MOD (gmail): turns a Gmail API message (format=full) into Telegram text + entities, and builds the RFC 822
 * message for a reply. HTML goes through {@link Html#fromHtml} and its spans become Telegram entities; quoted
 * previous messages, signatures' tracking pixels and the like are cut off so a mail reads like a chat message.
 */
public final class GmailMime {

    /** Longest Telegram message; emails above it are split */
    public static final int MAX_TEXT = 4000;

    public static final class Attachment {
        public String filename, mime, attachmentId, contentId;
        public long size;
        public boolean inline;
    }

    public static final class Parsed {
        public String id, threadId, historyId;
        public long internalDate;
        public final Set<String> labels = new HashSet<>();
        public String fromName = "", fromEmail = "", to = "", subject = "", messageId = "", references = "", inReplyTo = "";
        public String text, html;
        public final List<Attachment> attachments = new ArrayList<>();
    }

    /** One Telegram message worth of text */
    public static final class Body {
        public String text;
        public ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
    }

    public static final class OutAttachment {
        public final String filename, mime;
        public final byte[] data;

        public OutAttachment(String filename, String mime, byte[] data) {
            this.filename = filename;
            this.mime = mime;
            this.data = data;
        }
    }

    private GmailMime() {}

    // ---- parsing --------------------------------------------------------------------------------------------------

    public static Parsed parse(JSONObject msg) {
        Parsed p = new Parsed();
        p.id = msg.optString("id");
        p.threadId = msg.optString("threadId");
        p.historyId = msg.optString("historyId");
        p.internalDate = msg.optLong("internalDate");
        JSONArray labels = msg.optJSONArray("labelIds");
        if (labels != null) {
            for (int i = 0; i < labels.length(); i++) {
                p.labels.add(labels.optString(i));
            }
        }
        JSONObject payload = msg.optJSONObject("payload");
        if (payload == null) {
            return p;
        }
        JSONArray headers = payload.optJSONArray("headers");
        if (headers != null) {
            for (int i = 0; i < headers.length(); i++) {
                JSONObject h = headers.optJSONObject(i);
                if (h == null) {
                    continue;
                }
                String name = h.optString("name").toLowerCase(Locale.US);
                String value = decodeHeader(h.optString("value"));
                switch (name) {
                    case "from":
                        p.fromName = addressName(value);
                        p.fromEmail = addressEmail(value);
                        break;
                    case "to":
                        p.to = value;
                        break;
                    case "subject":
                        p.subject = value.trim();
                        break;
                    case "message-id":
                        p.messageId = value.trim();
                        break;
                    case "references":
                        p.references = value.trim();
                        break;
                    case "in-reply-to":
                        p.inReplyTo = value.trim();
                        break;
                }
            }
        }
        walk(payload, p);
        return p;
    }

    private static void walk(JSONObject part, Parsed p) {
        String mime = part.optString("mimeType", "").toLowerCase(Locale.US);
        String filename = part.optString("filename", "");
        JSONObject body = part.optJSONObject("body");
        JSONArray parts = part.optJSONArray("parts");
        String contentId = null, disposition = null, charset = null;
        JSONArray headers = part.optJSONArray("headers");
        if (headers != null) {
            for (int i = 0; i < headers.length(); i++) {
                JSONObject h = headers.optJSONObject(i);
                if (h == null) {
                    continue;
                }
                String n = h.optString("name").toLowerCase(Locale.US);
                if (n.equals("content-id")) {
                    contentId = h.optString("value").replace("<", "").replace(">", "").trim();
                } else if (n.equals("content-disposition")) {
                    disposition = h.optString("value").toLowerCase(Locale.US);
                } else if (n.equals("content-type")) {
                    Matcher m = Pattern.compile("charset=\"?([A-Za-z0-9._-]+)\"?", Pattern.CASE_INSENSITIVE).matcher(h.optString("value"));
                    if (m.find()) {
                        charset = m.group(1);
                    }
                }
            }
        }
        if (body != null && body.has("attachmentId") || !filename.isEmpty() && body != null && body.optInt("size") > 0) {
            Attachment a = new Attachment();
            a.filename = filename.isEmpty() ? (contentId != null ? contentId : "attachment") : filename;
            a.mime = mime.isEmpty() ? "application/octet-stream" : mime;
            a.attachmentId = body.optString("attachmentId", null);
            a.size = body.optLong("size");
            a.contentId = contentId;
            a.inline = disposition != null && disposition.startsWith("inline") || contentId != null && filename.isEmpty();
            p.attachments.add(a);
            return;
        }
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject c = parts.optJSONObject(i);
                if (c != null) {
                    walk(c, p);
                }
            }
            return;
        }
        if (body == null) {
            return;
        }
        String data = body.optString("data", null);
        if (data == null) {
            return;
        }
        String decoded = decodeBody(data, charset);
        if (mime.equals("text/plain")) {
            if (p.text == null) {
                p.text = decoded;
            }
        } else if (mime.equals("text/html")) {
            if (p.html == null) {
                p.html = decoded;
            }
        }
    }

    private static String decodeBody(String data, String charset) {
        byte[] bytes = Base64.decode(data, Base64.URL_SAFE);
        Charset cs = StandardCharsets.UTF_8;
        if (charset != null) {
            try {
                cs = Charset.forName(charset);
            } catch (Exception ignore) {}
        }
        return new String(bytes, cs);
    }

    /** RFC 2047 encoded words in headers (=?UTF-8?B?...?=) */
    public static String decodeHeader(String value) {
        if (value == null || !value.contains("=?")) {
            return value == null ? "" : value;
        }
        Matcher m = Pattern.compile("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=").matcher(value);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String cs = m.group(1), enc = m.group(2), payload = m.group(3);
            String out;
            try {
                Charset charset = Charset.forName(cs);
                if (enc.equalsIgnoreCase("B")) {
                    out = new String(Base64.decode(payload, Base64.DEFAULT), charset);
                } else {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    for (int i = 0; i < payload.length(); i++) {
                        char c = payload.charAt(i);
                        if (c == '_') {
                            bo.write(' ');
                        } else if (c == '=' && i + 2 < payload.length()) {
                            bo.write(Integer.parseInt(payload.substring(i + 1, i + 3), 16));
                            i += 2;
                        } else {
                            bo.write(c);
                        }
                    }
                    out = new String(bo.toByteArray(), charset);
                }
            } catch (Exception e) {
                out = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(out));
        }
        m.appendTail(sb);
        // encoded words separated by whitespace are one string
        return sb.toString().replaceAll("(?<=[^\\s])\\s+(?=\\S)", " ");
    }

    public static String addressEmail(String header) {
        if (header == null) {
            return "";
        }
        Matcher m = Pattern.compile("<([^>]+)>").matcher(header);
        if (m.find()) {
            return GmailIds.normalize(m.group(1));
        }
        return GmailIds.normalize(header.replace("\"", ""));
    }

    public static String addressName(String header) {
        if (header == null) {
            return "";
        }
        int lt = header.indexOf('<');
        String name = lt > 0 ? header.substring(0, lt) : "";
        name = name.trim();
        if (name.startsWith("\"") && name.endsWith("\"") && name.length() >= 2) {
            name = name.substring(1, name.length() - 1);
        }
        name = name.replace("\\\"", "\"").trim();
        if (name.isEmpty()) {
            String email = addressEmail(header);
            int at = email.indexOf('@');
            name = at > 0 ? email.substring(0, at) : email;
        }
        return name;
    }

    /** First address of a To: header, or "" */
    public static String firstAddress(String header) {
        if (TextUtils.isEmpty(header)) {
            return "";
        }
        String first = header.split(",")[0];
        return addressEmail(first);
    }

    // ---- email → Telegram text ------------------------------------------------------------------------------------

    /** The email as one or more Telegram messages: subject in bold on top, then the body without quoted history */
    public static List<Body> toTelegram(Parsed p) {
        SpannableStringBuilder body;
        if (!TextUtils.isEmpty(p.html)) {
            body = fromHtml(p.html);
            if (body.length() == 0 && !TextUtils.isEmpty(p.text)) {
                body = new SpannableStringBuilder(stripQuotedPlain(p.text));
            }
        } else {
            body = new SpannableStringBuilder(stripQuotedPlain(p.text == null ? "" : p.text));
        }
        tidy(body);

        SpannableStringBuilder all = new SpannableStringBuilder();
        String subject = p.subject == null ? "" : p.subject.trim();
        if (!subject.isEmpty()) {
            all.append(subject);
            all.setSpan(new StyleSpan(Typeface.BOLD), 0, subject.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (body.length() > 0) {
                all.append("\n\n");
            }
        }
        all.append(body);
        if (all.length() == 0) {
            all.append("(empty message)");
        }
        return split(all);
    }

    private static final String[] QUOTE_CUTS = {
            "class=\"gmail_quote",            // Gmail
            "class=\"gmail_quote_container",  // newer Gmail
            "id=\"appendonsend\"",            // Outlook web
            "class=\"protonmail_quote",       // Proton
            "class=\"yahoo_quoted",           // Yahoo
            "<blockquote type=\"cite\"",      // Apple Mail
            "class=\"moz-cite-prefix\"",      // Thunderbird
    };

    static SpannableStringBuilder fromHtml(String html) {
        String h = html;
        h = h.replaceAll("(?is)<(script|style|head|title)[^>]*>.*?</\\1>", "");
        h = h.replaceAll("(?is)<!--.*?-->", "");
        int cut = -1;
        for (String marker : QUOTE_CUTS) {
            int i = h.indexOf(marker);
            if (i >= 0) {
                int tagStart = h.lastIndexOf('<', i);
                int at = tagStart >= 0 ? tagStart : i;
                if (cut < 0 || at < cut) {
                    cut = at;
                }
            }
        }
        if (cut > 0) {
            h = h.substring(0, cut);
        }
        // block boundaries that fromHtml would otherwise glue together
        h = h.replaceAll("(?i)<br\\s*/?>", "\n");
        h = h.replaceAll("(?i)</(p|div|tr|li|h[1-6]|blockquote|pre|table)>", "</$1>\n");
        h = h.replaceAll("(?i)<li[^>]*>", "• ");
        h = h.replaceAll("(?i)<(td|th)[^>]*>", " ");
        h = h.replaceAll("(?i)<img[^>]*>", "");
        Spanned sp;
        try {
            sp = Html.fromHtml(h, Html.FROM_HTML_MODE_COMPACT);
        } catch (Throwable e) {
            sp = new SpannableStringBuilder(h.replaceAll("<[^>]+>", ""));
        }
        return new SpannableStringBuilder(sp);
    }

    private static final Pattern QUOTE_HEADER = Pattern.compile("^(On .{3,200} wrote:|-{2,} ?(Original|Forwarded) Message ?-{2,}|From: .+|Le .{3,200} a écrit ?:|Am .{3,200} schrieb .+:|\\d{1,2}[./]\\d{1,2}[./]\\d{2,4}.{0,80}<.+@.+>:?)\\s*$");

    static String stripQuotedPlain(String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith(">")) {
                continue;
            }
            if (QUOTE_HEADER.matcher(t).matches()) {
                break;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /** nbsp → space, 3+ blank lines → 1, trailing spaces, trimmed — edits keep spans aligned */
    static void tidy(SpannableStringBuilder s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '​' || c == '﻿') {
                s.replace(i, i + 1, c == ' ' ? " " : "");
                if (c != ' ') {
                    i--;
                }
            }
        }
        // trailing whitespace on each line
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                int j = i;
                while (j > 0 && (s.charAt(j - 1) == ' ' || s.charAt(j - 1) == '\t')) {
                    j--;
                }
                if (j < i) {
                    s.delete(j, i);
                    i = j;
                }
            }
        }
        int run = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                run++;
                if (run > 2) {
                    s.delete(i, i + 1);
                    i--;
                }
            } else {
                run = 0;
            }
        }
        while (s.length() > 0 && Character.isWhitespace(s.charAt(0))) {
            s.delete(0, 1);
        }
        while (s.length() > 0 && Character.isWhitespace(s.charAt(s.length() - 1))) {
            s.delete(s.length() - 1, s.length());
        }
    }

    private static List<Body> split(SpannableStringBuilder all) {
        ArrayList<Body> out = new ArrayList<>();
        int start = 0;
        while (start < all.length()) {
            int end = Math.min(all.length(), start + MAX_TEXT);
            if (end < all.length()) {
                int nl = TextUtils.lastIndexOf(all, '\n', start + MAX_TEXT / 2, end);
                if (nl > start) {
                    end = nl + 1;
                }
            }
            Body b = new Body();
            b.text = all.subSequence(start, end).toString();
            b.entities = entities(all, start, end);
            out.add(b);
            start = end;
        }
        return out;
    }

    static ArrayList<TLRPC.MessageEntity> entities(Spanned s, int from, int to) {
        ArrayList<TLRPC.MessageEntity> out = new ArrayList<>();
        Object[] spans = s.getSpans(from, to, Object.class);
        for (Object span : spans) {
            int a = Math.max(from, s.getSpanStart(span)), b = Math.min(to, s.getSpanEnd(span));
            if (b <= a) {
                continue;
            }
            TLRPC.MessageEntity e = null, e2 = null;
            if (span instanceof StyleSpan) {
                int st = ((StyleSpan) span).getStyle();
                if ((st & Typeface.BOLD) != 0) {
                    e = new TLRPC.TL_messageEntityBold();
                }
                if ((st & Typeface.ITALIC) != 0) {
                    e2 = new TLRPC.TL_messageEntityItalic();
                }
            } else if (span instanceof URLSpan) {
                String url = ((URLSpan) span).getURL();
                if (url == null || url.startsWith("mailto:") || url.startsWith("cid:") || url.startsWith("#")) {
                    continue;
                }
                String shown = s.subSequence(a, b).toString().trim();
                if (shown.equals(url) || ("http://" + shown).equals(url) || ("https://" + shown).equals(url)) {
                    e = new TLRPC.TL_messageEntityUrl();
                } else {
                    TLRPC.TL_messageEntityTextUrl t = new TLRPC.TL_messageEntityTextUrl();
                    t.url = url;
                    e = t;
                }
            } else if (span instanceof UnderlineSpan) {
                e = new TLRPC.TL_messageEntityUnderline();
            } else if (span instanceof StrikethroughSpan) {
                e = new TLRPC.TL_messageEntityStrike();
            } else if (span instanceof TypefaceSpan && "monospace".equals(((TypefaceSpan) span).getFamily())) {
                e = new TLRPC.TL_messageEntityCode();
            } else if (span instanceof QuoteSpan) {
                e = new TLRPC.TL_messageEntityBlockquote();
            }
            if (e != null) {
                e.offset = a - from;
                e.length = b - a;
                out.add(e);
            }
            if (e2 != null) {
                e2.offset = a - from;
                e2.length = b - a;
                out.add(e2);
            }
        }
        return out;
    }

    // ---- Telegram text → email ------------------------------------------------------------------------------------

    public static String replySubject(String subject) {
        String s = subject == null ? "" : subject.trim();
        if (s.isEmpty()) {
            return "";
        }
        return s.regionMatches(true, 0, "re:", 0, 3) ? s : "Re: " + s;
    }

    public static String newMessageId() {
        return "<" + UUID.randomUUID() + "@telegram.fork>";
    }

    /** A complete RFC 822 message, ready for messages.send */
    public static byte[] build(String from, String to, String subject, String messageId, String inReplyTo, String references, String text, List<OutAttachment> files) {
        StringBuilder h = new StringBuilder();
        h.append("From: ").append(from).append("\r\n");
        h.append("To: ").append(to).append("\r\n");
        h.append("Subject: ").append(encodeHeader(subject)).append("\r\n");
        h.append("Message-ID: ").append(messageId).append("\r\n");
        if (!TextUtils.isEmpty(inReplyTo)) {
            h.append("In-Reply-To: ").append(inReplyTo).append("\r\n");
        }
        if (!TextUtils.isEmpty(references)) {
            h.append("References: ").append(references).append("\r\n");
        }
        SimpleDateFormat df = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US);
        df.setTimeZone(TimeZone.getDefault());
        h.append("Date: ").append(df.format(new Date())).append("\r\n");
        h.append("MIME-Version: 1.0\r\n");
        String textPart = "Content-Type: text/plain; charset=\"UTF-8\"\r\nContent-Transfer-Encoding: base64\r\n\r\n" + base64Lines(text.getBytes(StandardCharsets.UTF_8));
        if (files == null || files.isEmpty()) {
            h.append(textPart);
            return h.toString().getBytes(StandardCharsets.UTF_8);
        }
        String boundary = "fork_" + UUID.randomUUID().toString().replace("-", "");
        h.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n\r\n");
        h.append("--").append(boundary).append("\r\n").append(textPart).append("\r\n");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            bo.write(h.toString().getBytes(StandardCharsets.UTF_8));
            for (OutAttachment f : files) {
                String name = encodeHeader(f.filename);
                String part = "--" + boundary + "\r\nContent-Type: " + f.mime + "; name=\"" + name + "\"\r\nContent-Disposition: attachment; filename=\"" + name + "\"\r\nContent-Transfer-Encoding: base64\r\n\r\n" + base64Lines(f.data) + "\r\n";
                bo.write(part.getBytes(StandardCharsets.UTF_8));
            }
            bo.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignore) {}
        return bo.toByteArray();
    }

    private static String base64Lines(byte[] data) {
        return Base64.encodeToString(data, Base64.CRLF);
    }

    static String encodeHeader(String s) {
        if (s == null) {
            return "";
        }
        boolean ascii = true;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 126 || s.charAt(i) < 32) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            return s;
        }
        return "=?UTF-8?B?" + Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP) + "?=";
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024f);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }
}
