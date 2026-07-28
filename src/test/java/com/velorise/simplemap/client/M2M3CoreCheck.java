package com.velorise.simplemap.client;

import com.velorise.simplemap.client.pipeline.MapWorkGraph;
import com.velorise.simplemap.client.pipeline.MapWorkKey;
import com.velorise.simplemap.client.pipeline.MapWorkStage;
import com.velorise.simplemap.client.pipeline.RegionRecord;
import com.velorise.simplemap.client.pipeline.RevisionStamp;
import com.velorise.simplemap.client.lod.PreparedBranch;
import com.velorise.simplemap.client.lod.RegionLodDeriver;
import com.velorise.simplemap.client.lod.RegionLodGraph;
import com.velorise.simplemap.client.session.MapSession;
import com.velorise.simplemap.client.session.MapSessionManager;

import java.util.Arrays;

/**
 * Dependency-light state validation for the M2/M3 migration and M4 foundation. Run with
 * {@code gradlew m2m4CoreCheck} inside the complete NeoForge project.
 */
public final class M2M3CoreCheck {
    private M2M3CoreCheck() { }

    public static void main(String[] args) {
        checkDurableStageState();
        checkImmutableSurfaceSource();
        checkSurfaceBatchPolicy();
        checkFarZoomDensityPolicy();
        checkRegionLodGraph();
        checkRegionLodDeriver();
        System.out.println("Simple Map M2/M3/M4 foundation checks passed");
    }

    private static void checkDurableStageState() {
        MapSessionManager sessions = MapSessionManager.getInstance();
        MapSession session = sessions.open("core-check", "minecraft:overworld");
        RevisionStamp stamp = session.stamp();
        MapWorkGraph graph = MapWorkGraph.getInstance();
        MapWorkKey key = new MapWorkKey(stamp, 3, -2,
                MapWorkStage.GPU_PREPARE, 0);
        long firstLeaf = 1L << 1;
        long secondLeaf = 1L << 2;

        require(graph.request(key, 7L, firstLeaf)
                        == MapWorkGraph.Admission.ACCEPTED,
                "initial work request was not accepted");
        RegionRecord.Lease lease = graph.tryBegin(key);
        require(lease != null, "work lease was not issued");

        // A same-revision mutation arriving while the worker is active must
        // survive completion of the older dirty mask.
        graph.request(key, 7L, secondLeaf);
        graph.complete(lease);
        RegionRecord.StageSnapshot stage = graph.snapshot(key).stages().get(
                new RegionRecord.StageKey(MapWorkStage.GPU_PREPARE, 0));
        require(stage != null && stage.state() == RegionRecord.StageState.DIRTY,
                "same-revision dirty state was lost");
        require(stage.dirtyMask() == secondLeaf,
                "the follow-up dirty leaf mask was not preserved");

        RegionRecord.Lease followUp = graph.tryBegin(key);
        require(followUp != null && followUp.dirtyMask() == secondLeaf,
                "durable dirty state could not reconstruct a queue hint");
        graph.markPrepared(key, 7L, secondLeaf);

        MapWorkKey gpu = new MapWorkKey(stamp, 3, -2,
                MapWorkStage.GPU_UPLOAD, 0);
        graph.request(gpu, 7L, firstLeaf | secondLeaf);
        graph.markGpuPublished(gpu, 7L, firstLeaf | secondLeaf);
        graph.markGpuEvicted(gpu, 7L, firstLeaf);
        RegionRecord.Snapshot gpuState = graph.snapshot(gpu);
        require((gpuState.gpuResidentMask() & firstLeaf) == 0L,
                "evicted GPU leaf remained resident");
        require((gpuState.gpuResidentMask() & secondLeaf) != 0L,
                "unrelated GPU leaf was evicted");

        sessions.bumpStyleGeneration();
        require(sessions.activeStamp().styleGeneration()
                        == sessions.active().styleGeneration(),
                "active session did not receive the style generation update");

        long closedSessionId = session.sessionId();
        sessions.closeActive();
        require(graph.request(key, 8L, firstLeaf)
                        == MapWorkGraph.Admission.CANCELLED,
                "late callback recreated a closed session record");
        require(graph.snapshot(closedSessionId, 3, -2) == null,
                "closed session record remained queryable");
    }

    private static void checkImmutableSurfaceSource() {
        RevisionStamp stamp = new RevisionStamp(99L, 2L, 3L, 4L);
        SurfaceRegionSource source = new SurfaceRegionSource(stamp, 0, 0);
        long before = MapMemoryLeaseManager.snapshot().categories()
                .get(MapMemoryLeaseManager.Category.PENDING_SOURCE).usedBytes();
        source.updatePalette(new MapManager.RegionSourcePalette(2L, 2L,
                new String[] { "minecraft:plains" },
                new String[] { "minecraft:stone" }));
        source.updatePalette(new MapManager.RegionSourcePalette(3L, 2L,
                new String[] { "minecraft:desert" },
                new String[] { "minecraft:sand" }));

        for (int chunkZ = 0; chunkZ < 4; chunkZ++) {
            for (int chunkX = 0; chunkX < 4; chunkX++) {
                long[] pixels = new long[16 * 16];
                int[] tints = new int[16 * 16];
                Arrays.fill(pixels, MapBlockData.packRaw((short) 64,
                        (short) 0, (byte) 0, (byte) 0, (short) 63));
                MapMemoryLeaseManager.Lease memory =
                        MapMemoryLeaseManager.tryAcquire(
                                MapMemoryLeaseManager.Category.PENDING_SOURCE,
                                4_608L, MapRequestLane.FULLSCREEN);
                require(memory != null, "source memory lease was denied");
                require(source.commit(new ChunkSnapshot(chunkX, chunkZ, 10L,
                                pixels, tints, null), memory),
                        "chunk snapshot commit failed");
            }
        }

        SurfaceRegionSource.View view = source.acquireView();
        require((view.dirtyLeafMask() & 1L) == 0L,
                "complete 4x4 chunk leaf remained dirty");
        require("minecraft:plains".equals(view.biomePalette()[0]),
                "same palette revision replaced immutable palette state");
        ChunkSnapshot chunk = view.chunks()[0];
        long authoritative = chunk.packedPixels()[0];
        long[] external = chunk.packedPixels();
        external[0] = MapBlockData.EMPTY_PACKED;
        require(chunk.packedPixels()[0] == authoritative,
                "ChunkSnapshot was mutable through its public accessor");

        source.markChunkDirty(0);
        SurfaceRegionSource.View dirtyView = source.acquireView();
        require((dirtyView.dirtyLeafMask() & 1L) != 0L,
                "chunk invalidation did not escalate to leaf dirty state");
        dirtyView.close();

        source.close();
        long pinned = MapMemoryLeaseManager.snapshot().categories()
                .get(MapMemoryLeaseManager.Category.PENDING_SOURCE).usedBytes();
        require(pinned > before,
                "source storage was released while an immutable view was pinned");
        view.close();
        long after = MapMemoryLeaseManager.snapshot().categories()
                .get(MapMemoryLeaseManager.Category.PENDING_SOURCE).usedBytes();
        require(after == before, "surface source leaked memory leases");
    }

    private static void checkSurfaceBatchPolicy() {
        require(SurfaceBatchPolicy.chooseBatchSize(MapRequestLane.FULLSCREEN,
                        false, false, 16) == 1,
                "cold fullscreen leaf did not stay focused");
        require(SurfaceBatchPolicy.chooseBatchSize(MapRequestLane.MINIMAP,
                        true, true, 3) == 2,
                "warm minimap demand did not expand to 2x2");
        require(SurfaceBatchPolicy.chooseBatchSize(MapRequestLane.FULLSCREEN,
                        true, true, 8) == 4,
                "dense fullscreen demand did not expand to 4x4");
        require(SurfaceBatchPolicy.chooseBatchSize(MapRequestLane.BACKGROUND,
                        false, false, 0) == 1,
                "cold background reconstruction was not bounded to one leaf");
        require(SurfaceBatchPolicy.chooseBatchSize(MapRequestLane.BACKGROUND,
                        true, true, 8) == 4,
                "ready background reconstruction did not expand cooperatively");
        require(!SurfaceBatchPolicy.shouldBuildPage(false, false, true,
                        MapRequestLane.FULLSCREEN),
                "unknown non-focused foreground leaf was admitted");
        require(SurfaceBatchPolicy.shouldBuildPage(false, true, true,
                        MapRequestLane.FULLSCREEN),
                "ready demanded foreground leaf was skipped");
        require(SurfaceBatchPolicy.shouldBuildPage(true, false, false,
                        MapRequestLane.FULLSCREEN),
                "focused leaf was not admitted progressively");
    }

    private static void checkFarZoomDensityPolicy() {
        require(MapLodPolicy.branchLevel(0.75f, 3) == 0,
                "near zoom should remain on exact L0");
        require(MapLodPolicy.branchLevel(0.29f, 3) == 1,
                "0.29x surface view did not select density-correct L1");
        require(MapLodPolicy.branchLevel(0.18f, 3) == 2,
                "0.18x surface view did not select density-correct L2");
        require(MapLodPolicy.branchLevel(0.06f, 3) == 3,
                "far surface view exceeded the L3 quality floor");

        MapRenderScalePolicy.Scales fbo = MapRenderScalePolicy.fullscreenFbo(
                0.29f, 2.0);
        require(Math.abs(fbo.renderPixelsPerBlock() - 0.58f) < 0.0001f,
                "physical FBO geometry scale was not expanded by GUI scale");
        require(Math.abs(fbo.policyPixelsPerBlock() - 0.29f) < 0.0001f,
                "LOD policy scale was incorrectly expanded by GUI scale");
        require(MapLodPolicy.branchLevel(fbo.policyPixelsPerBlock(), 3) == 1,
                "fullscreen FBO shifted a logical L1 view back to L0");

        MapSurfaceDemandPolicy.trim(-1000.0, 1000.0,
                -500.0, 500.0, fbo.policyPixelsPerBlock());
        MapSurfaceDemandPolicy.Snapshot demand = MapSurfaceDemandPolicy.snapshot();
        require(demand.trimmed() && demand.exactActiveWindow() == 4,
                "logical 0.29x demand policy did not enable the L1 far-zoom cap");
        require(MapRenderPlan.PHASE_L1_EXACT_UNDERLAY
                        < MapRenderPlan.branchPhase(1),
                "L1 exact fallback was not placed below the branch");
        require(MapRenderPlan.PHASE_L1_EXACT_UNDERLAY
                        > MapRenderPlan.branchPhase(2),
                "L1 exact fallback did not remain above its coarser ancestor");
    }

    private static void checkRegionLodGraph() {
        RevisionStamp stamp = new RevisionStamp(77L, 4L, 2L, 1L);
        RegionLodGraph graph = new RegionLodGraph(2);
        graph.updateLeaf(stamp, 0, 8, -1, 0,
                10L, true, true, true);

        RegionLodGraph.NodeKey baseKey = new RegionLodGraph.NodeKey(
                stamp.sessionId(), 0, 0, 8, -1);
        RegionLodGraph.NodeKey parentKey = new RegionLodGraph.NodeKey(
                stamp.sessionId(), 0, 1, 1, -1);
        RegionLodGraph.NodeSnapshot base = graph.snapshot(baseKey);
        RegionLodGraph.NodeSnapshot parent = graph.snapshot(parentKey);
        require(base != null && base.state() == RegionLodGraph.State.DIRTY,
                "base region LOD node was not dirtied");
        require(parent != null && parent.dirtyChildMask() != 0L,
                "leaf version did not propagate into the 8x8 parent");

        java.util.List<RegionLodGraph.Lease> coarse = graph.claimCoarseFirst(
                stamp.sessionId(), 0, 1);
        require(!coarse.isEmpty() && coarse.get(0).key().level() == 2,
                "coarse-first claim did not prioritize the highest missing ancestor");
        RegionLodGraph.Lease ancestorLease = coarse.get(0);
        PreparedBranch ancestor = prepared(ancestorLease);
        require(graph.markPrepared(ancestorLease, ancestor),
                "prepared ancestor was rejected");
        require(graph.markPublished(ancestor),
                "prepared ancestor was not published");
        require(graph.hasPublishedReplacement(stamp.sessionId(), 0,
                        8, -1, 1L),
                "published ancestor was not accepted as exact eviction replacement");

        RevisionStamp restyled = new RevisionStamp(stamp.sessionId(),
                stamp.sourceGeneration(), stamp.styleGeneration() + 1L,
                stamp.projectionGeneration());
        long oldTarget = graph.snapshot(parentKey).targetRevision();
        require(graph.invalidate(restyled, 0) > 0,
                "style generation did not invalidate published LOD state");
        require(graph.snapshot(parentKey).targetRevision() > oldTarget,
                "LOD target revision did not advance for style invalidation");

        RegionLodGraph.Lease baseLease = graph.tryBegin(baseKey);
        require(baseLease != null, "base LOD work lease was not issued");
        graph.updateLeaf(stamp, 0, 8, -1, 1,
                11L, true, true, true);
        PreparedBranch stale = prepared(baseLease);
        require(graph.markPrepared(baseLease, stale),
                "stale prepared result should be consumed for state reconciliation");
        require(graph.snapshot(baseKey).state() == RegionLodGraph.State.DIRTY,
                "new leaf dirty state was lost behind an older branch completion");
    }


    private static void checkRegionLodDeriver() {
        RevisionStamp stamp = new RevisionStamp(88L, 3L, 5L, 2L);
        RegionLodGraph graph = new RegionLodGraph(0);
        graph.updateLeaf(stamp, 0, 0, 0, 0,
                7L, true, true, true);
        RegionLodGraph.Lease lease = graph.tryBegin(
                new RegionLodGraph.NodeKey(stamp.sessionId(), 0, 0, 0, 0));
        require(lease != null, "LOD derivation lease was not issued");

        int[] pixels = new int[64 * 64];
        Arrays.fill(pixels, 0xFF332211);
        long[] coverage = new long[64];
        Arrays.fill(coverage, -1L);
        RegionLodDeriver.ChildSnapshot child =
                new RegionLodDeriver.ChildSnapshot(0, 7L, pixels,
                        coverage, coverage);
        PreparedBranch branch = RegionLodDeriver.derive(lease,
                java.util.List.of(child), () -> true);
        require(branch.pixels()[0] == 0xFF332211,
                "region LOD color reduction changed a uniform child");
        require(branch.knownMask() == 1L && branch.completeMask() == 1L,
                "region LOD coverage masks were not preserved");
        require(branch.dirtyMinX() == 0 && branch.dirtyMinY() == 0
                        && branch.dirtyMaxX() == 7 && branch.dirtyMaxY() == 7,
                "region LOD dirty rectangle did not match its child quadrant");
    }

    private static PreparedBranch prepared(RegionLodGraph.Lease lease) {
        return new PreparedBranch(lease.key(), lease.stamp(), lease.revision(),
                64, 64, new int[64 * 64], lease.knownMask(),
                lease.completeMask(), lease.childVersionSums(),
                0, 0, 63, 63);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
