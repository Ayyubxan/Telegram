package org.telegram.messenger.finance;

/** FORK MOD (Finance): a bot message waiting for review (status 0), accepted (1) or dismissed (2) */
public class InboxItem {
    public static final int PENDING = 0;
    public static final int ACCEPTED = 1;
    public static final int DISMISSED = 2;

    public String id;
    public String rule;
    public long dialogId;
    public int msgId;
    public String text = "";
    public BotParser.Parsed parsed;
    public int status;
    public long created;

    public String extId() {
        return "tg:" + dialogId + ":" + msgId;
    }
}
