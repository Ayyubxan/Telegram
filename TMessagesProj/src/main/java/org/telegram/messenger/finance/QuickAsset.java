package org.telegram.messenger.finance;

import java.util.Locale;

/**
 * FORK MOD (Finance): one-line asset entry — "XAU 50", "50 XAU", "AAPL 10", "USD 2000", "150000000", "150 000 000 so'm".
 * A known currency / crypto / metal code or an uppercase ticker makes a quoted asset (quantity × price);
 * a bare number is a value by hand in the base currency (or the currency written after it).
 */
public class QuickAsset {
    public int cls = -1;
    public String code;
    public double qty = Double.NaN;
    public String currency;
    public double value = Double.NaN;

    public boolean ok() {
        return cls == Asset.CLS_QUOTED ? code != null && !Double.isNaN(qty) && qty > 0 : cls == Asset.CLS_MANUAL && !Double.isNaN(value) && value > 0;
    }

    public static QuickAsset parse(String input) {
        QuickAsset q = new QuickAsset();
        if (input == null) return q;
        String s = input.trim().replace("so'm", "UZS").replace("so‘m", "UZS").replace("сум", "UZS").replace("сўм", "UZS");
        if (s.isEmpty()) return q;
        // split into number part and word part
        StringBuilder num = new StringBuilder(), word = new StringBuilder();
        for (String tok : s.split("\\s+")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            boolean numeric = t.matches("[-+−]?[\\d.,\\u202F\\u00A0]+");
            if (numeric) num.append(num.length() > 0 ? " " : "").append(t);
            else word.append(word.length() > 0 ? " " : "").append(t);
        }
        // a glued token like "XAU50" / "50XAU" is split only for KNOWN codes (so "Uy350000000" is not a ticker)
        if (num.length() == 0 && word.length() > 0) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([A-Za-z]{2,8})([\\d.,]+)$|^([\\d.,]+)([A-Za-z]{2,8})$").matcher(word.toString());
            if (m.matches()) {
                String code = (m.group(1) != null ? m.group(1) : m.group(4)).toUpperCase(Locale.ROOT);
                if (Currencies.isKnown(code)) {
                    word = new StringBuilder(code);
                    num = new StringBuilder(m.group(1) != null ? m.group(2) : m.group(3));
                }
            }
        }
        double n = Money.parse(num.toString());
        String w = word.toString().trim().toUpperCase(Locale.ROOT);
        if (w.isEmpty()) {
            if (!Double.isNaN(n)) { q.cls = Asset.CLS_MANUAL; q.value = n; q.currency = Finance.base(); }
            return q;
        }
        // unknown word = a stock ticker only if typed in capitals (AAPL, BRK.B, GC=F); "Uy" / "dom" are not tickers
        String rawWord = word.toString().trim();
        boolean looksTicker = Currencies.isKnown(w) || (rawWord.equals(rawWord.toUpperCase(Locale.ROOT)) && w.matches("[A-Z][A-Z0-9.\\-=^]{0,9}"));
        if (!looksTicker) {
            // free text — treat a number as a manual value, the words are ignored (name is typed separately)
            if (!Double.isNaN(n)) { q.cls = Asset.CLS_MANUAL; q.value = n; q.currency = Finance.base(); }
            return q;
        }
        if (Double.isNaN(n)) return q;
        q.cls = Asset.CLS_QUOTED;
        q.code = w;
        q.qty = n;
        return q;
    }

    public String describe() {
        if (!ok()) return "";
        if (cls == Asset.CLS_MANUAL) return Money.format(value, currency) + " (value by hand)";
        Currencies.Cur c = Currencies.get(code);
        String what = Currencies.isKnown(code) ? c.name : code + " (stock ticker)";
        String unit = c.kind == Currencies.METAL ? " g" : Currencies.isKnown(code) ? " " + code : " shares";
        double rate = Rates.uzs(code);
        String price = Double.isNaN(rate) ? "price will be fetched" : "= " + Money.format(Rates.convertOr0(qty * rate, "UZS", Finance.base()), Finance.base());
        return Money.number(qty, 8) + unit + " · " + what + " · " + price;
    }
}
