package org.telegram.messenger.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** FORK MOD (Finance): number formatting / parsing for amounts */
public class Money {
    private static final char GROUP = ' '; // narrow no-break space

    /** "1 250 000 UZS", "125.5 USD", "0.0125 BTC" */
    public static String format(double amount, String code) {
        return number(amount, Currencies.decimals(code)) + " " + code;
    }

    /** Signed: "+12 000 UZS" / "−12 000 UZS" */
    public static String signed(double amount, String code) {
        String s = format(Math.abs(amount), code);
        return amount < 0 ? "−" + s : amount > 0 ? "+" + s : s;
    }

    /** Number with thousands grouping and at most {@code decimals} fraction digits (trailing zeros dropped) */
    public static String number(double amount, int decimals) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) return "—";
        BigDecimal bd = new BigDecimal(amount).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros();
        if (bd.scale() < 0) bd = bd.setScale(0, RoundingMode.UNNECESSARY);
        String plain = bd.abs().toPlainString();
        int dot = plain.indexOf('.');
        String ip = dot < 0 ? plain : plain.substring(0, dot);
        String fp = dot < 0 ? "" : plain.substring(dot);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ip.length(); i++) {
            if (i > 0 && (ip.length() - i) % 3 == 0) sb.append(GROUP);
            sb.append(ip.charAt(i));
        }
        return (bd.signum() < 0 ? "−" : "") + sb + fp;
    }

    /** Short form for cards: 1.25M, 850K; below 10 000 — full */
    public static String compact(double amount, String code) {
        double a = Math.abs(amount);
        String s;
        if (a >= 1_000_000_000d) s = trim(a / 1_000_000_000d) + "B";
        else if (a >= 1_000_000d) s = trim(a / 1_000_000d) + "M";
        else if (a >= 100_000d) s = trim(a / 1_000d) + "K";
        else s = number(a, Currencies.decimals(code));
        return (amount < 0 ? "−" : "") + s + " " + code;
    }

    private static String trim(double v) {
        return number(v, v >= 100 ? 0 : v >= 10 ? 1 : 2);
    }

    /** Parses "1 250 000", "1,250,000.50", "1 250 000,5"; returns NaN if not a number */
    public static double parse(String s) {
        if (s == null) return Double.NaN;
        String t = s.trim().replace(" ", "").replace(" ", "").replace(" ", "").replace("−", "-");
        if (t.isEmpty()) return Double.NaN;
        // both separators present: the last one is the decimal point
        int lastComma = t.lastIndexOf(','), lastDot = t.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            if (lastComma > lastDot) t = t.replace(".", "").replace(',', '.');
            else t = t.replace(",", "");
        } else if (lastComma >= 0) {
            // a single comma: decimal if followed by 1–2 digits, otherwise thousands
            int digitsAfter = t.length() - lastComma - 1;
            t = (digitsAfter > 0 && digitsAfter <= 2 && t.indexOf(',') == lastComma) ? t.replace(',', '.') : t.replace(",", "");
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Plain editable text for an input field (no grouping) */
    public static String plain(double amount, String code) {
        if (amount == 0) return "";
        BigDecimal bd = new BigDecimal(amount).setScale(Math.max(Currencies.decimals(code), 2), RoundingMode.HALF_UP).stripTrailingZeros();
        if (bd.scale() < 0) bd = bd.setScale(0, RoundingMode.UNNECESSARY);
        return bd.toPlainString();
    }

    public static double round(double amount, String code) {
        return new BigDecimal(amount).setScale(Currencies.decimals(code), RoundingMode.HALF_UP).doubleValue();
    }
}
