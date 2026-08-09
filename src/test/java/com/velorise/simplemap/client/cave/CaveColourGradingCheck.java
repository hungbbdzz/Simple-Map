package com.velorise.simplemap.client.cave;

/** PASS77 runtime-independent checks for hue-preserving cave colour grading. */
public final class CaveColourGradingCheck {
    private CaveColourGradingCheck() { }

    public static void main(String[] args) {
        int stone = 0xFF68686C;
        int water = 0xFFB06028; // ABGR: R=0x28, G=0x60, B=0xB0
        int lava = 0xFF1830D0;  // ABGR: R=0xD0, G=0x30, B=0x18
        int gradedStone = CavePageStyler.gradeCaveColor(
                stone, false, false, CaveView.FULL, false);
        int gradedWater = CavePageStyler.gradeCaveColor(
                water, true, true, CaveView.FULL, false);
        int gradedLava = CavePageStyler.gradeCaveColor(
                lava, false, true, CaveView.FULL, false);
        require(channelRange(gradedStone) >= channelRange(stone),
                "neutral cave material lost contrast");
        require(blue(gradedWater) > blue(water)
                        && blue(gradedWater) > red(gradedWater),
                "water grading does not preserve a clear blue/cyan identity");
        require(red(gradedLava) > red(lava)
                        && red(gradedLava) > blue(gradedLava),
                "fluid grading does not preserve lava/orange identity");
        System.out.println("CAVE_COLOUR_GRADING_PASS");
    }

    private static int red(int abgr) { return abgr & 0xFF; }
    private static int green(int abgr) { return (abgr >>> 8) & 0xFF; }
    private static int blue(int abgr) { return (abgr >>> 16) & 0xFF; }
    private static int channelRange(int abgr) {
        int minimum = Math.min(red(abgr), Math.min(green(abgr), blue(abgr)));
        int maximum = Math.max(red(abgr), Math.max(green(abgr), blue(abgr)));
        return maximum - minimum;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
