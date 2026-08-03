package com.velorise.simplemap.client.cave;

/**
 * Metadata-only source-loading policy shared by surface reconstruction and tests.
 *
 * <p>Xaero treats a dimension's cave start and its map layer as separate state.
 * This policy follows the same principle: immutable dimension metadata selects a
 * loading topology, while local roofs never turn an open world into a cave world.
 * A surface leaf needs one neighbouring Minecraft chunk on every side for its
 * slope/edge samples, hence the 4x4 body is repaired as a coherent 6x6 window.</p>
 */
public final class AdaptiveDimensionLoadPolicy {
    public enum Topology {
        SKYLIT_OPEN,
        DARK_OPEN,
        HARD_CEILING
    }

    private static final int SURFACE_HALO_CHUNKS = 1;

    private AdaptiveDimensionLoadPolicy() {
    }

    public static Topology topology(boolean hasSkyLight, boolean hasCeiling) {
        if (hasCeiling) return Topology.HARD_CEILING;
        return hasSkyLight ? Topology.SKYLIT_OPEN : Topology.DARK_OPEN;
    }

    public static int surfaceHaloChunks() {
        return SURFACE_HALO_CHUNKS;
    }

    /**
     * Number of coherent page windows admitted by one 100 ms source slice.
     * Dark/open worlds such as The End have no useful live skylight shortcut and
     * therefore keep two world-save windows moving even at far zoom. Pressure and
     * fast player motion collapse every topology to one window for frame pacing.
     */
    public static int surfacePageBudget(Topology topology, boolean fullscreen,
            boolean pressured, boolean movingFast, float scale) {
        if (pressured || movingFast || !fullscreen) return 1;
        if (topology == Topology.DARK_OPEN) return 2;
        if (topology == Topology.HARD_CEILING) return 1;
        return scale < 0.12f ? 1 : 2;
    }

    /** Rebuild only after a meaningful pan/zoom, not every page-edge crossing. */
    public static boolean shouldRetarget(int oldMinX, int oldMaxX,
            int oldMinZ, int oldMaxZ, int oldCenterX, int oldCenterZ,
            int newMinX, int newMaxX, int newMinZ, int newMaxZ,
            int newCenterX, int newCenterZ) {
        boolean disjoint = newMaxX < oldMinX || newMinX > oldMaxX
                || newMaxZ < oldMinZ || newMinZ > oldMaxZ;
        if (disjoint) return true;
        boolean centerMoved = Math.abs(newCenterX - oldCenterX) >= 2
                || Math.abs(newCenterZ - oldCenterZ) >= 2;
        boolean extentMoved = Math.abs(newMinX - oldMinX) >= 2
                || Math.abs(newMaxX - oldMaxX) >= 2
                || Math.abs(newMinZ - oldMinZ) >= 2
                || Math.abs(newMaxZ - oldMaxZ) >= 2;
        return centerMoved || extentMoved;
    }
}
