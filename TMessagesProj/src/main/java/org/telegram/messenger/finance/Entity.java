package org.telegram.messenger.finance;

/** FORK MOD (Finance): who the money belongs to — "Personal" or one of the user's businesses */
public class Entity {
    public static final String PERSONAL = "personal";

    public String id;
    public String name = "";
    public String emoji = "";
    public String color = "";
    /** display currency for this entity's screens; empty — the global base currency */
    public String currency = "";
    public int sort;
    public boolean archived;
    public long updated;

    public boolean isPersonal() {
        return PERSONAL.equals(id);
    }

    public String title() {
        return (emoji == null || emoji.isEmpty() ? "" : emoji + " ") + name;
    }
}
