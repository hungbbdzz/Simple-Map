package com.velorise.simplemap.client;

/** Resolves a minimap position relative to a stable screen anchor. */
public final class MinimapPosition {
    private static final int EDGE_MARGIN = 2;

    private MinimapPosition() {
    }

    public static int[] resolve(int screenWidth, int screenHeight, int size) {
        if (MapConfig.legacyMinimapPositionPending) {
            int oldX = clamp((int) (screenWidth * MapConfig.minimapXPercent), EDGE_MARGIN,
                    screenWidth - size - EDGE_MARGIN);
            int oldY = clamp((int) (screenHeight * MapConfig.minimapYPercent), EDGE_MARGIN,
                    screenHeight - size - EDGE_MARGIN);
            setFromTopLeft(oldX, oldY, screenWidth, screenHeight, size);
            MapConfig.legacyMinimapPositionPending = false;
            MapConfig.save();
        }

        String anchor = sanitizeAnchor(MapConfig.minimapAnchor);
        int x = baseX(anchor, screenWidth, size) + MapConfig.minimapOffsetX;
        int y = baseY(anchor, screenHeight, size) + MapConfig.minimapOffsetY;
        return new int[] {
                clamp(x, EDGE_MARGIN, screenWidth - size - EDGE_MARGIN),
                clamp(y, EDGE_MARGIN, screenHeight - size - EDGE_MARGIN)
        };
    }

    public static void setFromTopLeft(int x, int y, int screenWidth, int screenHeight, int size) {
        String horizontal = nearestAxis(x,
                EDGE_MARGIN,
                (screenWidth - size) / 2,
                screenWidth - size - EDGE_MARGIN,
                "LEFT", "CENTER", "RIGHT");
        String vertical = nearestAxis(y,
                EDGE_MARGIN,
                (screenHeight - size) / 2,
                screenHeight - size - EDGE_MARGIN,
                "TOP", "MIDDLE", "BOTTOM");

        MapConfig.minimapAnchor = vertical + "_" + horizontal;
        MapConfig.minimapOffsetX = x - baseX(MapConfig.minimapAnchor, screenWidth, size);
        MapConfig.minimapOffsetY = y - baseY(MapConfig.minimapAnchor, screenHeight, size);
    }

    public static String sanitizeAnchor(String anchor) {
        if (anchor == null) return "TOP_RIGHT";
        return switch (anchor) {
            case "TOP_LEFT", "TOP_CENTER", "TOP_RIGHT",
                    "MIDDLE_LEFT", "MIDDLE_CENTER", "MIDDLE_RIGHT",
                    "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT" -> anchor;
            default -> "TOP_RIGHT";
        };
    }

    private static int baseX(String anchor, int screenWidth, int size) {
        if (anchor.endsWith("_LEFT")) return EDGE_MARGIN;
        if (anchor.endsWith("_CENTER")) return (screenWidth - size) / 2;
        return screenWidth - size - EDGE_MARGIN;
    }

    private static int baseY(String anchor, int screenHeight, int size) {
        if (anchor.startsWith("TOP_")) return EDGE_MARGIN;
        if (anchor.startsWith("MIDDLE_")) return (screenHeight - size) / 2;
        return screenHeight - size - EDGE_MARGIN;
    }

    private static String nearestAxis(int value, int first, int middle, int last,
            String firstName, String middleName, String lastName) {
        int firstDistance = Math.abs(value - first);
        int middleDistance = Math.abs(value - middle);
        int lastDistance = Math.abs(value - last);
        if (firstDistance <= middleDistance && firstDistance <= lastDistance) return firstName;
        if (middleDistance <= lastDistance) return middleName;
        return lastName;
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(value, max));
    }
}
