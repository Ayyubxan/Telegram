package org.telegram.messenger.nexo;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** FORK MOD (Nexo): English date helpers (backup captions, Mods screen) */
public class Dates {
    public static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};
    public static final String[] MONTHS_SHORT = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    public static final String[] WEEKDAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
    public static final String[] WEEKDAYS_SHORT = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

    /** "Monday, 12 October" */
    public static String longDate(LocalDate d) {
        return WEEKDAYS[d.getDayOfWeek().getValue() - 1] + ", " + d.getDayOfMonth() + " " + MONTHS[d.getMonthValue() - 1];
    }

    /** "12 October" */
    public static String dayMonth(LocalDate d) {
        return d.getDayOfMonth() + " " + MONTHS[d.getMonthValue() - 1];
    }

    /** "2026-12-31" → "31 Dec" (other year → "31 Dec 2027") */
    public static String shortDate(String iso) {
        LocalDate d = parse(iso);
        if (d == null) return iso;
        return shortDate(d);
    }

    public static String shortDate(LocalDate d) {
        String base = d.getDayOfMonth() + " " + MONTHS_SHORT[d.getMonthValue() - 1];
        return d.getYear() == LocalDate.now().getYear() ? base : base + " " + d.getYear();
    }

    /** "today", "tomorrow" or "Mon, 12 Oct" */
    public static String shortDay(LocalDate d, LocalDate today) {
        if (d.equals(today)) return "today";
        if (d.equals(today.plusDays(1))) return "tomorrow";
        return WEEKDAYS_SHORT[d.getDayOfWeek().getValue() - 1] + ", " + d.getDayOfMonth() + " " + MONTHS_SHORT[d.getMonthValue() - 1];
    }

    /** Until the deadline: "12 days left", "tomorrow", "last day today", "3 days overdue" */
    public static String left(String iso, LocalDate today) {
        LocalDate d = parse(iso);
        if (d == null) return "";
        long n = ChronoUnit.DAYS.between(today, d);
        if (n > 1) return n + " days left";
        if (n == 1) return "tomorrow";
        if (n == 0) return "last day today";
        return (-n) + (n == -1 ? " day overdue" : " days overdue");
    }

    public static String daysAgo(long days) {
        if (days == 0) return "today";
        if (days == 1) return "yesterday";
        return days + " days ago";
    }

    /** 1 → "1st", 22 → "22nd" */
    public static String ordinal(int n) {
        int m100 = n % 100;
        if (m100 >= 11 && m100 <= 13) return n + "th";
        switch (n % 10) {
            case 1: return n + "st";
            case 2: return n + "nd";
            case 3: return n + "rd";
            default: return n + "th";
        }
    }

    public static LocalDate parse(String iso) {
        try {
            return LocalDate.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }
}
