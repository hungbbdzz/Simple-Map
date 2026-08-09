package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapLodPolicy;
import java.nio.file.Files;
import java.nio.file.Path;

/** Guards logical cave LOD, exact-first L0 output and CPU-first branch feed. */
public final class CaveLogicalLodRootCoverageCheck {
    private CaveLogicalLodRootCoverageCheck() { }

    public static void main(String[] args) throws Exception {
        require(MapLodPolicy.branchLevel(0.478f, 3) == 1,
                "logical 0.478x cave zoom did not select L1");
        require(MapLodPolicy.stabilizeCaveBranchLevel(1, 0, 0.478f) == 1,
                "cave hysteresis retained exact L0 past the logical boundary");
        Path client = Path.of("src/main/java/com/velorise/simplemap/client");
        String renderer = Files.readString(client.resolve("MapRenderer.java"));
        String fbo = Files.readString(client.resolve("FullscreenMapFramebufferRenderer.java"));
        String manager = Files.readString(client.resolve("cave/UnifiedCaveTextureManager.java"));
        require(renderer.contains("float activePolicyScale = caveMode ? cavePolicyScale : policyScale")
                        && fbo.contains("float cavePolicyScale = renderPixelsPerBlock"),
                "fullscreen cave renderer still selects LOD from GUI-scaled density");
        int exactLevel = renderer.indexOf("if (level <= 0)");
        int traversal = renderer.indexOf("if (!centerOutTraversal)", exactLevel);
        String exactBlock = renderer.substring(exactLevel, traversal);
        require(exactLevel >= 0 && traversal > exactLevel
                        && !exactBlock.contains("collectCaveLevelZeroUnderlay"),
                "new exact cave projections can still expose a blurry branch underlay");
        require(renderer.contains("if (branchOnly && level > 0)"),
                "far-zoom cave rendering lost branch hierarchy coverage");
        int branchFeed = manager.indexOf("updateBranchCandidate(info, mergedBase");
        int exactCoalescing = manager.indexOf(
                "CaveTilePublicationPolicy.shouldPublish", branchFeed);
        int exactAdmission = manager.indexOf("ensureAtlasSlot(info)", branchFeed);
        require(branchFeed >= 0 && exactCoalescing > branchFeed
                        && exactAdmission > exactCoalescing,
                "branch/root coverage still waits for exact coalescing or atlas admission");
        require(manager.contains("candidateProjectionKnownRows")
                        && manager.contains("markBranchCandidate"),
                "CPU branch staging lacks atomic projection coverage or retry coalescing");
        System.out.println("CAVE_LOGICAL_LOD_ROOT_COVERAGE_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
