package com.velorise.simplemap.client.cave.archive;

import com.velorise.simplemap.client.cave.CaveChunkTile;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Compact cave archive partitioned by canonical dimension id.
 *
 * <p>Xaero owns map layers from a {@code MapDimension}; a chunk coordinate is
 * therefore never a world-global identity. SimpleMap used to keep this service
 * session-scoped but dimension-blind, so Overworld/Nether/End/custom dimensions
 * at the same chunk coordinates could share fingerprints, completeness and
 * resident compact tiles across a dimension handoff. PASS125 makes dimension an
 * explicit archive ownership boundary while retaining lightweight indexed
 * identity for recently visited dimensions.</p>
 */
public final class CaveArchiveV2Service {
    public record Summary(int tiles, long bytes, long ingested,
            long replaced, long staleIgnored) { }

    private static final CaveArchiveV2Service INSTANCE =
            new CaveArchiveV2Service();
    private static final int MAX_RESIDENT_TILES = 32768;
    private static final long MAX_RESIDENT_BYTES = 192L * 1024L * 1024L;
    /** Metadata-only inactive partitions are cheap; bound pathological modpacks. */
    private static final int MAX_DIMENSION_PARTITIONS = 8;
    private static final String UNKNOWN_DIMENSION = "simplemap:unknown";

    private static final class Partition {
        final LinkedHashMap<Long, CompactCaveTile> tiles =
                new LinkedHashMap<>(256, 0.75f, true);
        final Map<Long, Long> pageFingerprints = new HashMap<>();
        final Map<Long, Long> indexedContributions = new HashMap<>();
        final Map<Long, Long> indexedFingerprints = new HashMap<>();
        final Set<Long> indexedCompleteChunks = new HashSet<>();
        final Set<Long> indexedFullProjectionChunks = new HashSet<>();
        long bytes;
        long ingested;
        long replaced;
        long staleIgnored;

        void dropResidents() {
            tiles.clear();
            bytes = 0L;
        }

        void clear() {
            dropResidents();
            pageFingerprints.clear();
            indexedContributions.clear();
            indexedFingerprints.clear();
            indexedCompleteChunks.clear();
            indexedFullProjectionChunks.clear();
            ingested = 0L;
            replaced = 0L;
            staleIgnored = 0L;
        }
    }

    /** Access ordered so very old custom dimensions can be forgotten safely. */
    private final LinkedHashMap<String, Partition> partitions =
            new LinkedHashMap<>(8, 0.75f, true);
    private String activeDimension = UNKNOWN_DIMENSION;
    private Partition activePartition;

    private CaveArchiveV2Service() {
        activePartition = new Partition();
        partitions.put(activeDimension, activePartition);
    }

    public static CaveArchiveV2Service getInstance() { return INSTANCE; }

    /**
     * Selects the archive namespace before persistence replay/live ingestion for a
     * map dimension. Inactive resident tiles are released so dimensions do not each
     * reserve a 192 MiB working set, while indexed fingerprints/completeness remain
     * available for idempotent replay when the user returns.
     */
    public synchronized void activateDimension(String dimension) {
        String next = normalizeDimension(dimension);
        if (next.equals(activeDimension)) return;

        Partition previous = activePartition;
        if (previous != null) previous.dropResidents();

        activeDimension = next;
        activePartition = partitions.computeIfAbsent(next, ignored -> new Partition());
        trimPartitions();
    }

    public synchronized String activeDimension() {
        return activeDimension;
    }

    public synchronized boolean ingest(CaveChunkTile.Snapshot snapshot) {
        CompactCaveTile compact = CompactCaveTile.fromLegacy(snapshot);
        if (compact == null) return false;
        return ingestCompact(activePartition, compact);
    }

    public synchronized CompactCaveTile get(int chunkX, int chunkZ) {
        return activePartition.tiles.get(pack(chunkX, chunkZ));
    }

    /** Copies a rectangular resident window under one monitor hold. */
    public synchronized void fillWindow(int firstChunkX, int firstChunkZ,
            int edge, CompactCaveTile[] target) {
        if (edge <= 0 || target == null || target.length < edge * edge) {
            throw new IllegalArgumentException("archive window");
        }
        Partition partition = activePartition;
        int index = 0;
        for (int dz = 0; dz < edge; dz++) {
            for (int dx = 0; dx < edge; dx++) {
                target[index++] = partition.tiles.get(pack(firstChunkX + dx,
                        firstChunkZ + dz));
            }
        }
    }

    public synchronized boolean isResident(int chunkX, int chunkZ) {
        return activePartition.tiles.get(pack(chunkX, chunkZ)) != null;
    }

    public synchronized boolean hasCompleteChunk(int chunkX, int chunkZ) {
        CompactCaveTile tile = activePartition.tiles.get(pack(chunkX, chunkZ));
        return tile != null && tile.completeCoverage();
    }

    public synchronized boolean hasFullProjectionChunk(int chunkX, int chunkZ) {
        CompactCaveTile tile = activePartition.tiles.get(pack(chunkX, chunkZ));
        return tile != null && tile.fullProjectionCoverage();
    }

    /** Used by persistence replay without rebuilding a legacy snapshot. */
    public synchronized boolean ingest(CompactCaveTile compact) {
        if (compact == null) return false;
        return ingestCompact(activePartition, compact);
    }

    /** 16-bit central-page resident coverage, ordered localX * 4 + localZ. */
    public synchronized int residentProjectionMask(int globalPageX,
            int globalPageZ, boolean fullProjection) {
        Partition partition = activePartition;
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        int mask = 0;
        for (int localZ = 0; localZ < 4; localZ++) {
            for (int localX = 0; localX < 4; localX++) {
                CompactCaveTile tile = partition.tiles.get(pack(
                        firstChunkX + localX, firstChunkZ + localZ));
                boolean covered = tile != null && (fullProjection
                        ? tile.fullProjectionCoverage()
                        : tile.completeCoverage());
                if (covered) mask |= 1 << (localX * 4 + localZ);
            }
        }
        return mask;
    }

    /** 16-bit central-page residency regardless of projection completeness. */
    public synchronized int residentAnyMask(int globalPageX, int globalPageZ) {
        Partition partition = activePartition;
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        int mask = 0;
        for (int localZ = 0; localZ < 4; localZ++) {
            for (int localX = 0; localX < 4; localX++) {
                if (partition.tiles.containsKey(pack(firstChunkX + localX,
                        firstChunkZ + localZ))) {
                    mask |= 1 << (localX * 4 + localZ);
                }
            }
        }
        return mask;
    }

    /** 16-bit persistent identity coverage, independent from resident LRU state. */
    public synchronized int indexedAnyMask(int globalPageX, int globalPageZ) {
        Partition partition = activePartition;
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        int mask = 0;
        for (int localZ = 0; localZ < 4; localZ++) {
            for (int localX = 0; localX < 4; localX++) {
                if (partition.indexedFingerprints.containsKey(pack(
                        firstChunkX + localX, firstChunkZ + localZ))) {
                    mask |= 1 << (localX * 4 + localZ);
                }
            }
        }
        return mask;
    }

    /** Installs persistent source identity without forcing tile residency. */
    public synchronized boolean index(CompactCaveTile compact) {
        if (compact == null) return false;
        Partition partition = activePartition;
        long key = pack(compact.chunkX(), compact.chunkZ());
        long contentFingerprint = compact.contentFingerprint();
        long previousFingerprint = partition.indexedFingerprints.getOrDefault(
                key, Long.MIN_VALUE);
        if (previousFingerprint == contentFingerprint) return false;

        long previousContribution = partition.indexedContributions.getOrDefault(key, 0L);
        long currentContribution = tileContribution(compact);
        updatePageFingerprint(partition, compact.chunkX(), compact.chunkZ(),
                previousContribution, currentContribution);
        partition.indexedContributions.put(key, currentContribution);
        partition.indexedFingerprints.put(key, contentFingerprint);
        setCoverage(partition, key, compact);
        return true;
    }

    /** 16-bit central-page indexed coverage that survives resident LRU eviction. */
    public synchronized int indexedProjectionMask(int globalPageX,
            int globalPageZ, boolean fullProjection) {
        Partition partition = activePartition;
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        Set<Long> coverage = fullProjection
                ? partition.indexedFullProjectionChunks
                : partition.indexedCompleteChunks;
        int mask = 0;
        for (int localZ = 0; localZ < 4; localZ++) {
            for (int localX = 0; localX < 4; localX++) {
                if (coverage.contains(pack(firstChunkX + localX,
                        firstChunkZ + localZ))) {
                    mask |= 1 << (localX * 4 + localZ);
                }
            }
        }
        return mask;
    }

    public synchronized boolean hasCompletePage(int globalPageX, int globalPageZ) {
        return residentProjectionMask(globalPageX, globalPageZ, false) == 0xFFFF;
    }

    public synchronized boolean hasFullProjectionPage(int globalPageX,
            int globalPageZ) {
        return residentProjectionMask(globalPageX, globalPageZ, true) == 0xFFFF;
    }

    public synchronized boolean hasIndexedCompleteChunk(int chunkX, int chunkZ) {
        return activePartition.indexedCompleteChunks.contains(pack(chunkX, chunkZ));
    }

    public synchronized boolean hasIndexedFullProjectionChunk(int chunkX,
            int chunkZ) {
        return activePartition.indexedFullProjectionChunks.contains(pack(chunkX, chunkZ));
    }

    public synchronized boolean hasIndexedCompletePage(int globalPageX,
            int globalPageZ) {
        return indexedProjectionMask(globalPageX, globalPageZ, false) == 0xFFFF;
    }

    public synchronized boolean hasIndexedFullProjectionPage(int globalPageX,
            int globalPageZ) {
        return indexedProjectionMask(globalPageX, globalPageZ, true) == 0xFFFF;
    }

    private boolean ingestCompact(Partition partition, CompactCaveTile compact) {
        long key = pack(compact.chunkX(), compact.chunkZ());
        long contentFingerprint = compact.contentFingerprint();
        long indexedFingerprint = partition.indexedFingerprints.getOrDefault(
                key, Long.MIN_VALUE);
        if (indexedFingerprint == contentFingerprint) {
            partition.staleIgnored++;
            CompactCaveTile resident = partition.tiles.get(key);
            if (resident == null) {
                partition.tiles.put(key, compact);
                partition.bytes += compact.estimatedBytes();
                trim(partition);
            }
            return false;
        }

        CompactCaveTile residentPrevious = partition.tiles.get(key);
        if (residentPrevious != null) {
            partition.bytes -= residentPrevious.estimatedBytes();
            partition.replaced++;
        } else if (indexedFingerprint != Long.MIN_VALUE) {
            partition.replaced++;
        }

        long previousContribution = partition.indexedContributions.getOrDefault(key, 0L);
        long currentContribution = tileContribution(compact);
        updatePageFingerprint(partition, compact.chunkX(), compact.chunkZ(),
                previousContribution, currentContribution);
        partition.indexedContributions.put(key, currentContribution);
        partition.indexedFingerprints.put(key, contentFingerprint);
        setCoverage(partition, key, compact);

        partition.tiles.put(key, compact);
        partition.bytes += compact.estimatedBytes();
        partition.ingested++;
        trim(partition);
        return true;
    }

    public synchronized Summary summary() {
        Partition partition = activePartition;
        return new Summary(partition.tiles.size(), partition.bytes,
                partition.ingested, partition.replaced, partition.staleIgnored);
    }

    /** Source revision consumed by exact-page, CIMG and branch cache validation. */
    public synchronized long pageRevision(int globalPageX, int globalPageZ) {
        return activePartition.pageFingerprints.getOrDefault(
                pack(globalPageX, globalPageZ), 0L);
    }

    /** True world/session reset. Dimension switching uses activateDimension(). */
    public synchronized void clear() {
        for (Partition partition : partitions.values()) partition.clear();
        partitions.clear();
        activeDimension = UNKNOWN_DIMENSION;
        activePartition = new Partition();
        partitions.put(activeDimension, activePartition);
    }

    private static void setCoverage(Partition partition, long key,
            CompactCaveTile compact) {
        if (compact.completeCoverage()) partition.indexedCompleteChunks.add(key);
        else partition.indexedCompleteChunks.remove(key);
        if (compact.fullProjectionCoverage()) {
            partition.indexedFullProjectionChunks.add(key);
        } else {
            partition.indexedFullProjectionChunks.remove(key);
        }
    }

    private static void updatePageFingerprint(Partition partition, int chunkX,
            int chunkZ, long previousContribution, long currentContribution) {
        int pageX = Math.floorDiv(chunkX, 4);
        int pageZ = Math.floorDiv(chunkZ, 4);
        long pageKey = pack(pageX, pageZ);
        long fingerprint = partition.pageFingerprints.getOrDefault(pageKey, 0L);
        if (previousContribution != 0L) fingerprint ^= previousContribution;
        if (currentContribution != 0L) fingerprint ^= currentContribution;
        if (fingerprint == 0L) partition.pageFingerprints.remove(pageKey);
        else partition.pageFingerprints.put(pageKey, fingerprint);
    }

    private static long tileContribution(CompactCaveTile tile) {
        long value = tile.contentFingerprint()
                ^ Long.rotateLeft(pack(tile.chunkX(), tile.chunkZ()), 17)
                ^ ((long) tile.runCount() << 32)
                ^ (tile.completeCoverage()
                        ? 0x6C8E9CF570932BD5L : 0xA5A5A5A55A5A5A5AL);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return value == 0L ? 1L : value;
    }

    private static void trim(Partition partition) {
        var iterator = partition.tiles.entrySet().iterator();
        while ((partition.tiles.size() > MAX_RESIDENT_TILES
                || partition.bytes > MAX_RESIDENT_BYTES) && iterator.hasNext()) {
            CompactCaveTile evicted = iterator.next().getValue();
            partition.bytes -= evicted.estimatedBytes();
            iterator.remove();
        }
    }

    private void trimPartitions() {
        var iterator = partitions.entrySet().iterator();
        while (partitions.size() > MAX_DIMENSION_PARTITIONS && iterator.hasNext()) {
            Map.Entry<String, Partition> entry = iterator.next();
            if (entry.getKey().equals(activeDimension)) continue;
            entry.getValue().clear();
            iterator.remove();
        }
    }

    private static String normalizeDimension(String dimension) {
        if (dimension == null || dimension.isBlank()) return UNKNOWN_DIMENSION;
        return dimension.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}
