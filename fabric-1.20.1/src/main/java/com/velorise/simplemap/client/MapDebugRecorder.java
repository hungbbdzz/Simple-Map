package com.velorise.simplemap.client;
import com.velorise.simplemap.client.cave.CaveTextureManager.CaveMapManager;
import com.velorise.simplemap.client.MapManager.MapLightManager;
import com.velorise.simplemap.client.MapManager.MapLightManager.LightRegion;
import com.velorise.simplemap.client.MapManager.MapConfig;
import com.velorise.simplemap.client.MapDebugRecorder.MapPipelineTelemetry;
import com.velorise.simplemap.client.MapDebugRecorder.MapPipelineTelemetry.MapPipelineStage;
import com.velorise.simplemap.client.MapRenderer.MapDrawResult;
import com.velorise.simplemap.client.cave.CaveMode.CavePageHoleReason;
import com.velorise.simplemap.client.MapViewportCoordinator.MapSurfaceDemandPolicy;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import com.velorise.simplemap.client.MapMemoryBudgetPolicy.MapGpuBudgetController;
import com.velorise.simplemap.client.MapMemoryBudgetPolicy.MapResidencyManager;
import com.velorise.simplemap.client.MapManager.MapArchitectureCoordinator;
import com.velorise.simplemap.client.cave.*;
import com.velorise.simplemap.client.renderer.*;

import com.velorise.simplemap.client.gui.MapScreen;
import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph;
import com.velorise.simplemap.client.RegionSurfaceLodService.RegionLodGraph;
import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph.RevisionStamp;
import com.velorise.simplemap.client.session.MapSessionManager.MapSession;
import com.velorise.simplemap.client.session.MapSessionManager;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Low-overhead diagnostic recorder for alpha builds.
 *
 * <p>Two streams are intentionally separated:</p>
 * <ul>
 *   <li>metrics.csv: fixed 1 Hz samples (250 ms during a marked reproduction).</li>
 *   <li>events.jsonl: sparse lifecycle/anomaly events with flexible payload text.</li>
 * </ul>
 *
 * <p>All disk I/O and expensive map-state scans are performed on one low-priority
 * daemon writer thread. The client thread captures only the current viewport and
 * allocation-light counters, preventing the 1 Hz recorder from becoming a visible
 * frame-time pulse.</p>
 */
public final class MapDebugRecorder {

    public enum ExactPageState {
        ABSENT,
        REQUESTED,
        BUILDING,
        CPU_READY,
        CPU_PARTIAL,
        KNOWN_EMPTY,
        UPLOAD_QUEUED,
        GPU_READY,
        GPU_EVICTED,
        FAILED_RETRYABLE,
        STALE_GENERATION
    }

    public static final class ExactPageStateTracker {

    private static final ExactPageStateTracker INSTANCE = new ExactPageStateTracker();
    private static final int MAX_ENTRIES = 16_384;

    private final Map<String, Entry> entries = new LinkedHashMap<>(512, 0.75f, true);
    private final EnumMap<ExactPageState, AtomicLong> transitions =
            new EnumMap<>(ExactPageState.class);

    private ExactPageStateTracker() {
        for (ExactPageState state : ExactPageState.values()) {
            transitions.put(state, new AtomicLong());
        }
    }

    public static ExactPageStateTracker getInstance() {
        return INSTANCE;
    }

    public synchronized void transition(String key, ExactPageState state,
            MapRequestLane lane, long revision) {
        if (!DEBUG_ENABLED || key == null || state == null) return;
        Entry previous = entries.get(key);
        long now = System.currentTimeMillis();
        MapRequestLane effectiveLane = lane == null ? MapRequestLane.BACKGROUND : lane;
        if (previous != null && previous.revision > revision
                && state != ExactPageState.STALE_GENERATION) {
            return;
        }
        if (previous != null && previous.state == state
                && previous.lane == effectiveLane
                && previous.revision == revision) {
            return;
        }
        entries.put(key, new Entry(state, effectiveLane, revision, now));
        transitions.get(state).incrementAndGet();
        trim();
    }

    public synchronized ExactPageState state(String key) {
        Entry entry = entries.get(key);
        return entry == null ? ExactPageState.ABSENT : entry.state;
    }

    /** Removes terminal diagnostic state when the owning page cache entry retires. */
    public synchronized void remove(String key) {
        if (key != null) entries.remove(key);
    }

    /**
     * Retires a transient diagnostic state only when it still matches the expected
     * lifecycle stage. A viewport demand may expire after a page has already become
     * CPU/GPU ready; that newer authoritative state must not be erased by cleanup.
     */
    public synchronized void removeIfState(String key, ExactPageState expected) {
        if (key == null || expected == null) return;
        Entry entry = entries.get(key);
        if (entry != null && entry.state == expected) entries.remove(key);
    }

    public synchronized Snapshot snapshot() {
        EnumMap<ExactPageState, Long> counts = new EnumMap<>(ExactPageState.class);
        for (ExactPageState state : ExactPageState.values()) counts.put(state, 0L);
        long now = System.currentTimeMillis();
        long oldestAgeMs = 0L;
        long requestedOlderThan5s = 0L;
        long buildingOlderThan5s = 0L;
        long cpuReadyOlderThan5s = 0L;
        for (Entry entry : entries.values()) {
            counts.put(entry.state, counts.get(entry.state) + 1L);
            long age = Math.max(0L, now - entry.updatedAtMs);
            oldestAgeMs = Math.max(oldestAgeMs, age);
            if (age < 5_000L) continue;
            if (entry.state == ExactPageState.REQUESTED) requestedOlderThan5s++;
            else if (entry.state == ExactPageState.BUILDING) buildingOlderThan5s++;
            else if (entry.state == ExactPageState.CPU_READY) cpuReadyOlderThan5s++;
        }
        EnumMap<ExactPageState, Long> transitionCounts = new EnumMap<>(ExactPageState.class);
        for (ExactPageState state : ExactPageState.values()) {
            transitionCounts.put(state, transitions.get(state).get());
        }
        return new Snapshot(entries.size(), counts, transitionCounts,
                oldestAgeMs, requestedOlderThan5s, buildingOlderThan5s,
                cpuReadyOlderThan5s);
    }

    public synchronized void clear() {
        entries.clear();
    }

    /** Resets diagnostic state at the start of a new map session. */
    public synchronized void reset() {
        entries.clear();
        for (AtomicLong counter : transitions.values()) counter.set(0L);
    }

    public synchronized void clearPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            clear();
            return;
        }
        entries.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private void trim() {
        while (entries.size() > MAX_ENTRIES) {
            String eldest = entries.keySet().iterator().next();
            entries.remove(eldest);
        }
    }

    private record Entry(ExactPageState state, MapRequestLane lane,
            long revision, long updatedAtMs) {
    }

    public record Snapshot(int trackedPages,
            Map<ExactPageState, Long> pagesByState,
            Map<ExactPageState, Long> transitionsByState,
            long oldestStateAgeMs, long requestedOlderThan5s,
            long buildingOlderThan5s, long cpuReadyOlderThan5s) {
    }
}


    // PASS150 guard marker retained for cumulative source-invariant checks:
    // PASS150_cave_central_4x4
    // predecessor guard marker: PASS156_ordered_frontier_bounded_minimap
    // predecessor guard marker: PASS157_bounded_lod_source_window
    // PASS158_cave_exclusive_foreground cumulative guard marker.
    // predecessor guard marker: PASS159_layer_projection_lifecycle
    // predecessor guard marker: PASS160_persistent_cave_writer_stale_authority_fix
    // predecessor guard marker: PASS161_settled_partial_frontier
    // predecessor guard marker: PASS162_source_presentation_decoupled
    // predecessor guard marker: PASS163_cache_empty_source_authority
    /* predecessor marker: PASS164_frontier_backlog_shared_cave_product */
    // predecessor guard marker: PASS165_self_progressing_projection_frontier_watchdog
    // predecessor guard marker: PASS166_ordered_preupload_frontier_repair
    // predecessor guard marker: PASS167_stable_product_revision_persistent_writer
    // predecessor guard marker: PASS168_ordered_writer_no_global_publication_mutex
    // predecessor guard marker: PASS169_single_generation_ack_on_ingest
    /**
     * Diagnostic capture and F8/F9/F10 debug controls are disabled by default in
     * release builds. Pass {@code -Dsimplemap.debug=true} on the JVM command line
     * to enable telemetry recording and the in-game debug overlay.
     */
    public static final boolean DEBUG_ENABLED = Boolean.getBoolean("simplemap.debug");
    private static final String BUILD_PROVENANCE =
            "RELEASE_2_0_2";
    private static final Logger LOGGER = LogManager.getLogger();
    private static final MapDebugRecorder INSTANCE = new MapDebugRecorder();
    private static final DateTimeFormatter DIRECTORY_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());
    private static final long NORMAL_INTERVAL_NANOS = 1_000_000_000L;
    private static final long BURST_INTERVAL_NANOS = 250_000_000L;
    private static final long BURST_DURATION_NANOS = 20_000_000_000L;
    private static final int WRITE_QUEUE_CAPACITY = 16_384;
    private static final long FLUSH_INTERVAL_NANOS = 3_000_000_000L;

    private final BlockingQueue<WriteItem> writeQueue =
            new ArrayBlockingQueue<>(WRITE_QUEUE_CAPACITY);
    private final AtomicLong droppedWriteItems = new AtomicLong();
    private final AtomicBoolean writerStarted = new AtomicBoolean();
    private final Map<String, Long> rateLimitNanos = new ConcurrentHashMap<>();
    private final Runtime runtime = Runtime.getRuntime();
    private final com.sun.management.OperatingSystemMXBean osBean;
    private final com.sun.management.ThreadMXBean threadBean;

    private volatile boolean recording = DEBUG_ENABLED;
    private volatile boolean overlayVisible = DEBUG_ENABLED;
    private volatile long burstUntilNanos;
    private volatile long lastSampleNanos;
    private volatile long sessionStartNanos;
    private volatile long activeSessionId = Long.MIN_VALUE;
    private volatile Path activeDirectory;
    private volatile DebugSnapshot lastSnapshot = DebugSnapshot.empty();
    /** Heavy diagnostic scans are refreshed by the low-priority writer thread. */
    private volatile DeepSnapshots deepSnapshots;

    private BufferedWriter metricsWriter;
    private BufferedWriter eventsWriter;
    private long lastFlushNanos;
    private long lastAllocatedBytes = -1L;
    private long lastAllocationSampleNanos;
    private long lastGcCount;
    private long lastGcTimeMs;

    private Method serverAverageTickMethod;
    private Class<?> serverAverageTickOwner;
    private Method gpuUtilizationMethod;
    private boolean gpuMethodResolved;

    private MapDebugRecorder() {
        java.lang.management.OperatingSystemMXBean baseOs =
                ManagementFactory.getOperatingSystemMXBean();
        osBean = baseOs instanceof com.sun.management.OperatingSystemMXBean sun
                ? sun : null;
        java.lang.management.ThreadMXBean baseThread =
                ManagementFactory.getThreadMXBean();
        threadBean = baseThread instanceof com.sun.management.ThreadMXBean sun
                ? sun : null;
        if (DEBUG_ENABLED && threadBean != null && threadBean.isThreadAllocatedMemorySupported()
                && !threadBean.isThreadAllocatedMemoryEnabled()) {
            try {
                threadBean.setThreadAllocatedMemoryEnabled(true);
            } catch (SecurityException ignored) {
            }
        }
    }

    public static MapDebugRecorder getInstance() {
        return INSTANCE;
    }

    public boolean isRecording() {
        return recording;
    }

    public boolean isOverlayVisible() {
        return overlayVisible;
    }

    public Path activeDirectory() {
        return activeDirectory;
    }

    public DebugSnapshot lastSnapshot() {
        return lastSnapshot;
    }

    public void toggleOverlay() {
        if (!DEBUG_ENABLED) return;
        overlayVisible = !overlayVisible;
        if (overlayVisible && lastSnapshot.timestampMs() == 0L) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.level != null && mc.player != null) {
                lastSnapshot = capture(mc, System.nanoTime(), NORMAL_INTERVAL_NANOS);
            }
        }
        event("OVERLAY_TOGGLED", "visible=" + overlayVisible);
    }

    public void toggleRecording(Minecraft minecraft) {
        if (!DEBUG_ENABLED) return;
        boolean next = !recording;
        if (!next) {
            event("RECORDING_PAUSED", "source=keybind");
            offer(new WriteItem(Stream.FLUSH, ""));
            recording = false;
            return;
        }
        recording = true;
        if (minecraft != null && minecraft.level != null) ensureSession(minecraft);
        event("RECORDING_STARTED", "source=keybind");
    }

    public void markIssue(Minecraft minecraft) {
        if (!DEBUG_ENABLED) return;
        burstUntilNanos = System.nanoTime() + BURST_DURATION_NANOS;
        ensureSession(minecraft);
        event("USER_MARKER", "burst_ms=20000 screen="
                + screenName(minecraft == null ? null : minecraft.screen));
        offer(new WriteItem(Stream.FLUSH, ""));
    }

    /** Called once per client tick after map scheduling has updated telemetry. */
    public void tick(Minecraft minecraft) {
        if (!DEBUG_ENABLED) return;
        if (minecraft == null || minecraft.level == null || minecraft.player == null) return;
        if (!recording && !overlayVisible) return;
        if (recording) ensureSession(minecraft);
        long now = System.nanoTime();
        long interval = (recording && now < burstUntilNanos) ? BURST_INTERVAL_NANOS : NORMAL_INTERVAL_NANOS;
        if (lastSampleNanos != 0L && now - lastSampleNanos < interval) return;
        long previous = lastSampleNanos;
        lastSampleNanos = now;
        DebugSnapshot snapshot = capture(minecraft, now,
                previous == 0L ? interval : now - previous);
        lastSnapshot = snapshot;
        if (recording) {
            offer(new WriteItem(Stream.METRICS, snapshot.toCsvLine()));
        }
    }

    public void onWorldLeave() {
        if (!DEBUG_ENABLED) return;
        event("WORLD_LEAVE", "session_id=" + activeSessionId);
        activeSessionId = Long.MIN_VALUE;
        activeDirectory = null;
        lastSampleNanos = 0L;
        burstUntilNanos = 0L;
        rateLimitNanos.clear();
        offer(new WriteItem(Stream.ROTATE, ""));
    }

    public void event(String type, String detail) {
        if (!DEBUG_ENABLED || !recording || activeDirectory == null) return;
        String safeType = type == null || type.isBlank() ? "UNKNOWN" : type;
        offer(new WriteItem(Stream.EVENTS, jsonEvent(safeType, detail)));
    }

    /**
     * Reserves one sparse-event slot without constructing a detail message on a hot path.
     * Callers should only format the event payload when this returns true.
     */
    public boolean shouldEmitEvent(String rateKey, long minimumIntervalMillis) {
        if (!DEBUG_ENABLED || !recording || activeDirectory == null) return false;
        long now = System.nanoTime();
        long minimumNanos = Math.max(1L, minimumIntervalMillis) * 1_000_000L;
        Long previous = rateLimitNanos.put(String.valueOf(rateKey), now);
        return previous == null || now - previous >= minimumNanos;
    }

    private void ensureSession(Minecraft minecraft) {
        if (!DEBUG_ENABLED) return;
        MapSession session = MapSessionManager.getInstance().active();
        if (session == null || session.sessionId() == activeSessionId) return;
        activeSessionId = session.sessionId();
        // Telemetry counters are diagnostic only. Reset them so every capture folder
        // starts at a meaningful zero baseline instead of inheriting the prior world.
        MapPipelineTelemetry.getInstance().reset();
        ExactPageStateTracker.getInstance().reset();
        sessionStartNanos = System.nanoTime();
        lastSampleNanos = 0L;
        lastAllocatedBytes = totalAllocatedBytes();
        lastAllocationSampleNanos = sessionStartNanos;
        long[] gc = gcTotals();
        lastGcCount = gc[0];
        lastGcTimeMs = gc[1];
        String timestamp = DIRECTORY_TIME.format(Instant.now());
        String identity = sanitize(session.worldIdentity());
        String dimension = sanitize(session.dimensionIdentity());
        Path root = minecraft.gameDirectory.toPath().resolve("simplemap-debug");
        Path directory = root.resolve(timestamp + "_s" + session.sessionId()
                + '_' + identity + '_' + dimension);
        try {
            Files.createDirectories(directory);
            Files.writeString(root.resolve("latest.txt"),
                    directory.toAbsolutePath().toString() + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            startWriterIfNeeded();
            // OPEN must be queued before activeDirectory becomes visible to worker
            // callbacks, otherwise an early event could race ahead of writer rotation.
            offer(new WriteItem(Stream.OPEN, directory.toString()));
            activeDirectory = directory;
            offer(new WriteItem(Stream.METRICS_HEADER, DebugSnapshot.csvHeader()));
            offer(new WriteItem(Stream.EVENTS, jsonEvent("WORLD_SESSION_OPEN",
                    "world=" + session.worldIdentity() + " dimension="
                            + session.dimensionIdentity()
                            + " build=" + BUILD_PROVENANCE
                            + " cave_cache_epoch=" + CaveMapManager.EPOCH)));
            LOGGER.info("SimpleMap debug recorder writing to {}", directory.toAbsolutePath());
        } catch (IOException exception) {
            recording = false;
            LOGGER.error("Could not create SimpleMap debug directory {}", directory, exception);
        }
    }

    private void startWriterIfNeeded() {
        if (!writerStarted.compareAndSet(false, true)) return;
        Thread writer = new Thread(this::writerLoop, "SimpleMap-DebugWriter");
        writer.setDaemon(true);
        writer.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
        writer.start();
    }

    private void writerLoop() {
        while (true) {
            try {
                WriteItem item = writeQueue.take();
                switch (item.stream()) {
                    case OPEN -> openWriters(Path.of(item.line()));
                    case ROTATE -> closeWriters();
                    case FLUSH -> flushWriters();
                    case METRICS_HEADER -> {
                        if (metricsWriter != null) {
                            metricsWriter.write(item.line());
                            metricsWriter.newLine();
                        }
                    }
                    case METRICS -> {
                        if (metricsWriter != null) {
                            metricsWriter.write(item.line());
                            metricsWriter.newLine();
                        }
                        refreshDeepSnapshots();
                    }
                    case EVENTS -> {
                        if (eventsWriter != null) {
                            eventsWriter.write(item.line());
                            eventsWriter.newLine();
                        }
                    }
                }
                long now = System.nanoTime();
                if (now - lastFlushNanos >= FLUSH_INTERVAL_NANOS) {
                    flushWriters();
                    lastFlushNanos = now;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                closeWriters();
                return;
            } catch (IOException exception) {
                LOGGER.error("SimpleMap debug writer failed", exception);
                closeWriters();
            } catch (RuntimeException exception) {
                LOGGER.error("SimpleMap debug writer crashed", exception);
            }
        }
    }

    private void openWriters(Path directory) throws IOException {
        closeWriters();
        Files.createDirectories(directory);
        metricsWriter = Files.newBufferedWriter(directory.resolve("metrics.csv"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        eventsWriter = Files.newBufferedWriter(directory.resolve("events.jsonl"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(directory.resolve("README.txt"),
                "SimpleMap diagnostic capture\n"
                        + "metrics.csv = fixed interval numeric samples\n"
                        + "events.jsonl = sparse state transitions and anomalies\n"
                        + "Press F8 to toggle overlay, F9 to pause/resume recording, "
                        + "F10 to mark a problem and record at 250 ms for 20 seconds.\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        lastFlushNanos = System.nanoTime();
    }

    private void flushWriters() throws IOException {
        if (metricsWriter != null) metricsWriter.flush();
        if (eventsWriter != null) eventsWriter.flush();
    }

    private void closeWriters() {
        try {
            flushWriters();
        } catch (IOException ignored) {
        }
        try {
            if (metricsWriter != null) metricsWriter.close();
        } catch (IOException ignored) {
        }
        try {
            if (eventsWriter != null) eventsWriter.close();
        } catch (IOException ignored) {
        }
        metricsWriter = null;
        eventsWriter = null;
    }

    private void offer(WriteItem item) {
        if (item == null) return;
        if (!writeQueue.offer(item)) droppedWriteItems.incrementAndGet();
    }

    private DebugSnapshot capture(Minecraft minecraft, long nowNanos,
            long sampleIntervalNanos) {
        MapPipelineTelemetry pipeline = MapPipelineTelemetry.getInstance();
        MapPipelineTelemetry.Snapshot p = pipeline.snapshot();
        MapPipelineTelemetry.RenderSnapshot render = pipeline.renderSnapshot();
        MapObservationTelemetry.Snapshot observation =
                MapObservationTelemetry.getInstance().snapshot();
        MapWorkScheduler.Snapshot scheduler = MapWorkScheduler.snapshot();
        MapGpuBudgetController.Snapshot gpu =
                MapGpuBudgetController.getInstance().snapshot();
        DeepSnapshots deep = deepSnapshots;
        if (deep == null) {
            // One synchronous bootstrap sample is acceptable at world start; all
            // recurring 1 Hz scans are refreshed by the writer thread afterward.
            refreshDeepSnapshots();
            deep = deepSnapshots;
        }
        MapSession session = MapSessionManager.getInstance().active();
        RevisionStamp stamp = session == null ? null : session.stamp();
        MapManager mapManager = MapManager.getInstance();
        MapScreen mapScreen = minecraft.screen instanceof MapScreen screen ? screen : null;

        long heapUsed = deep.heapUsedBytes();
        long heapCommitted = deep.heapCommittedBytes();
        long heapMax = deep.heapMaxBytes();
        double allocationMiBPerSecond = deep.allocationMiBPerSecond();
        long gcCountDelta = deep.gcCountDelta();
        long gcTimeDelta = deep.gcTimeMsDelta();
        double processCpu = deep.processCpuPercent();
        double systemCpu = deep.systemCpuPercent();
        double serverMspt = serverMspt(minecraft);
        double serverTps = serverMspt > 0.0
                ? Math.min(20.0, 1000.0 / serverMspt) : -1.0;
        int ping = ping(minecraft);
        double gpuUtilization = gpuUtilization(minecraft);
        double frameAverageMs = MapPerformanceGovernor.getInstance()
                .smoothedFrameNanos() / 1_000_000.0;

        MapPipelineTelemetry.StageSnapshot[] stages =
                new MapPipelineTelemetry.StageSnapshot[MapPipelineStage.values().length];
        for (MapPipelineStage stage : MapPipelineStage.values()) {
            stages[stage.ordinal()] = pipeline.stageSnapshot(stage);
        }

        return new DebugSnapshot(
                System.currentTimeMillis(),
                (nowNanos - sessionStartNanos) / 1_000_000L,
                sampleIntervalNanos / 1_000_000.0,
                nowNanos < burstUntilNanos,
                droppedWriteItems.get(),
                minecraft.getFps(), frameAverageMs,
                MapPerformanceGovernor.getInstance().underPressure(),
                heapUsed, heapCommitted, heapMax,
                observation.heapPressure(), allocationMiBPerSecond,
                gcCountDelta, gcTimeDelta,
                processCpu, systemCpu, gpuUtilization,
                serverMspt, serverTps, ping,
                session == null ? "" : session.worldIdentity(),
                mapManager.getCurrentDimensionResourceId(),
                stamp == null ? 0L : stamp.sessionId(),
                stamp == null ? 0L : stamp.sourceGeneration(),
                stamp == null ? 0L : stamp.styleGeneration(),
                stamp == null ? 0L : stamp.projectionGeneration(),
                minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(),
                screenName(minecraft.screen),
                CaveMode.getCaveType(minecraft).name(),
                mapScreen == null ? minecraft.player.getX() : mapScreen.getCenterX(),
                mapScreen == null ? minecraft.player.getZ() : mapScreen.getCenterZ(),
                mapScreen == null ? MapConfig.minimapZoom : mapScreen.getScale(),
                MapConfig.minimapEnabled,
                mapScreen != null,
                mapManager.isViewingLiveDimension(),
                p, render, stages, scheduler, observation, deep.workGraph(), gpu,
                deep.residency(), deep.atlas(), deep.texture(), deep.sourceDb(),
                deep.cave(), deep.exactStates(), deep.lodGraph(), deep.architecture(),
                mapManager.dirtyRegionCount(),
                RegionDataStore.pendingSaveCount(),
                RegionDataStore.inFlightSaveCount(),
                MapLightManager.getInstance().dirtyRegionCount(),
                MapLightManager.getInstance().pendingSaveCount(),
                MapLightManager.getInstance().inFlightSaveCount());
    }

    private void refreshDeepSnapshots() {
        try {
            long nowNanos = System.nanoTime();
            long heapUsed = runtime.totalMemory() - runtime.freeMemory();
            long heapCommitted = runtime.totalMemory();
            long heapMax = runtime.maxMemory();
            double allocationMiBPerSecond = allocationRate(nowNanos);
            long[] totals = gcTotals();
            long gcCountDelta = Math.max(0L, totals[0] - lastGcCount);
            long gcTimeDelta = Math.max(0L, totals[1] - lastGcTimeMs);
            lastGcCount = totals[0];
            lastGcTimeMs = totals[1];
            double processCpu = osBean == null ? -1.0
                    : percent(osBean.getProcessCpuLoad());
            double systemCpu = osBean == null ? -1.0
                    : percent(osBean.getCpuLoad());

            deepSnapshots = new DeepSnapshots(
                    heapUsed, heapCommitted, heapMax,
                    allocationMiBPerSecond, gcCountDelta, gcTimeDelta,
                    processCpu, systemCpu,
                    MapWorkGraph.getInstance().snapshot(),
                    MapResidencyManager.getInstance().snapshot(),
                    MapAtlasMemoryTracker.getInstance().snapshot(),
                    MapTextureManager.getInstance().debugSnapshot(),
                    SurfaceRegionSourceDatabase.getInstance().debugSnapshot(),
                    CaveTextureManager.getInstance().debugSnapshot(),
                    ExactPageStateTracker.getInstance().snapshot(),
                    MapOverviewTextureManager.getInstance().lodGraphSummary(),
                    MapArchitectureCoordinator.getInstance().summary());
        } catch (RuntimeException exception) {
            LOGGER.debug("SimpleMap background diagnostic snapshot failed", exception);
        }
    }

    private double allocationRate(long nowNanos) {
        long allocated = totalAllocatedBytes();
        if (allocated < 0L) return -1.0;
        if (lastAllocatedBytes < 0L || lastAllocationSampleNanos == 0L) {
            lastAllocatedBytes = allocated;
            lastAllocationSampleNanos = nowNanos;
            return 0.0;
        }
        long elapsed = nowNanos - lastAllocationSampleNanos;
        long delta = Math.max(0L, allocated - lastAllocatedBytes);
        lastAllocatedBytes = allocated;
        lastAllocationSampleNanos = nowNanos;
        if (elapsed <= 0L) return 0.0;
        return delta / 1048576.0 / (elapsed / 1_000_000_000.0);
    }

    private long totalAllocatedBytes() {
        if (threadBean == null || !threadBean.isThreadAllocatedMemorySupported()
                || !threadBean.isThreadAllocatedMemoryEnabled()) return -1L;
        long total = 0L;
        long[] ids = threadBean.getAllThreadIds();
        for (long id : ids) {
            long value = threadBean.getThreadAllocatedBytes(id);
            if (value > 0L) total += value;
        }
        return total;
    }

    private static long[] gcTotals() {
        long count = 0L;
        long time = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long beanCount = bean.getCollectionCount();
            long beanTime = bean.getCollectionTime();
            if (beanCount > 0L) count += beanCount;
            if (beanTime > 0L) time += beanTime;
        }
        return new long[] { count, time };
    }

    private double serverMspt(Minecraft minecraft) {
        Object server = minecraft.getSingleplayerServer();
        if (server == null) return -1.0;
        try {
            Class<?> owner = server.getClass();
            if (serverAverageTickMethod == null || serverAverageTickOwner != owner) {
                serverAverageTickOwner = owner;
                serverAverageTickMethod = null;
                for (String name : List.of("getAverageTickTimeNanos", "getAverageTickTime")) {
                    try {
                        Method method = owner.getMethod(name);
                        method.setAccessible(true);
                        serverAverageTickMethod = method;
                        break;
                    } catch (ReflectiveOperationException ignored) {
                    }
                }
            }
            if (serverAverageTickMethod == null) return -1.0;
            Object result = serverAverageTickMethod.invoke(server);
            if (!(result instanceof Number number)) return -1.0;
            double value = number.doubleValue();
            return serverAverageTickMethod.getName().endsWith("Nanos")
                    ? value / 1_000_000.0 : value;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return -1.0;
        }
    }

    private int ping(Minecraft minecraft) {
        try {
            if (minecraft.getConnection() == null || minecraft.player == null) return -1;
            var info = minecraft.getConnection().getPlayerInfo(minecraft.player.getUUID());
            return info == null ? -1 : info.getLatency();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private double gpuUtilization(Minecraft minecraft) {
        try {
            if (!gpuMethodResolved) {
                gpuMethodResolved = true;
                try {
                    gpuUtilizationMethod = minecraft.getClass()
                            .getMethod("getGpuUtilization");
                    gpuUtilizationMethod.setAccessible(true);
                } catch (ReflectiveOperationException ignored) {
                    gpuUtilizationMethod = null;
                }
            }
            if (gpuUtilizationMethod == null) return -1.0;
            Object value = gpuUtilizationMethod.invoke(minecraft);
            return value instanceof Number number ? number.doubleValue() : -1.0;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return -1.0;
        }
    }

    private String jsonEvent(String type, String detail) {
        // Events can originate on CPU/IO workers. Never dereference Minecraft/player
        // state here; use the latest immutable client-thread snapshot instead.
        DebugSnapshot snapshot = lastSnapshot;
        long sessionId = activeSessionId == Long.MIN_VALUE
                ? snapshot.sessionId() : activeSessionId;
        StringBuilder json = new StringBuilder(320);
        json.append('{')
                .append("\"timestamp_ms\":").append(System.currentTimeMillis())
                .append(",\"elapsed_ms\":")
                .append(sessionStartNanos == 0L ? 0L
                        : (System.nanoTime() - sessionStartNanos) / 1_000_000L)
                .append(",\"type\":\"").append(jsonEscape(type)).append('"')
                .append(",\"thread\":\"")
                .append(jsonEscape(Thread.currentThread().getName())).append('"')
                .append(",\"session_id\":").append(sessionId)
                .append(",\"dimension\":\"")
                .append(jsonEscape(snapshot.dimension())).append('"');
        if (snapshot.timestampMs() != 0L) {
            json.append(",\"player_x\":").append(format(snapshot.playerX()))
                    .append(",\"player_y\":").append(format(snapshot.playerY()))
                    .append(",\"player_z\":").append(format(snapshot.playerZ()));
        }
        json.append(",\"detail\":\"")
                .append(jsonEscape(detail == null ? "" : detail)).append("\"}");
        return json.toString();
    }

    private static String screenName(Object screen) {
        return screen == null ? "GAME" : screen.getClass().getSimpleName();
    }

    private static String sanitize(String value) {
        String cleaned = value == null ? "unknown"
                : value.replaceAll("[^a-zA-Z0-9._-]+", "_");
        return cleaned.length() > 72 ? cleaned.substring(0, 72) : cleaned;
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String csv(String value) {
        if (value == null) return "";
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String format(double value) {
        if (!Double.isFinite(value)) return "-1";
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static double percent(double fraction) {
        return fraction < 0.0 ? -1.0 : fraction * 100.0;
    }

    private record DeepSnapshots(
            long heapUsedBytes, long heapCommittedBytes, long heapMaxBytes,
            double allocationMiBPerSecond, long gcCountDelta, long gcTimeMsDelta,
            double processCpuPercent, double systemCpuPercent,
            MapWorkGraph.Snapshot workGraph,
            MapResidencyManager.Snapshot residency,
            MapAtlasMemoryTracker.Snapshot atlas,
            MapTextureManager.DebugSnapshot texture,
            SurfaceRegionSourceDatabase.DebugSnapshot sourceDb,
            CaveTextureManager.DebugSnapshot cave,
            ExactPageStateTracker.Snapshot exactStates,
            RegionLodGraph.Summary lodGraph,
            MapArchitectureCoordinator.Summary architecture) {

        private static DeepSnapshots from(DebugSnapshot snapshot) {
            return new DeepSnapshots(
                    snapshot.heapUsedBytes(), snapshot.heapCommittedBytes(),
                    snapshot.heapMaxBytes(), snapshot.allocationMiBPerSecond(),
                    snapshot.gcCountDelta(), snapshot.gcTimeMsDelta(),
                    snapshot.processCpuPercent(), snapshot.systemCpuPercent(),
                    snapshot.workGraph(), snapshot.residency(), snapshot.atlas(),
                    snapshot.texture(), snapshot.sourceDb(), snapshot.cave(),
                    snapshot.exactStates(), snapshot.lodGraph(),
                    snapshot.architecture());
        }
    }

    private enum Stream { OPEN, ROTATE, FLUSH, METRICS_HEADER, METRICS, EVENTS }

    private record WriteItem(Stream stream, String line) { }

    /** One immutable row, also consumed by the on-screen overlay. */
    public record DebugSnapshot(
            long timestampMs, long elapsedMs, double sampleIntervalMs,
            boolean burst, long writerDropped,
            int fps, double frameAverageMs, boolean governorPressure,
            long heapUsedBytes, long heapCommittedBytes, long heapMaxBytes,
            double heapPressure, double allocationMiBPerSecond,
            long gcCountDelta, long gcTimeMsDelta,
            double processCpuPercent, double systemCpuPercent,
            double gpuUtilizationPercent,
            double serverMspt, double serverTps, int pingMs,
            String worldIdentity, String dimension,
            long sessionId, long sourceGeneration,
            long styleGeneration, long projectionGeneration,
            double playerX, double playerY, double playerZ,
            String screen, String mapMode,
            double mapCenterX, double mapCenterZ, double mapScale,
            boolean minimapEnabled, boolean fullscreenOpen,
            boolean viewingLiveDimension,
            MapPipelineTelemetry.Snapshot pipeline,
            MapPipelineTelemetry.RenderSnapshot render,
            MapPipelineTelemetry.StageSnapshot[] stages,
            MapWorkScheduler.Snapshot scheduler,
            MapObservationTelemetry.Snapshot observation,
            MapWorkGraph.Snapshot workGraph,
            MapGpuBudgetController.Snapshot gpu,
            MapResidencyManager.Snapshot residency,
            MapAtlasMemoryTracker.Snapshot atlas,
            MapTextureManager.DebugSnapshot texture,
            SurfaceRegionSourceDatabase.DebugSnapshot sourceDb,
            CaveTextureManager.DebugSnapshot cave,
            ExactPageStateTracker.Snapshot exactStates,
            RegionLodGraph.Summary lodGraph,
            MapArchitectureCoordinator.Summary architecture,
            int surfaceDirty, int surfacePending, int surfaceInFlight,
            int lightDirty, int lightPending, int lightInFlight) {

        static DebugSnapshot empty() {
            return new DebugSnapshot(0L, 0L, 0.0, false, 0L,
                    0, 0.0, false, 0L, 0L, 1L, 0.0, 0.0,
                    0L, 0L, -1.0, -1.0, -1.0, -1.0, -1.0, -1,
                    "", "", 0L, 0L, 0L, 0L,
                    0.0, 0.0, 0.0, "", "", 0.0, 0.0, 1.0,
                    false, false, true,
                    null, null,
                    new MapPipelineTelemetry.StageSnapshot[MapPipelineStage.values().length],
                    null, null, null, null, null, null,
                    MapTextureManager.DebugSnapshot.empty(),
                    SurfaceRegionSourceDatabase.DebugSnapshot.empty(),
                    CaveTextureManager.DebugSnapshot.empty(),
                    emptyExactStates(), RegionLodGraph.Summary.empty(),
                    MapArchitectureCoordinator.getInstance().summary(),
                    0, 0, 0, 0, 0, 0);
        }

        private static ExactPageStateTracker.Snapshot emptyExactStates() {
            java.util.EnumMap<ExactPageState, Long> pages =
                    new java.util.EnumMap<>(ExactPageState.class);
            java.util.EnumMap<ExactPageState, Long> transitions =
                    new java.util.EnumMap<>(ExactPageState.class);
            for (ExactPageState state : ExactPageState.values()) {
                pages.put(state, 0L);
                transitions.put(state, 0L);
            }
            return new ExactPageStateTracker.Snapshot(0, pages, transitions,
                    0L, 0L, 0L, 0L);
        }

        public double heapUsedMiB() { return heapUsedBytes / 1048576.0; }
        public double heapMaxMiB() { return heapMaxBytes / 1048576.0; }

        static String csvHeader() {
            List<String> columns = new ArrayList<>();
            add(columns,
                    "timestamp_ms", "elapsed_ms", "sample_interval_ms", "burst",
                    "writer_dropped", "fps", "frame_avg_ms", "governor_pressure",
                    "heap_used_mib", "heap_committed_mib", "heap_max_mib",
                    "heap_pressure", "allocation_mib_s", "gc_count_delta",
                    "gc_time_ms_delta", "process_cpu_pct", "system_cpu_pct",
                    "gpu_utilization_pct", "server_mspt", "server_tps", "ping_ms",
                    "world", "dimension", "session_id", "source_generation",
                    "style_generation", "projection_generation",
                    "player_x", "player_y", "player_z", "screen", "map_mode",
                    "map_center_x", "map_center_z", "map_scale", "minimap_enabled",
                    "fullscreen_open", "viewing_live_dimension",
                    "request_minimap", "request_fullscreen", "admit_minimap",
                    "admit_fullscreen", "exact_build_queued", "exact_build_completed",
                    "exact_build_discarded", "exact_gpu_ready", "exact_pages_drawn",
                    "branch_nodes_drawn", "legacy_fallbacks_drawn", "no_content_passes",
                    "last_render_exact", "last_render_branch", "last_render_legacy",
                    "last_render_has_content", "last_render_age_ms",
                    "last_render_projection", "last_render_hierarchy_level",
                    "source_present", "source_absent", "source_deferred", "source_failed",
                    "tasks_cancelled", "tasks_completed_discarded",
                    "plan_builds", "plan_reuses", "plan_quads",
                    "plan_texture_groups", "batch_submissions",
                    "branch_updates_queued", "branch_updates_dropped",
                    "source_leases_open", "source_leases_closed",
                    "decode_cancel_no_consumer");
            for (MapPipelineStage stage : MapPipelineStage.values()) {
                String name = stage.name().toLowerCase(Locale.ROOT);
                columns.add("latency_" + name + "_avg_ms");
                columns.add("latency_" + name + "_max_ms");
                columns.add("latency_" + name + "_count");
            }
            add(columns,
                    "cpu_active", "cpu_queued", "cpu_queued_cost", "cpu_active_cost",
                    "io_active", "io_queued", "io_queued_cost", "io_active_cost",
                    "cpu_queued_minimap", "cpu_queued_fullscreen",
                    "cpu_queued_background", "cpu_queued_prefetch",
                    "io_queued_minimap", "io_queued_fullscreen",
                    "io_queued_background", "io_queued_prefetch",
                    "work_completed_minimap", "work_completed_fullscreen",
                    "work_completed_background", "work_completed_prefetch",
                    "work_denied_minimap", "work_denied_fullscreen",
                    "work_denied_background", "work_denied_prefetch",
                    "mutation_columns", "mutation_chunks", "mutation_regions",
                    "generated_chunks", "observation_pressure", "decoded_regions",
                    "decoded_chunks", "decode_queue", "decoded_bytes",
                    "decoded_target_bytes", "workgraph_regions", "workgraph_dirty",
                    "workgraph_running", "workgraph_prepared", "workgraph_ready",
                    "workgraph_published", "workgraph_cancelled",
                    "surface_dirty", "surface_pending", "surface_in_flight",
                    "light_dirty", "light_pending", "light_in_flight",
                    "resident_entries", "pinned_entries", "residency_used_mib",
                    "residency_budget_mib", "global_evictions", "budget_failures",
                    "atlas_allocations", "atlas_allocated_mib", "atlas_planned_mib",
                    "vram_available_mib", "gpu_reserved_ms", "gpu_minimap_reserved_ms",
                    "gpu_reserved_kib", "gpu_minimap_kib", "gpu_frame_budget_ms",
                    "gpu_frame_byte_budget_kib", "gpu_surface_prediction_ms",
                    "gpu_cave_prediction_ms", "gpu_branch_prediction_ms",
                    "gpu_legacy_prediction_ms", "gpu_surface_reservation_denied",
                    "gpu_cave_reservation_denied", "gpu_branch_reservation_denied",
                    "gpu_legacy_reservation_denied",
                    "gpu_oversize_foreground_admissions",
                    "gpu_branch_bootstrap_admissions",
                    "fullscreen_fbo_frames", "fullscreen_fbo_redraws",
                    "fullscreen_fbo_reuses", "fullscreen_fbo_coalesced",
                    "fullscreen_fbo_fallbacks", "fullscreen_fbo_reallocations",
                    "fullscreen_fbo_width",
                    "fullscreen_fbo_height", "fullscreen_fbo_disabled",
                    "minimap_fbo_redraws", "minimap_fbo_reuses",
                    "minimap_fbo_coalesced", "minimap_fbo_fallbacks",
                    "minimap_fbo_reallocations",
                    "minimap_fbo_width", "minimap_fbo_height",
                    "minimap_fbo_disabled", "surface_demand_trimmed", "surface_demand_area_ratio",
                    "surface_demand_left_pct", "surface_demand_right_pct",
                    "surface_demand_vertical_pct", "surface_exact_active_window",
                    "texture_regions", "texture_pages", "texture_pages_initialized",
                    "texture_pages_pending", "texture_pages_completed_pending",
                    "texture_dirty_regions", "texture_dirty_pages",
                    "texture_pending_batches", "texture_page_demands",
                    "texture_pending_leaf_publications",
                    "source_db_regions", "source_db_resident_chunks",
                    "source_db_dirty_chunks", "source_db_pinned_views",
                    "source_db_closing_regions", "batch_capture_attempts",
                    "batch_capture_ready", "batch_capture_deferred",
                    "batch_false_ready", "batch_focused_plans",
                    "batch_expanded_plans", "batch_missing_regions_total",
                    "batch_missing_chunks_total", "batch_dirty_chunks_total",
                    "batch_last_required_chunks", "batch_last_present_chunks",
                    "batch_last_missing_chunks", "batch_last_dirty_chunks",
                    "batch_last_missing_regions", "batch_last_pipeline_ready",
                    "batch_last_strict_ready", "cave_pages", "cave_requests",
                    "cave_pending_builds", "cave_completed_builds",
                    "cave_initialized_pages", "cave_partial_pages",
                    "cave_known_empty_pages", "cave_resident_pages",
                    "cave_fullscreen_slice", "cave_fullscreen_plan_pages",
                    "exact_state_tracked", "exact_state_oldest_age_ms",
                    "exact_requested_older_5s", "exact_building_older_5s",
                    "exact_cpu_ready_older_5s", "lod_graph_nodes",
                    "lod_graph_dirty", "lod_graph_running", "lod_graph_prepared",
                    "lod_graph_published", "lod_graph_resident",
                    "lod_graph_level0_nodes", "lod_graph_coarse_nodes",
                    "arch_page_table_generation", "arch_page_table_entries",
                    "arch_page_table_storages", "arch_page_table_staged",
                    "arch_page_table_swaps", "arch_page_table_generation_mismatches",
                    "arch_upload_queued", "arch_upload_in_flight",
                    "arch_upload_queued_mib", "arch_upload_staging_mib",
                    "arch_upload_submitted", "arch_upload_committed",
                    "arch_upload_rejected", "arch_upload_stale",
                    "arch_upload_oversized", "arch_minimap_ring_generation",
                    "arch_minimap_ring_diameter", "arch_minimap_requests",
                    "arch_minimap_skipped", "arch_minimap_last_good_revision",
                    "arch_minimap_last_good_available", "arch_surface_requests",
                    "arch_cave_archive_tiles", "arch_cave_archive_mib",
                    "arch_cave_archive_ingested", "arch_cave_archive_replaced",
                    "arch_cave_archive_stale_ignored", "arch_cave_band_entries",
                    "arch_cave_coarse_entries", "arch_cave_layered_hits",
                    "arch_cave_layered_misses", "arch_cave_full_builds",
                    "arch_cave_stale_rejected", "arch_persistence_queued",
                    "arch_persistence_surface_committed",
                    "arch_persistence_cave_committed",
                    "arch_persistence_failures", "arch_persistence_recoveries");
            for (ExactPageState state : ExactPageState.values()) {
                String name = state.name().toLowerCase(Locale.ROOT);
                columns.add("exact_state_" + name);
                columns.add("exact_transition_" + name);
            }
            return String.join(",", columns);
        }

        String toCsvLine() {
            List<String> values = new ArrayList<>(192);
            FullscreenMapFramebufferRenderer.Snapshot fullscreenFbo =
                    FullscreenMapFramebufferRenderer.getInstance().snapshot();
            MinimapFramebufferRenderer.Snapshot minimapFbo =
                    MinimapFramebufferRenderer.getInstance().snapshot();
            MapSurfaceDemandPolicy.Snapshot surfaceDemand =
                    MapSurfaceDemandPolicy.snapshot();
            MapArchitectureCoordinator.Summary architectureSnapshot =
                    architecture == null
                            ? MapArchitectureCoordinator.getInstance().summary()
                            : architecture;
            add(values,
                    timestampMs, elapsedMs, format(sampleIntervalMs), burst, writerDropped,
                    fps, format(frameAverageMs), governorPressure,
                    format(heapUsedBytes / 1048576.0),
                    format(heapCommittedBytes / 1048576.0),
                    format(heapMaxBytes / 1048576.0), format(heapPressure),
                    format(allocationMiBPerSecond), gcCountDelta, gcTimeMsDelta,
                    format(processCpuPercent), format(systemCpuPercent),
                    format(gpuUtilizationPercent), format(serverMspt),
                    format(serverTps), pingMs, csv(worldIdentity), csv(dimension),
                    sessionId, sourceGeneration, styleGeneration, projectionGeneration,
                    format(playerX), format(playerY), format(playerZ), csv(screen),
                    csv(mapMode), format(mapCenterX), format(mapCenterZ), format(mapScale),
                    minimapEnabled, fullscreenOpen, viewingLiveDimension,
                    pipeline.viewportRequests(MapRequestLane.MINIMAP),
                    pipeline.viewportRequests(MapRequestLane.FULLSCREEN),
                    pipeline.pageAdmissions(MapRequestLane.MINIMAP),
                    pipeline.pageAdmissions(MapRequestLane.FULLSCREEN),
                    pipeline.exactBuildQueued(), pipeline.exactBuildCompleted(),
                    pipeline.exactBuildDiscarded(), pipeline.exactGpuReady(),
                    pipeline.exactPagesDrawn(), pipeline.branchNodesDrawn(),
                    pipeline.legacyFallbacksDrawn(), pipeline.noContentRenderPasses(),
                    render.exactPages(), render.branchNodes(), render.legacyFallbacks(),
                    render.hadContent(), format(render.ageMillis()),
                    csv(render.projection()), render.hierarchyLevel(),
                    pipeline.sourcePresent(), pipeline.sourceAbsent(),
                    pipeline.sourceDeferred(), pipeline.sourceFailed(),
                    pipeline.tasksCancelledBeforeRun(),
                    pipeline.tasksCompletedButDiscarded(),
                    pipeline.renderPlanBuilds(), pipeline.renderPlanReuses(),
                    pipeline.renderPlanQuads(), pipeline.renderPlanTextureGroups(),
                    pipeline.rawBatchSubmissions(), pipeline.branchUpdatesQueued(),
                    pipeline.branchUpdatesDropped(), pipeline.sourceLeasesOpened(),
                    pipeline.sourceLeasesClosed(),
                    pipeline.sourceDecodesCancelledNoConsumers());
            for (MapPipelineStage stage : MapPipelineStage.values()) {
                MapPipelineTelemetry.StageSnapshot snapshot = stages[stage.ordinal()];
                if (snapshot == null) snapshot = new MapPipelineTelemetry.StageSnapshot(0, 0, 0);
                values.add(format(snapshot.averageMillis()));
                values.add(format(snapshot.maxMillis()));
                values.add(Long.toString(snapshot.count()));
            }
            add(values,
                    scheduler.cpuActive(), scheduler.cpuQueued(), scheduler.cpuQueuedCost(),
                    scheduler.cpuActiveCost(), scheduler.ioActive(), scheduler.ioQueued(),
                    scheduler.ioQueuedCost(), scheduler.ioActiveCost(),
                    scheduler.cpuQueued(MapRequestLane.MINIMAP),
                    scheduler.cpuQueued(MapRequestLane.FULLSCREEN),
                    scheduler.cpuQueued(MapRequestLane.BACKGROUND),
                    scheduler.cpuQueued(MapRequestLane.PREFETCH),
                    scheduler.ioQueued(MapRequestLane.MINIMAP),
                    scheduler.ioQueued(MapRequestLane.FULLSCREEN),
                    scheduler.ioQueued(MapRequestLane.BACKGROUND),
                    scheduler.ioQueued(MapRequestLane.PREFETCH),
                    scheduler.completed(MapRequestLane.MINIMAP),
                    scheduler.completed(MapRequestLane.FULLSCREEN),
                    scheduler.completed(MapRequestLane.BACKGROUND),
                    scheduler.completed(MapRequestLane.PREFETCH),
                    scheduler.denied(MapRequestLane.MINIMAP),
                    scheduler.denied(MapRequestLane.FULLSCREEN),
                    scheduler.denied(MapRequestLane.BACKGROUND),
                    scheduler.denied(MapRequestLane.PREFETCH),
                    observation.mutationColumns(), observation.mutationChunks(),
                    observation.mutationRegions(), observation.generatedChunks(),
                    observation.pressure(), observation.decodedRegions(),
                    observation.decodedChunks(), observation.decodeQueue(),
                    observation.decodedBytes(), observation.decodedTargetBytes(),
                    workGraph.regions(), workGraph.dirtyStages(), workGraph.runningStages(),
                    workGraph.preparedStages(), workGraph.readyStages(),
                    workGraph.publishedStages(), workGraph.cancelledStages(),
                    surfaceDirty, surfacePending, surfaceInFlight,
                    lightDirty, lightPending, lightInFlight,
                    residency.residentEntries(), residency.pinnedEntries(),
                    format(residency.estimatedBytes() / 1048576.0),
                    format(residency.budgetBytes() / 1048576.0),
                    residency.globalEvictions(), residency.budgetFailures(),
                    atlas.allocationCount(), format(atlas.allocatedBytes() / 1048576.0),
                    format(atlas.plannedAtlasBytes() / 1048576.0),
                    atlas.detectedAvailableVramBytes() <= 0L ? "-1"
                            : format(atlas.detectedAvailableVramBytes() / 1048576.0),
                    format(gpu.reservedNanos() / 1_000_000.0),
                    format(gpu.minimapReservedNanos() / 1_000_000.0),
                    gpu.reservedBytes() / 1024L, gpu.minimapReservedBytes() / 1024L,
                    format(gpu.frameBudgetNanos() / 1_000_000.0),
                    gpu.frameByteBudget() / 1024L,
                    format(gpu.surfaceExactPredictionNanos() / 1_000_000.0),
                    format(gpu.caveExactPredictionNanos() / 1_000_000.0),
                    format(gpu.branchPredictionNanos() / 1_000_000.0),
                    format(gpu.legacyPredictionNanos() / 1_000_000.0),
                    gpu.surfaceExactDeniedReservations(),
                    gpu.caveExactDeniedReservations(),
                    gpu.branchDeniedReservations(), gpu.legacyDeniedReservations(),
                    gpu.oversizedForegroundAdmissions(), gpu.branchBootstrapAdmissions(),
                    fullscreenFbo.renderedFrames(), fullscreenFbo.redrawFrames(),
                    fullscreenFbo.reuseFrames(), fullscreenFbo.coalescedFrames(),
                    fullscreenFbo.fallbackFrames(), fullscreenFbo.reallocations(),
                    fullscreenFbo.width(),
                    fullscreenFbo.height(), fullscreenFbo.disabled(),
                    minimapFbo.redrawFrames(), minimapFbo.reuseFrames(),
                    minimapFbo.coalescedFrames(), minimapFbo.fallbackFrames(),
                    minimapFbo.reallocations(),
                    minimapFbo.width(), minimapFbo.height(), minimapFbo.disabled(),
                    surfaceDemand.trimmed(), format(surfaceDemand.areaRatio()),
                    format(surfaceDemand.leftFraction() * 100.0),
                    format(surfaceDemand.rightFraction() * 100.0),
                    format(surfaceDemand.verticalFraction() * 100.0),
                    surfaceDemand.exactActiveWindow(),
                    texture.regions(), texture.pages(), texture.initializedPages(),
                    texture.pendingPages(), texture.completedPendingPages(),
                    texture.dirtyRegions(), texture.dirtyPages(),
                    texture.pendingBatches(), texture.pageDemands(),
                    texture.pendingLeafPublications(), sourceDb.regions(),
                    sourceDb.residentChunks(), sourceDb.dirtyChunks(),
                    sourceDb.pinnedViews(), sourceDb.closingRegions(),
                    sourceDb.captureAttempts(), sourceDb.captureReady(),
                    sourceDb.captureDeferred(), sourceDb.falseReady(),
                    sourceDb.focusedBatchPlans(), sourceDb.expandedBatchPlans(),
                    sourceDb.missingRegionsTotal(), sourceDb.missingChunksTotal(),
                    sourceDb.dirtyChunksTotal(), sourceDb.lastRequiredChunks(),
                    sourceDb.lastPresentChunks(), sourceDb.lastMissingChunks(),
                    sourceDb.lastDirtyChunks(), sourceDb.lastMissingRegions(),
                    sourceDb.lastPipelineReady(), sourceDb.lastStrictReady(),
                    cave.pages(), cave.requests(), cave.pendingBuilds(),
                    cave.completedBuilds(), cave.initializedPages(),
                    cave.partialPages(), cave.knownEmptyPages(), cave.residentPages(),
                    cave.fullscreenSlice(), cave.fullscreenPlanPages(),
                    exactStates.trackedPages(), exactStates.oldestStateAgeMs(),
                    exactStates.requestedOlderThan5s(),
                    exactStates.buildingOlderThan5s(),
                    exactStates.cpuReadyOlderThan5s(), lodGraph.nodes(),
                    lodGraph.dirty(), lodGraph.running(), lodGraph.prepared(),
                    lodGraph.published(), lodGraph.resident(),
                    lodGraph.level0Nodes(), lodGraph.coarseNodes(),
                    architectureSnapshot.pageTable().generation(),
                    architectureSnapshot.pageTable().entries(),
                    architectureSnapshot.pageTable().storages(),
                    architectureSnapshot.pageTable().staged(),
                    architectureSnapshot.pageTable().swaps(),
                    architectureSnapshot.pageTable().generationMismatches(),
                    architectureSnapshot.uploads().queued(),
                    architectureSnapshot.uploads().inFlight(),
                    format(architectureSnapshot.uploads().queuedBytes() / 1048576.0),
                    format(architectureSnapshot.uploads().stagingBytes() / 1048576.0),
                    architectureSnapshot.uploads().submitted(),
                    architectureSnapshot.uploads().committed(),
                    architectureSnapshot.uploads().rejected(),
                    architectureSnapshot.uploads().stale(),
                    architectureSnapshot.uploads().oversized(),
                    architectureSnapshot.minimap().ringGeneration(),
                    architectureSnapshot.minimap().diameter(),
                    architectureSnapshot.minimap().requests(),
                    architectureSnapshot.minimap().skipped(),
                    architectureSnapshot.minimap().lastGoodRevision(),
                    architectureSnapshot.minimap().lastGoodAvailable(),
                    architectureSnapshot.surfaceRequests(),
                    0L,
                    "0.0",
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    architectureSnapshot.persistence().queued(),
                    architectureSnapshot.persistence().surfaceCommitted(),
                    architectureSnapshot.persistence().caveCommitted(),
                    architectureSnapshot.persistence().failures(),
                    architectureSnapshot.persistence().recoveries());
            for (ExactPageState state : ExactPageState.values()) {
                values.add(Long.toString(exactStates.pagesByState()
                        .getOrDefault(state, 0L)));
                values.add(Long.toString(exactStates.transitionsByState()
                        .getOrDefault(state, 0L)));
            }
            return String.join(",", values);
        }

        private static void add(List<String> output, Object... values) {
            for (Object value : values) output.add(String.valueOf(value));
        }
    }

    private static void add(List<String> output, Object... values) {
        for (Object value : values) output.add(String.valueOf(value));
    }

    public static final class MapPipelineTelemetry {
    public enum MapPipelineStage {
        SOURCE_QUEUE,
        ANVIL_READ,
        DATA_FIX,
        CHUNK_DECODE,
        WORLD_SOURCE_FANOUT,
        SOURCE_WAIT,
        SURFACE_CAPTURE,
        SURFACE_ASSEMBLY,
        SURFACE_PROJECTION,
        CAVE_PROJECTION,
        CAVE_ARCHIVE_SCAN,
        EXACT_QUEUE,
        EXACT_BUILD,
        EXACT_UPLOAD,
        BRANCH_QUEUE,
        BRANCH_DERIVE,
        BRANCH_UPLOAD
    }

    private static final MapPipelineTelemetry INSTANCE = new MapPipelineTelemetry();

    private final EnumMap<MapRequestLane, AtomicLong> viewportRequests =
            new EnumMap<>(MapRequestLane.class);
    private final EnumMap<MapRequestLane, AtomicLong> pageAdmissions =
            new EnumMap<>(MapRequestLane.class);
    private final AtomicLong exactBuildQueued = new AtomicLong();
    private final AtomicLong exactBuildCompleted = new AtomicLong();
    private final AtomicLong exactBuildDiscarded = new AtomicLong();
    private final AtomicLong exactGpuReady = new AtomicLong();
    private final AtomicLong exactPagesDrawn = new AtomicLong();
    private final AtomicLong branchNodesDrawn = new AtomicLong();
    private final AtomicLong legacyFallbacksDrawn = new AtomicLong();
    private final AtomicLong noContentRenderPasses = new AtomicLong();
    private final AtomicLong renderPlanBuilds = new AtomicLong();
    private final AtomicLong renderPlanReuses = new AtomicLong();
    private final AtomicLong renderPlanQuads = new AtomicLong();
    private final AtomicLong renderPlanTextureGroups = new AtomicLong();
    private final AtomicLong rawBatchSubmissions = new AtomicLong();
    private final AtomicLong sourcePresent = new AtomicLong();
    private final AtomicLong sourceAbsent = new AtomicLong();
    private final AtomicLong sourceDeferred = new AtomicLong();
    private final AtomicLong sourceFailed = new AtomicLong();
    private final AtomicLong tasksCancelledBeforeRun = new AtomicLong();
    private final AtomicLong tasksCompletedButDiscarded = new AtomicLong();
    private final AtomicLong branchUpdatesQueued = new AtomicLong();
    private final AtomicLong branchUpdatesDropped = new AtomicLong();
    private final AtomicLong sourceLeasesOpened = new AtomicLong();
    private final AtomicLong sourceLeasesClosed = new AtomicLong();
    private final AtomicLong sourceDecodesCancelledNoConsumers = new AtomicLong();
    private final AtomicLong caveSourceLeaseCancelledViewportExit = new AtomicLong();
    private final AtomicLong caveSourceLeaseCancelledGenerationSuperseded =
            new AtomicLong();
    private final AtomicLong caveSourceRetainedAfterViewportExit = new AtomicLong();
    private final AtomicLong caveSourceArchiveCommittedOffscreen = new AtomicLong();
    private final AtomicLong caveHandoffOffered = new AtomicLong();
    private final AtomicLong caveHandoffAccepted = new AtomicLong();
    private final AtomicLong caveHandoffRejectedSuperseded = new AtomicLong();
    private final AtomicLong caveHandoffRejectedPlannerMismatch = new AtomicLong();
    private final AtomicLong caveHandoffRetainedLoadingGeneration = new AtomicLong();
    private final AtomicLongArray caveHoleReasons =
            new AtomicLongArray(CavePageHoleReason.values().length);
    private volatile int lastRenderExactPages;
    private volatile int lastRenderBranchNodes;
    private volatile int lastRenderLegacyFallbacks;
    private volatile boolean lastRenderHadContent;
    private volatile String lastRenderProjection = "NONE";
    private volatile int lastRenderHierarchyLevel;
    private volatile long lastRenderNanos;
    private final AtomicLongArray stageCount =
            new AtomicLongArray(MapPipelineStage.values().length);
    private final AtomicLongArray stageTotalNanos =
            new AtomicLongArray(MapPipelineStage.values().length);
    private final AtomicLongArray stageMaxNanos =
            new AtomicLongArray(MapPipelineStage.values().length);

    private MapPipelineTelemetry() {
        for (MapRequestLane lane : MapRequestLane.values()) {
            viewportRequests.put(lane, new AtomicLong());
            pageAdmissions.put(lane, new AtomicLong());
        }
    }

    public static MapPipelineTelemetry getInstance() {
        return INSTANCE;
    }

    public void recordViewportRequest(MapRequestLane lane) {
        viewportRequests.get(safe(lane)).incrementAndGet();
    }

    public void recordPageAdmission(MapRequestLane lane) {
        pageAdmissions.get(safe(lane)).incrementAndGet();
    }

    public void recordExactBuildQueued() {
        exactBuildQueued.incrementAndGet();
    }

    public void recordExactBuildCompleted() {
        exactBuildCompleted.incrementAndGet();
    }

    public void recordExactBuildDiscarded() {
        exactBuildDiscarded.incrementAndGet();
    }

    public void recordExactGpuReady() {
        exactGpuReady.incrementAndGet();
    }

    public void recordRenderContext(String projection, int hierarchyLevel) {
        lastRenderProjection = projection == null ? "UNKNOWN" : projection;
        lastRenderHierarchyLevel = Math.max(0, hierarchyLevel);
    }

    public void recordRenderResult(MapDrawResult result) {
        MapDrawResult effective = result == null ? MapDrawResult.EMPTY : result;
        lastRenderExactPages = Math.max(0, effective.exactPagesDrawn());
        lastRenderBranchNodes = Math.max(0, effective.branchNodesDrawn());
        lastRenderLegacyFallbacks = Math.max(0, effective.legacyFallbacksDrawn());
        lastRenderHadContent = effective.drewAnyMapContent();
        lastRenderNanos = System.nanoTime();
        if (!effective.drewAnyMapContent()) {
            long count = noContentRenderPasses.incrementAndGet();
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent("RENDER_NO_CONTENT", 1000L)) {
                recorder.event("RENDER_NO_CONTENT",
                        "count=" + count + " exact_gpu=" + exactGpuReady.get()
                                + " exact_drawn=" + exactPagesDrawn.get());
            }
            return;
        }
        exactPagesDrawn.addAndGet(lastRenderExactPages);
        branchNodesDrawn.addAndGet(lastRenderBranchNodes);
        legacyFallbacksDrawn.addAndGet(lastRenderLegacyFallbacks);
    }

    public void recordRenderPlanBuild(int quads, int textureGroups) {
        renderPlanBuilds.incrementAndGet();
        renderPlanQuads.addAndGet(Math.max(0, quads));
        renderPlanTextureGroups.addAndGet(Math.max(0, textureGroups));
    }

    public void recordRenderPlanReuse() {
        renderPlanReuses.incrementAndGet();
    }

    public void recordRawBatchSubmissions(int submissions) {
        rawBatchSubmissions.addAndGet(Math.max(0, submissions));
    }

    public void recordSourceState(String stateName) {
        if (stateName == null) return;
        switch (stateName) {
            case "PRESENT" -> sourcePresent.incrementAndGet();
            case "ABSENT" -> sourceAbsent.incrementAndGet();
            case "DEFERRED" -> sourceDeferred.incrementAndGet();
            case "FAILED" -> {
                long count = sourceFailed.incrementAndGet();
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent("SOURCE_FAILED", 500L)) {
                    recorder.event("SOURCE_FAILED", "count=" + count);
                }
            }
            default -> { }
        }
    }

    public void recordTaskCancelledBeforeRun() {
        long count = tasksCancelledBeforeRun.incrementAndGet();
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (recorder.shouldEmitEvent("TASK_CANCELLED_BEFORE_RUN", 500L)) {
            recorder.event("TASK_CANCELLED_BEFORE_RUN", "count=" + count);
        }
    }

    public void recordTaskCompletedButDiscarded() {
        long count = tasksCompletedButDiscarded.incrementAndGet();
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (recorder.shouldEmitEvent("TASK_COMPLETED_DISCARDED", 500L)) {
            recorder.event("TASK_COMPLETED_DISCARDED", "count=" + count);
        }
    }


    public void recordBranchUpdateQueued() {
        branchUpdatesQueued.incrementAndGet();
    }

    public void recordBranchUpdateDropped() {
        long count = branchUpdatesDropped.incrementAndGet();
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (recorder.shouldEmitEvent("BRANCH_UPDATE_DROPPED", 500L)) {
            recorder.event("BRANCH_UPDATE_DROPPED", "count=" + count);
        }
    }

    public void recordSourceLeaseOpened() {
        sourceLeasesOpened.incrementAndGet();
    }

    public void recordSourceLeaseClosed() {
        sourceLeasesClosed.incrementAndGet();
    }

    public void recordSourceDecodeCancelledNoConsumers() {
        sourceDecodesCancelledNoConsumers.incrementAndGet();
    }

    public void recordCaveSourceLeaseCancelledViewportExit() {
        caveSourceLeaseCancelledViewportExit.incrementAndGet();
    }

    public void recordCaveSourceLeaseCancelledGenerationSuperseded(int count) {
        if (count > 0) caveSourceLeaseCancelledGenerationSuperseded.addAndGet(count);
    }

    public void recordCaveSourceRetainedAfterViewportExit() {
        caveSourceRetainedAfterViewportExit.incrementAndGet();
    }

    public void recordCaveSourceArchiveCommittedOffscreen() {
        caveSourceArchiveCommittedOffscreen.incrementAndGet();
    }

    public void recordCaveHandoffOffered() {
        caveHandoffOffered.incrementAndGet();
    }

    public void recordCaveHandoffAccepted() {
        caveHandoffAccepted.incrementAndGet();
    }

    public void recordCaveHandoffRejectedSuperseded() {
        caveHandoffRejectedSuperseded.incrementAndGet();
    }

    public void recordCaveHandoffRejectedPlannerMismatch() {
        caveHandoffRejectedPlannerMismatch.incrementAndGet();
    }

    public void recordCaveHandoffRetainedLoadingGeneration() {
        caveHandoffRetainedLoadingGeneration.incrementAndGet();
    }

    public void recordCavePageHole(CavePageHoleReason reason) {
        if (reason != null) caveHoleReasons.incrementAndGet(reason.ordinal());
    }

    public void recordStageNanos(MapPipelineStage stage, long nanos) {
        if (stage == null || nanos < 0L) return;
        int index = stage.ordinal();
        stageCount.incrementAndGet(index);
        stageTotalNanos.addAndGet(index, nanos);
        long previous;
        do {
            previous = stageMaxNanos.get(index);
            if (nanos <= previous) break;
        } while (!stageMaxNanos.compareAndSet(index, previous, nanos));
    }

    public StageSnapshot stageSnapshot(MapPipelineStage stage) {
        if (stage == null) return new StageSnapshot(0L, 0L, 0L);
        int index = stage.ordinal();
        return new StageSnapshot(stageCount.get(index), stageTotalNanos.get(index),
                stageMaxNanos.get(index));
    }

    public RenderSnapshot renderSnapshot() {
        long ageNanos = lastRenderNanos == 0L ? Long.MAX_VALUE
                : Math.max(0L, System.nanoTime() - lastRenderNanos);
        return new RenderSnapshot(lastRenderExactPages, lastRenderBranchNodes,
                lastRenderLegacyFallbacks, lastRenderHadContent, ageNanos,
                lastRenderProjection, lastRenderHierarchyLevel);
    }

    public void reset() {
        for (AtomicLong counter : viewportRequests.values()) counter.set(0L);
        for (AtomicLong counter : pageAdmissions.values()) counter.set(0L);
        exactBuildQueued.set(0L);
        exactBuildCompleted.set(0L);
        exactBuildDiscarded.set(0L);
        exactGpuReady.set(0L);
        exactPagesDrawn.set(0L);
        branchNodesDrawn.set(0L);
        legacyFallbacksDrawn.set(0L);
        noContentRenderPasses.set(0L);
        renderPlanBuilds.set(0L);
        renderPlanReuses.set(0L);
        renderPlanQuads.set(0L);
        renderPlanTextureGroups.set(0L);
        rawBatchSubmissions.set(0L);
        sourcePresent.set(0L);
        sourceAbsent.set(0L);
        sourceDeferred.set(0L);
        sourceFailed.set(0L);
        tasksCancelledBeforeRun.set(0L);
        tasksCompletedButDiscarded.set(0L);
        branchUpdatesQueued.set(0L);
        branchUpdatesDropped.set(0L);
        sourceLeasesOpened.set(0L);
        sourceLeasesClosed.set(0L);
        sourceDecodesCancelledNoConsumers.set(0L);
        caveSourceLeaseCancelledViewportExit.set(0L);
        caveSourceLeaseCancelledGenerationSuperseded.set(0L);
        caveSourceRetainedAfterViewportExit.set(0L);
        caveSourceArchiveCommittedOffscreen.set(0L);
        caveHandoffOffered.set(0L);
        caveHandoffAccepted.set(0L);
        caveHandoffRejectedSuperseded.set(0L);
        caveHandoffRejectedPlannerMismatch.set(0L);
        caveHandoffRetainedLoadingGeneration.set(0L);
        for (CavePageHoleReason reason : CavePageHoleReason.values()) {
            caveHoleReasons.set(reason.ordinal(), 0L);
        }
        lastRenderExactPages = 0;
        lastRenderBranchNodes = 0;
        lastRenderLegacyFallbacks = 0;
        lastRenderProjection = "NONE";
        lastRenderHierarchyLevel = 0;
        lastRenderHadContent = false;
        lastRenderNanos = 0L;
        for (MapPipelineStage stage : MapPipelineStage.values()) {
            int index = stage.ordinal();
            stageCount.set(index, 0L);
            stageTotalNanos.set(index, 0L);
            stageMaxNanos.set(index, 0L);
        }
    }

    public Snapshot snapshot() {
        long[] requests = new long[MapRequestLane.values().length];
        long[] admissions = new long[MapRequestLane.values().length];
        for (MapRequestLane lane : MapRequestLane.values()) {
            requests[lane.ordinal()] = viewportRequests.get(lane).get();
            admissions[lane.ordinal()] = pageAdmissions.get(lane).get();
        }
        return new Snapshot(requests, admissions,
                exactBuildQueued.get(), exactBuildCompleted.get(),
                exactBuildDiscarded.get(), exactGpuReady.get(),
                exactPagesDrawn.get(), branchNodesDrawn.get(),
                legacyFallbacksDrawn.get(), noContentRenderPasses.get(),
                renderPlanBuilds.get(), renderPlanReuses.get(),
                renderPlanQuads.get(), renderPlanTextureGroups.get(),
                rawBatchSubmissions.get(),
                sourcePresent.get(), sourceAbsent.get(), sourceDeferred.get(),
                sourceFailed.get(), tasksCancelledBeforeRun.get(),
                tasksCompletedButDiscarded.get(), branchUpdatesQueued.get(),
                branchUpdatesDropped.get(), sourceLeasesOpened.get(),
                sourceLeasesClosed.get(), sourceDecodesCancelledNoConsumers.get(),
                caveSourceLeaseCancelledViewportExit.get(),
                caveSourceLeaseCancelledGenerationSuperseded.get(),
                caveSourceRetainedAfterViewportExit.get(),
                caveSourceArchiveCommittedOffscreen.get(),
                caveHandoffOffered.get(), caveHandoffAccepted.get(),
                caveHandoffRejectedSuperseded.get(),
                caveHandoffRejectedPlannerMismatch.get(),
                caveHandoffRetainedLoadingGeneration.get());
    }

    public String compactSummary() {
        Snapshot s = snapshot();
        return "requests[minimap=" + s.viewportRequests(MapRequestLane.MINIMAP)
                + ",fullscreen=" + s.viewportRequests(MapRequestLane.FULLSCREEN)
                + "] admissions[minimap=" + s.pageAdmissions(MapRequestLane.MINIMAP)
                + ",fullscreen=" + s.pageAdmissions(MapRequestLane.FULLSCREEN)
                + "] exact[queued=" + s.exactBuildQueued()
                + ",completed=" + s.exactBuildCompleted()
                + ",discarded=" + s.exactBuildDiscarded()
                + ",gpu=" + s.exactGpuReady()
                + ",drawn=" + s.exactPagesDrawn()
                + "] branchDrawn=" + s.branchNodesDrawn()
                + " legacyDrawn=" + s.legacyFallbacksDrawn()
                + " noContent=" + s.noContentRenderPasses()
                + " plan[build=" + s.renderPlanBuilds()
                + ",reuse=" + s.renderPlanReuses()
                + ",quads=" + s.renderPlanQuads()
                + ",groups=" + s.renderPlanTextureGroups()
                + ",submits=" + s.rawBatchSubmissions() + "]"
                + " source[present=" + s.sourcePresent()
                + ",absent=" + s.sourceAbsent()
                + ",deferred=" + s.sourceDeferred()
                + ",failed=" + s.sourceFailed()
                + "] cancelled=" + s.tasksCancelledBeforeRun()
                + " completedDiscarded=" + s.tasksCompletedButDiscarded()
                + " branch[queued=" + s.branchUpdatesQueued()
                + ",dropped=" + s.branchUpdatesDropped() + "]"
                + " leases[open=" + s.sourceLeasesOpened()
                + ",closed=" + s.sourceLeasesClosed()
                + ",cancelNoConsumer=" + s.sourceDecodesCancelledNoConsumers() + "]"
                + " caveSource[cancelViewport="
                + s.caveSourceLeaseCancelledViewportExit()
                + ",cancelGeneration="
                + s.caveSourceLeaseCancelledGenerationSuperseded()
                + ",retainedViewport=" + s.caveSourceRetainedAfterViewportExit()
                + ",archiveOffscreen="
                + s.caveSourceArchiveCommittedOffscreen() + "]"
                + " caveHandoff[offered=" + s.caveHandoffOffered()
                + ",accepted=" + s.caveHandoffAccepted()
                + ",superseded=" + s.caveHandoffRejectedSuperseded()
                + ",planner=" + s.caveHandoffRejectedPlannerMismatch()
                + ",loading=" + s.caveHandoffRetainedLoadingGeneration() + "]"
                + " latencyMs[read=" + averageMillis(MapPipelineStage.ANVIL_READ)
                + ",datafix=" + averageMillis(MapPipelineStage.DATA_FIX)
                + ",decode=" + averageMillis(MapPipelineStage.CHUNK_DECODE)
                + ",sourceWait=" + averageMillis(MapPipelineStage.SOURCE_WAIT)
                + ",surfaceCapture=" + averageMillis(MapPipelineStage.SURFACE_CAPTURE)
                + "/" + maxMillis(MapPipelineStage.SURFACE_CAPTURE)
                + ",surfaceAssembly=" + averageMillis(MapPipelineStage.SURFACE_ASSEMBLY)
                + "/" + maxMillis(MapPipelineStage.SURFACE_ASSEMBLY)
                + ",exactBuild=" + averageMillis(MapPipelineStage.EXACT_BUILD)
                + ",exactUpload=" + averageMillis(MapPipelineStage.EXACT_UPLOAD)
                + ",branch=" + averageMillis(MapPipelineStage.BRANCH_DERIVE) + "]"
                + schedulerSummary() + surfaceSourceSummary()
                + persistenceSummary() + gpuSummary() + residencySummary();
    }

    private String schedulerSummary() {
        MapWorkScheduler.Snapshot scheduler = MapWorkScheduler.snapshot();
        return " work[cpu=" + scheduler.cpuActive() + "/" + scheduler.cpuQueued()
                + ",cpuCost=" + scheduler.cpuQueuedCost()
                + ",io=" + scheduler.ioActive() + "/" + scheduler.ioQueued()
                + ",ioCost=" + scheduler.ioQueuedCost()
                + ",lanes=" + scheduler.cpuQueued(MapRequestLane.MINIMAP)
                + "/" + scheduler.cpuQueued(MapRequestLane.FULLSCREEN)
                + "/" + scheduler.cpuQueued(MapRequestLane.BACKGROUND)
                + "/" + scheduler.cpuQueued(MapRequestLane.PREFETCH)
                + ",done=" + scheduler.completed(MapRequestLane.MINIMAP)
                + "/" + scheduler.completed(MapRequestLane.FULLSCREEN)
                + "/" + scheduler.completed(MapRequestLane.BACKGROUND)
                + "/" + scheduler.completed(MapRequestLane.PREFETCH)
                + ",denied=" + scheduler.denied(MapRequestLane.MINIMAP)
                + "/" + scheduler.denied(MapRequestLane.FULLSCREEN)
                + "/" + scheduler.denied(MapRequestLane.BACKGROUND)
                + "/" + scheduler.denied(MapRequestLane.PREFETCH) + "]";
    }

    private String surfaceSourceSummary() {
        SurfaceRegionSourceDatabase.Snapshot source =
                SurfaceRegionSourceDatabase.getInstance().snapshot();
        return " sourceDb[regions=" + source.regions()
                + ",chunks=" + source.residentChunks()
                + ",views=" + source.pinnedViews()
                + ",closing=" + source.closingRegions() + "]";
    }

    private String persistenceSummary() {
        MapManager manager = MapManager.getInstance();
        MapLightManager light = MapLightManager.getInstance();
        return " persist[surfaceDirty=" + manager.dirtyRegionCount()
                + ",surfacePending=" + RegionDataStore.pendingSaveCount()
                + ",surfaceFlight=" + RegionDataStore.inFlightSaveCount()
                + ",lightDirty=" + light.dirtyRegionCount()
                + ",lightPending=" + light.pendingSaveCount()
                + ",lightFlight=" + light.inFlightSaveCount() + "]";
    }

    private String gpuSummary() {
        MapGpuBudgetController.Snapshot gpu =
                MapGpuBudgetController.getInstance().snapshot();
        return " gpu[reservedMs=" + millis(gpu.reservedNanos())
                + ",minimapMs=" + millis(gpu.minimapReservedNanos())
                + ",bytesKiB=" + (gpu.reservedBytes() / 1024L)
                + ",minimapKiB=" + (gpu.minimapReservedBytes() / 1024L)
                + ",surface=" + millis(gpu.surfaceExactPredictionNanos())
                + ",cave=" + millis(gpu.caveExactPredictionNanos())
                + ",branch=" + millis(gpu.branchPredictionNanos()) + "]";
    }

    private String residencySummary() {
        MapResidencyManager.Snapshot residency =
                MapResidencyManager.getInstance().snapshot();
        MapMemoryBudgetPolicy.AtlasSnapshot atlas =
                MapMemoryBudgetPolicy.atlasSnapshot();
        return " residency[entries=" + residency.residentEntries()
                + ",pinned=" + residency.pinnedEntries()
                + ",MiB=" + formatMiB(residency.estimatedBytes())
                + "/" + formatMiB(residency.budgetBytes())
                + ",evict=" + residency.globalEvictions()
                + ",fail=" + residency.budgetFailures() + "]"
                + " atlas[allocated=" + formatMiB(atlas.allocatedBytes())
                + ",planned=" + formatMiB(atlas.plannedAtlasBytes())
                + ",available=" + (atlas.detectedAvailableVramBytes() > 0L
                        ? formatMiB(atlas.detectedAvailableVramBytes()) : "unknown")
                + "]";
    }

    private String maxMillis(MapPipelineStage stage) {
        long nanos = stageMaxNanos.get(stage.ordinal());
        return String.format(java.util.Locale.ROOT, "%.2f",
                nanos / 1_000_000.0);
    }

    private static String formatMiB(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0);
    }

    private static String millis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1_000_000.0);
    }

    private String averageMillis(MapPipelineStage stage) {
        StageSnapshot snapshot = stageSnapshot(stage);
        if (snapshot.count() == 0L) return "0";
        double millis = (snapshot.totalNanos() / (double) snapshot.count()) / 1_000_000.0;
        return String.format(java.util.Locale.ROOT, "%.2f", millis);
    }

    private static MapRequestLane safe(MapRequestLane lane) {
        return lane == null ? MapRequestLane.FULLSCREEN : lane;
    }

    public record Snapshot(long[] viewportRequestsByLane,
            long[] pageAdmissionsByLane,
            long exactBuildQueued,
            long exactBuildCompleted,
            long exactBuildDiscarded,
            long exactGpuReady,
            long exactPagesDrawn,
            long branchNodesDrawn,
            long legacyFallbacksDrawn,
            long noContentRenderPasses,
            long renderPlanBuilds,
            long renderPlanReuses,
            long renderPlanQuads,
            long renderPlanTextureGroups,
            long rawBatchSubmissions,
            long sourcePresent,
            long sourceAbsent,
            long sourceDeferred,
            long sourceFailed,
            long tasksCancelledBeforeRun,
            long tasksCompletedButDiscarded,
            long branchUpdatesQueued,
            long branchUpdatesDropped,
            long sourceLeasesOpened,
            long sourceLeasesClosed,
            long sourceDecodesCancelledNoConsumers,
            long caveSourceLeaseCancelledViewportExit,
            long caveSourceLeaseCancelledGenerationSuperseded,
            long caveSourceRetainedAfterViewportExit,
            long caveSourceArchiveCommittedOffscreen,
            long caveHandoffOffered,
            long caveHandoffAccepted,
            long caveHandoffRejectedSuperseded,
            long caveHandoffRejectedPlannerMismatch,
            long caveHandoffRetainedLoadingGeneration) {

        public long viewportRequests(MapRequestLane lane) {
            return viewportRequestsByLane[safe(lane).ordinal()];
        }

        public long pageAdmissions(MapRequestLane lane) {
            return pageAdmissionsByLane[safe(lane).ordinal()];
        }
    }

    public record RenderSnapshot(int exactPages, int branchNodes,
            int legacyFallbacks, boolean hadContent, long ageNanos,
            String projection, int hierarchyLevel) {
        public double ageMillis() {
            return ageNanos == Long.MAX_VALUE ? -1.0 : ageNanos / 1_000_000.0;
        }
    }

    public record StageSnapshot(long count, long totalNanos, long maxNanos) {
        public double averageMillis() {
            return count == 0L ? 0.0 : (totalNanos / (double) count) / 1_000_000.0;
        }

        public double maxMillis() {
            return maxNanos / 1_000_000.0;
        }
    }
}

}

/** Tracks actual allocated RGBA8 atlas storage, without counting slot contents twice. */
final class MapAtlasMemoryTracker {
    private static final MapAtlasMemoryTracker INSTANCE = new MapAtlasMemoryTracker();
    private final Map<String, Long> allocations = new ConcurrentHashMap<>();

    private MapAtlasMemoryTracker() {
    }

    public static MapAtlasMemoryTracker getInstance() {
        return INSTANCE;
    }

    public void register(String key, long bytes) {
        if (key == null || key.isBlank()) return;
        allocations.put(key, Math.max(0L, bytes));
        MapMemoryBudgetPolicy.refreshRuntimeVramBudget();
    }

    public void remove(String key) {
        if (key != null) allocations.remove(key);
    }

    public Snapshot snapshot() {
        long bytes = 0L;
        for (long value : allocations.values()) bytes += Math.max(0L, value);
        return new Snapshot(allocations.size(), bytes,
                MapMemoryBudgetPolicy.plannedAtlasBytes(),
                MapMemoryBudgetPolicy.residentContentBudgetBytes(),
                MapMemoryBudgetPolicy.detectedAvailableVramBytes());
    }

    public record Snapshot(int allocationCount, long allocatedBytes,
            long plannedAtlasBytes, long residentContentBudgetBytes,
            long detectedAvailableVramBytes) {
    }
}

/** Lightweight per-lane timing and queue telemetry for the unified scheduler. */
final class MapObservationTelemetry {
    public enum Lane {
        LIVE_CRITICAL,
        MUTATION_REPAIR,
        LIVE_VISIBLE,
        SAVED_VISIBLE,
        LAYER_WARMUP,
        ARCHIVE_BACKGROUND,
        PUBLICATION
    }

    private static final MapObservationTelemetry INSTANCE = new MapObservationTelemetry();
    private final java.util.EnumMap<Lane, MutableLane> lanes = new java.util.EnumMap<>(Lane.class);
    private volatile int mutationColumns;
    private volatile int mutationChunks;
    private volatile int mutationRegions;
    private volatile int generatedChunks;
    private volatile boolean pressure;
    private volatile int decodedRegions;
    private volatile int decodedChunks;
    private volatile long decodedBytes;
    private volatile long decodedTargetBytes;
    private volatile int decodeQueue;
    private volatile double heapPressure;
    private volatile int workGraphRegions;
    private volatile int workGraphDirty;
    private volatile int workGraphRunning;
    private volatile int workGraphReady;

    private MapObservationTelemetry() {
        for (Lane lane : Lane.values()) lanes.put(lane, new MutableLane());
    }

    public static MapObservationTelemetry getInstance() {
        return INSTANCE;
    }

    public synchronized void reset() {
        for (MutableLane lane : lanes.values()) {
            lane.runs = 0L;
            lane.units = 0L;
            lane.totalNanos = 0L;
            lane.lastNanos = 0L;
        }
        mutationColumns = 0;
        mutationChunks = 0;
        mutationRegions = 0;
        generatedChunks = 0;
        pressure = false;
        decodedRegions = 0;
        decodedChunks = 0;
        decodedBytes = 0L;
        decodedTargetBytes = 0L;
        decodeQueue = 0;
        heapPressure = 0.0D;
        workGraphRegions = 0;
        workGraphDirty = 0;
        workGraphRunning = 0;
        workGraphReady = 0;
    }

    public synchronized void record(Lane lane, long elapsedNanos, int units) {
        MutableLane value = lanes.get(lane);
        value.runs++;
        value.units += Math.max(0, units);
        value.totalNanos += Math.max(0L, elapsedNanos);
        value.lastNanos = Math.max(0L, elapsedNanos);
    }

    public void updateQueues(int mutationColumns, int mutationChunks, int mutationRegions,
            int generatedChunks, boolean pressure) {
        this.mutationColumns = mutationColumns;
        this.mutationChunks = mutationChunks;
        this.mutationRegions = mutationRegions;
        this.generatedChunks = generatedChunks;
        this.pressure = pressure;
    }

    public void updateDecodedSource(int regions, int chunks, long bytes,
            long targetBytes, int queue, double heapPressure) {
        this.decodedRegions = regions;
        this.decodedChunks = chunks;
        this.decodedBytes = bytes;
        this.decodedTargetBytes = targetBytes;
        this.decodeQueue = queue;
        this.heapPressure = heapPressure;
    }

    public void updateWorkGraph(int regions, int dirty, int running, int ready) {
        this.workGraphRegions = regions;
        this.workGraphDirty = dirty;
        this.workGraphRunning = running;
        this.workGraphReady = ready;
    }

    public synchronized Snapshot snapshot() {
        java.util.EnumMap<Lane, LaneSnapshot> copy = new java.util.EnumMap<>(Lane.class);
        for (Map.Entry<Lane, MutableLane> entry : lanes.entrySet()) {
            MutableLane lane = entry.getValue();
            copy.put(entry.getKey(), new LaneSnapshot(
                    lane.runs, lane.units, lane.totalNanos, lane.lastNanos));
        }
        return new Snapshot(Map.copyOf(copy), mutationColumns, mutationChunks, mutationRegions,
                generatedChunks, pressure, decodedRegions, decodedChunks,
                decodedBytes, decodedTargetBytes, decodeQueue, heapPressure,
                workGraphRegions, workGraphDirty, workGraphRunning, workGraphReady);
    }

    private static final class MutableLane {
        private long runs;
        private long units;
        private long totalNanos;
        private long lastNanos;
    }

    public record LaneSnapshot(long runs, long units,
            long totalNanos, long lastNanos) {
    }

    public record Snapshot(Map<Lane, LaneSnapshot> lanes,
            int mutationColumns, int mutationChunks, int mutationRegions,
            int generatedChunks, boolean pressure, int decodedRegions,
            int decodedChunks, long decodedBytes, long decodedTargetBytes,
            int decodeQueue, double heapPressure, int workGraphRegions,
            int workGraphDirty, int workGraphRunning, int workGraphReady) {
    }
}
