package com.velorise.simplemap.client.persistence.v2;

import com.velorise.simplemap.client.RegionDataStore;
import com.velorise.simplemap.client.cave.archive.CompactCaveTile;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
        return append(dimensionDirectory, worldIdentity, regionX, regionZ,
                new RegionContainerV2.Record(
                        new RegionContainerV2.RecordKey(
                                RegionContainerV2.RecordType.CAVE_ARCHIVE, localKey),
                        tile.revision(), styleRevision, payload), false);
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
