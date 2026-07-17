package com.velorise.simplemap.client;

/** Parsing and formatting helpers for user-entered ARGB colors. */
public final class ColorCode {
    private ColorCode() {
    }

    public static int parse(String text) {
        String value = text == null ? "" : text.trim();
        if (value.startsWith("#")) value = value.substring(1);
        if (value.length() != 6 && value.length() != 8) {
            throw new IllegalArgumentException("Use #RRGGBB or #AARRGGBB");
        }
        try {
            long parsed = Long.parseUnsignedLong(value, 16);
            if (value.length() == 6) parsed |= 0xFF000000L;
            return (int) parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid hexadecimal color", exception);
        }
    }

    public static String format(int argb) {
        return String.format("#%08X", argb);
    }
}
