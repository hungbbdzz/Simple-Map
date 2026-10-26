package com.velorise.simplemap.client;
import com.velorise.simplemap.client.RegionDataStore.SurfaceChunkCoverage;
import com.velorise.simplemap.client.MapViewportCoordinator.MapViewLoadPlanner;
import com.velorise.simplemap.client.gpu.GpuPageTable.TileKey;
import com.velorise.simplemap.client.gpu.GpuPageTable.PageTableEntry;
import com.velorise.simplemap.client.gpu.GpuPageTable.MapUploadEngine.UploadCommand;
import com.velorise.simplemap.client.MapViewportCoordinator.MapSurfaceDemandPolicy;
import com.velorise.simplemap.client.MapViewportCoordinator.MapViewportDemandPolicy;
import com.velorise.simplemap.client.color.*;

import com.velorise.simplemap.client.gpu.GpuPageTable;
import com.velorise.simplemap.client.gpu.GpuPageTable.MapUploadEngine;
import com.velorise.simplemap.client.MinimapRenderer.FixedTileRing;
import com.velorise.simplemap.client.RegionDataStore.RegionContainerV2;
import com.velorise.simplemap.client.renderer.RetainedFullscreenFramePolicy.LodSelector;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-light invariants for M5 through M10. */
public final class ArchitectureStackCheck {
    private ArchitectureStackCheck() { }

    public static void main(String[] args) throws Exception {
        pageTableIsAtomic();
        uploadEngineIsBounded();
        minimapRingIsFixed();
        regionContainerRecoversFromCorruptTail();
        architectureBoundariesAreDeterministic();
        viewportDemandIsCenterFirst();
        chunkGranularSurfacePublicationIsCoherent();
        surfaceVoidCoverageIsIndependent();
        System.out.println("Simple Map M5-M11 + chunk-granular architecture checks passed");
    }

    private static void pageTableIsAtomic() {
        GpuPageTable table = new GpuPageTable();
        TileKey key = new TileKey(1L, 0, 0, 2, 3,
                TileKey.VARIANT_SURFACE_EXACT);
        PageTableEntry first = new PageTableEntry(1, 4, 10L, 1L,
                0, PageTableEntry.FLAG_RESIDENT, 0, 0, 64, 1024);
        table.stage(key, first);
        require(table.resolve(key) == null, "back table leaked before swap");
        table.swapAtFrameBoundary();
        require(table.resolve(key).slot() == 4, "front table did not publish");
        PageTableEntry second = new PageTableEntry(1, 8, 10L, 2L,
                0, PageTableEntry.FLAG_RESIDENT, 64, 0, 64, 1024);
        table.stage(key, second);
        require(table.resolve(key).slot() == 4, "front changed before boundary");
        var retired = table.swapAtFrameBoundary();
        require(table.resolve(key).slot() == 8, "replacement not visible");
        require(retired.size() == 1 && retired.get(0).slot() == 4,
                "old slot retired before/after wrong boundary");
    }

    private static void uploadEngineIsBounded() {
        MapUploadEngine engine = MapUploadEngine.getInstance();
        engine.clear();
        int[] committed = {0};
        UploadCommand command = new UploadCommand(null, null,
                MapRequestLane.MINIMAP, 128, 1L, null,
                () -> committed[0]++, null, null);
        require(engine.submit(command), "upload was not admitted");
        engine.drain(System.nanoTime() + 10_000_000L, 1024);
        require(committed[0] == 1, "upload did not execute");
        require(engine.summary().queued() == 0, "upload queue leaked");
    }

    private static void minimapRingIsFixed() {
        FixedTileRing ring = new FixedTileRing(13);
        ring.recenter(10, -4);
        require(ring.snapshotKeys().length == 169, "ring footprint changed");
        long generation = ring.generation();
        require(!ring.recenter(10, -4), "same center rebuilt ring");
        require(ring.generation() == generation, "ring generation changed");
    }

    private static void regionContainerRecoversFromCorruptTail() throws Exception {
        Path directory = Files.createTempDirectory("simplemap-smr2-check");
        Path file = directory.resolve("r.0.0.smr2");
        RegionContainerV2.Header header = new RegionContainerV2.Header(
                77L, 0, 0, 1);
        RegionContainerV2.RecordKey key = new RegionContainerV2.RecordKey(
                RegionContainerV2.RecordType.SURFACE_SOURCE, 0);
        RegionContainerV2.append(file, header,
                new RegionContainerV2.Record(key, 1L, 1L,
                        new byte[] {1, 2, 3, 4}));
        Files.write(file, new byte[] {9, 8, 7},
                java.nio.file.StandardOpenOption.APPEND);
        RegionContainerV2.ReadResult result = RegionContainerV2.read(file);
        require(result.latest().containsKey(key),
                "valid record lost after corrupt tail");
        require(result.truncatedOrCorruptTail(),
                "corrupt tail was not detected");
        RegionContainerV2.append(file, header,
                new RegionContainerV2.Record(key, 2L, 1L,
                        new byte[] {5, 6, 7, 8}));
        RegionContainerV2.ReadResult recovered = RegionContainerV2.read(file);
        require(!recovered.truncatedOrCorruptTail(),
                "append did not truncate corrupt tail");
        require(recovered.latest().get(key).sourceRevision() == 2L,
                "record appended after recovery was not visible");
        RegionContainerV2.compact(file);
        require(!RegionContainerV2.read(file).truncatedOrCorruptTail(),
                "compaction did not preserve recovered file");
    }


    private static void architectureBoundariesAreDeterministic() {
        require(LodSelector.surfaceLevel(0.75f) == 0, "near zoom did not select L0");
        require(LodSelector.surfaceLevel(0.29f) == 1, "0.29x did not select L1");
        require(LodSelector.surfaceLevel(0.18f) == 2, "0.18x did not select L2");
        require(LodSelector.surfaceLevel(0.06f) == 3, "far zoom did not cap at L3");
        MapSurfaceDemandPolicy.Bounds bounds = MapSurfaceDemandPolicy.trim(
                0.0, 1000.0, 0.0, 1000.0, 0.29f);
        require(bounds.maxX() - bounds.minX() == 1000.0,
                "zoom policy clipped part of the visible viewport");
        require(!MapSurfaceDemandPolicy.snapshot().trimmed(),
                "fullscreen demand should be controlled by LOD, not edge trimming");
    }

    private static void viewportDemandIsCenterFirst() {
        MapViewportDemandPolicy.Bounds trimmed =
                MapViewportDemandPolicy.trimEdgeSlivers(
                        10.0, 502.0, -502.0, -10.0,
                        MapRequestLane.FULLSCREEN);
        require(trimmed.minX() == 10.0 && trimmed.maxX() == 502.0
                        && trimmed.minZ() == -502.0 && trimmed.maxZ() == -10.0,
                "fullscreen viewport was locked to 64-block page boundaries");
        MapViewportDemandPolicy.Bounds minimap =
                MapViewportDemandPolicy.trimEdgeSlivers(
                        10.0, 502.0, 10.0, 502.0,
                        MapRequestLane.MINIMAP);
        require(minimap.minX() == 10.0 && minimap.maxX() == 502.0,
                "minimap demand was incorrectly trimmed");

        MapViewLoadPlanner.State planner = new MapViewLoadPlanner.State();
        long[] pages =
                new long[MapViewLoadPlanner.FULLSCREEN_SLICE_SIZE];
        planner.configure("overworld", 0, 4, 0, 3);
        planner.configure("overworld", 1, 5, 0, 3);
        int count = planner.fillCurrentFullscreenSlice(pages);
        require(planner.retainedOverlap() && count == 20,
                "continuous pan did not retain overlap");
        require(MapViewLoadPlanner.packedX(pages[0]) == 3
                        && MapViewLoadPlanner.packedZ(pages[0]) == 1,
                "fullscreen demand did not begin at viewport centre");
        for (int index = 0; index < 9; index++) {
            require(Math.max(Math.abs(MapViewLoadPlanner.packedX(pages[index]) - 3),
                    Math.abs(MapViewLoadPlanner.packedZ(pages[index]) - 1)) <= 1,
                    "first fullscreen ring escaped the inspected area");
        }
    }

    private static void chunkGranularSurfacePublicationIsCoherent() {
        require(MapPageLayout.PAGE_SIZE == 64
                        && MapPageLayout.SUBTILE_SIZE == 16
                        && MapPageLayout.SUBTILES_PER_PAGE == 4,
                "surface leaf/chunk geometry changed unexpectedly");

        long[] rows = new long[MapPageLayout.PAGE_SIZE];
        long firstChunk = 0xFFFFL;
        for (int z = 0; z < 16; z++) rows[z] = firstChunk;
        require(MapPageLayout.completeSubtileMask(rows) == 0x0001,
                "one complete 16x16 chunk was not independently publishable");

        rows[15] &= ~(1L << 15);
        require(MapPageLayout.completeSubtileMask(rows) == 0,
                "partial chunk was incorrectly accepted as coherent");

        java.util.Arrays.fill(rows, -1L);
        require(MapPageLayout.completeSubtileMask(rows)
                        == MapPageLayout.FULL_SUBTILE_MASK,
                "complete 64x64 page did not expose all sixteen chunks");
    }

    private static void surfaceVoidCoverageIsIndependent() {
        int regionSize = SurfaceChunkCoverage.CHUNKS_PER_AXIS * 16;
        long[] pixels = new long[regionSize * regionSize];
        java.util.Arrays.fill(pixels, MapBlockData.EMPTY_PACKED);
        long[] legacy = SurfaceChunkCoverage.inferLegacy(pixels, regionSize,
                MapBlockData.EMPTY_PACKED);
        require(!SurfaceChunkCoverage.isComplete(legacy, 0),
                "unobserved legacy void was incorrectly marked complete");
        require(SurfaceChunkCoverage.markComplete(legacy, 0),
                "completed void scan did not publish coverage");
        require(SurfaceChunkCoverage.isComplete(legacy, 0),
                "known-void coverage still depended on terrain material");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
