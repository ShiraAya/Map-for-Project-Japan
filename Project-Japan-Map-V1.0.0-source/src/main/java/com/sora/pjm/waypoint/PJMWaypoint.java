package com.sora.pjm.waypoint;

import java.util.Locale;
import java.util.UUID;

/** A user-created PJM waypoint. */
public final class PJMWaypoint {
    public static final int DEFAULT_COLOR_RGB = 0xFFD35A;

    private String id;
    private String name;
    private double x;
    private double y;
    private double z;

    /**
     * Boxed for backwards compatibility: older JSON has no color field, so Gson leaves it null
     * and colorRgb() transparently falls back to the original gold.
     */
    private Integer colorRgb;

    /**
     * Xaero-style short marker text. Older PJM files do not contain it, so symbol() derives one
     * from the name without requiring a migration pass.
     */
    private String symbol;

    /**
     * Boxed for backwards compatibility. Missing means enabled, matching pre-rework PJM behavior.
     */
    private Boolean enabled;

    public PJMWaypoint(String id, String name,
                       double x, double y, double z, Integer colorRgb) {
        this(id, name, x, y, z, colorRgb, null, null);
    }

    public PJMWaypoint(String id, String name,
                       double x, double y, double z, Integer colorRgb,
                       String symbol, Boolean enabled) {
        this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        this.name = name == null || name.isBlank() ? "Waypoint" : name.trim();
        this.x = x;
        this.y = y;
        this.z = z;
        this.colorRgb = colorRgb;
        this.symbol = normalizeSymbol(symbol);
        this.enabled = enabled;
    }

    public static PJMWaypoint create(String name,
                                     double x, double y, double z,
                                     int colorRgb) {
        return create(name, x, y, z, colorRgb, null, true);
    }

    public static PJMWaypoint create(String name,
                                     double x, double y, double z,
                                     int colorRgb,
                                     String symbol,
                                     boolean enabled) {
        return new PJMWaypoint(
                UUID.randomUUID().toString(), name, x, y, z,
                colorRgb & 0xFFFFFF, symbol, enabled);
    }

    public PJMWaypoint edited(String newName,
                              double newX, double newY, double newZ,
                              int newColorRgb) {
        return edited(newName, newX, newY, newZ, newColorRgb, symbol(), enabled());
    }

    public PJMWaypoint edited(String newName,
                              double newX, double newY, double newZ,
                              int newColorRgb,
                              String newSymbol,
                              boolean newEnabled) {
        return new PJMWaypoint(
                id, newName, newX, newY, newZ,
                newColorRgb & 0xFFFFFF, newSymbol, newEnabled);
    }

    public PJMWaypoint withEnabled(boolean newEnabled) {
        return new PJMWaypoint(
                id, name, x, y, z, colorRgb(), symbol(), newEnabled);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name == null || name.isBlank() ? "Waypoint" : name;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public int colorRgb() {
        return colorRgb == null ? DEFAULT_COLOR_RGB : colorRgb & 0xFFFFFF;
    }

    public int colorArgb() {
        return 0xFF000000 | colorRgb();
    }

    public String symbol() {
        String normalized = normalizeSymbol(symbol);
        return normalized == null ? deriveSymbol(name()) : normalized;
    }

    public boolean enabled() {
        return enabled == null || enabled;
    }

    private static String normalizeSymbol(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;

        StringBuilder out = new StringBuilder();
        int count = 0;
        for (int offset = 0; offset < trimmed.length() && count < 2; ) {
            int codePoint = trimmed.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) continue;
            out.appendCodePoint(codePoint);
            count++;
        }
        return out.isEmpty() ? null : out.toString().toUpperCase(Locale.ROOT);
    }

    private static String deriveSymbol(String value) {
        String normalized = normalizeSymbol(value);
        return normalized == null ? "?" : normalized;
    }
}
