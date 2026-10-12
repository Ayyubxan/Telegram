package org.telegram.messenger.finance;

/**
 * FORK MOD (Finance): a wealth item that is not a money account — real estate, a car, gold, crypto, stocks, a business.
 * {@code cls} decides how it is valued: MANUAL — the last entered value; QUOTED — {@code qty} × rate of {@code code}
 * (a currency / crypto / metal code from {@link Rates}, or a stock ticker).
 */
public class Asset {
    public static final int CLS_MANUAL = 0;
    public static final int CLS_QUOTED = 1;

    /** asset groups (Kubera-style sheets): key, title, emoji */
    public static final String[][] GROUPS = {
        {"cash", "Cash & currency", "💵"}, {"metals", "Precious metals", "🥇"}, {"crypto", "Crypto", "₿"}, {"stocks", "Stocks & funds", "📈"},
        {"realty", "Real estate", "🏠"}, {"vehicles", "Vehicles", "🚗"}, {"business", "Business", "🏪"}, {"other", "Other", "💎"}
    };

    public static String groupTitle(String key) {
        for (String[] g : GROUPS) if (g[0].equals(key)) return g[1];
        return "Other";
    }

    public static String groupEmoji(String key) {
        for (String[] g : GROUPS) if (g[0].equals(key)) return g[2];
        return "💎";
    }

    /** Sensible group for a rate code: fiat → cash, XAU/XAG → metals, crypto → crypto, anything else (ticker) → stocks */
    public static String groupForCode(String code) {
        if (code == null) return "other";
        Currencies.Cur c = Currencies.get(code);
        if (!Currencies.isKnown(code)) return "stocks";
        if (c.kind == Currencies.METAL) return "metals";
        if (c.kind == Currencies.CRYPTO) return "crypto";
        return "cash";
    }

    public String id;
    public String entity;
    public String name = "";
    public String emoji = "";
    public int cls;
    /** rate code or ticker for QUOTED */
    public String code;
    public double qty;
    /** currency of the manual value */
    public String currency;
    public String note = "";
    /** one of GROUPS keys */
    public String group = "other";
    public int sort;
    public boolean archived;
    public long updated;

    public String title() {
        return (emoji == null || emoji.isEmpty() ? "" : emoji + " ") + name;
    }
}
