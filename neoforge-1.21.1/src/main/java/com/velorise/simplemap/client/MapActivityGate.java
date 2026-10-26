package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/**
 * Allocation-free movement state shared by map producers.
 *
 * <p>Movement no longer means "disable the map". Packet mutation, broad saved-world
 * reads, warmup and persistence remain blocked, while one centre-out foreground
 * streaming lane may continue from already-loaded chunks. The performance governor
 * gives that lane a small deadline only on healthy frames and pauses it immediately
 * when Minecraft chunk generation or frame pacing is under pressure.</p>
 */
public final class MapActivityGate {
    private static final MapActivityGate INSTANCE = new MapActivityGate();
    public static final long MOVEMENT_SETTLE_NANOS = 3_000_000_000L;
    public static final long TELEPORT_QUARANTINE_NANOS = 350_000_000L;
    /**
     * Destination chunks can arrive over several client ticks after a position jump.
     * Keep their exact-page demand repairable for a bounded window instead of
     * declaring currently-missing subtiles terminally empty.
     */
    public static final long TELEPORT_RECOVERY_NANOS = 5_000_000_000L;
    private static final double POSITION_EPSILON_SQ = 0.0004D;
    private static final double VELOCITY_EPSILON_SQ = 0.0004D;
    private static final double TELEPORT_DISTANCE_SQ = 64.0D * 64.0D;

    private volatile boolean movementWindow;
    private volatile boolean activelyMoving;
    private volatile long movementEpoch;
    /** Increments for every teleport/dimension handoff, even inside one movement window. */
    private volatile long teleportEpoch;
    private volatile long teleportRecoveryUntilNanos;
    private volatile double horizontalSpeedSquared;
    private volatile long foregroundBlockedUntilNanos;
    private Level observedLevel;
    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    private long lastMovementNanos;
    private long lastUpdateNanos;

    private MapActivityGate() { }

    public static MapActivityGate getInstance() { return INSTANCE; }

    /** Called once from the client post-tick before any map subsystem is pumped. */
    public synchronized void update(Minecraft minecraft) {
        long now = System.nanoTime();
        lastUpdateNanos = now;
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            reset();
            return;
        }
        Level level = minecraft.level;
        double x = minecraft.player.getX();
        double z = minecraft.player.getZ();
        double velocitySq = minecraft.player.getDeltaMovement().horizontalDistanceSqr();
        boolean levelChanged = observedLevel != level;
        double distanceSq = 0.0D;
        boolean movedByPosition = levelChanged || Double.isNaN(lastX);
        if (!movedByPosition) {
            double dx = x - lastX;
            double dz = z - lastZ;
            distanceSq = dx * dx + dz * dz;
            movedByPosition = distanceSq > POSITION_EPSILON_SQ;
        }
        activelyMoving = movedByPosition || velocitySq > VELOCITY_EPSILON_SQ;
        horizontalSpeedSquared = velocitySq;
        observedLevel = level;
        lastX = x;
        lastZ = z;
        if (activelyMoving) lastMovementNanos = now;
        if (levelChanged || distanceSq > TELEPORT_DISTANCE_SQ) {
            foregroundBlockedUntilNanos = now + TELEPORT_QUARANTINE_NANOS;
            teleportRecoveryUntilNanos = now + TELEPORT_RECOVERY_NANOS;
            teleportEpoch++;
        }
        boolean nextWindow = lastMovementNanos != 0L
                && now - lastMovementNanos < MOVEMENT_SETTLE_NANOS;
        if (nextWindow && !movementWindow) movementEpoch++;
        movementWindow = nextWindow;
    }

    /**
     * Heavy/background work remains blocked for the complete settle window.
     * Existing callers for packet mutation and persistence intentionally use this.
     */
    public boolean blocksMapWork() { return movementWindow; }

    /** Foreground loaded-chunk streaming is blocked only for a teleport/world handoff. */
    public boolean blocksForegroundStreaming() {
        long now = lastUpdateNanos == 0L ? System.nanoTime() : lastUpdateNanos;
        return now < foregroundBlockedUntilNanos;
    }

    public boolean isActivelyMoving() { return activelyMoving; }

    public boolean isSettlingAfterMovement() {
        return movementWindow && !activelyMoving;
    }

    public double horizontalSpeedSquared() { return horizontalSpeedSquared; }

    public long movementEpoch() { return movementEpoch; }

    /** Distinct from movementEpoch: repeated teleports must each revoke old ownership once. */
    public long teleportEpoch() { return teleportEpoch; }

    public boolean isTeleportRecoveryActive() {
        long now = lastUpdateNanos == 0L ? System.nanoTime() : lastUpdateNanos;
        return teleportEpoch != 0L && now < teleportRecoveryUntilNanos;
    }

    public synchronized void reset() {
        movementWindow = false;
        activelyMoving = false;
        movementEpoch = 0L;
        teleportEpoch = 0L;
        horizontalSpeedSquared = 0.0D;
        foregroundBlockedUntilNanos = 0L;
        teleportRecoveryUntilNanos = 0L;
        observedLevel = null;
        lastX = Double.NaN;
        lastZ = Double.NaN;
        lastMovementNanos = 0L;
        lastUpdateNanos = 0L;
    }
}
