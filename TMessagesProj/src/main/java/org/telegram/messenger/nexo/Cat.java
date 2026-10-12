package org.telegram.messenger.nexo;

import android.graphics.Color;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** FORK MOD (Nexo): user category. {@link #color} — "#RRGGBB" (same as Mac). */
public class Cat {
    public String id;
    public String name;
    public String emoji;
    public String color;
    public int order;
    public boolean del;
    public long u;

    public Cat(String id, String name, String emoji, String color, int order, boolean del, long u) {
        this.id = id; this.name = name; this.emoji = emoji; this.color = color; this.order = order; this.del = del; this.u = u;
    }

    public Cat copy() {
        return new Cat(id, name, emoji, color, order, del, u);
    }

    public int colorInt() {
        try {
            return Color.parseColor(color);
        } catch (Exception e) {
            return 0xFF64748B;
        }
    }

    public static final List<String> PALETTE = Arrays.asList(
        "#22C55E", "#3B82F6", "#8B5CF6", "#EC4899", "#EF4444",
        "#F97316", "#F59E0B", "#14B8A6", "#06B6D4", "#64748B"
    );

    /** Fixed categories of old versions — now ordinary editable records */
    public static final LinkedHashMap<String, String[]> LEGACY = new LinkedHashMap<>();
    static {
        LEGACY.put("sport", new String[]{"Sport", "🏃", "#22C55E"});
        LEGACY.put("sex", new String[]{"Sex", "❤️", "#EC4899"});
        LEGACY.put("work", new String[]{"Work", "💼", "#3B82F6"});
        LEGACY.put("life", new String[]{"Life", "🌱", "#F59E0B"});
        LEGACY.put("learning", new String[]{"Learning", "📚", "#8B5CF6"});
    }
}
