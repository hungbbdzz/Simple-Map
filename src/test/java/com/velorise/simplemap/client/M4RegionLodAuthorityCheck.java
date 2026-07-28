package com.velorise.simplemap.client;

import com.velorise.simplemap.client.lod.PreparedBranch;
import com.velorise.simplemap.client.lod.RegionLodDeriver;
import com.velorise.simplemap.client.lod.RegionLodGraph;
import com.velorise.simplemap.client.pipeline.RevisionStamp;

import java.util.Arrays;
import java.util.List;

/** Standalone core invariants for the V17.8 M4 region hierarchy. */
public final class M4RegionLodAuthorityCheck {
    private M4RegionLodAuthorityCheck() { }

    public static void main(String[] args) {
        require(MapRegionLodPolicy.directProjectionEnabled(0.29f),
                "far-zoom direct projection policy is disabled");
        require(!MapRegionLodPolicy.directProjectionEnabled(0.75f),
                "close zoom should not capture full regions for coarse LOD");
        require(MapRegionLodPolicy.targetLevel(0.29f) == 0
                        && MapRegionLodPolicy.targetLevel(0.03f) == 1,
                "region hierarchy density policy changed unexpectedly");
        require(MapRegionLodPolicy.worldSize(1) == 4096,
                "8x8 region hierarchy world span is incorrect");

        RevisionStamp stamp = new RevisionStamp(91L, 4L, 2L, 1L);
        RegionLodGraph graph = new RegionLodGraph(2);
        RegionLodGraph.NodeKey base = graph.requestRegion(stamp, 0,
                -3, 5, 12L);
        require(base != null, "direct source region was not seeded");
        RegionLodGraph.NodeSnapshot seeded = graph.snapshot(base);
        require(seeded != null && seeded.state() == RegionLodGraph.State.DIRTY,
                "seeded source region is not durable dirty state");
        require(seeded.dirtyChildMask() == -1L,
                "direct source projection did not dirty all leaf slots");

        RegionLodGraph.Lease lease = graph.tryBegin(base);
        require(lease != null, "direct source region lease missing");
        int[] pixels = new int[64 * 64];
        Arrays.fill(pixels, 0xFF335577);
        long[] rows = new long[64];
        Arrays.fill(rows, -1L);
        PreparedBranch prepared = new PreparedBranch(base, stamp,
                lease.revision(), 64, 64, pixels, -1L, -1L,
                rows, rows, lease.childVersionSums(), 0, 0, 63, 63);
        require(graph.markPrepared(lease, prepared),
                "direct source output was rejected");
        require(graph.markPublished(prepared),
                "direct source output was not published");
        require(graph.hasPublishedReplacement(stamp.sessionId(), 0,
                        -3, 5, 12L),
                "published region did not protect exact eviction");

        // Parent state must be reconstructed from durable child propagation.
        RegionLodGraph.NodeKey parentKey = new RegionLodGraph.NodeKey(
                stamp.sessionId(), 0, 1, Math.floorDiv(-3, 8),
                Math.floorDiv(5, 8));
        RegionLodGraph.Lease parentLease = graph.tryBegin(parentKey);
        require(parentLease != null, "8x8 parent was not dirtied");
        int childIndex = Math.floorMod(5, 8) * 8 + Math.floorMod(-3, 8);
        RegionLodDeriver.ChildSnapshot child =
                new RegionLodDeriver.ChildSnapshot(childIndex,
                        parentLease.childVersionSums()[childIndex],
                        pixels, rows, rows);
        PreparedBranch parent = RegionLodDeriver.derive(parentLease,
                List.of(child), () -> true);
        require(parent.knownMask() == (1L << childIndex),
                "parent coverage did not preserve its direct child");
        require(graph.markPrepared(parentLease, parent)
                        && graph.markPublished(parent),
                "parent publication failed");

        // A queue/admission failure returns the lease to DIRTY; no work is lost.
        graph.markLeafDirty(stamp, 0, -3, 5, 2, 13L);
        RegionLodGraph.Lease retry = graph.tryBegin(base);
        require(retry != null, "new dirty revision was not admitted");
        graph.defer(retry);
        require(graph.snapshot(base).state() == RegionLodGraph.State.DIRTY,
                "deferred M4 work lost durable dirty state");

        // Late exact output must not roll the source watermark backwards.
        long before = graph.snapshot(base).aggregateVersionSum();
        graph.updateLeaf(stamp, 0, -3, 5, 2,
                1L, true, true, true);
        require(graph.snapshot(base).aggregateVersionSum() == before,
                "stale exact callback rolled region version backwards");
        System.out.println("M4_REGION_LOD_AUTHORITY_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
