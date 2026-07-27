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

    private static final long ACTIVE_RETRY_MS = 250L;
    private static final long SURFACE_IDLE_INTERVAL_MS = 10_000L;
    private static final long LIGHT_IDLE_INTERVAL_MS = 10_000L;

    private long nextSurfacePumpMs;
    private long nextLightPumpMs;

    private MapPersistenceCoordinator() {
    }

    public static MapPersistenceCoordinator getInstance() {
        return INSTANCE;
    }

    public void tick(MapManager mapManager) {
        if (mapManager == null) return;

        // Cave persistence already owns a bounded region-batch pump. Run it every
        // client tick so admission backoff can recover promptly, but it may submit
        // only its configured small number of batches.
        CaveMapManager.getInstance().tickSave();

        long now = System.currentTimeMillis();
        MapWorkScheduler.Snapshot pressure = MapWorkScheduler.snapshot();
        boolean ioBusy = pressure.ioQueuedCost() >= 220L || pressure.ioQueued() >= 6;

        if (now >= nextSurfacePumpMs) {
            int quota = ioBusy ? 1 : 3;
            int remaining = mapManager.pumpDirtyRegionSaves(quota);
            nextSurfacePumpMs = now + (remaining > 0
                    ? ACTIVE_RETRY_MS : SURFACE_IDLE_INTERVAL_MS);
        }

        if (now >= nextLightPumpMs) {
            int quota = ioBusy ? 1 : 2;
            int remaining = MapLightManager.getInstance().pumpDirtyRegionSaves(quota);
            nextLightPumpMs = now + (remaining > 0
                    ? ACTIVE_RETRY_MS : LIGHT_IDLE_INTERVAL_MS);
        }
    }

    public void reset() {
        nextSurfacePumpMs = 0L;
        nextLightPumpMs = 0L;
    }
}
