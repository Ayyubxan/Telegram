package org.telegram.messenger.finance;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FORK MOD (Finance): turns "emoji-prefixed line" bot messages (bank bots, business bots) into transaction fields.
 * A message is split into lines; each line's leading emoji / symbol run is its prefix; a {@link LineRule} maps a prefix
 * (optionally with a keyword) to a field. {@link #guess} proposes a mapping for a sample message.
 */
public class BotParser {
    public static final String F_IGNORE = "ignore";
    public static final String F_AMOUNT_IN = "amount_in";
    public static final String F_AMOUNT_OUT = "amount_out";
    public static final String F_AMOUNT = "amount";
    public static final String F_KIND_IN = "kind_in";
    public static final String F_KIND_OUT = "kind_out";
    public static final String F_CARD = "card";
    public static final String F_NOTE = "note";
    public static final String F_DATE = "date";
    public static final String F_BALANCE = "balance";
    public static final String F_TITLE = "title";

    public static final String[] FIELDS = {F_AMOUNT_IN, F_AMOUNT_OUT, F_AMOUNT, F_KIND_IN, F_KIND_OUT, F_CARD, F_NOTE, F_DATE, F_BALANCE, F_TITLE, F_IGNORE};

    public static String fieldTitle(String f) {
        switch (f) {
            case F_AMOUNT_IN: return "Amount · income (＋)";
            case F_AMOUNT_OUT: return "Amount · expense (－)";
            case F_AMOUNT: return "Amount (sign from the type line)";
            case F_KIND_IN: return "Marks income";
            case F_KIND_OUT: return "Marks expense";
            case F_CARD: return "Card / account";
            case F_NOTE: return "Note / merchant";
            case F_DATE: return "Date & time";
            case F_BALANCE: return "Balance after (ignored)";
            case F_TITLE: return "Type of operation (goes to the note)";
            default: return "Ignore";
        }
    }

    /** prefix (leading emoji run) + optional keyword → field */
    public static class LineRule {
        public String prefix = "";
        public String match = "";
        public String field = F_IGNORE;

        public LineRule() {}

        public LineRule(String prefix, String match, String field) {
            this.prefix = prefix; this.match = match == null ? "" : match; this.field = field;
        }

        public boolean matches(String prefix, String text) {
            if (!this.prefix.equals(prefix)) return false;
            return match.isEmpty() || text.toLowerCase(Locale.ROOT).contains(match.toLowerCase(Locale.ROOT));
        }

        public String label() {
            return (prefix.isEmpty() ? "" : prefix + " ") + (match.isEmpty() ? "" : "\"" + match + "\" ");
        }

        public JSONObject toJson() {
            try { return new JSONObject().put("p", prefix).put("m", match).put("f", field); } catch (Exception e) { return new JSONObject(); }
        }

        public static LineRule fromJson(JSONObject o) {
            return new LineRule(o.optString("p"), o.optString("m"), o.optString("f", F_IGNORE));
        }
    }

    /** What a message yielded */
    public static class Parsed {
        /** Tx.INCOME / Tx.EXPENSE / -1 unknown */
        public int kind = -1;
        public double amount = Double.NaN;
        public String currency;
        public String card;
        public String note = "";
        public String title = "";
        /** ms, 0 if none */
        public long ts;
        public double balance = Double.NaN;

        public boolean hasAmount() {
            return !Double.isNaN(amount) && amount > 0;
        }

        public String toJson() {
            try {
                return new JSONObject().put("kind", kind).put("amount", Double.isNaN(amount) ? JSONObject.NULL : amount).put("currency", currency == null ? JSONObject.NULL : currency)
                    .put("card", card == null ? JSONObject.NULL : card).put("note", note).put("title", title).put("ts", ts).put("balance", Double.isNaN(balance) ? JSONObject.NULL : balance).toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        public static Parsed fromJson(String s) {
            Parsed p = new Parsed();
            if (s == null) return p;
            try {
                JSONObject o = new JSONObject(s);
                p.kind = o.optInt("kind", -1); p.amount = o.isNull("amount") ? Double.NaN : o.optDouble("amount"); p.currency = o.isNull("currency") ? null : o.optString("currency");
                p.card = o.isNull("card") ? null : o.optString("card"); p.note = o.optString("note", ""); p.title = o.optString("title", ""); p.ts = o.optLong("ts");
                p.balance = o.isNull("balance") ? Double.NaN : o.optDouble("balance");
            } catch (Exception ignore) {}
            return p;
        }
    }

    /** A line split into its leading symbol run and the text */
    public static class Line {
        public final String prefix, text, raw;

        Line(String prefix, String text, String raw) {
            this.prefix = prefix; this.text = text; this.raw = raw;
        }
    }

    public static List<Line> lines(String message) {
        List<Line> out = new ArrayList<>();
        if (message == null) return out;
        for (String raw : message.split("\\r?\\n")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            int i = 0;
            while (i < t.length()) {
                int cp = t.codePointAt(i);
                if (Character.isLetterOrDigit(cp) || cp == '#' || cp == '-' || cp == '+' || cp == '*' || cp == '−') break;
                if (Character.isWhitespace(cp)) break;
                i += Character.charCount(cp);
            }
            String prefix = t.substring(0, i).trim();
            String text = t.substring(i).trim();
            out.add(new Line(prefix, text, t));
        }
        return out;
    }

    // region Guessing

    private static final String[] IN_WORDS = {"#kirim", "kirim", "to'ldirish", "toldirish", "popolnenie", "perevod na kartu", "postuplenie", "zachislenie", "tushum", "qaytarildi", "bekor qilish", "refund", "credit"};
    private static final String[] OUT_WORDS = {"#chiqim", "chiqim", "to'lov", "tolov", "platezh", "oplata", "pokupka", "spisanie", "xarid", "amaliyot", "withdraw", "debit", "perevod s karty"};

    /** Proposes a mapping for a sample message */
    public static List<LineRule> guess(String sample) {
        List<LineRule> out = new ArrayList<>();
        for (Line l : lines(sample)) {
            String p = l.prefix;
            String low = l.text.toLowerCase(Locale.ROOT);
            String field;
            String match = "";
            if (p.contains("➕") || p.contains("+")) field = F_AMOUNT_IN;
            else if (p.contains("➖") || p.contains("−") || p.contains("-")) field = F_AMOUNT_OUT;
            else if (p.contains("💳")) field = F_CARD;
            else if (p.contains("📍") || p.contains("🏪") || p.contains("🏷")) field = F_NOTE;
            else if (p.contains("🕓") || p.contains("🕘") || p.contains("🕐") || p.contains("📅") || p.contains("⏰") || p.contains("🗓")) field = F_DATE;
            else if (p.contains("🏦")) field = F_BALANCE;
            else if (p.contains("💵") && findAmount(l.text) != null && hasAmountLine(sample, "➕", "➖")) field = F_BALANCE;
            else if (p.contains("🟢")) field = F_KIND_IN;
            else if (p.contains("🔴")) field = F_KIND_OUT;
            else if (containsAny(low, IN_WORDS) && isHashtag(low, IN_WORDS)) { field = F_KIND_IN; match = hashtag(l.text); }
            else if (containsAny(low, OUT_WORDS) && isHashtag(low, OUT_WORDS)) { field = F_KIND_OUT; match = hashtag(l.text); }
            else if (!p.isEmpty() && findAmount(l.text) != null) field = F_AMOUNT;
            else if (!p.isEmpty() && out.isEmpty()) field = F_TITLE;
            else if (findDate(l.text) != null) field = F_DATE;
            else field = F_IGNORE;
            boolean dup = false;
            for (LineRule r : out) if (r.prefix.equals(p) && r.match.equals(match)) dup = true;
            if (!dup) out.add(new LineRule(p, match, field));
        }
        return out;
    }

    private static boolean hasAmountLine(String sample, String... prefixes) {
        for (Line l : lines(sample)) for (String p : prefixes) if (l.prefix.contains(p)) return true;
        return false;
    }

    private static boolean containsAny(String low, String[] words) {
        for (String w : words) if (low.contains(w)) return true;
        return false;
    }

    private static boolean isHashtag(String low, String[] words) {
        return low.startsWith("#");
    }

    private static String hashtag(String text) {
        int sp = text.indexOf(' ');
        return sp < 0 ? text : text.substring(0, sp);
    }

    // endregion

    // region Parsing

    private static final Pattern AMOUNT = Pattern.compile("([-+−]\\s?)?(\\d[\\d\\s\\u202F\\u00A0.,]*\\d|\\d)");
    private static final Pattern CURRENCY = Pattern.compile("\\b([A-Z]{3})\\b");
    private static final Pattern CARD = Pattern.compile("\\*+\\s?(\\d{4})\\b|(\\d{4})\\s*$");
    private static final Pattern DATE = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2,4})");
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})");

    /** First number in the text (grouping spaces / dots / commas understood), or null */
    public static Double findAmount(String text) {
        Matcher m = AMOUNT.matcher(text);
        while (m.find()) {
            String num = m.group(2);
            // skip card numbers like ***7043 and times like 14:45
            int start = m.start(2);
            if (start > 0 && (text.charAt(start - 1) == '*' || text.charAt(start - 1) == ':')) continue;
            if (m.end() < text.length() && text.charAt(m.end()) == ':') continue;
            if (DATE.matcher(num).matches()) continue;
            double v = Money.parse(num.trim());
            if (!Double.isNaN(v)) return v;
        }
        return null;
    }

    public static String findCurrency(String text) {
        String low = text.toLowerCase(Locale.ROOT);
        if (low.contains("so'm") || low.contains("so‘m") || low.contains("сум") || low.contains("so`m") || low.contains(" sum")) return "UZS";
        Matcher m = CURRENCY.matcher(text);
        while (m.find()) {
            String c = m.group(1);
            if (Currencies.isKnown(c)) return c;
        }
        return null;
    }

    public static String findCard(String text) {
        Matcher m = CARD.matcher(text);
        if (m.find()) return m.group(1) != null ? m.group(1) : m.group(2);
        return null;
    }

    /** Date (+ time) in the text as epoch ms, or null */
    public static Long findDate(String text) {
        Matcher d = DATE.matcher(text);
        if (!d.find()) return null;
        try {
            int day = Integer.parseInt(d.group(1)), month = Integer.parseInt(d.group(2));
            int year = Integer.parseInt(d.group(3));
            if (year < 100) year += 2000;
            LocalTime time = LocalTime.NOON;
            Matcher t = TIME.matcher(text);
            if (t.find()) time = LocalTime.of(Math.min(23, Integer.parseInt(t.group(1))), Math.min(59, Integer.parseInt(t.group(2))));
            return LocalDateTime.of(LocalDate.of(year, month, day), time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    public static Parsed parse(String message, List<LineRule> mapping) {
        Parsed p = new Parsed();
        boolean kindIn = false, kindOut = false;
        Double untyped = null;
        int untypedCount = 0;
        for (Line l : lines(message)) {
            String field = F_IGNORE;
            for (LineRule r : mapping) if (r.matches(l.prefix, l.text)) { field = r.field; break; }
            switch (field) {
                case F_AMOUNT_IN: case F_AMOUNT_OUT: case F_AMOUNT: {
                    Double a = findAmount(l.text);
                    if (a != null) {
                        p.amount = Math.abs(a);
                        if (field.equals(F_AMOUNT_IN)) p.kind = Tx.INCOME;
                        else if (field.equals(F_AMOUNT_OUT)) p.kind = Tx.EXPENSE;
                        else if (l.text.trim().startsWith("-") || l.text.trim().startsWith("−")) p.kind = Tx.EXPENSE;
                        String c = findCurrency(l.text);
                        if (c != null) p.currency = c;
                    }
                    break;
                }
                case F_KIND_IN: kindIn = true; p.title = l.text; break;
                case F_KIND_OUT: kindOut = true; p.title = l.text; break;
                case F_CARD: { String c = findCard(l.text); if (c != null) p.card = c; break; }
                case F_NOTE: p.note = p.note.isEmpty() ? l.text : p.note + " · " + l.text; break;
                case F_DATE: { Long t = findDate(l.text); if (t != null) p.ts = t; break; }
                case F_BALANCE: { Double b = findAmount(l.text); if (b != null) p.balance = b; break; }
                case F_TITLE: {
                    Double a = findAmount(l.text);
                    if (a != null && findCurrency(l.text) != null) { if (untyped == null) untyped = a; untypedCount++; }
                    else p.title = l.text;
                    break;
                }
                default: {
                    // no rule for this line: ➕ / ➖ prefixes still tell the sign; any other line with "<number> <currency>" is a fallback amount
                    Double a = findAmount(l.text);
                    if (a != null && (l.prefix.contains("➕") || l.prefix.contains("➖"))) {
                        if (!p.hasAmount()) {
                            p.amount = Math.abs(a);
                            p.kind = l.prefix.contains("➕") ? Tx.INCOME : Tx.EXPENSE;
                            String c = findCurrency(l.text);
                            if (c != null) p.currency = c;
                        }
                    } else if (!l.prefix.isEmpty() && a != null && findCurrency(l.text) != null) {
                        if (untyped == null) untyped = a;
                        untypedCount++;
                    }
                }
            }
        }
        if (p.kind < 0 && kindIn != kindOut) p.kind = kindIn ? Tx.INCOME : Tx.EXPENSE;
        if (!p.hasAmount() && untyped != null && untypedCount == 1) p.amount = Math.abs(untyped); // several unmapped amount lines = a balance report, not a transaction
        if (p.currency == null) {
            String c = findCurrency(message);
            if (c != null) p.currency = c;
        }
        if (p.ts == 0) { Long t = findDate(message); if (t != null) p.ts = t; }
        if (p.card == null) { String c = findCard(message); if (c != null) p.card = c; }
        return p;
    }

    /** Short human summary of a parse */
    public static String summary(Parsed p) {
        StringBuilder sb = new StringBuilder();
        if (p.hasAmount()) sb.append(p.kind == Tx.INCOME ? "+" : p.kind == Tx.EXPENSE ? "−" : "±").append(Money.number(p.amount, 2)).append(p.currency != null ? " " + p.currency : "");
        else sb.append("no amount");
        if (p.card != null) sb.append(" · *").append(p.card);
        if (!p.note.isEmpty()) sb.append(" · ").append(p.note);
        return sb.toString();
    }

    // endregion
}
