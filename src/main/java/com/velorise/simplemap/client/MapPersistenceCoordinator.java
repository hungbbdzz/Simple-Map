package com.velorise.simplemap.client;

/**
 * Single pull-driven control loop for persistent map data.
 *
 * <p>Xaero-style persistence does not let every cache submit an arbitrary number
 * of writes from the client tick. Dirty data remains owned by its subsystem and
 * this coordinator only admits a small amount of work per pass. Foreground reads
 * therefore remain responsive even after long cave exploration sessions.</p>
 */
public final class MapPersistenceCoordinator {
    private static final MapPersistenceCoordinator INSTANCE =
            new MapPersistenceCoordinator();

    private static final long ACTIVE_RETRY_MS = 1_000L;
    private static final long SURFACE_IDLE_INTERVAL_MS = 10_000L;
    private static final long LIGHT_IDLE_INTERVAL_MS = 10_000L;
    private static final long PLAYER_IDLE_BEFORE_SAVE_MS = 3_000L;
    private static final double MOVEMENT_EPSILON_SQ = 0.0001D;

    private long nextSurfacePumpMs;
    private long nextLightPumpMs;
    private long lastPlayerMovementMs;
    private double lastPlayerX = Double.NaN;
    private double lastPlayerZ = Double.NaN;
    private int nextSubsystem;

    private MapPersistenceCoordinator() {
    }

    public static MapPersistenceCoordinator getInstance() {
        return INSTANCE;
    }

    public void tick(MapManager mapManager) {
        if (mapManager == null) return;
        if (MapActivityGate.getInstance().blocksMapWork()) return;
        long now = System.currentTimeMillis();
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player == null) return;

        double playerX = minecraft.player.getX();
        double playerZ = minecraft.player.getZ();
        if (Double.isNaN(lastPlayerX)) {
            lastPlayerX = playerX;
            lastPlayerZ = playerZ;
            lastPlayerMovementMs = now;
            return;
        }
        double dx = playerX - lastPlayerX;
        double dz = playerZ - lastPlayerZ;
        lastPlayerX = playerX;
        lastPlayerZ = playerZ;
        if (dx * dx + dz * dz > MOVEMENT_EPSILON_SQ) {
            lastPlayerMovementMs = now;
            return;
        }

        // Persistence is bulk memory/codec work, not foreground observation. Do
        // not snapshot or encode while movement mutations or other IO are active.
        if (now - lastPlayerMovementMs < PLAYER_IDLE_BEFORE_SAVE_MS
                || MapMutationBus.getInstance().pendingColumns() != 0
                || MapMutationBus.getInstance().pendingChunks() != 0
                || MapMutationBus.getInstance().pendingRegions() != 0
                || MapWorkScheduler.ioQueuedCount() != 0) {
            return;
        }

        // Drain at most one subsystem per second. Dirty ownership remains with the
        // subsystem, so a busy travel session cannot retain repeated snapshots.
        switch (nextSubsystem++ % 3) {
            case 0 -> {
                if (now >= nextSurfacePumpMs) {
                    int remaining = mapManager.pumpDirtyRegionSaves(1);
                    nextSurfacePumpMs = now + (remaining > 0
                            ? ACTIVE_RETRY_MS : SURFACE_IDLE_INTERVAL_MS);
                }
            }
            case 1 -> {
                if (now >= nextLightPumpMs) {
                    int remaining = MapLightManager.getInstance()
                            .pumpDirtyRegionSaves(1);
                    nextLightPumpMs = now + (remaining > 0
                            ? ACTIVE_RETRY_MS : LIGHT_IDLE_INTERVAL_MS);
                }
            }
            default -> CaveMapManager.getInstance().tickSave();
        }
    }

    public void reset() {
        nextSurfacePumpMs = 0L;
        nextLightPumpMs = 0L;
        lastPlayerMovementMs = 0L;
        lastPlayerX = Double.NaN;
        lastPlayerZ = Double.NaN;
        nextSubsystem = 0;
    }
}
