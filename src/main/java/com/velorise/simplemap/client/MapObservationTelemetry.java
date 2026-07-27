package com.velorise.simplemap.client;

import java.util.EnumMap;
import java.util.Map;

/** Lightweight per-lane timing and queue telemetry for the unified scheduler. */
public final class MapObservationTelemetry {
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
    private final EnumMap<Lane, MutableLane> lanes = new EnumMap<>(Lane.class);
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
        EnumMap<Lane, LaneSnapshot> copy = new EnumMap<>(Lane.class);
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
