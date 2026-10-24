package com.velorise.simplemap.client;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import com.velorise.simplemap.client.color.SurfaceColorizer.SurfaceTintData;
import com.velorise.simplemap.client.color.*;
import com.velorise.simplemap.client.cave.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Binary I/O and palette-safe merging for surface {@code .smdat} regions. */
public final class RegionDataStore {
    private static final Logger LOGGER = LogManager.getLogger();
    public static final int REGION_SIZE = 512;
    public static final int PIXEL_COUNT = REGION_SIZE * REGION_SIZE;
    private static final int LEGACY_BYTES_PER_PIXEL = 6;
    private static final int VERSION_2_BYTES_PER_PIXEL = 8;
    private static final int VERSION_3_BYTES_PER_PIXEL = 12;
    public static final int COMPLETE_CHUNK_WORDS = SurfaceChunkCoverage.WORDS;
    private static final int MAX_PALETTE_ENTRIES = 65_535;
    private static final int MAX_COMPRESSED_FILE_BYTES = 8 * 1024 * 1024;

    /* Saves are coalesced by destination so chunk updates cannot create an unbounded queue. */
    private static final Map<String, SaveRequest> PENDING_SAVES = new ConcurrentHashMap<>();
    private static final Map<String, SaveRequest> IN_FLIGHT_SAVES = new ConcurrentHashMap<>();
    private static final AtomicBoolean SAVE_DRAIN_SCHEDULED = new AtomicBoolean();
    private static final int MAX_PENDING_SAVES = 8;
    private static final long SAVE_RETRY_DELAY_MS = 500L;

    public static final String FILE_EXT = ".smdat";

    public enum SaveStatus {
        COMMITTED,
        FAILED_RETRYING,
        SUPERSEDED,
        REJECTED
    }

    public record SaveResult(SaveStatus status, File directory, int regionX,
            int regionZ, Throwable failure) {
        public boolean success() { return status == SaveStatus.COMMITTED; }
        public boolean retrying() { return status == SaveStatus.FAILED_RETRYING; }
    }

    private RegionDataStore() {
    }

    public record StoredRegion(long[] pixels, int[] tints, String[] biomePalette,
            String[] blockPalette, long[] completeChunks) {
        public StoredRegion {
            if (pixels == null || pixels.length != PIXEL_COUNT) {
                throw new IllegalArgumentException("A SimpleMap region must contain exactly " + PIXEL_COUNT + " pixels");
            }
            if (tints == null || tints.length != PIXEL_COUNT) {
                throw new IllegalArgumentException("A SimpleMap region must contain exactly " + PIXEL_COUNT + " tint values");
            }
            biomePalette = biomePalette == null ? new String[0] : biomePalette;
            blockPalette = blockPalette == null ? new String[0] : blockPalette;
            completeChunks = completeChunks == null
                    ? inferCompleteChunks(pixels) : completeChunks;
            if (completeChunks.length != COMPLETE_CHUNK_WORDS) {
                throw new IllegalArgumentException("A SimpleMap region must contain exactly "
                        + COMPLETE_CHUNK_WORDS + " chunk coverage words");
            }
        }

        public StoredRegion(long[] pixels, int[] tints, String[] biomePalette,
                String[] blockPalette) {
            this(pixels, tints, biomePalette, blockPalette, null);
        }

        /** Backwards-compatible constructor for callers without tint metadata. */
        public StoredRegion(long[] pixels, String[] biomePalette, String[] blockPalette) {
            this(pixels, unknownTints(), biomePalette, blockPalette, null);
        }

        public StoredRegion deepCopy() {
            return new StoredRegion(Arrays.copyOf(pixels, pixels.length),
                    Arrays.copyOf(tints, tints.length),
                    Arrays.copyOf(biomePalette, biomePalette.length),
                    Arrays.copyOf(blockPalette, blockPalette.length),
                    Arrays.copyOf(completeChunks, completeChunks.length));
        }
    }

    private static long[] inferCompleteChunks(long[] pixels) {
        return SurfaceChunkCoverage.inferLegacy(pixels, REGION_SIZE,
                MapBlockData.EMPTY_PACKED);
    }

    private static int[] unknownTints() {
        int[] tints = new int[PIXEL_COUNT];
        Arrays.fill(tints, SurfaceTintData.UNKNOWN);
        return tints;
    }

    public static void saveAsync(File directory, int rx, int rz, long[] packedPixels, int[] tints,
            String[] biomePalette, String[] blockPalette) {
        // Compatibility/flush path: preserve the historical never-drop contract.
        // Normal client-tick persistence should call trySaveAsync() first so it
        // cannot retain snapshots for every loaded region at once.
        enqueueSave(directory, rx, rz, packedPixels, tints,
                biomePalette, blockPalette, null, true, null);
    }

    public static void saveAsync(File directory, int rx, int rz, long[] packedPixels,
            int[] tints, String[] biomePalette, String[] blockPalette,
            long[] completeChunks, Consumer<SaveResult> completion) {
        enqueueSave(directory, rx, rz, packedPixels, tints,
                biomePalette, blockPalette, completeChunks, true, completion);
    }

    public static boolean trySaveAsync(File directory, int rx, int rz,
            long[] packedPixels, int[] tints, String[] biomePalette,
            String[] blockPalette) {
        return enqueueSave(directory, rx, rz, packedPixels, tints,
                biomePalette, blockPalette, null, false, null);
    }

    public static void saveAsync(File directory, int rx, int rz,
            long[] packedPixels, int[] tints, String[] biomePalette,
            String[] blockPalette, Consumer<SaveResult> completion) {
        enqueueSave(directory, rx, rz, packedPixels, tints,
                biomePalette, blockPalette, null, true, completion);
    }


    /**
     * Enqueues a coalesced save and reports actual disk outcomes. ACCEPTED only
     * means the request is retained; COMMITTED is emitted after writeAtomic()
     * succeeds. Transient failures emit FAILED_RETRYING while retaining the same
     * immutable snapshot for retry.
     */
    public static boolean trySaveAsync(File directory, int rx, int rz,
            long[] packedPixels, int[] tints, String[] biomePalette,
            String[] blockPalette, Consumer<SaveResult> completion) {
        return enqueueSave(directory, rx, rz, packedPixels, tints,
                biomePalette, blockPalette, null, false, completion);
    }

    public static boolean trySaveAsync(File directory, int rx, int rz,
            long[] packedPixels, int[] tints, String[] biomePalette,
            String[] blockPalette, long[] completeChunks,
            Consumer<SaveResult> completion) {
        return enqueueSave(directory, rx, rz, packedPixels, tints,
                biomePalette, blockPalette, completeChunks, false, completion);
    }

    /**
     * Accepts arrays freshly created by Region.snapshot() and transfers their
     * ownership to the IO request without cloning another multi-MiB region.
     */
    public static boolean trySaveOwnedAsync(File directory, int rx, int rz,
            long[] packedPixels, int[] tints, String[] biomePalette,
            String[] blockPalette, long[] completeChunks,
            Consumer<SaveResult> completion) {
        return enqueueSave(directory, rx, rz, packedPixels, tints,
                biomePalette, blockPalette, completeChunks, false, completion,
                false);
    }

    public static boolean canAcceptSave(File directory, int rx, int rz) {
        if (directory == null) return false;
        String key = saveKey(directory, rx, rz);
        return PENDING_SAVES.containsKey(key)
                || IN_FLIGHT_SAVES.containsKey(key)
                || PENDING_SAVES.size() < MAX_PENDING_SAVES;
    }

    private static boolean enqueueSave(File directory, int rx, int rz,
            long[] packedPixels, int[] tints, String[] biomePalette,
            String[] blockPalette, long[] completeChunks, boolean force,
            Consumer<SaveResult> completion) {
        return enqueueSave(directory, rx, rz, packedPixels, tints, biomePalette,
                blockPalette, completeChunks, force, completion, true);
    }

    private static boolean enqueueSave(File directory, int rx, int rz,
            long[] packedPixels, int[] tints, String[] biomePalette,
            String[] blockPalette, long[] completeChunks, boolean force,
            Consumer<SaveResult> completion, boolean defensiveCopy) {
        if (directory == null || packedPixels == null || packedPixels.length != PIXEL_COUNT
                || tints == null || tints.length != PIXEL_COUNT
                || (completeChunks != null
                        && completeChunks.length != COMPLETE_CHUNK_WORDS)) {
            notifyCompletion(completion, new SaveResult(SaveStatus.REJECTED,
                    directory, rx, rz, null));
            return false;
        }
        String key = saveKey(directory, rx, rz);
        if (!force && !PENDING_SAVES.containsKey(key)
                && !IN_FLIGHT_SAVES.containsKey(key)
                && PENDING_SAVES.size() >= MAX_PENDING_SAVES) {
            notifyCompletion(completion, new SaveResult(SaveStatus.REJECTED,
                    directory, rx, rz, null));
            return false;
        }
        long[] ownedPixels = defensiveCopy
                ? Arrays.copyOf(packedPixels, packedPixels.length) : packedPixels;
        int[] ownedTints = defensiveCopy
                ? Arrays.copyOf(tints, tints.length) : tints;
        String[] ownedBiomes = defensiveCopy
                ? Arrays.copyOf(biomePalette, biomePalette.length) : biomePalette;
        String[] ownedBlocks = defensiveCopy
                ? Arrays.copyOf(blockPalette, blockPalette.length) : blockPalette;
        long[] ownedCoverage = completeChunks == null
                ? inferCompleteChunks(ownedPixels)
                : (defensiveCopy ? Arrays.copyOf(completeChunks, completeChunks.length)
                        : completeChunks);
        SaveRequest request = new SaveRequest(directory, rx, rz,
                ownedPixels, ownedTints, ownedBiomes, ownedBlocks, ownedCoverage,
                completion);
        SaveRequest previous = PENDING_SAVES.put(key, request);
        if (previous != null && previous != request) {
            previous.notifyResult(SaveStatus.SUPERSEDED, null);
        }
        scheduleSaveDrain(0L);
        return true;
    }

    public static void saveAsync(File directory, int rx, int rz, long[] packedPixels,
            String[] biomePalette, String[] blockPalette) {
        saveAsync(directory, rx, rz, packedPixels, unknownTints(), biomePalette, blockPalette);
    }

    /** Compatibility overload for callers that still expose object pixels at an API boundary. */
    public static void saveAsync(File directory, int rx, int rz, MapBlockData[] pixels,
            String[] biomePalette, String[] blockPalette) {
        if (pixels == null || pixels.length != PIXEL_COUNT) return;
        long[] packed = new long[pixels.length];
        for (int i = 0; i < pixels.length; i++) packed[i] = MapBlockData.pack(pixels[i]);
        saveAsync(directory, rx, rz, packed, biomePalette, blockPalette);
    }

    private static void scheduleSaveDrain(long delayMs) {
        if (!SAVE_DRAIN_SCHEDULED.compareAndSet(false, true)) return;
        MapWorkScheduler.scheduleIo(Math.max(0L, delayMs), TimeUnit.MILLISECONDS,
                MapRequestLane.BACKGROUND, MapWorkScheduler.WorkType.DISK_WRITE,
                0, 20, () -> true, () -> {
            boolean failed = false;
            SaveRequest request = null;
            try {
                var iterator = PENDING_SAVES.values().iterator();
                if (iterator.hasNext()) request = iterator.next();
                if (request == null || !PENDING_SAVES.remove(request.key(), request)) return;
                IN_FLIGHT_SAVES.put(request.key(), request);
                try {
                    writeAtomic(new File(request.directory(), fileName(request.rx(), request.rz())),
                            request.storedRegionView());
                    request.notifyResult(SaveStatus.COMMITTED, null);
                } catch (IOException exception) {
                    failed = true;
                    // A newer snapshot wins. Otherwise retain this exact snapshot
                    // and retry later; a transient disk error must not clear dirty data.
                    SaveRequest newer = PENDING_SAVES.putIfAbsent(
                            request.key(), request);
                    request.notifyResult(newer == null
                                    ? SaveStatus.FAILED_RETRYING
                                    : SaveStatus.SUPERSEDED,
                            exception);
                    LOGGER.error("Failed to save region {},{}", request.rx(), request.rz(), exception);
                } finally {
                    IN_FLIGHT_SAVES.remove(request.key(), request);
                }
            } finally {
                SAVE_DRAIN_SCHEDULED.set(false);
                if (!PENDING_SAVES.isEmpty()) {
                    scheduleSaveDrain(failed ? SAVE_RETRY_DELAY_MS : 2L);
                }
            }
        });
    }

    public static boolean load(File directory, int rx, int rz, long[] outPixels, int[] outTints,
            List<String> outBiomePalette, List<String> outBlockPalette) {
        return load(directory, rx, rz, outPixels, outTints, null,
                outBiomePalette, outBlockPalette);
    }

    public static boolean load(File directory, int rx, int rz, long[] outPixels,
            int[] outTints, long[] outCompleteChunks,
            List<String> outBiomePalette, List<String> outBlockPalette) {
        Arrays.fill(outPixels, MapBlockData.EMPTY_PACKED);
        Arrays.fill(outTints, SurfaceTintData.UNKNOWN);
        if (outCompleteChunks != null) Arrays.fill(outCompleteChunks, 0L);
        File file = new File(directory, fileName(rx, rz));
        StoredRegion pending = latestPending(directory, rx, rz);
        if (pending == null && !file.isFile()) return false;
        try {
            StoredRegion stored = pending != null ? pending : read(file);
            System.arraycopy(stored.pixels(), 0, outPixels, 0, outPixels.length);
            System.arraycopy(stored.tints(), 0, outTints, 0, outTints.length);
            if (outCompleteChunks != null) {
                if (outCompleteChunks.length != COMPLETE_CHUNK_WORDS) {
                    throw new IllegalArgumentException("Invalid chunk coverage output");
                }
                System.arraycopy(stored.completeChunks(), 0, outCompleteChunks, 0,
                        COMPLETE_CHUNK_WORDS);
            }
            outBiomePalette.clear();
            outBiomePalette.addAll(Arrays.asList(stored.biomePalette()));
            outBlockPalette.clear();
            outBlockPalette.addAll(Arrays.asList(stored.blockPalette()));
            return true;
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Failed to load region {},{}", rx, rz, exception);
            Arrays.fill(outPixels, MapBlockData.EMPTY_PACKED);
            Arrays.fill(outTints, SurfaceTintData.UNKNOWN);
            if (outCompleteChunks != null) Arrays.fill(outCompleteChunks, 0L);
            outBiomePalette.clear();
            outBlockPalette.clear();
            return false;
        }
    }

    public static boolean load(File directory, int rx, int rz, long[] outPixels,
            List<String> outBiomePalette, List<String> outBlockPalette) {
        return load(directory, rx, rz, outPixels, unknownTints(), outBiomePalette, outBlockPalette);
    }

    /** Compatibility overload for legacy callers. */
    public static boolean load(File directory, int rx, int rz, MapBlockData[] outPixels,
            List<String> outBiomePalette, List<String> outBlockPalette) {
        long[] packed = new long[outPixels.length];
        boolean loaded = load(directory, rx, rz, packed, outBiomePalette, outBlockPalette);
        for (int i = 0; i < outPixels.length; i++) {
            outPixels[i] = MapBlockData.isEmpty(packed[i]) ? null : MapBlockData.unpack(packed[i]);
        }
        return loaded;
    }

    /** Reads and validates a complete region file. Safe to use on either logical side. */
    public static StoredRegion read(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("Region file does not exist");
        long size = Files.size(file.toPath());
        if (size <= 0 || size > MAX_COMPRESSED_FILE_BYTES) {
            throw new IOException("Invalid region file size: " + size);
        }
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            return read(input);
        }
    }

    /** Reads a region received through a packet without writing untrusted bytes first. */
    public static StoredRegion read(byte[] data) throws IOException {
        if (data == null || data.length == 0 || data.length > MAX_COMPRESSED_FILE_BYTES) {
            throw new IOException("Invalid region payload length");
        }
        try (InputStream input = new ByteArrayInputStream(data)) {
            return read(input);
        }
    }

    private static StoredRegion read(InputStream rawInput) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(rawInput))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != MapBlockData.FILE_MAGIC || (version < 1 || version > MapBlockData.FILE_VERSION)) {
                throw new IOException("Unsupported region format (magic=" + Integer.toHexString(magic)
                        + ", version=" + version + ")");
            }

            String[] biomePalette = readPalette(input);
            String[] blockPalette = readPalette(input);
            int bytesPerPixel = version >= 3 ? VERSION_3_BYTES_PER_PIXEL
                    : (version >= 2 ? VERSION_2_BYTES_PER_PIXEL : LEGACY_BYTES_PER_PIXEL);
            int coverageBytes = version >= 4
                    ? COMPLETE_CHUNK_WORDS * Long.BYTES : 0;
            int expectedBytes = PIXEL_COUNT * bytesPerPixel + coverageBytes;
            byte[] raw = readCompressedPayload(input, expectedBytes);
            if (raw.length != expectedBytes) {
                throw new EOFException("Unexpected pixel payload length " + raw.length);
            }

            long[] pixels = new long[PIXEL_COUNT];
            int[] tints = unknownTints();
            int pointer = 0;
            for (int i = 0; i < pixels.length; i++) {
                short topY = (short) (((raw[pointer++] & 0xFF) << 8) | (raw[pointer++] & 0xFF));
                short blockId = (short) (((raw[pointer++] & 0xFF) << 8) | (raw[pointer++] & 0xFF));
                byte biomeId = raw[pointer++];
                byte flags = raw[pointer++];
                short floorY = version >= 2
                        ? (short) (((raw[pointer++] & 0xFF) << 8) | (raw[pointer++] & 0xFF))
                        : topY;
                pixels[i] = MapBlockData.packRaw(topY, blockId, biomeId, flags, floorY);
                if (version >= 3) {
                    tints[i] = ((raw[pointer++] & 0xFF) << 24)
                            | ((raw[pointer++] & 0xFF) << 16)
                            | ((raw[pointer++] & 0xFF) << 8)
                            | (raw[pointer++] & 0xFF);
                }
            }
            long[] completeChunks = version >= 4
                    ? new long[COMPLETE_CHUNK_WORDS] : inferCompleteChunks(pixels);
            if (version >= 4) {
                for (int word = 0; word < COMPLETE_CHUNK_WORDS; word++) {
                    long value = 0L;
                    for (int octet = 0; octet < Long.BYTES; octet++) {
                        value = (value << 8) | (raw[pointer++] & 0xFFL);
                    }
                    completeChunks[word] = value;
                }
            }
            validatePaletteReferences(pixels, biomePalette.length, blockPalette.length);
            if (version < 6) {
                // PASS123: retain old pixels as last-good, but force only
                // water-bearing chunks through the restored beta compositor/source
                // authority path before treating them as current completion.
                invalidateLegacyFluidCompletion(pixels, completeChunks);
            }
            repairChunkBorderBiomeSeams(pixels, biomePalette, completeChunks);
            return new StoredRegion(pixels, tints, biomePalette, blockPalette,
                    completeChunks);
        }
    }

    /** Writes a complete region through a same-directory temporary file and atomic replacement. */
    public static void writeAtomic(File target, StoredRegion region) throws IOException {
        if (target == null || region == null) throw new IOException("Missing region destination or data");
        File parent = target.getParentFile();
        if (parent == null) throw new IOException("Region destination has no parent directory");
        Files.createDirectories(parent.toPath());
        File temporary = Files.createTempFile(parent.toPath(), target.getName() + ".", ".tmp").toFile();
        try {
            try (OutputStream rawOutput = new BufferedOutputStream(new FileOutputStream(temporary));
                    DataOutputStream output = new DataOutputStream(rawOutput)) {
                writeTo(output, region);
            }
            atomicReplace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    /** Encodes a region to a byte array for bounded network transport. */
    public static byte[] toBytes(StoredRegion region) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(512 * 1024);
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(bytes))) {
            writeTo(output, region);
        }
        return bytes.toByteArray();
    }

    /**
     * Water migrations keep the binary pixel layout but change which producer/
     * compositor semantics are current. Keep last-good pixels in memory and clear
     * only water-bearing completion so the canonical live/Anvil writer repairs those
     * chunks transactionally. PASS123 uses this v6 migration to retire water rows
     * captured while disk and live Surface writers could race each other.
     */
    /**
     * Repairs 16x16 chunk-border {@code minecraft:plains} seams in existing
     * {@code .smdat} region files that were captured while a neighbour chunk was
     * unloaded on the client (causing {@code ClientLevel.getUncachedNoiseBiome} to
     * inject {@code Biomes.PLAINS} along the 2-4 block chunk border in Warm Ocean,
     * Savanna, etc.).
     */
    private static int repairChunkBorderBiomeSeams(long[] pixels,
            String[] biomePalette, long[] completeChunks) {
        if (pixels == null || pixels.length != PIXEL_COUNT
                || biomePalette == null || biomePalette.length <= 1) {
            return 0;
        }
        int plainsIdx = -1;
        for (int i = 0; i < biomePalette.length; i++) {
            if ("minecraft:plains".equals(biomePalette[i])) {
                plainsIdx = i;
                break;
            }
        }
        if (plainsIdx < 0) return 0;

        int chunksPerAxis = SurfaceChunkCoverage.CHUNKS_PER_AXIS;
        boolean[] chunkCoreHasPlains = new boolean[chunksPerAxis * chunksPerAxis];
        boolean[] chunkCoreHasNonPlains = new boolean[chunksPerAxis * chunksPerAxis];

        for (int cz = 0; cz < chunksPerAxis; cz++) {
            int baseZ = cz << 4;
            for (int cx = 0; cx < chunksPerAxis; cx++) {
                int baseX = cx << 4;
                int cIdx = (cz << 5) | cx;
                boolean hasPlains = false;
                boolean hasOther = false;
                for (int lz = 4; lz <= 11; lz++) {
                    int row = (baseZ + lz) * REGION_SIZE + baseX;
                    for (int lx = 4; lx <= 11; lx++) {
                        long packed = pixels[row + lx];
                        if (MapBlockData.isEmpty(packed)) continue;
                        int bId = MapBlockData.biomeId(packed) & 0xFF;
                        if (bId == plainsIdx) hasPlains = true;
                        else if (bId != (MapBlockData.NO_BIOME & 0xFF)) hasOther = true;
                    }
                }
                chunkCoreHasPlains[cIdx] = hasPlains;
                chunkCoreHasNonPlains[cIdx] = hasOther;
            }
        }

        int repairedChunks = 0;
        for (int cz = 0; cz < chunksPerAxis; cz++) {
            int baseZ = cz << 4;
            for (int cx = 0; cx < chunksPerAxis; cx++) {
                int cIdx = (cz << 5) | cx;
                if (chunkCoreHasPlains[cIdx] || !chunkCoreHasNonPlains[cIdx]) continue;
                int baseX = cx << 4;
                boolean chunkRepaired = false;

                for (int lz = 0; lz < 16; lz++) {
                    int row = (baseZ + lz) * REGION_SIZE + baseX;
                    for (int lx = 0; lx < 16; lx++) {
                        if (lx >= 4 && lx <= 11 && lz >= 4 && lz <= 11) continue;
                        long packed = pixels[row + lx];
                        if (MapBlockData.isEmpty(packed)) continue;
                        int bId = MapBlockData.biomeId(packed) & 0xFF;
                        if (bId != plainsIdx) continue;

                        int adjCx = lx < 4 ? cx - 1 : (lx > 11 ? cx + 1 : cx);
                        int adjCz = lz < 4 ? cz - 1 : (lz > 11 ? cz + 1 : cz);
                        if (adjCx >= 0 && adjCx < chunksPerAxis
                                && adjCz >= 0 && adjCz < chunksPerAxis) {
                            if (chunkCoreHasPlains[(adjCz << 5) | adjCx]) continue;
                        }
                        if (adjCx != cx && cx >= 0 && cx < chunksPerAxis
                                && adjCz >= 0 && adjCz < chunksPerAxis
                                && chunkCoreHasPlains[(adjCz << 5) | cx]) {
                            continue;
                        }
                        if (adjCz != cz && adjCx >= 0 && adjCx < chunksPerAxis
                                && cz >= 0 && cz < chunksPerAxis
                                && chunkCoreHasPlains[(cz << 5) | adjCx]) {
                            continue;
                        }

                        int innerX = Math.max(4, Math.min(11, lx));
                        int innerZ = Math.max(4, Math.min(11, lz));
                        long innerPacked = pixels[(baseZ + innerZ) * REGION_SIZE + baseX + innerX];
                        byte replacementBiome = MapBlockData.biomeId(innerPacked);
                        if ((replacementBiome & 0xFF) == plainsIdx
                                || replacementBiome == MapBlockData.NO_BIOME) {
                            // Search the 8x8 core for any valid non-plains biome
                            searchCore:
                            for (int sz = 4; sz <= 11; sz++) {
                                int sRow = (baseZ + sz) * REGION_SIZE + baseX;
                                for (int sx = 4; sx <= 11; sx++) {
                                    long sp = pixels[sRow + sx];
                                    if (MapBlockData.isEmpty(sp)) continue;
                                    byte sb = MapBlockData.biomeId(sp);
                                    if ((sb & 0xFF) != plainsIdx && sb != MapBlockData.NO_BIOME) {
                                        replacementBiome = sb;
                                        break searchCore;
                                    }
                                }
                            }
                        }
                        if ((replacementBiome & 0xFF) != plainsIdx
                                && replacementBiome != MapBlockData.NO_BIOME) {
                            pixels[row + lx] = MapBlockData.withBiomeId(packed, replacementBiome);
                            chunkRepaired = true;
                        }
                    }
                }
                if (chunkRepaired) {
                    repairedChunks++;
                    if (completeChunks != null
                            && completeChunks.length == COMPLETE_CHUNK_WORDS) {
                        SurfaceChunkCoverage.clearComplete(completeChunks, cIdx);
                    }
                }
            }
        }
        return repairedChunks;
    }

    private static int invalidateLegacyFluidCompletion(long[] pixels,
            long[] completeChunks) {
        if (pixels == null || pixels.length != PIXEL_COUNT
                || completeChunks == null
                || completeChunks.length != COMPLETE_CHUNK_WORDS) return 0;
        int invalidated = 0;
        for (int chunkZ = 0; chunkZ < SurfaceChunkCoverage.CHUNKS_PER_AXIS; chunkZ++) {
            for (int chunkX = 0; chunkX < SurfaceChunkCoverage.CHUNKS_PER_AXIS; chunkX++) {
                int chunkIndex = chunkZ * SurfaceChunkCoverage.CHUNKS_PER_AXIS + chunkX;
                if (!SurfaceChunkCoverage.isComplete(completeChunks, chunkIndex)) continue;
                boolean water = false;
                for (int z = 0; z < 16 && !water; z++) {
                    int row = (chunkZ * 16 + z) * REGION_SIZE + chunkX * 16;
                    for (int x = 0; x < 16; x++) {
                        long packed = pixels[row + x];
                        if (MapBlockData.isFluid(packed) && !MapBlockData.isGlowing(packed)) {
                            water = true;
                            break;
                        }
                    }
                }
                if (water && SurfaceChunkCoverage.clearComplete(
                        completeChunks, chunkIndex)) {
                    invalidated++;
                }
            }
        }
        return invalidated;
    }

    private static void writeTo(DataOutputStream output, StoredRegion region) throws IOException {
        validatePaletteReferences(region.pixels(), region.biomePalette().length, region.blockPalette().length);
        output.writeInt(MapBlockData.FILE_MAGIC);
        output.writeInt(MapBlockData.FILE_VERSION);
        writePalette(output, region.biomePalette());
        writePalette(output, region.blockPalette());

        byte[] raw = new byte[PIXEL_COUNT * VERSION_3_BYTES_PER_PIXEL
                + COMPLETE_CHUNK_WORDS * Long.BYTES];
        int pointer = 0;
        for (int i = 0; i < region.pixels().length; i++) {
            long packed = region.pixels()[i];
            short topY = MapBlockData.topY(packed);
            short blockId = MapBlockData.blockId(packed);
            raw[pointer++] = (byte) (topY >>> 8);
            raw[pointer++] = (byte) topY;
            raw[pointer++] = (byte) (blockId >>> 8);
            raw[pointer++] = (byte) blockId;
            raw[pointer++] = MapBlockData.biomeId(packed);
            raw[pointer++] = MapBlockData.flags(packed);
            short floorY = MapBlockData.floorY(packed);
            raw[pointer++] = (byte) (floorY >>> 8);
            raw[pointer++] = (byte) floorY;
            int tint = region.tints()[i];
            raw[pointer++] = (byte) (tint >>> 24);
            raw[pointer++] = (byte) (tint >>> 16);
            raw[pointer++] = (byte) (tint >>> 8);
            raw[pointer++] = (byte) tint;
        }
        for (long word : region.completeChunks()) {
            for (int shift = 56; shift >= 0; shift -= 8) {
                raw[pointer++] = (byte) (word >>> shift);
            }
        }
        try (GZIPOutputStream gzip = new GZIPOutputStream(output, 64 * 1024)) {
            gzip.write(raw);
            gzip.finish();
        }
    }

    /**
     * Palette-safe union. Non-empty pixels from {@code overlay} replace pixels
     * from {@code base}; empty overlay pixels preserve the base exploration.
     */
    public static StoredRegion merge(StoredRegion base, StoredRegion overlay) throws IOException {
        if (base == null) return overlay.deepCopy();
        if (overlay == null) return base.deepCopy();

        List<String> outputBiomes = new ArrayList<>(Arrays.asList(base.biomePalette()));
        List<String> outputBlocks = new ArrayList<>(Arrays.asList(base.blockPalette()));
        Map<String, Integer> biomeIds = index(outputBiomes);
        Map<String, Integer> blockIds = index(outputBlocks);
        int[] biomeRemap = buildRemap(overlay.biomePalette(), outputBiomes, biomeIds, 254, "biome");
        int[] blockRemap = buildRemap(overlay.blockPalette(), outputBlocks, blockIds, 65_534, "block");

        long[] merged = Arrays.copyOf(base.pixels(), PIXEL_COUNT);
        int[] mergedTints = Arrays.copyOf(base.tints(), PIXEL_COUNT);
        long[] mergedCompleteChunks = Arrays.copyOf(base.completeChunks(),
                COMPLETE_CHUNK_WORDS);
        for (int word = 0; word < COMPLETE_CHUNK_WORDS; word++) {
            mergedCompleteChunks[word] |= overlay.completeChunks()[word];
        }
        for (int i = 0; i < PIXEL_COUNT; i++) {
            long incoming = overlay.pixels()[i];
            if (MapBlockData.isEmpty(incoming)) continue;

            short incomingBlock = MapBlockData.blockId(incoming);
            byte incomingBiome = MapBlockData.biomeId(incoming);
            short outputBlock = MapBlockData.NO_BLOCK;
            byte outputBiome = MapBlockData.NO_BIOME;

            if (incomingBlock != MapBlockData.NO_BLOCK) {
                int sourceIndex = incomingBlock & 0xFFFF;
                if (sourceIndex >= overlay.blockPalette().length) {
                    throw new IOException("Invalid incoming block palette reference " + sourceIndex);
                }
                outputBlock = (short) blockRemap[sourceIndex];
            }
            if (incomingBiome != MapBlockData.NO_BIOME) {
                int sourceIndex = incomingBiome & 0xFF;
                if (sourceIndex >= overlay.biomePalette().length) {
                    throw new IOException("Invalid incoming biome palette reference " + sourceIndex);
                }
                outputBiome = (byte) biomeRemap[sourceIndex];
            }
            merged[i] = MapBlockData.packRaw(MapBlockData.topY(incoming), outputBlock,
                    outputBiome, MapBlockData.flags(incoming), MapBlockData.floorY(incoming));
            mergedTints[i] = overlay.tints()[i];
        }
        return new StoredRegion(merged, mergedTints,
                outputBiomes.toArray(String[]::new), outputBlocks.toArray(String[]::new),
                mergedCompleteChunks);
    }

    /** Merges a validated incoming packet into a local file atomically. */
    public static void mergeIntoFile(File localFile, byte[] incomingBytes) throws IOException {
        StoredRegion incoming = read(incomingBytes);
        StoredRegion merged = localFile.isFile() ? merge(read(localFile), incoming) : incoming;
        writeAtomic(localFile, merged);
    }

    public static boolean hasFile(File directory, int rx, int rz) {
        if (directory == null) return false;
        return hasPending(directory, rx, rz)
                || new File(directory, fileName(rx, rz)).isFile();
    }

    public static boolean hasPending(File directory, int rx, int rz) {
        if (directory == null) return false;
        String key = saveKey(directory, rx, rz);
        return PENDING_SAVES.containsKey(key) || IN_FLIGHT_SAVES.containsKey(key);
    }

    /** Returns the newest queued/in-flight snapshot, avoiding an eviction/reload race. */
    public static StoredRegion latestPending(File directory, int rx, int rz) {
        if (directory == null) return null;
        String key = saveKey(directory, rx, rz);
        SaveRequest request = PENDING_SAVES.get(key);
        if (request == null) request = IN_FLIGHT_SAVES.get(key);
        return request == null ? null : request.storedRegion();
    }


    public static int pendingSaveCount() {
        return PENDING_SAVES.size();
    }

    public static int inFlightSaveCount() {
        return IN_FLIGHT_SAVES.size();
    }

    public static String fileName(int rx, int rz) {
        return "r." + rx + "." + rz + FILE_EXT;
    }

    private static String[] readPalette(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_PALETTE_ENTRIES) throw new IOException("Invalid palette size " + count);
        String[] output = new String[count];
        for (int i = 0; i < count; i++) output[i] = readPaletteEntry(input);
        return output;
    }

    private static void writePalette(DataOutputStream output, String[] palette) throws IOException {
        if (palette.length > MAX_PALETTE_ENTRIES) throw new IOException("Palette is too large");
        output.writeInt(palette.length);
        for (String value : palette) writePaletteEntry(output, value == null ? "" : value);
    }

    private static void writePaletteEntry(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65_535) throw new IOException("Palette entry is too long");
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readPaletteEntry(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Truncated palette entry");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readCompressedPayload(DataInputStream input, int expectedBytes) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(input, 64 * 1024);
                ByteArrayOutputStream output = new ByteArrayOutputStream(expectedBytes)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = gzip.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
                if (output.size() > expectedBytes) throw new IOException("Oversized region payload");
            }
            return output.toByteArray();
        }
    }

    private static Map<String, Integer> index(List<String> palette) {
        Map<String, Integer> ids = new HashMap<>();
        for (int i = 0; i < palette.size(); i++) ids.putIfAbsent(palette.get(i), i);
        return ids;
    }

    private static int[] buildRemap(String[] source, List<String> output, Map<String, Integer> ids,
            int maximumIndex, String paletteName) throws IOException {
        int[] remap = new int[source.length];
        for (int i = 0; i < source.length; i++) {
            String value = source[i];
            Integer target = ids.get(value);
            if (target == null) {
                target = output.size();
                if (target > maximumIndex) throw new IOException("Merged " + paletteName + " palette is too large");
                output.add(value);
                ids.put(value, target);
            }
            remap[i] = target;
        }
        return remap;
    }

    private static void validatePaletteReferences(long[] pixels, int biomeCount, int blockCount) throws IOException {
        if (pixels.length != PIXEL_COUNT) throw new IOException("Invalid pixel count " + pixels.length);
        if (biomeCount > 255 || blockCount > 65_535) {
            throw new IOException("Palette exceeds packed-index capacity");
        }
        for (long packed : pixels) {
            if (MapBlockData.isEmpty(packed)) continue;
            short block = MapBlockData.blockId(packed);
            byte biome = MapBlockData.biomeId(packed);
            if (block != MapBlockData.NO_BLOCK && (block & 0xFFFF) >= blockCount) {
                throw new IOException("Block palette index out of range");
            }
            if (biome != MapBlockData.NO_BIOME && (biome & 0xFF) >= biomeCount) {
                throw new IOException("Biome palette index out of range");
            }
        }
    }

    private static void atomicReplace(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String saveKey(File directory, int rx, int rz) {
        return new File(directory, fileName(rx, rz)).toPath().toAbsolutePath().normalize().toString();
    }

    private static final class SaveRequest {
        private final File directory;
        private final int rx;
        private final int rz;
        private final long[] packedPixels;
        private final int[] tints;
        private final String[] biomePalette;
        private final String[] blockPalette;
        private final long[] completeChunks;
        private final Consumer<SaveResult> completion;

        private SaveRequest(File directory, int rx, int rz, long[] packedPixels,
                int[] tints, String[] biomePalette, String[] blockPalette,
                long[] completeChunks, Consumer<SaveResult> completion) {
            this.directory = directory;
            this.rx = rx;
            this.rz = rz;
            this.packedPixels = packedPixels;
            this.tints = tints;
            this.biomePalette = biomePalette;
            this.blockPalette = blockPalette;
            this.completeChunks = completeChunks;
            this.completion = completion;
        }

        private File directory() { return directory; }
        private int rx() { return rx; }
        private int rz() { return rz; }

        private String key() {
            return saveKey(directory, rx, rz);
        }

        /** Immutable owned arrays for the IO worker; no second 3 MiB clone. */
        private StoredRegion storedRegionView() {
            return new StoredRegion(packedPixels, tints, biomePalette, blockPalette,
                    completeChunks);
        }

        /** Defensive copy for readers racing an in-flight save. */
        private StoredRegion storedRegion() {
            return storedRegionView().deepCopy();
        }

        private void notifyResult(SaveStatus status, Throwable failure) {
            notifyCompletion(completion,
                    new SaveResult(status, directory, rx, rz, failure));
        }
    }

    private static void notifyCompletion(Consumer<SaveResult> completion,
            SaveResult result) {
        if (completion == null) return;
        try {
            completion.accept(result);
        } catch (Throwable callbackFailure) {
            LOGGER.warn("SimpleMap save completion callback failed", callbackFailure);
        }
    }



/**
 * Chunk-granular Surface observation state. Material payload and observation are
 * deliberately independent: a fully scanned void chunk is complete map data.
 */
public static final class SurfaceChunkCoverage {
    public static final int CHUNKS_PER_AXIS = 32;
    public static final int CHUNK_COUNT = CHUNKS_PER_AXIS * CHUNKS_PER_AXIS;
    public static final int WORDS = CHUNK_COUNT / Long.SIZE;

    private SurfaceChunkCoverage() {
    }

    public static boolean isComplete(long[] mask, int chunkIndex) {
        return mask != null && mask.length == WORDS
                && chunkIndex >= 0 && chunkIndex < CHUNK_COUNT
                && (mask[chunkIndex >>> 6] & (1L << (chunkIndex & 63))) != 0L;
    }

    public static boolean markComplete(long[] mask, int chunkIndex) {
        if (mask == null || mask.length != WORDS
                || chunkIndex < 0 || chunkIndex >= CHUNK_COUNT) return false;
        int word = chunkIndex >>> 6;
        long bit = 1L << (chunkIndex & 63);
        boolean changed = (mask[word] & bit) == 0L;
        mask[word] |= bit;
        return changed;
    }


    public static boolean clearComplete(long[] mask, int chunkIndex) {
        if (mask == null || mask.length != WORDS
                || chunkIndex < 0 || chunkIndex >= CHUNK_COUNT) return false;
        int word = chunkIndex >>> 6;
        long bit = 1L << (chunkIndex & 63);
        boolean changed = (mask[word] & bit) != 0L;
        mask[word] &= ~bit;
        return changed;
    }

    /** Legacy v1-v3 files had no explicit coverage; only all-solid chunks qualify. */
    public static long[] inferLegacy(long[] pixels, int regionSize,
            long emptyPacked) {
        long[] mask = new long[WORDS];
        if (pixels == null || regionSize != CHUNKS_PER_AXIS * 16
                || pixels.length != regionSize * regionSize) return mask;
        for (int chunkZ = 0; chunkZ < CHUNKS_PER_AXIS; chunkZ++) {
            for (int chunkX = 0; chunkX < CHUNKS_PER_AXIS; chunkX++) {
                boolean complete = true;
                for (int z = 0; z < 16 && complete; z++) {
                    int row = (chunkZ * 16 + z) * regionSize + chunkX * 16;
                    for (int x = 0; x < 16; x++) {
                        if (pixels[row + x] == emptyPacked) {
                            complete = false;
                            break;
                        }
                    }
                }
                if (complete) markComplete(mask, chunkZ * CHUNKS_PER_AXIS + chunkX);
            }
        }
        return mask;
    }
}


    /**
 * Append-journal region container with CRC recovery and atomic compaction.
 * A truncated or corrupt tail is ignored; earlier committed records remain
 * readable. Source records have higher authority than disposable derived data.
 */
public static final class RegionContainerV2 {
    public static final int MAGIC = 0x534D5232; // SMR2
    private static final int RECORD_MAGIC = 0x52454332; // REC2
    private static final int VERSION = 2;
    private static final int MAX_RECORD_BYTES = 64 * 1024 * 1024;
    /** Single-writer append state; avoids rescanning a multi-MiB container before
     * every record while still detecting external replacement/truncation. */
    private static final Map<Path, AppendState> APPEND_STATES = new HashMap<>();

    public enum RecordType {
        SURFACE_SOURCE(1, true),
        CAVE_ARCHIVE(2, true),
        SURFACE_EXACT(16, false),
        SURFACE_LOD(17, false),
        CAVE_BAND(18, false),
        CAVE_LOD(19, false),
        MIGRATION_MARKER(31, true);

        private final int id;
        private final boolean source;
        RecordType(int id, boolean source) { this.id = id; this.source = source; }
        public int id() { return id; }
        public boolean source() { return source; }
        public static RecordType byId(int id) {
            for (RecordType type : values()) if (type.id == id) return type;
            return null;
        }
    }

    public record Header(long worldIdentity, int regionX, int regionZ,
            int dataVersion) { }
    public record RecordKey(RecordType type, int localKey) { }
    public record Record(RecordKey key, long sourceRevision,
            long styleRevision, byte[] payload) { }
    public record ReadResult(Header header, Map<RecordKey, Record> latest,
            long validBytes, boolean truncatedOrCorruptTail) { }

    private RegionContainerV2() { }

    public static synchronized boolean append(Path path, Header header,
            Record record) throws IOException {
        if (path == null || header == null || record == null
                || record.key() == null || record.key().type() == null
                || record.payload() == null
                || record.payload().length > MAX_RECORD_BYTES) {
            throw new IllegalArgumentException("invalid region container append");
        }
        Path identity = path.toAbsolutePath().normalize();
        Files.createDirectories(identity.getParent());
        long size = Files.exists(identity) ? Files.size(identity) : 0L;
        boolean create = size == 0L;
        boolean recovered = false;
        AppendState state = APPEND_STATES.get(identity);
        if (!create && (state == null || state.fileSize() != size
                || !sameHeader(state.header(), header))) {
            ReadResult existing = read(identity);
            if (existing.header() == null || !sameHeader(existing.header(), header)) {
                throw new IOException("SMR2 header identity mismatch");
            }
            if (existing.truncatedOrCorruptTail()) {
                try (FileChannel channel = FileChannel.open(identity,
                        StandardOpenOption.WRITE)) {
                    channel.truncate(existing.validBytes());
                    channel.force(true);
                }
                size = existing.validBytes();
                recovered = true;
            }
            state = new AppendState(existing.header(), size);
            APPEND_STATES.put(identity, state);
        }
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(identity,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)))) {
            if (create) writeHeader(output, header);
            CRC32 crc = new CRC32();
            crc.update(record.payload());
            output.writeInt(RECORD_MAGIC);
            output.writeByte(record.key().type().id());
            output.writeInt(record.key().localKey());
            output.writeLong(record.sourceRevision());
            output.writeLong(record.styleRevision());
            output.writeInt(record.payload().length);
            output.writeInt((int) crc.getValue());
            output.write(record.payload());
            output.flush();
        }
        APPEND_STATES.put(identity, new AppendState(header, Files.size(identity)));
        return recovered;
    }

    public static ReadResult read(Path path) throws IOException {
        if (path == null || !Files.exists(path) || Files.size(path) == 0L) {
            return new ReadResult(null, Map.of(), 0L, false);
        }
        LinkedHashMap<RecordKey, Record> latest = new LinkedHashMap<>();
        long validBytes = 0L;
        boolean damaged = false;
        Header header;
        try (CountingInputStream counting = new CountingInputStream(
                new BufferedInputStream(Files.newInputStream(path)));
             DataInputStream input = new DataInputStream(counting)) {
            header = readHeader(input);
            validBytes = counting.count();
            while (true) {
                long recordStart = counting.count();
                try {
                    int magic = input.readInt();
                    if (magic != RECORD_MAGIC) {
                        damaged = true;
                        break;
                    }
                    RecordType type = RecordType.byId(input.readUnsignedByte());
                    int localKey = input.readInt();
                    long sourceRevision = input.readLong();
                    long styleRevision = input.readLong();
                    int length = input.readInt();
                    int expectedCrc = input.readInt();
                    if (type == null || length < 0 || length > MAX_RECORD_BYTES) {
                        damaged = true;
                        break;
                    }
                    byte[] payload = input.readNBytes(length);
                    if (payload.length != length) {
                        damaged = true;
                        break;
                    }
                    CRC32 crc = new CRC32();
                    crc.update(payload);
                    if ((int) crc.getValue() != expectedCrc) {
                        damaged = true;
                        break;
                    }
                    RecordKey key = new RecordKey(type, localKey);
                    Record candidate = new Record(key, sourceRevision,
                            styleRevision, payload);
                    Record previous = latest.get(key);
                    if (previous == null
                            || previous.sourceRevision() < sourceRevision
                            || (previous.sourceRevision() == sourceRevision
                            && previous.styleRevision() <= styleRevision)) {
                        latest.put(key, candidate);
                    }
                    validBytes = counting.count();
                } catch (EOFException end) {
                    if (counting.count() != recordStart) damaged = true;
                    break;
                }
            }
        }
        return new ReadResult(header, Map.copyOf(latest), validBytes, damaged);
    }

    public static synchronized void compact(Path path) throws IOException {
        Path identity = path.toAbsolutePath().normalize();
        APPEND_STATES.remove(identity);
        ReadResult result = read(identity);
        if (result.header() == null) return;
        Path temporary = identity.resolveSibling(identity.getFileName() + ".compact.tmp");
        Files.deleteIfExists(temporary);
        boolean first = true;
        for (Record record : result.latest().values()) {
            if (first) {
                Files.createDirectories(temporary.toAbsolutePath().getParent());
                try (DataOutputStream output = new DataOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                    writeHeader(output, result.header());
                }
                first = false;
            }
            append(temporary, result.header(), record);
        }
        if (first) {
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                writeHeader(output, result.header());
            }
        }
        try {
            Files.move(temporary, identity, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, identity, StandardCopyOption.REPLACE_EXISTING);
        }
        APPEND_STATES.remove(temporary.toAbsolutePath().normalize());
        APPEND_STATES.remove(identity);
    }


    private static boolean sameHeader(Header left, Header right) {
        return left.worldIdentity() == right.worldIdentity()
                && left.regionX() == right.regionX()
                && left.regionZ() == right.regionZ()
                && left.dataVersion() == right.dataVersion();
    }

    private static void writeHeader(DataOutputStream output, Header header)
            throws IOException {
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeLong(header.worldIdentity());
        output.writeInt(header.regionX());
        output.writeInt(header.regionZ());
        output.writeInt(header.dataVersion());
    }

    private static Header readHeader(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC) throw new IOException("Invalid SMR2 magic");
        int version = input.readInt();
        if (version != VERSION) throw new IOException("Unsupported SMR2 version " + version);
        return new Header(input.readLong(), input.readInt(), input.readInt(),
                input.readInt());
    }

    private record AppendState(Header header, long fileSize) { }

    private static final class CountingInputStream extends java.io.FilterInputStream {
        private long count;
        private CountingInputStream(java.io.InputStream input) { super(input); }
        long count() { return count; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) count++;
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length)
                throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) count += read;
            return read;
        }
    }

    public static final class MapPersistenceV2Service {
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

        public boolean caveArchiveRegionExists(File dimensionDirectory,
                int regionX, int regionZ) {
            return dimensionDirectory != null
                    && Files.isRegularFile(containerPath(dimensionDirectory, regionX, regionZ));
        }

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
}
}
