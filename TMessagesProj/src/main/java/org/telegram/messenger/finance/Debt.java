package org.telegram.messenger.finance;

/** FORK MOD (Finance): money somebody owes me (OWED_TO_ME) or I owe (I_OWE); payments are stored separately */
public class Debt {
    public static final int OWED_TO_ME = 0;
    public static final int I_OWE = 1;

    public String id;
    public int dir;
    /** counterparty name as typed — kept even if the Telegram account is gone */
    public String name = "";
    /** linked Telegram user, 0 if none */
    public long userId;
    public double amount;
    public String currency;
    public long day;
    /** due date, 0 if none */
    public long due;
    public String note = "";
    public String entity;
    public boolean closed;
    public long updated;
    /** sum of payments (filled by the DB when listing) */
    public double paid;

    public double left() {
        return Math.max(0, amount - paid);
    }
}
