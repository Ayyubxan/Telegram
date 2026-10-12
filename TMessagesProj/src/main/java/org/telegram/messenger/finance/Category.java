package org.telegram.messenger.finance;

/** FORK MOD (Finance): expense or income category; {@code entity} null — shared by everyone, otherwise only that entity */
public class Category {
    public static final int EXPENSE = 0;
    public static final int INCOME = 1;

    public String id;
    public int kind;
    public String name = "";
    public String emoji = "";
    public String color;
    public String entity;
    public int sort;
    public boolean archived;
    public long updated;

    /** Tailwind-like palette shared with habit categories */
    public static final java.util.List<String> PALETTE = java.util.Arrays.asList(
        "#22C55E", "#3B82F6", "#8B5CF6", "#EC4899", "#EF4444",
        "#F97316", "#F59E0B", "#14B8A6", "#06B6D4", "#64748B"
    );

    public int colorInt() {
        return colorInt(color);
    }

    public static int colorInt(String c) {
        try {
            return android.graphics.Color.parseColor(c == null || c.isEmpty() ? PALETTE.get(9) : c);
        } catch (Exception e) {
            return 0xFF64748B;
        }
    }

    public String title() {
        return (emoji == null || emoji.isEmpty() ? "" : emoji + " ") + name;
    }
}
