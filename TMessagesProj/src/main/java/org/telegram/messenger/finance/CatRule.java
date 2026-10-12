package org.telegram.messenger.finance;

/** FORK MOD (Finance): "messages from this chat whose merchant/text contains X are category Y (and account Z)" — learned from Inbox reviews */
public class CatRule {
    public String id;
    /** import rule (chat) it belongs to; null — any chat */
    public String rule;
    public String pattern = "";
    public String category;
    public String account;
    /** add matching messages without review */
    public boolean auto;
    public long updated;

    public boolean matches(String text) {
        if (text == null || pattern == null || pattern.isEmpty()) return false;
        return text.toLowerCase(java.util.Locale.ROOT).contains(pattern.toLowerCase(java.util.Locale.ROOT));
    }
}
