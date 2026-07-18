package com.velorise.simplemap.client;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Binary I/O and palette-safe merging for surface {@code .smdat} regions. */
public final class RegionDataStore {
    private static final Logger LOGGER = LogManager.getLogger();
    public static final int REGION_SIZE = 512;
    public static final int PIXEL_COUNT = REGION_SIZE * REGION_SIZE;
    private static final int LEGACY_BYTES_PER_PIXEL = 6;
    private static final int BYTES_PER_PIXEL = 8;
    private static final int MAX_PALETTE_ENTRIES = 65_535;
    private static final int MAX_COMPRESSED_FILE_BYTES = 8 * 1024 * 1024;

    private static final ExecutorService IO_POOL = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SimpleMap-RegionIO");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    });

    /* Saves are coalesced by destination so chunk updates cannot create an unbounded queue. */
    private static final Map<String, SaveRequest> PENDING_SAVES = new ConcurrentHashMap<>();
    private static final Map<String, SaveRequest> IN_FLIGHT_SAVES = new ConcurrentHashMap<>();
    private static final AtomicBoolean SAVE_DRAIN_SCHEDULED = new AtomicBoolean();

    public static final String FILE_EXT = ".smdat";

    private RegionDataStore() {
    }

    public record StoredRegion(long[] pixels, String[] biomePalette, String[] blockPalette) {
        public StoredRegion {
            if (pixels == null || pixels.length != PIXEL_COUNT) {
                throw new IllegalArgumentException("A SimpleMap region must contain exactly " + PIXEL_COUNT + " pixels");
            }
            biomePalette = biomePalette == null ? new String[0] : biomePalette;
            blockPalette = blockPalette == null ? new String[0] : blockPalette;
        }

        public StoredRegion deepCopy() {
            return new StoredRegion(Arrays.copyOf(pixels, pixels.length),
                    Arrays.copyOf(biomePalette, biomePalette.length),
                    Arrays.copyOf(blockPalette, blockPalette.length));
        }
    }

    public static void saveAsync(File directory, int rx, int rz, long[] packedPixels,
            String[] biomePalette, String[] blockPalette) {
        if (directory == null || packedPixels == null || packedPixels.length != PIXEL_COUNT) return;
        SaveRequest request = new SaveRequest(directory, rx, rz,
                Arrays.copyOf(packedPixels, packedPixels.length),
                Arrays.copyOf(biomePalette, biomePalette.length),
                Arrays.copyOf(blockPalette, blockPalette.length));
        PENDING_SAVES.put(request.key(), request);
        scheduleSaveDrain();
    }

    /** Compatibility overload for callers that still expose object pixels at an API boundary. */
    public static void saveAsync(File directory, int rx, int rz, MapBlockData[] pixels,
            String[] biomePalette, String[] blockPalette) {
        if (pixels == null || pixels.length != PIXEL_COUNT) return;
        long[] packed = new long[pixels.length];
        for (int i = 0; i < pixels.length; i++) packed[i] = MapBlockData.pack(pixels[i]);
        saveAsync(directory, rx, rz, packed, biomePalette, blockPalette);
    }

    private static void scheduleSaveDrain() {
        if (!SAVE_DRAIN_SCHEDULED.compareAndSet(false, true)) return;
        IO_POOL.execute(() -> {
            try {
                while (true) {
                    SaveRequest request = PENDING_SAVES.values().stream().findFirst().orElse(null);
                    if (request == null) break;
                    if (!PENDING_SAVES.remove(request.key(), request)) continue;
                    IN_FLIGHT_SAVES.put(request.key(), request);
                    try {
                        writeAtomic(new File(request.directory(), fileName(request.rx(), request.rz())),
                                request.storedRegion());
                    } catch (IOException exception) {
                        LOGGER.error("Failed to save region {},{}", request.rx(), request.rz(), exception);
                    } finally {
                        IN_FLIGHT_SAVES.remove(request.key(), request);
                    }
                }
            } finally {
                SAVE_DRAIN_SCHEDULED.set(false);
                if (!PENDING_SAVES.isEmpty()) scheduleSaveDrain();
            }
        });
    }

    public static boolean load(File directory, int rx, int rz, long[] outPixels,
            List<String> outBiomePalette, List<String> outBlockPalette) {
        Arrays.fill(outPixels, MapBlockData.EMPTY_PACKED);
        File file = new File(directory, fileName(rx, rz));
        StoredRegion pending = latestPending(directory, rx, rz);
        if (pending == null && !file.isFile()) return false;
        try {
            StoredRegion stored = pending != null ? pending : read(file);
            System.arraycopy(stored.pixels(), 0, outPixels, 0, outPixels.length);
            outBiomePalette.clear();
            outBiomePalette.addAll(Arrays.asList(stored.biomePalette()));
            outBlockPalette.clear();
            outBlockPalette.addAll(Arrays.asList(stored.blockPalette()));
            return true;
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Failed to load region {},{}", rx, rz, exception);
            Arrays.fill(outPixels, MapBlockData.EMPTY_PACKED);
            outBiomePalette.clear();
            outBlockPalette.clear();
            return false;
        }
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
            if (magic != MapBlockData.FILE_MAGIC || (version != 1 && version != MapBlockData.FILE_VERSION)) {
                throw new IOException("Unsupported region format (magic=" + Integer.toHexString(magic)
                        + ", version=" + version + ")");
            }

            String[] biomePalette = readPalette(input);
            String[] blockPalette = readPalette(input);
            int bytesPerPixel = version >= 2 ? BYTES_PER_PIXEL : LEGACY_BYTES_PER_PIXEL;
            byte[] raw = readCompressedPayload(input, PIXEL_COUNT * bytesPerPixel);
            if (raw.length != PIXEL_COUNT * bytesPerPixel) {
                throw new EOFException("Unexpected pixel payload length " + raw.length);
            }

            long[] pixels = new long[PIXEL_COUNT];
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
            }
            validatePaletteReferences(pixels, biomePalette.length, blockPalette.length);
            return new StoredRegion(pixels, biomePalette, blockPalette);
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

    private static void writeTo(DataOutputStream output, StoredRegion region) throws IOException {
        validatePaletteReferences(region.pixels(), region.biomePalette().length, region.blockPalette().length);
        output.writeInt(MapBlockData.FILE_MAGIC);
        output.writeInt(MapBlockData.FILE_VERSION);
        writePalette(output, region.biomePalette());
        writePalette(output, region.blockPalette());

        byte[] raw = new byte[PIXEL_COUNT * BYTES_PER_PIXEL];
        int pointer = 0;
        for (long packed : region.pixels()) {
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
        }
        return new StoredRegion(merged, outputBiomes.toArray(String[]::new), outputBlocks.toArray(String[]::new));
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

    private record SaveRequest(File directory, int rx, int rz, long[] packedPixels,
            String[] biomePalette, String[] blockPalette) {
        private String key() {
            return saveKey(directory, rx, rz);
        }

        private StoredRegion storedRegion() {
            return new StoredRegion(Arrays.copyOf(packedPixels, packedPixels.length),
                    Arrays.copyOf(biomePalette, biomePalette.length),
                    Arrays.copyOf(blockPalette, blockPalette.length));
        }
    }
}
