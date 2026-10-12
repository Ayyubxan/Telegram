package org.telegram.messenger.finance;

/** FORK MOD (Finance): a money account (cash, card, bank, business cash desk…) in one currency, owned by one entity */
public class Account {
    public static final int TYPE_CASH = 0;
    public static final int TYPE_CARD = 1;
    public static final int TYPE_BANK = 2;
    public static final int TYPE_OTHER = 3;

    public String id;
    public String entity;
    public String name = "";
    public String emoji = "";
    public String currency;
    public int type;
    public double initial;
    public int sort;
    public boolean archived;
    /** counts toward net worth */
    public boolean wealth = true;
    public long updated;

    public String title() {
        return (emoji == null || emoji.isEmpty() ? "" : emoji + " ") + name;
    }
}
