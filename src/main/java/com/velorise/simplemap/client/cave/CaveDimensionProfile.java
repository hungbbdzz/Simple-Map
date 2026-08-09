package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.DimensionMapProfile;
import com.velorise.simplemap.client.MapManager;
import net.minecraft.world.level.Level;

/**
 * Dimension-scoped cave capability learned from metadata and real roof structure.
 *
 * <p>The important distinction is capability, not a registry-id special case:
 * skylit/open worlds default to layered local caves, hard-ceiling worlds support a
 * persistent Full Cave projection, and dark-but-open worlds remain surface-first.
 * Capability controls AUTO/default behavior only; it never removes a manual view.</p>
 *
 * <p>Local roof observations deliberately do not promote a whole dimension. The
 * End is the important counterexample: travelling across its island samples many
 * distinct, strongly covered chunks even though the dimension is open. Xaero also
 * keeps the resolved cave start separate from map-layer identity; stable dimension
 * metadata is the only safe input for a persistent AUTO projection.</p>
 */
public final class CaveDimensionProfile {
    public enum Capability {
        /** Surface by default. Local automatic/manual Layered Cave is still legal. */
        SURFACE_ONLY,
        /** Normal open world with local caves. */
        LAYERED,
        /** Dimension-wide enclosed volume suitable for player-Y-independent Full Cave. */
        FULL
    }

    private CaveDimensionProfile() {
    }

    /** Strong metadata signal used by vanilla and modded Nether-style dimensions. */
    public static boolean hasHardCeiling(Level level) {
        return level != null && level.dimensionType().hasCeiling();
    }

    public static Capability capability(Level level) {
        if (level == null) return Capability.SURFACE_ONLY;
        if (hasHardCeiling(level)) return Capability.FULL;
        if (level.dimensionType().hasSkyLight()) return Capability.LAYERED;
        // A missing skylight flag is not proof of a cave world. The End, space,
        // void and many custom dimensions are dark but open. A manual Top Y still
        // activates Layered/Full Cave without mutating the AUTO classification.
        return Capability.SURFACE_ONLY;
    }

    /**
     * Stable capability for a remotely viewed dimension. Live dimensions still
     * use their DimensionType metadata; the three vanilla dimensions can be
     * classified without borrowing metadata from the player's current level.
     */
    public static Capability capability(Level liveLevel, String viewedDimension) {
        String id = viewedDimension == null ? "" : viewedDimension;
        if (liveLevel != null
                && id.equals(liveLevel.dimension().location().toString())) {
            return capability(liveLevel);
        }
        DimensionMapProfile profile = MapManager.getInstance()
                .getCurrentDimensionProfile();
        if (profile != null && id.equals(profile.resourceId()) && profile.known()) {
            if (profile.hasCeiling()) return Capability.FULL;
            if (profile.hasSkyLight()) return Capability.LAYERED;
            return Capability.SURFACE_ONLY;
        }
        // Unknown custom remote dimensions stay conservative until visited once.
        return switch (id) {
            case "minecraft:the_nether" -> Capability.FULL;
            case "minecraft:overworld" -> Capability.LAYERED;
            default -> Capability.SURFACE_ONLY;
        };
    }

    /** True only when AUTO should use a dimension-wide Full Cave projection. */
    public static boolean supportsFullCave(Level level) {
        return capability(level) == Capability.FULL;
    }

    /** Persistent AUTO cave state requires immutable hard-ceiling metadata. */
    public static boolean isPersistentCaveDimension(Level level) {
        return hasHardCeiling(level);
    }

    public static boolean prefersFullCave(Level level) {
        return supportsFullCave(level);
    }

    /**
     * Xaero starts every column from WORLD_SURFACE, including the Nether. The
     * heightmap already points at the roof where one exists, while starting at the
     * build ceiling needlessly walks empty upper sections in tall dimensions.
     */
    public static boolean shouldScanFromWorldTop(Level level) {
        return false;
    }

    /**
     * Retained compatibility hook. Local roof evidence is useful for the resolved
     * player cave-start, but is intentionally not dimension capability evidence.
     */
    public static void recordProbe(Level level, int chunkX, int chunkZ,
            boolean covered, int confidence) {
        // No-op by design. See the class-level End/open-dark rationale.
    }

    public static synchronized void reset() {
        // No learned dimension-wide state is retained.
    }
}
