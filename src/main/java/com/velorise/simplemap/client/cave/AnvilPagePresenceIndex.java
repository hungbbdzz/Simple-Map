package com.velorise.simplemap.client.cave;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny read-only index of generated Anvil chunks, reduced to Simple Map's 4x4
 * chunk cave pages.
 *
 * <p>A cold fullscreen cave build must not probe every page in the rectangular
 * viewport. Explored maps are normally sparse, while each {@code .mca} header
 * already contains the exact generated-chunk bitmap. Reading only those 4 KiB
 * headers gives the source scheduler the same region-presence frontier used by
 * mature world maps without opening or decoding any chunk NBT on the render
 * thread.</p>
 */
final class AnvilPagePresenceIndex {
    private static final AnvilPagePresenceIndex INSTANCE =
            new AnvilPagePresenceIndex();
    private static final Pattern REGION_NAME =
            Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final long REFRESH_INTERVAL_MS = 1_000L;
    private static final int HEADER_BYTES = 4_096;

    private String identity = "";
    private Set<Long> pages = Set.of();
    private Map<Long, long[]> regionChunks = Map.of();
    private long revision;
    private long lastRefreshMs;
    private int regionFiles;
    private int chunks;

    private AnvilPagePresenceIndex() {
    }

    static AnvilPagePresenceIndex getInstance() {
        return INSTANCE;
    }

    synchronized void reset() {
        identity = "";
        pages = Set.of();
        regionChunks = Map.of();
        revision++;
        lastRefreshMs = 0L;
        regionFiles = 0;
        chunks = 0;
    }

    synchronized Snapshot snapshot(ServerLevel level) {
        if (level == null) {
            return new Snapshot(Set.of(), Map.of(), revision, 0, 0, false);
        }
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        Path dimensionRoot = DimensionType.getStorageFolder(level.dimension(), worldRoot);
        Path regionDirectory = dimensionRoot.resolve("region");
        String nextIdentity = level.dimension().location() + "|"
                + regionDirectory.toAbsolutePath().normalize();
        long now = System.currentTimeMillis();
        if (!identity.equals(nextIdentity)
                || now - lastRefreshMs >= REFRESH_INTERVAL_MS) {
            refresh(nextIdentity, regionDirectory, now);
        }
        return new Snapshot(pages, regionChunks, revision, regionFiles, chunks, true);
    }

    private void refresh(String nextIdentity, Path regionDirectory, long now) {
        HashSet<Long> discoveredPages = new HashSet<>();
        HashMap<Long, long[]> discoveredRegionChunks = new HashMap<>();
        int discoveredRegions = 0;
        int discoveredChunks = 0;
        if (Files.isDirectory(regionDirectory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                    regionDirectory, "r.*.*.mca")) {
                for (Path path : stream) {
                    Matcher matcher = REGION_NAME.matcher(path.getFileName().toString());
                    if (!matcher.matches()) continue;
                    int regionX;
                    int regionZ;
                    try {
                        regionX = Integer.parseInt(matcher.group(1));
                        regionZ = Integer.parseInt(matcher.group(2));
                    } catch (NumberFormatException ignored) {
                        continue;
                    }
                    int found = scanHeader(path, regionX, regionZ,
                            discoveredPages, discoveredRegionChunks);
                    if (found < 0) {
                        lastRefreshMs = now;
                        return;
                    }
                    if (found > 0) discoveredRegions++;
                    discoveredChunks += found;
                }
            } catch (IOException ignored) {
                // The save can rotate a region while autosaving. Retain the last
                // complete snapshot and retry shortly instead of publishing a
                // transient empty index.
                lastRefreshMs = now;
                return;
            }
        }
        Set<Long> immutable = Set.copyOf(discoveredPages);
        Map<Long, long[]> immutableRegions = Map.copyOf(discoveredRegionChunks);
        if (!identity.equals(nextIdentity) || !immutable.equals(pages)
                || !samePresence(regionChunks, immutableRegions)) revision++;
        identity = nextIdentity;
        pages = immutable;
        regionChunks = immutableRegions;
        regionFiles = discoveredRegions;
        chunks = discoveredChunks;
        lastRefreshMs = now;
    }

    private static int scanHeader(Path path, int regionX, int regionZ,
            Set<Long> destination, Map<Long, long[]> regionDestination) {
        if (!Files.isRegularFile(path)) return 0;
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            while (header.hasRemaining() && channel.read(header) > 0) {
                // Continue until the location table is full or EOF is reached.
            }
        } catch (IOException ignored) {
            return -1;
        }
        header.flip();
        int entries = Math.min(1_024, header.remaining() / Integer.BYTES);
        int found = 0;
        long[] presence = new long[16];
        for (int index = 0; index < entries; index++) {
            if (header.getInt() == 0) continue;
            presence[index >>> 6] |= 1L << (index & 63);
            int localChunkX = index & 31;
            int localChunkZ = index >>> 5;
            int pageX = regionX * CaveLoadHierarchy.PAGES_PER_REGION
                    + (localChunkX >>> 2);
            int pageZ = regionZ * CaveLoadHierarchy.PAGES_PER_REGION
                    + (localChunkZ >>> 2);
            destination.add(CaveLoadHierarchy.pack(pageX, pageZ));
            found++;
        }
        if (found > 0) {
            regionDestination.put(CaveLoadHierarchy.pack(regionX, regionZ), presence);
        }
        return found;
    }

    private static boolean samePresence(Map<Long, long[]> first,
            Map<Long, long[]> second) {
        if (first.size() != second.size() || !first.keySet().equals(second.keySet())) {
            return false;
        }
        for (Map.Entry<Long, long[]> entry : first.entrySet()) {
            if (!java.util.Arrays.equals(entry.getValue(), second.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    record Snapshot(Set<Long> pages, Map<Long, long[]> regionChunks,
            long revision, int regionFiles, int chunks, boolean ready) {
        boolean hasChunk(int chunkX, int chunkZ) {
            if (!ready) return false;
            int regionX = Math.floorDiv(chunkX, 32);
            int regionZ = Math.floorDiv(chunkZ, 32);
            long[] presence = regionChunks.get(
                    CaveLoadHierarchy.pack(regionX, regionZ));
            if (presence == null) return false;
            int local = Math.floorMod(chunkZ, 32) * 32
                    + Math.floorMod(chunkX, 32);
            return (presence[local >>> 6] & (1L << (local & 63))) != 0L;
        }
    }
}
