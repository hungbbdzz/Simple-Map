package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Compact live view of the same immutable samples written to metrics.csv. */
public final class MapDebugOverlay {
    private MapDebugOverlay() { }

    public static void render(GuiGraphics graphics) {
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (!recorder.isOverlayVisible()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.player == null) return;
        MapDebugRecorder.DebugSnapshot s = recorder.lastSnapshot();
        if (s.timestampMs() == 0L) return;

        List<Line> lines = new ArrayList<>();
        String state = recorder.isRecording() ? (s.burst() ? "REC BURST" : "REC") : "PAUSED";
        lines.add(new Line("SimpleMap Debug  " + state + "  F8/F9/F10", 0xFFFFFFFF));
        lines.add(new Line(String.format(Locale.ROOT,
                "FPS %d  frame %.1f ms  CPU %.0f%%  alloc %.1f MiB/s",
                s.fps(), s.frameAverageMs(), s.processCpuPercent(),
                s.allocationMiBPerSecond()),
                s.governorPressure() ? 0xFFFFB45C : 0xFFD8D8D8));
        String gpuText = s.gpuUtilizationPercent() < 0.0
                ? "n/a" : String.format(Locale.ROOT, "%.0f%%",
                s.gpuUtilizationPercent());
        lines.add(new Line(String.format(Locale.ROOT,
                "Heap %.0f/%.0f MiB  GC +%d / +%d ms  GPU %s",
                s.heapUsedMiB(), s.heapMaxMiB(), s.gcCountDelta(),
                s.gcTimeMsDelta(), gpuText), 0xFFD8D8D8));
        lines.add(new Line(String.format(Locale.ROOT,
                "%s %s  center %.0f,%.0f  zoom %.3fx",
                s.screen(), s.mapMode(), s.mapCenterX(), s.mapCenterZ(), s.mapScale()),
                0xFFC9E6FF));

        var p = s.pipeline();
        boolean outputBroken = p.exactBuildQueued() > 0L
                && p.exactGpuReady() == 0L;
        lines.add(new Line("Exact q/c/discard/gpu/draw "
                + p.exactBuildQueued() + '/' + p.exactBuildCompleted() + '/'
                + p.exactBuildDiscarded() + '/' + p.exactGpuReady() + '/'
                + p.exactPagesDrawn(), outputBroken ? 0xFFFF7676 : 0xFFD8D8D8));
        var render = s.render();
        lines.add(new Line("Current " + render.projection() + " L"
                + render.hierarchyLevel() + " exact/branch/legacy "
                + render.exactPages() + '/' + render.branchNodes() + '/'
                + render.legacyFallbacks() + " content " + bool(render.hadContent()),
                render.hadContent() ? 0xFFD8D8D8 : 0xFFFF7676));
        var gpu = s.gpu();
        var fullscreenFbo = FullscreenMapFramebufferRenderer.getInstance().snapshot();
        lines.add(new Line(String.format(Locale.ROOT,
                "Branch GPU %.3f ms deny/bootstrap %d/%d  Full FBO draw/reuse/coalesce/fallback %d/%d/%d/%d",
                gpu.branchPredictionNanos() / 1_000_000.0,
                gpu.branchDeniedReservations(), gpu.branchBootstrapAdmissions(),
                fullscreenFbo.redrawFrames(), fullscreenFbo.reuseFrames(),
                fullscreenFbo.coalescedFrames(), fullscreenFbo.fallbackFrames()),
                gpu.branchBootstrapAdmissions() > 0L || render.branchNodes() > 0
                        ? 0xFFB9D7B0 : 0xFFFFB45C));
        var minimapFbo = MinimapFramebufferRenderer.getInstance().snapshot();
        lines.add(new Line(String.format(Locale.ROOT,
                "Minimap FBO redraw/reuse/coalesce/fallback %d/%d/%d/%d  realloc %d",
                minimapFbo.redrawFrames(), minimapFbo.reuseFrames(),
                minimapFbo.coalescedFrames(), minimapFbo.fallbackFrames(),
                minimapFbo.reallocations()),
                minimapFbo.disabled() || minimapFbo.fallbackFrames() > 0L
                        ? 0xFFFFB45C : 0xFFB9D7B0));
        var demand = MapSurfaceDemandPolicy.snapshot();
        lines.add(new Line(String.format(Locale.ROOT,
                "Surface demand area %.0f%% trim L/R/V %.0f/%.0f/%.0f%% exactWindow %d",
                demand.areaRatio() * 100.0, demand.leftFraction() * 100.0,
                demand.rightFraction() * 100.0, demand.verticalFraction() * 100.0,
                demand.exactActiveWindow()), demand.trimmed()
                        ? 0xFFB9D7B0 : 0xFFD8D8D8));
        lines.add(new Line("Source present/absent/defer/fail "
                + p.sourcePresent() + '/' + p.sourceAbsent() + '/'
                + p.sourceDeferred() + '/' + p.sourceFailed()
                + "  noContent " + p.noContentRenderPasses(),
                p.sourceFailed() > 0L ? 0xFFFF7676 : 0xFFD8D8D8));

        var scheduler = s.scheduler();
        lines.add(new Line("Work CPU " + scheduler.cpuActive() + '/'
                + scheduler.cpuQueued() + " cost " + scheduler.cpuTotalCost()
                + "  IO " + scheduler.ioActive() + '/' + scheduler.ioQueued()
                + " cost " + scheduler.ioTotalCost(), 0xFFD8D8D8));

        var texture = s.texture();
        lines.add(new Line("Texture pages " + texture.initializedPages() + '/'
                + texture.pages() + " running/ready " + texture.pendingPages()
                + '/' + texture.completedPendingPages() + " dirty "
                + texture.dirtyPages() + " batches " + texture.pendingBatches(),
                texture.pendingBatches() == 0 && texture.dirtyPages() > 0
                        ? 0xFFFFB45C : 0xFFD8D8D8));

        var source = s.sourceDb();
        boolean falseReady = source.lastPipelineReady() && !source.lastStrictReady();
        lines.add(new Line("SourceDB regions/chunks/dirty/views " + source.regions()
                + '/' + source.residentChunks() + '/' + source.dirtyChunks()
                + '/' + source.pinnedViews() + " plans 1x/expanded "
                + source.focusedBatchPlans() + '/' + source.expandedBatchPlans(),
                0xFFD8D8D8));
        lines.add(new Line("Last batch req/present/missing/dirty "
                + source.lastRequiredChunks() + '/' + source.lastPresentChunks() + '/'
                + source.lastMissingChunks() + '/' + source.lastDirtyChunks()
                + " ready " + bool(source.lastPipelineReady()) + '/'
                + bool(source.lastStrictReady()),
                falseReady || source.lastMissingChunks() > 0 ? 0xFFFF7676 : 0xFFD8D8D8));

        var cave = s.cave();
        lines.add(new Line("Cave pages/init/partial/resident " + cave.pages() + '/'
                + cave.initializedPages() + '/' + cave.partialPages() + '/'
                + cave.residentPages() + " req/pending/done " + cave.requests() + '/'
                + cave.pendingBuilds() + '/' + cave.completedBuilds(),
                cave.pendingBuilds() > 0 && cave.completedBuilds() > 0
                        ? 0xFFFFB45C : 0xFFD8D8D8));

        var exact = s.exactStates();
        long requested = exact.pagesByState().getOrDefault(ExactPageState.REQUESTED, 0L);
        long building = exact.pagesByState().getOrDefault(ExactPageState.BUILDING, 0L);
        long upload = exact.pagesByState().getOrDefault(ExactPageState.UPLOAD_QUEUED, 0L);
        long gpuReady = exact.pagesByState().getOrDefault(ExactPageState.GPU_READY, 0L);
        long evicted = exact.pagesByState().getOrDefault(ExactPageState.GPU_EVICTED, 0L);
        long failed = exact.pagesByState().getOrDefault(ExactPageState.FAILED_RETRYABLE, 0L);
        long stale = exact.pagesByState().getOrDefault(ExactPageState.STALE_GENERATION, 0L);
        lines.add(new Line("Page state req/build/upload/gpu/evict/fail/stale "
                + requested + '/' + building + '/' + upload + '/' + gpuReady + '/'
                + evicted + '/' + failed + '/' + stale + " old>5s "
                + exact.requestedOlderThan5s() + '/'
                + exact.buildingOlderThan5s() + '/'
                + exact.cpuReadyOlderThan5s(),
                failed > 0 || stale > 0 || exact.buildingOlderThan5s() > 0
                        ? 0xFFFF7676 : 0xFFD8D8D8));

        var graph = s.workGraph();
        lines.add(new Line("Graph regions D/R/P/ready/pub " + graph.regions() + ' '
                + graph.dirtyStages() + '/' + graph.runningStages() + '/'
                + graph.preparedStages() + '/' + graph.readyStages() + '/'
                + graph.publishedStages(), 0xFFD8D8D8));

        var lod = s.lodGraph();
        lines.add(new Line("M4 graph nodes/L0/coarse D/R/P/pub " + lod.nodes()
                + '/' + lod.level0Nodes() + '/' + lod.coarseNodes() + ' '
                + lod.dirty() + '/' + lod.running() + '/' + lod.prepared()
                + '/' + lod.published(), 0xFFD8D8D8));

        var architecture = s.architecture();
        if (architecture != null) {
            var pageTable = architecture.pageTable();
            var uploads = architecture.uploads();
            lines.add(new Line("M5 page table entries/storage/gen/swap/mismatch "
                    + pageTable.entries() + '/' + pageTable.storages() + '/'
                    + pageTable.generation() + '/' + pageTable.swaps() + '/'
                    + pageTable.generationMismatches(),
                    pageTable.generationMismatches() > 0L
                            ? 0xFFFFB45C : 0xFFD8D8D8));
            lines.add(new Line("M9 upload q/run/commit/reject/stale "
                    + uploads.queued() + '/' + uploads.inFlight() + '/'
                    + uploads.committed() + '/' + uploads.rejected() + '/'
                    + uploads.stale() + " staging "
                    + String.format(Locale.ROOT, "%.1f MiB",
                            uploads.stagingBytes() / 1048576.0),
                    uploads.rejected() > 0L || uploads.stale() > 0L
                            ? 0xFFFFB45C : 0xFFD8D8D8));
            var archive = architecture.caveArchive();
            var persistence = architecture.persistence();
            lines.add(new Line("M7/M10 cave tiles/MiB " + archive.tiles() + '/'
                    + String.format(Locale.ROOT, "%.1f", archive.bytes() / 1048576.0)
                    + " persist q/ok/fail " + persistence.queued() + '/'
                    + (persistence.surfaceCommitted() + persistence.caveCommitted())
                    + '/' + persistence.failures(),
                    persistence.failures() > 0L ? 0xFFFF7676 : 0xFFD8D8D8));
        }

        var residency = s.residency();
        lines.add(new Line(String.format(Locale.ROOT,
                "Residency %d pinned %d  %.1f/%.1f MiB  evict/fail %d/%d",
                residency.residentEntries(), residency.pinnedEntries(),
                residency.estimatedBytes() / 1048576.0,
                residency.budgetBytes() / 1048576.0,
                residency.globalEvictions(), residency.budgetFailures()),
                residency.budgetFailures() > 0L ? 0xFFFF7676 : 0xFFD8D8D8));

        Path directory = recorder.activeDirectory();
        if (directory != null) {
            lines.add(new Line("File: simplemap-debug/" + directory.getFileName(),
                    0xFFB9D7B0));
        }

        int lineHeight = minecraft.font.lineHeight + 1;
        int maxWidth = 0;
        for (Line line : lines) maxWidth = Math.max(maxWidth, minecraft.font.width(line.text()));
        int right = graphics.guiWidth() - 5;
        int top = 5;
        int left = Math.max(4, right - maxWidth - 8);
        int bottom = top + lines.size() * lineHeight + 6;
        graphics.fill(left, top, right, bottom, 0xC0101010);
        graphics.renderOutline(left, top, right - left, bottom - top, 0x806A6A6A);
        int y = top + 3;
        for (Line line : lines) {
            graphics.drawString(minecraft.font, line.text(), right - 4
                    - minecraft.font.width(line.text()), y, line.color(), false);
            y += lineHeight;
        }
    }

    private static String bool(boolean value) {
        return value ? "Y" : "N";
    }

    private record Line(String text, int color) { }
}
