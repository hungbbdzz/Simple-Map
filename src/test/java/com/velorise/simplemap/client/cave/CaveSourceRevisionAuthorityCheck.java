package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.cave.archive.CaveArchiveV2Service;
import com.velorise.simplemap.client.cave.archive.CompactCaveTile;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS73 guard for source-versioned Full/Layered presentation caches. */
public final class CaveSourceRevisionAuthorityCheck {
    private CaveSourceRevisionAuthorityCheck() { }

    public static void main(String[] args) throws Exception {
        verifyArchiveRevisionFeedsRepository();
        verifyProjectionScopedDisplayRevisions();
        verifyCacheValidationArchitecture();
        System.out.println("CAVE_SOURCE_REVISION_AUTHORITY_PASS");
    }

    private static void verifyArchiveRevisionFeedsRepository() {
        CaveArchiveV2Service archive = CaveArchiveV2Service.getInstance();
        archive.clear();
        CaveTileRepository repository = CaveTileRepository.getInstance();
        int chunkX = 8;
        int chunkZ = -4;
        int pageX = Math.floorDiv(chunkX, 4);
        int pageZ = Math.floorDiv(chunkZ, 4);
        long before = repository.getPageRevision(pageX, pageZ);

        require(archive.ingest(tile(chunkX, chunkZ, 11L, 0xFF334455)),
                "first archive ingest failed");
        long first = repository.getPageRevision(pageX, pageZ);
        require(first != before,
                "repository source revision ignored archive ingestion");

        archive.clear();
        require(archive.ingest(tile(chunkX, chunkZ, 11L, 0xFF334455)),
                "stable archive replay failed");
        long replayed = repository.getPageRevision(pageX, pageZ);
        require(replayed == first,
                "archive page fingerprint is not stable across replay");

        require(archive.ingest(tile(chunkX, chunkZ, 12L, 0xFF556677)),
                "archive replacement failed");
        long second = repository.getPageRevision(pageX, pageZ);
        require(second != first,
                "repository source revision ignored archive replacement");
    }

    private static void verifyProjectionScopedDisplayRevisions() {
        CaveTileRepository repository = CaveTileRepository.getInstance();
        int chunkX = 20;
        int chunkZ = 12;
        int pageX = Math.floorDiv(chunkX, 4);
        int pageZ = Math.floorDiv(chunkZ, 4);
        long generation = repository.generation();

        long fullBefore = repository.getPageRevision(
                CaveView.FULL, Integer.MIN_VALUE, pageX, pageZ);
        long layered20Before = repository.getPageRevision(
                CaveView.LAYERED, 20, pageX, pageZ);
        long layeredMinus20Before = repository.getPageRevision(
                CaveView.LAYERED, -20, pageX, pageZ);

        repository.markDisplayTileAbsent(
                CaveView.LAYERED, 20, chunkX, chunkZ, generation);
        long fullAfterLayered = repository.getPageRevision(
                CaveView.FULL, Integer.MIN_VALUE, pageX, pageZ);
        long layered20After = repository.getPageRevision(
                CaveView.LAYERED, 20, pageX, pageZ);
        long layeredMinus20After = repository.getPageRevision(
                CaveView.LAYERED, -20, pageX, pageZ);

        require(fullAfterLayered == fullBefore,
                "Layered display mutation invalidated Full Cave");
        require(layered20After != layered20Before,
                "Layered display mutation did not advance its own band");
        require(layeredMinus20After == layeredMinus20Before,
                "Layered display mutation invalidated another retained band");

        repository.markDisplayTileAbsent(
                CaveView.FULL, Integer.MIN_VALUE, chunkX, chunkZ, generation);
        long fullAfterFull = repository.getPageRevision(
                CaveView.FULL, Integer.MIN_VALUE, pageX, pageZ);
        long layered20AfterFull = repository.getPageRevision(
                CaveView.LAYERED, 20, pageX, pageZ);
        require(fullAfterFull != fullAfterLayered,
                "Full display mutation did not advance Full Cave");
        require(layered20AfterFull == layered20After,
                "Full display mutation invalidated Layered Cave");
    }

    private static CompactCaveTile tile(int chunkX, int chunkZ,
            long revision, int color) {
        int[] offsets = new int[257];
        short[] top = new short[256];
        short[] floor = new short[256];
        int[] colors = new int[256];
        short[] biomes = new short[256];
        byte[] block = new byte[256];
        byte[] sky = new byte[256];
        byte[] fluid = new byte[256];
        byte[] flags = new byte[256];
        byte[] statuses = new byte[256];
        for (int column = 0; column < 256; column++) {
            offsets[column] = column;
            top[column] = 42;
            floor[column] = 32;
            colors[column] = color;
            flags[column] = CompactCaveTile.FLAG_LEGACY_COLOR;
            statuses[column] =
                    (byte) CompactCaveTile.ColumnStatus.COMPLETE.ordinal();
        }
        offsets[256] = 256;
        return new CompactCaveTile(chunkX, chunkZ, revision, offsets,
                top, floor, colors, biomes, block, sky, fluid, flags, statuses);
    }

    private static void verifyCacheValidationArchitecture() throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String archive = Files.readString(root.resolve(
                "archive/CaveArchiveV2Service.java"));
        String repository = Files.readString(root.resolve("CaveTileRepository.java"));
        String cimg = Files.readString(root.resolve("CaveRegionImageCache.java"));
        String manager = Files.readString(root.resolve(
                "UnifiedCaveTextureManager.java"));
        String lod = Files.readString(root.resolve("CaveLodTree.java"));

        require(archive.contains("pageRevision(int globalPageX, int globalPageZ)")
                        && archive.contains("indexedContributions")
                        && archive.contains("indexedFingerprints")
                        && archive.contains("updatePageFingerprint(")
                        && archive.contains("Resident eviction is not a source mutation"),
                "vertical archive source identity depends on resident LRU state");
        require(repository.contains("projectionPageRevisions")
                        && repository.contains("Long2ObjectOpenHashMap<Long2LongOpenHashMap>")
                        && repository.contains("projectionRevisionNamespace")
                        && repository.contains("getPageRevision(CaveView view, int layerY")
                        && repository.contains("archiveRevision")
                        && repository.contains("Long.rotateLeft"),
                "exact-page source revision is not projection-scoped");
        require(cimg.contains("private static final int VERSION = 8;")
                        && cimg.contains("long[] pageSourceStamps")
                        && cimg.contains("pageSourceStamp(int localPageX"),
                "CIMG does not persist page source versions");
        require(manager.contains("validRegionImagePageMask(image)")
                        && manager.contains("cached != 0L && cached == current")
                        && manager.contains("cachedSourceRevision != currentSourceRevision")
                        && manager.contains("pageSourceStamps[ordinal] = sourceRevision")
                        && manager.contains("consumedRegionImageTimestamps")
                        && manager.contains("result.sourceRevision() != currentSource")
                        && !manager.contains("result.sourceRevision() < currentSource")
                        && !manager.contains("result.sourceRevision() > currentSource"),
                "CIMG/build replay is not validated by exact source fingerprint equality");
        require(lod.contains("if (foreground) break;")
                        && lod.contains("CAVE_BRANCH_BACKGROUND_DENIAL_BYPASSED"),
                "branch publication still spins through foreground denials");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
