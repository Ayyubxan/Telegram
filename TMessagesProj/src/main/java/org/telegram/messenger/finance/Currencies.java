package org.telegram.messenger.finance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FORK MOD (Finance): known currencies — fiat (all CBU-quoted ones appear after the first rate refresh),
 * crypto and precious metals (priced per gram).
 */
public class Currencies {
    public static final int FIAT = 0;
    public static final int CRYPTO = 1;
    public static final int METAL = 2;

    public static class Cur {
        public final String code;
        public final String name;
        public final int kind;
        public final int decimals;
        /** CoinGecko id for crypto, Yahoo symbol for metals */
        public final String src;

        Cur(String code, String name, int kind, int decimals, String src) {
            this.code = code; this.name = name; this.kind = kind; this.decimals = decimals; this.src = src;
        }

        public String title() {
            return code + " · " + name;
        }
    }

    private static final LinkedHashMap<String, Cur> known = new LinkedHashMap<>();

    private static void add(String code, String name, int kind, int decimals, String src) {
        known.put(code, new Cur(code, name, kind, decimals, src));
    }

    static {
        add("UZS", "Uzbek so'm", FIAT, 0, null);
        add("USD", "US dollar", FIAT, 2, null);
        add("EUR", "Euro", FIAT, 2, null);
        add("RUB", "Russian ruble", FIAT, 2, null);
        add("GBP", "British pound", FIAT, 2, null);
        add("KZT", "Kazakh tenge", FIAT, 2, null);
        add("KGS", "Kyrgyz som", FIAT, 2, null);
        add("TJS", "Tajik somoni", FIAT, 2, null);
        add("TRY", "Turkish lira", FIAT, 2, null);
        add("AED", "UAE dirham", FIAT, 2, null);
        add("CNY", "Chinese yuan", FIAT, 2, null);
        add("KRW", "South Korean won", FIAT, 0, null);
        add("JPY", "Japanese yen", FIAT, 0, null);
        add("CHF", "Swiss franc", FIAT, 2, null);
        add("SAR", "Saudi riyal", FIAT, 2, null);
        add("BTC", "Bitcoin", CRYPTO, 8, "bitcoin");
        add("ETH", "Ethereum", CRYPTO, 6, "ethereum");
        add("TON", "Toncoin", CRYPTO, 4, "the-open-network");
        add("USDT", "Tether", CRYPTO, 2, "tether");
        add("SOL", "Solana", CRYPTO, 4, "solana");
        add("BNB", "BNB", CRYPTO, 4, "binancecoin");
        add("XAU", "Gold (gram)", METAL, 3, "GC=F");
        add("XAG", "Silver (gram)", METAL, 2, "SI=F");
    }

    /** Fiat codes quoted by the central bank that are not in the built-in list are registered here after a refresh */
    public static synchronized void register(String code, String name) {
        if (!known.containsKey(code)) known.put(code, new Cur(code, name, FIAT, 2, null));
    }

    public static synchronized boolean isKnown(String code) {
        return known.containsKey(code);
    }

    public static synchronized Cur get(String code) {
        Cur c = known.get(code);
        return c != null ? c : new Cur(code, code, FIAT, 2, null);
    }

    public static synchronized List<Cur> all() {
        return new ArrayList<>(known.values());
    }

    public static synchronized List<Cur> ofKind(int kind) {
        List<Cur> out = new ArrayList<>();
        for (Cur c : known.values()) if (c.kind == kind) out.add(c);
        return out;
    }

    public static synchronized Map<String, Cur> crypto() {
        LinkedHashMap<String, Cur> m = new LinkedHashMap<>();
        for (Cur c : known.values()) if (c.kind == CRYPTO) m.put(c.src, c);
        return m;
    }

    public static int decimals(String code) {
        return get(code).decimals;
    }
}
