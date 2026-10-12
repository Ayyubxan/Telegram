package org.telegram.messenger.finance;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * FORK MOD (Finance): exchange rates, all stored as "UZS per one unit".
 * Fiat — Central Bank of Uzbekistan (daily); crypto — CoinGecko (USD); gold/silver — Yahoo futures (USD per troy ounce → per gram);
 * stock tickers — Yahoo chart API. Manual overrides survive refreshes.
 */
public class Rates {
    public static final long STALE_MS = 6 * 60 * 60 * 1000L;
    public static final double TROY_OUNCE_G = 31.1034768;

    private static volatile boolean refreshing;
    private static final HashMap<String, Double> cache = new HashMap<>();
    private static volatile boolean cacheLoaded;

    private static synchronized void ensureCache() {
        if (cacheLoaded) return;
        cache.clear();
        cache.putAll(Finance.db().loadRates());
        cache.put("UZS", 1d);
        cacheLoaded = true;
    }

    /** UZS per one unit of {@code code}, or NaN if unknown */
    public static double uzs(String code) {
        if ("UZS".equals(code)) return 1d;
        ensureCache();
        synchronized (Rates.class) {
            Double d = cache.get(code);
            return d == null ? Double.NaN : d;
        }
    }

    public static boolean known(String code) {
        return !Double.isNaN(uzs(code));
    }

    /** Converts; NaN when a rate is missing */
    public static double convert(double amount, String from, String to) {
        if (from.equals(to)) return amount;
        double a = uzs(from), b = uzs(to);
        if (Double.isNaN(a) || Double.isNaN(b) || b == 0) return Double.NaN;
        return amount * a / b;
    }

    /** Same as {@link #convert} but 0 instead of NaN (for sums where a missing rate should not poison the total) */
    public static double convertOr0(double amount, String from, String to) {
        double v = convert(amount, from, to);
        return Double.isNaN(v) ? 0 : v;
    }

    public static double toBase(double amount, String from) {
        return convertOr0(amount, from, Finance.base());
    }

    public static void setManual(String code, double uzsPerUnit) {
        Finance.db().saveRate(code, uzsPerUnit, true, "manual", null);
        synchronized (Rates.class) { cache.put(code, uzsPerUnit); }
        Finance.notifyChanged();
    }

    public static void clearManual(String code) {
        Finance.db().setRateManual(code, false);
        cacheLoaded = false;
        Finance.notifyChanged();
    }

    public static long lastUpdated() {
        return Finance.prefs().getLong("ratesUpdated", 0);
    }

    public static boolean isRefreshing() {
        return refreshing;
    }

    public static void refreshIfStale(Utilities.Callback<Boolean> done) {
        if (System.currentTimeMillis() - lastUpdated() > STALE_MS) refresh(done);
        else if (done != null) done.run(true);
    }

    /** Fetches everything on a background thread; {@code done} is called on the UI thread with "anything succeeded" */
    public static void refresh(Utilities.Callback<Boolean> done) {
        if (refreshing) { if (done != null) AndroidUtilities.runOnUIThread(() -> done.run(false)); return; }
        refreshing = true;
        Utilities.globalQueue.postRunnable(() -> {
            boolean any = false;
            try { any |= fetchCbu(); } catch (Throwable e) { FileLog.e(e); }
            try { any |= fetchCrypto(); } catch (Throwable e) { FileLog.e(e); }
            try { any |= fetchMetals(); } catch (Throwable e) { FileLog.e(e); }
            try { any |= fetchTickers(); } catch (Throwable e) { FileLog.e(e); }
            if (any) Finance.prefs().edit().putLong("ratesUpdated", System.currentTimeMillis()).apply();
            cacheLoaded = false;
            refreshing = false;
            final boolean ok = any;
            Finance.notifyChanged();
            if (done != null) AndroidUtilities.runOnUIThread(() -> done.run(ok));
        });
    }

    // region Sources

    /** cbu.uz — all fiat currencies against UZS */
    private static boolean fetchCbu() throws Exception {
        String body = get("https://cbu.uz/uz/arkhiv-kursov-valyut/json/");
        JSONArray arr = new JSONArray(body);
        int n = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String code = o.optString("Ccy");
            double rate = o.optDouble("Rate", Double.NaN);
            double nominal = o.optDouble("Nominal", 1);
            if (code.isEmpty() || Double.isNaN(rate) || nominal <= 0) continue;
            Currencies.register(code, o.optString("CcyNm_EN", code));
            Finance.db().saveRate(code, rate / nominal, false, "cbu", o.optString("CcyNm_EN", null));
            n++;
        }
        return n > 0;
    }

    /** CoinGecko simple price in USD → UZS via the USD rate */
    private static boolean fetchCrypto() throws Exception {
        Map<String, Currencies.Cur> ids = Currencies.crypto();
        if (ids.isEmpty()) return false;
        double usd = usdRate();
        if (Double.isNaN(usd)) return false;
        String body = get("https://api.coingecko.com/api/v3/simple/price?ids=" + String.join(",", ids.keySet()) + "&vs_currencies=usd");
        JSONObject o = new JSONObject(body);
        int n = 0;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String id = it.next();
            Currencies.Cur c = ids.get(id);
            double p = o.getJSONObject(id).optDouble("usd", Double.NaN);
            if (c == null || Double.isNaN(p)) continue;
            Finance.db().saveRate(c.code, p * usd, false, "coingecko", c.name);
            n++;
        }
        return n > 0;
    }

    /** Gold / silver futures (USD per troy ounce) → UZS per gram */
    private static boolean fetchMetals() {
        double usd = usdRate();
        if (Double.isNaN(usd)) return false;
        int n = 0;
        for (Currencies.Cur c : Currencies.ofKind(Currencies.METAL)) {
            try {
                double oz = yahooPrice(c.src);
                if (!Double.isNaN(oz)) {
                    Finance.db().saveRate(c.code, oz / TROY_OUNCE_G * usd, false, "yahoo", c.name);
                    n++;
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return n > 0;
    }

    /** Stock tickers used by quoted assets (any code that is not a known currency / metal / crypto) */
    private static boolean fetchTickers() {
        double usd = usdRate();
        if (Double.isNaN(usd)) return false;
        int n = 0;
        for (String ticker : Finance.db().quotedCodes()) {
            if (Currencies.isKnown(ticker)) continue;
            try {
                double[] p = yahooQuote(ticker);
                if (p != null) {
                    Finance.db().saveRate(ticker, p[0] * usd, false, "yahoo", ticker);
                    n++;
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return n > 0;
    }

    private static double usdRate() {
        double usd = Finance.db().loadRate("USD");
        return usd;
    }

    /** Yahoo chart meta.regularMarketPrice; returns NaN if unavailable */
    public static double yahooPrice(String symbol) throws Exception {
        double[] q = yahooQuote(symbol);
        return q == null ? Double.NaN : q[0];
    }

    /** {price, 1 if USD else 0}; null if unavailable. Non-USD quotes are converted to USD when the currency is known */
    public static double[] yahooQuote(String symbol) throws Exception {
        String body = get("https://query1.finance.yahoo.com/v8/finance/chart/" + java.net.URLEncoder.encode(symbol, "UTF-8") + "?range=1d&interval=1d");
        JSONObject o = new JSONObject(body);
        JSONArray result = o.getJSONObject("chart").optJSONArray("result");
        if (result == null || result.length() == 0) return null;
        JSONObject meta = result.getJSONObject(0).getJSONObject("meta");
        double price = meta.optDouble("regularMarketPrice", Double.NaN);
        if (Double.isNaN(price)) return null;
        String cur = meta.optString("currency", "USD");
        if (!"USD".equals(cur)) {
            double inUzs = uzs(cur);
            double usd = usdRate();
            if (Double.isNaN(inUzs) || Double.isNaN(usd)) return null;
            price = price * inUzs / usd;
        }
        return new double[]{price, 1};
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) TelegramFork/1.0");
        conn.setRequestProperty("Accept", "application/json");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code + " for " + url);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } finally {
            conn.disconnect();
        }
    }

    // endregion
}
