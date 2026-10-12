package org.telegram.messenger.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** FORK MOD (Finance): one transaction — expense, income or a transfer between two accounts */
public class Tx {
    public static final int EXPENSE = 0;
    public static final int INCOME = 1;
    public static final int TRANSFER = 2;

    public static final int SOURCE_MANUAL = 0;
    public static final int SOURCE_BOT = 1;
    public static final int SOURCE_API = 2;

    public String id;
    public int kind;
    /** amount in the (from) account's currency */
    public double amount;
    public String currency;
    public String account;
    /** transfers only */
    public String toAccount;
    public double toAmount;
    public String category;
    public String entity;
    /** moment of the transaction, ms */
    public long ts;
    /** LocalDate.toEpochDay() of {@link #ts} in the local zone */
    public long day;
    public String note = "";
    public int source;
    public String extId;
    public long updated;

    public LocalDate date() {
        return LocalDate.ofEpochDay(day);
    }

    public void setMoment(long ms) {
        ts = ms;
        day = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay();
    }

    public void setDate(LocalDate d) {
        LocalDate old = date();
        if (old.equals(d) && ts != 0) return;
        java.time.LocalTime t = ts == 0 ? java.time.LocalTime.now() : Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalTime();
        setMoment(d.atTime(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
    }

    public boolean isTransfer() {
        return kind == TRANSFER;
    }

    /** signed amount for the from-account: expenses and transfers out are negative */
    public double signed() {
        return kind == INCOME ? amount : -amount;
    }
}
