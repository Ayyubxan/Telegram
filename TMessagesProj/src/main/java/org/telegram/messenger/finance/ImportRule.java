package org.telegram.messenger.finance;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FORK MOD (Finance): how messages of one Telegram chat (a bank bot, a business bot) become transactions.
 * {@code mapping} says which line prefix / keyword means which field; {@code cards} maps card suffixes to accounts.
 */
public class ImportRule {
    public String id;
    public long dialogId;
    public String name = "";
    public boolean enabled = true;
    public final List<BotParser.LineRule> mapping = new ArrayList<>();
    /** default account when the message has no (known) card */
    public String account;
    public String catIn, catOut;
    /** true — add transactions without review; false — put them into the Inbox */
    public boolean auto;
    public final LinkedHashMap<String, String> cards = new LinkedHashMap<>();
    public String sample = "";
    public long updated;

    public String mappingJson() {
        JSONArray a = new JSONArray();
        for (BotParser.LineRule r : mapping) a.put(r.toJson());
        return a.toString();
    }

    public void setMappingJson(String json) {
        mapping.clear();
        if (json == null || json.isEmpty()) return;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) mapping.add(BotParser.LineRule.fromJson(a.getJSONObject(i)));
        } catch (Exception ignore) {}
    }

    public String cardsJson() {
        return new JSONObject(cards).toString();
    }

    public void setCardsJson(String json) {
        cards.clear();
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(json);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                cards.put(k, o.optString(k));
            }
        } catch (Exception ignore) {}
    }

    /** Account for a parsed message: by card suffix, else the default */
    public String accountFor(String card) {
        if (card != null && cards.containsKey(card)) return cards.get(card);
        return account;
    }

    public Map<String, String> cardMap() {
        return cards;
    }
}
