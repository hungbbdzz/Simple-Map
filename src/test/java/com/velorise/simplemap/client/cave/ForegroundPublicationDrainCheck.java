package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ForegroundPublicationDrainCheck {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client");
        String manager = Files.readString(
                root.resolve("cave/UnifiedCaveTextureManager.java"));
        String reader = Files.readString(root.resolve("cave/CaveWorldSaveReader.java"));
        String repository = Files.readString(root.resolve("cave/CaveTileRepository.java"));
        String viewport = Files.readString(root.resolve("MapViewportCoordinator.java"));
        String scanner = Files.readString(root.resolve("ChunkScanner.java"));

        require(manager.contains("Math.min(completedBuilds.size(), MAX_PAGES)"),
                "completed cave queue still has the 72-entry head-of-line cap");
        require(manager.contains("CAVE_COMPLETION_QUEUE_BYPASS"),
                "missing completion queue bypass telemetry");
        require(reader.contains("resolvedPageStamps"),
                "world reader still keys completed pages to volatile raw revisions");
        require(repository.contains("getDisplayPageResolutionStamp"),
                "repository lacks a display-only page fingerprint");
        require(viewport.contains("tickLoadedPlayerHalo"),
                "player render-distance maintenance is still tied to minimap visibility");
        require(scanner.contains("maintainLoadedSurfaceHalo"),
                "loaded Surface chunks are not continuously drained while MapScreen is open");
        require(!viewport.contains(
                "openingFullscreen) {\n            UnifiedCaveTextureManager.getInstance().suspendLane(MapRequestLane.MINIMAP)"),
                "opening fullscreen still revokes the player-local loading set");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
