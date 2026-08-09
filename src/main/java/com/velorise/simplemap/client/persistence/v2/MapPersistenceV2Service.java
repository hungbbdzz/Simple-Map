package com.velorise.simplemap.client.persistence.v2;

import com.velorise.simplemap.client.MapConfig;
import com.velorise.simplemap.client.RegionDataStore;
import com.velorise.simplemap.client.cave.archive.CompactCaveTile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Non-destructive M10 sidecar writer. Legacy caches remain readable during
 * migration; successful SMR2 writes are authoritative only after CRC validation.
 */
public final class MapPersistenceV2Service {
    public record Summary(int queued, long surfaceCommitted,
            long caveCommitted, long failures, long recoveries) { }

    private static final MapPersistenceV2Service INSTANCE =
            new MapPersistenceV2Service();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SimpleMap-PersistenceV2");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService loader = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "SimpleMap-PersistenceV2-Read");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger queued = new AtomicInteger();
    private long surfaceCommitted;
    private long caveCommitted;
    private long failures;
    private long recoveries;

    private MapPersistenceV2Service() { }
    public static MapPersistenceV2Service getInstance() { return INSTANCE; }

    public CompletableFuture<Boolean> appendSurface(File dimensionDirectory,
            long worldIdentity, int regionX, int regionZ, long sourceRevision,
            long styleRevision, RegionDataStore.StoredRegion region) {
        if (dimensionDirectory == null || region == null) {
            return CompletableFuture.completedFuture(false);
        }
        byte[] payload;
        try {
            payload = encodeSurface(region);
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return append(dimensionDirectory, worldIdentity, regionX, regionZ,
                new RegionContainerV2.Record(
                        new RegionContainerV2.RecordKey(
                                RegionContainerV2.RecordType.SURFACE_SOURCE, 0),
                        sourceRevision, styleRevision, payload), true);
    }

    public CompletableFuture<Boolean> appendCave(File dimensionDirectory,
            long worldIdentity, CompactCaveTile tile, long styleRevision) {
        if (dimensionDirectory == null || tile == null) {
            return CompletableFuture.completedFuture(false);
        }
        byte[] payload;
        try {
            payload = encodeCave(tile);
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        int regionX = tile.chunkX() >> 5;
        int regionZ = tile.chunkZ() >> 5;
        int localKey = ((tile.chunkZ() & 31) << 5) | (tile.chunkX() & 31);
        // Keep Accurate and Vanilla archives independent inside the same SMR2
        // region. The style field is a persistent schema signature, not the
        // session-local style generation supplied by older callers.
        localKey |= Math.max(0, MapConfig.blockColourMode) << 10;
        return append(dimensionDirectory, worldIdentity, regionX, regionZ,
                new RegionContainerV2.Record(
                        new RegionContainerV2.RecordKey(
                                RegionContainerV2.RecordType.CAVE_ARCHIVE, localKey),
                        tile.revision(), caveArchiveStyleSignature(), payload), false);
    }

    /**
     * Replays colour-schema-isolated cave archives at world open. PASS56 wrote
     * SMR2 records but never read them, so every client restart repeated the complete
     * Anvil/DataFixer scan before a Layered Top-Y could use the archive.
     */
    public CompletableFuture<Integer> loadCaveArchives(File dimensionDirectory,
            long worldIdentity, Consumer<CompactCaveTile> consumer) {
        if (dimensionDirectory == null || consumer == null) {
            return CompletableFuture.completedFuture(0);
        }
        Path containerDirectory = dimensionDirectory.toPath().resolve("containers-v2");
        if (!Files.isDirectory(containerDirectory)) {
            return CompletableFuture.completedFuture(0);
        }
        return CompletableFuture.supplyAsync(() -> {
            int loaded = 0;
            try (var paths = Files.list(containerDirectory)) {
                for (Path path : (Iterable<Path>) paths
                        .filter(candidate -> candidate.getFileName().toString()
                                .endsWith(".smr2"))::iterator) {
                    try {
                        RegionContainerV2.ReadResult result = RegionContainerV2.read(path);
                        if (result.header() == null
                                || result.header().worldIdentity() != worldIdentity) continue;
                        for (RegionContainerV2.Record record : result.latest().values()) {
                            if (record.key().type()
                                    != RegionContainerV2.RecordType.CAVE_ARCHIVE
                                    || record.styleRevision()
                                            != caveArchiveStyleSignature()) continue;
                            CompactCaveTile tile = decodeCave(record.payload());
                            if (tile == null) continue;
                            consumer.accept(tile);
                            loaded++;
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // One corrupt region sidecar must not block the remaining map.
                    }
                }
            } catch (IOException ignored) {
                return loaded;
            }
            return loaded;
        }, loader);
    }

    /**
     * Loads only the compact cave records belonging to one 64x64 exact page.
     * A page is four-by-four chunks and a Minecraft region is 32x32 chunks, so
     * page boundaries never cross an SMR2 region boundary. This is the random-
     * access resident refill used after the archive LRU evicts an indexed tile.
     */
    public CompletableFuture<Integer> loadCaveArchivePage(
            File dimensionDirectory, long worldIdentity, int globalPageX,
            int globalPageZ, Consumer<CompactCaveTile> consumer) {
        if (dimensionDirectory == null || consumer == null) {
            return CompletableFuture.completedFuture(0);
        }
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        int regionX = Math.floorDiv(firstChunkX, 32);
        int regionZ = Math.floorDiv(firstChunkZ, 32);
        Path path = containerPath(dimensionDirectory, regionX, regionZ);
        if (!Files.isRegularFile(path)) {
            return CompletableFuture.completedFuture(0);
        }
        int localStartX = Math.floorMod(firstChunkX, 32);
        int localStartZ = Math.floorMod(firstChunkZ, 32);
        long expectedStyle = caveArchiveStyleSignature();
        return CompletableFuture.supplyAsync(() -> {
            int loaded = 0;
            try {
                RegionContainerV2.ReadResult result = RegionContainerV2.read(path);
                if (result.header() == null
                        || result.header().worldIdentity() != worldIdentity
                        || result.header().regionX() != regionX
                        || result.header().regionZ() != regionZ) return 0;
                for (RegionContainerV2.Record record : result.latest().values()) {
                    if (record.key().type()
                            != RegionContainerV2.RecordType.CAVE_ARCHIVE
                            || record.styleRevision() != expectedStyle) continue;
                    int localChunk = record.key().localKey() & 0x3FF;
                    int localX = localChunk & 31;
                    int localZ = (localChunk >>> 5) & 31;
                    if (localX < localStartX || localX >= localStartX + 4
                            || localZ < localStartZ || localZ >= localStartZ + 4) {
                        continue;
                    }
                    CompactCaveTile tile = decodeCave(record.payload());
                    if (tile == null
                            || Math.floorDiv(tile.chunkX(), 4) != globalPageX
                            || Math.floorDiv(tile.chunkZ(), 4) != globalPageZ) {
                        continue;
                    }
                    consumer.accept(tile);
                    loaded++;
                }
            } catch (IOException | RuntimeException ignored) {
                return loaded;
            }
            return loaded;
        }, loader);
    }

    /**
     * Persistent archive colour schema. CVR/SMR2 store resolved raw material
     * colours, so replay is valid only for the same colour mode and schema.
     */
    private static long caveArchiveStyleSignature() {
        return 0x4341563600000000L
                | (Math.max(0, MapConfig.blockColourMode) & 0xFFFFL);
    }

    public synchronized Summary summary() {
        return new Summary(queued.get(), surfaceCommitted, caveCommitted,
                failures, recoveries);
    }

    private CompletableFuture<Boolean> append(File dimensionDirectory,
            long worldIdentity, int regionX, int regionZ,
            RegionContainerV2.Record record, boolean surface) {
        Path path = containerPath(dimensionDirectory, regionX, regionZ);
        queued.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            try {
                RegionContainerV2.Header header = new RegionContainerV2.Header(
                        worldIdentity, regionX, regionZ, 1);
                boolean recovered = RegionContainerV2.append(path, header, record);
                synchronized (this) {
                    if (surface) surfaceCommitted++;
                    else caveCommitted++;
                    if (recovered) recoveries++;
                }
                if (path.toFile().length() > 32L * 1024L * 1024L) {
                    RegionContainerV2.compact(path);
                }
                return true;
            } catch (IOException exception) {
                synchronized (this) { failures++; }
                return false;
            } finally {
                queued.decrementAndGet();
            }
        }, writer);
    }

    private static Path containerPath(File dimensionDirectory,
            int regionX, int regionZ) {
        return dimensionDirectory.toPath().resolve("containers-v2")
                .resolve("r." + regionX + "." + regionZ + ".smr2");
    }

    private static byte[] encodeSurface(RegionDataStore.StoredRegion region)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4 * 1024 * 1024);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(region.pixels().length);
            for (long pixel : region.pixels()) output.writeLong(pixel);
            output.writeInt(region.tints().length);
            for (int tint : region.tints()) output.writeInt(tint);
            output.writeInt(region.completeChunks().length);
            for (long word : region.completeChunks()) output.writeLong(word);
            writeStrings(output, region.biomePalette());
            writeStrings(output, region.blockPalette());
        }
        return bytes.toByteArray();
    }

    private static byte[] encodeCave(CompactCaveTile tile) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(tile.chunkX());
            output.writeInt(tile.chunkZ());
            output.writeLong(tile.revision());
            output.writeInt(tile.runCount());
            for (int column = 0; column < 256; column++) {
                output.writeByte(tile.status(column).ordinal());
                int count = tile.runEnd(column) - tile.runStart(column);
                output.writeShort(count);
                for (int run = tile.runStart(column); run < tile.runEnd(column); run++) {
                    output.writeShort(tile.topY(run));
                    output.writeShort(tile.floorY(run));
                    output.writeInt(tile.materialId(run));
                    output.writeShort(tile.biomeId(run));
                    output.writeByte(tile.blockLight(run));
                    output.writeByte(tile.skyLight(run));
                    output.writeByte(tile.fluidDepth(run));
                    output.writeByte(tile.flags(run));
                }
            }
        }
        return bytes.toByteArray();
    }

    private static CompactCaveTile decodeCave(byte[] payload) throws IOException {
        if (payload == null) return null;
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(payload))) {
            int chunkX = input.readInt();
            int chunkZ = input.readInt();
            long revision = input.readLong();
            int declaredRuns = input.readInt();
            if (declaredRuns < 0 || declaredRuns > 1_000_000) {
                throw new IOException("invalid cave run count");
            }
            int[] offsets = new int[CompactCaveTile.COLUMNS + 1];
            short[] top = new short[declaredRuns];
            short[] floor = new short[declaredRuns];
            int[] material = new int[declaredRuns];
            short[] biome = new short[declaredRuns];
            byte[] block = new byte[declaredRuns];
            byte[] sky = new byte[declaredRuns];
            byte[] fluid = new byte[declaredRuns];
            byte[] flags = new byte[declaredRuns];
            byte[] statuses = new byte[CompactCaveTile.COLUMNS];
            int cursor = 0;
            for (int column = 0; column < CompactCaveTile.COLUMNS; column++) {
                offsets[column] = cursor;
                statuses[column] = input.readByte();
                int count = input.readUnsignedShort();
                if (cursor + count > declaredRuns) {
                    throw new IOException("cave run overflow");
                }
                for (int run = 0; run < count; run++) {
                    top[cursor] = input.readShort();
                    floor[cursor] = input.readShort();
                    material[cursor] = input.readInt();
                    biome[cursor] = input.readShort();
                    block[cursor] = input.readByte();
                    sky[cursor] = input.readByte();
                    fluid[cursor] = input.readByte();
                    flags[cursor] = input.readByte();
                    cursor++;
                }
            }
            offsets[CompactCaveTile.COLUMNS] = cursor;
            if (cursor != declaredRuns) throw new IOException("cave run mismatch");
            return new CompactCaveTile(chunkX, chunkZ, revision, offsets,
                    top, floor, material, biome, block, sky, fluid, flags, statuses);
        }
    }

    private static void writeStrings(DataOutputStream output, String[] values)
            throws IOException {
        String[] safe = values == null ? new String[0] : values;
        output.writeInt(safe.length);
        for (String value : safe) {
            byte[] encoded = (value == null ? "" : value)
                    .getBytes(StandardCharsets.UTF_8);
            output.writeInt(encoded.length);
            output.write(encoded);
        }
    }
}
