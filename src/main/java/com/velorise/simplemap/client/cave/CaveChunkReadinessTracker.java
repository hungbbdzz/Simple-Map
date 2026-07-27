package com.velorise.simplemap.client.cave;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Guards live cave transactions against partially delivered, replaced or mutated
 * chunks.
 *
 * <p>Object identity catches packet-driven chunk/section replacement. Per-chunk
 * mutation epochs additionally catch block and light changes that mutate the same
 * LevelChunk and LevelChunkSection instances in place. A transaction can therefore
 * never publish a projection assembled from two world revisions.</p>
 *
 * <p>Unlike the previous alpha, light correctness is no longer an unbounded hard
 * gate. Xaero waits for complete neighbouring chunks but does not reject a chunk
 * forever because a modded dimension never flips {@code isLightCorrect()}. In
 * skylit dimensions Simple Map waits briefly for light, then permits a provisional
 * geometry transaction. A later light packet invalidates and restyles it.</p>
 */
final class CaveChunkReadinessTracker {
    static final int RETRY_DELAY_TICKS = 2;
    private static final int STABLE_TICKS = 2;
    private static final int INITIAL_SETTLE_TICKS = 2;
    private static final int TELEPORT_SETTLE_TICKS = 5;
    private static final int TELEPORT_CHUNK_DISTANCE = 3;
    private static final int TELEPORT_VERTICAL_DISTANCE = 32;
    private static final int LIGHT_GRACE_TICKS = 8;
    private static final int NEIGHBOUR_DIAMETER = 3;
    private static final int NEIGHBOUR_COUNT = NEIGHBOUR_DIAMETER * NEIGHBOUR_DIAMETER;
    private static final int CENTRE_INDEX = 4;
    private static final int MAX_OBSERVATIONS = 4096;
    private static final int MAX_MUTATION_EPOCHS = 16_384;
    private static final int MAX_LIGHT_WAITS = 4096;

    private final Map<Long, Observation> observations =
            new LinkedHashMap<>(256, 0.75f, true);
    private final Map<Long, Long> mutationEpochs =
            new LinkedHashMap<>(512, 0.75f, true);
    private final Map<Long, Long> lightWaitSince =
            new LinkedHashMap<>(256, 0.75f, true);

    private Level observedLevel;
    private String observedDimension = "";
    private int lastPlayerChunkX = Integer.MIN_VALUE;
    private int lastPlayerChunkZ = Integer.MIN_VALUE;
    private int lastPlayerY = Integer.MIN_VALUE;
    private long settleUntilTick = Long.MIN_VALUE;
    private long mutationSequence = 1L;

    /**
     * Observes player movement once per client tick.
     *
     * @return true when in-flight live transactions should be discarded without
     * clearing already published textures.
     */
    boolean observePlayer(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            reset();
            return false;
        }

        Level level = minecraft.level;
        String dimension = level.dimension().location().toString();
        long tick = level.getGameTime();
        int chunkX = ((int) Math.floor(minecraft.player.getX())) >> 4;
        int chunkZ = ((int) Math.floor(minecraft.player.getZ())) >> 4;
        int playerY = (int) Math.floor(minecraft.player.getY());

        if (level != observedLevel || !dimension.equals(observedDimension)) {
            observedLevel = level;
            observedDimension = dimension;
            lastPlayerChunkX = chunkX;
            lastPlayerChunkZ = chunkZ;
            lastPlayerY = playerY;
            observations.clear();
            mutationEpochs.clear();
            lightWaitSince.clear();
            mutationSequence = 1L;
            settleUntilTick = tick + INITIAL_SETTLE_TICKS;
            return true;
        }

        boolean largeHorizontalMove = Math.max(
                Math.abs(chunkX - lastPlayerChunkX),
                Math.abs(chunkZ - lastPlayerChunkZ)) >= TELEPORT_CHUNK_DISTANCE;
        boolean largeVerticalMove = Math.abs(playerY - lastPlayerY)
                >= TELEPORT_VERTICAL_DISTANCE;

        lastPlayerChunkX = chunkX;
        lastPlayerChunkZ = chunkZ;
        lastPlayerY = playerY;

        if (!largeHorizontalMove && !largeVerticalMove) return false;

        observations.clear();
        lightWaitSince.clear();
        settleUntilTick = Math.max(settleUntilTick, tick + TELEPORT_SETTLE_TICKS);
        return true;
    }

    boolean isSettling(Level level) {
        return level != null && level == observedLevel
                && level.getGameTime() < settleUntilTick;
    }

    /** Invalidates all centre snapshots whose 3x3 capture contains this chunk. */
    void markChunkChanged(int chunkX, int chunkZ) {
        long epoch = ++mutationSequence;
        mutationEpochs.put(ChunkPos.asLong(chunkX, chunkZ), epoch);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                long centerKey = ChunkPos.asLong(chunkX + dx, chunkZ + dz);
                observations.remove(centerKey);
                lightWaitSince.remove(centerKey);
            }
        }
        trimMutationEpochs();
    }

    Snapshot acquire(Level level, int chunkX, int chunkZ) {
        if (level == null || level != observedLevel || isSettling(level)) return null;

        Capture capture = capture(level, chunkX, chunkZ);
        long key = ChunkPos.asLong(chunkX, chunkZ);
        if (capture == null) {
            observations.remove(key);
            lightWaitSince.remove(key);
            return null;
        }

        long tick = level.getGameTime();
        boolean lightGateRelevant = level.dimensionType().hasSkyLight()
                && !level.dimensionType().hasCeiling();
        if (lightGateRelevant && !capture.lightCorrect()) {
            long firstWait = lightWaitSince.computeIfAbsent(key, ignored -> tick);
            trimLightWaits();
            if (tick - firstWait < LIGHT_GRACE_TICKS) return null;
        } else {
            lightWaitSince.remove(key);
        }

        Observation observation = observations.get(key);
        if (observation == null || !sameCapture(observation, capture)) {
            observations.put(key, new Observation(capture.chunks(),
                    capture.centerSections(), capture.epochs(), tick));
            trimObservations();
            return null;
        }

        if (tick - observation.stableSinceTick < STABLE_TICKS) return null;
        return new Snapshot(level, chunkX, chunkZ, capture.chunks().clone(),
                capture.centerSections().clone(), capture.epochs().clone(), tick,
                !capture.lightCorrect());
    }

    boolean stillValid(Level level, Snapshot snapshot) {
        if (snapshot == null || level == null || level != snapshot.level()
                || isSettling(level)) return false;
        Capture current = capture(level, snapshot.chunkX(), snapshot.chunkZ());
        return current != null
                && sameChunks(snapshot.chunks(), current.chunks())
                && sameSections(snapshot.centerSections(), current.centerSections())
                && sameEpochs(snapshot.epochs(), current.epochs());
    }

    void reset() {
        observations.clear();
        mutationEpochs.clear();
        lightWaitSince.clear();
        observedLevel = null;
        observedDimension = "";
        lastPlayerChunkX = Integer.MIN_VALUE;
        lastPlayerChunkZ = Integer.MIN_VALUE;
        lastPlayerY = Integer.MIN_VALUE;
        settleUntilTick = Long.MIN_VALUE;
        mutationSequence = 1L;
    }

    private void trimObservations() {
        int excess = observations.size() - MAX_OBSERVATIONS;
        if (excess <= 0) return;
        Iterator<Long> iterator = observations.keySet().iterator();
        while (excess-- > 0 && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private void trimMutationEpochs() {
        int excess = mutationEpochs.size() - MAX_MUTATION_EPOCHS;
        if (excess <= 0) return;
        Iterator<Long> iterator = mutationEpochs.keySet().iterator();
        while (excess-- > 0 && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private void trimLightWaits() {
        int excess = lightWaitSince.size() - MAX_LIGHT_WAITS;
        if (excess <= 0) return;
        Iterator<Long> iterator = lightWaitSince.keySet().iterator();
        while (excess-- > 0 && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private Capture capture(Level level, int centerChunkX, int centerChunkZ) {
        LevelChunk[] result = new LevelChunk[NEIGHBOUR_COUNT];
        long[] epochs = new long[NEIGHBOUR_COUNT];
        boolean allLightCorrect = true;
        int index = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                LevelChunk chunk = fullChunk(level, chunkX, chunkZ);
                if (chunk == null) return null;
                if (!chunk.isLightCorrect()) allLightCorrect = false;
                result[index] = chunk;
                epochs[index] = mutationEpochs.getOrDefault(
                        ChunkPos.asLong(chunkX, chunkZ), 0L);
                index++;
            }
        }
        LevelChunkSection[] sections = result[CENTRE_INDEX].getSections().clone();
        return new Capture(result, sections, epochs, allLightCorrect);
    }

    private static LevelChunk fullChunk(Level level, int chunkX, int chunkZ) {
        try {
            ChunkAccess access = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            return access instanceof LevelChunk chunk ? chunk : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean sameChunks(LevelChunk[] first, LevelChunk[] second) {
        if (first == null || second == null || first.length != second.length) return false;
        for (int i = 0; i < first.length; i++) {
            if (first[i] != second[i]) return false;
        }
        return true;
    }

    private static boolean sameSections(LevelChunkSection[] first, LevelChunkSection[] second) {
        if (first == null || second == null || first.length != second.length) return false;
        for (int i = 0; i < first.length; i++) {
            if (first[i] != second[i]) return false;
        }
        return true;
    }

    private static boolean sameEpochs(long[] first, long[] second) {
        if (first == null || second == null || first.length != second.length) return false;
        for (int i = 0; i < first.length; i++) {
            if (first[i] != second[i]) return false;
        }
        return true;
    }

    private static boolean sameCapture(Observation observation, Capture capture) {
        return sameChunks(observation.chunks(), capture.chunks())
                && sameSections(observation.centerSections(), capture.centerSections())
                && sameEpochs(observation.epochs(), capture.epochs());
    }

    record Snapshot(Level level, int chunkX, int chunkZ,
            LevelChunk[] chunks, LevelChunkSection[] centerSections,
            long[] epochs, long acquiredTick, boolean provisionalLight) {
        LevelChunk centerChunk() {
            return chunks[CENTRE_INDEX];
        }
    }

    private record Capture(LevelChunk[] chunks,
            LevelChunkSection[] centerSections, long[] epochs,
            boolean lightCorrect) {
    }

    private record Observation(LevelChunk[] chunks,
            LevelChunkSection[] centerSections, long[] epochs,
            long stableSinceTick) {
    }
}
