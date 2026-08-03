package com.velorise.simplemap.client.cave;

/** Pure guard proving that local roof samples cannot classify a whole dimension. */
public final class CaveDimensionEvidence {
    private CaveDimensionEvidence() {
    }

    /**
     * Local roofs are not dimension topology. Even broad samples can come from the
     * End's main island, floating-island worlds or roofed structures. Persistent
     * AUTO Full Cave therefore requires DimensionType.hasCeiling(); sample counts
     * alone never promote a dark/open dimension.
     */
    public static boolean classifyEnclosed(int sampleCount, int coveredCount,
            int strongCoveredCount, int openCount) {
        return false;
    }
}
