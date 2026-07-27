package com.velorise.simplemap.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Global residency authority shared by surface exact, cave exact, branch and
 * legacy textures.
 *
 * <p>V16.4 shared only an importance score while each atlas still evicted
 * locally. V16.5 adds a hard warm-content ceiling and explicit render-thread
 * eviction callbacks. Fixed atlas storage is controlled by
 * {@link MapMemoryBudgetPolicy}; this manager controls which page/branch payloads
 * remain resident inside that storage and which CPU-side copies stay attached to
 * active GPU entries.</p>
 */
public final class MapResidencyManager {
    private static final MapResidencyManager INSTANCE = new MapResidencyManager();
    private static final ThreadLocal<MapRequestLane> RENDER_LANE = new ThreadLocal<>();
    private static final long MINIMAP_PIN_NANOS = 4_000_000_000L;
    private static final long FULLSCREEN_PIN_NANOS = 3_000_000_000L;

    public enum Kind {
        SURFACE_EXACT(5),
        CAVE_EXACT(5),
        SURFACE_BRANCH(3),
        CAVE_BRANCH(3),
        LEGACY(1);

        private final int baseRank;

        Kind(int baseRank) {
            this.baseRank = baseRank;
        }
    }

    @FunctionalInterface
    public interface EvictionHandler {
        /** Returns true only when the resident object was actually retired. */
        boolean evict();
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong globalEvictions = new AtomicLong();
    private final AtomicLong budgetFailures = new AtomicLong();
    /** Changes only when existing atlas UVs can become unsafe. */
    private final AtomicLong topologyRevision = new AtomicLong();
    /** Changes whenever visible resident coverage is added, updated or removed. */
    private final AtomicLong contentRevision = new AtomicLong();

    private MapResidencyManager() {
    }

    public static MapResidencyManager getInstance() {
        return INSTANCE;
    }

    public static void beginRender(MapRequestLane lane) {
        RENDER_LANE.set(lane == null ? MapRequestLane.FULLSCREEN : lane);
    }

    public static void endRender() {
        RENDER_LANE.remove();
    }

    public static MapRequestLane currentRenderLane() {
        MapRequestLane lane = RENDER_LANE.get();
        return lane == null ? MapRequestLane.FULLSCREEN : lane;
    }

    public void register(String key, Kind kind, long estimatedBytes) {
        register(key, kind, estimatedBytes, null);
    }

    public void register(String key, Kind kind, long estimatedBytes,
            EvictionHandler evictionHandler) {
        if (key == null) return;
        long now = System.nanoTime();
        Kind effectiveKind = kind == null ? Kind.LEGACY : kind;
        boolean[] coverageChanged = {false};
        entries.compute(key, (ignored, old) -> {
            Entry entry = old == null ? new Entry() : old;
            if (old == null || entry.kind != effectiveKind) coverageChanged[0] = true;
            entry.kind = effectiveKind;
            entry.estimatedBytes = Math.max(0L, estimatedBytes);
            if (evictionHandler != null) entry.evictionHandler = evictionHandler;
            if (entry.createdNanos == 0L) entry.createdNanos = now;
            entry.lastResidentNanos = now;
            if (entry.lastTouchNanos == 0L) entry.lastTouchNanos = now;
            return entry;
        });
        // Pixel uploads into an existing atlas slot do not invalidate geometry/UV
        // plans. Only newly visible coverage (or a kind/topology transition) does.
        if (coverageChanged[0]) contentRevision.incrementAndGet();
    }

    public long topologyRevision() {
        return topologyRevision.get();
    }

    public long contentRevision() {
        return contentRevision.get();
    }

    /** Call when an atlas object/storage is recreated and all cached UV plans are unsafe. */
    public void markTopologyChanged() {
        topologyRevision.incrementAndGet();
        contentRevision.incrementAndGet();
    }

    /**
     * Marks a visible coverage/mask transition without invalidating atlas UVs.
     * Pixel-only updates inside an already visible slot deliberately do not call
     * this method.
     */
    public void markCoverageChanged() {
        contentRevision.incrementAndGet();
    }

    public void touch(String key) {
        touch(key, currentRenderLane());
    }

    public void touch(String key, MapRequestLane lane) {
        if (key == null) return;
        long now = System.nanoTime();
        MapRequestLane effective = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        entries.computeIfPresent(key, (ignored, entry) -> {
            entry.lastTouchNanos = now;
            if (effective.rank() >= entry.strongestLaneRank
                    || now - entry.lastLaneNanos > 3_000_000_000L) {
                entry.strongestLaneRank = effective.rank();
                entry.lastLaneNanos = now;
            }
            return entry;
        });
    }

    public void remove(String key) {
        if (key != null && entries.remove(key) != null) {
            topologyRevision.incrementAndGet();
            contentRevision.incrementAndGet();
        }
    }

    /** Lower values are better eviction victims. */
    public long evictionScore(String key) {
        Entry entry = key == null ? null : entries.get(key);
        if (entry == null) return Long.MIN_VALUE / 4;
        long now = System.nanoTime();
        long laneAge = now - entry.lastLaneNanos;
        int laneRank = laneAge > 3_000_000_000L ? 0 : entry.strongestLaneRank;
        long recentBucket = Math.max(0L,
                10_000_000_000L - Math.min(10_000_000_000L,
                        now - entry.lastTouchNanos));
        // Large weak residents are slightly preferred as victims because they
        // recover more budget per eviction without overriding lane/kind priority.
        long sizePenalty = Math.min((1L << 48) - 1L,
                Math.max(0L, entry.estimatedBytes) << 8);
        return ((long) laneRank << 56)
                + ((long) entry.kind.baseRank << 52)
                + recentBucket - sizePenalty;
    }

    public boolean isPinned(String key) {
        Entry entry = key == null ? null : entries.get(key);
        if (entry == null) return false;
        long now = System.nanoTime();
        long age = now - entry.lastLaneNanos;
        if (entry.strongestLaneRank >= MapRequestLane.MINIMAP.rank()) {
            return age <= MINIMAP_PIN_NANOS;
        }
        if (entry.strongestLaneRank >= MapRequestLane.FULLSCREEN.rank()) {
            return age <= FULLSCREEN_PIN_NANOS;
        }
        return false;
    }

    public String chooseVictim(Collection<String> candidates, String protectedKey) {
        if (candidates == null || candidates.isEmpty()) return null;
        String best = null;
        long bestScore = Long.MAX_VALUE;
        for (String candidate : candidates) {
            if (candidate == null || candidate.equals(protectedKey)
                    || isPinned(candidate)) continue;
            long score = evictionScore(candidate);
            if (best == null || score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best != null) return best;
        // Local atlas exhaustion must not deadlock publication. If every local
        // entry is pinned, pick the least-important non-protected fallback.
        for (String candidate : candidates) {
            if (candidate == null || candidate.equals(protectedKey)) continue;
            long score = evictionScore(candidate);
            if (best == null || score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Enforces the global resident-content ceiling. Call on the render thread
     * after publishing or registering a new resident. Visible minimap entries are
     * never selected; fullscreen pins are relaxed only when the hard ceiling is
     * exceeded by more than ten percent.
     */
    public boolean enforceBudget(String protectedKey, MapRequestLane requesterLane) {
        long budget = MapMemoryBudgetPolicy.residentContentBudgetBytes();
        if (budget <= 0L) return true;
        int guard = 0;
        while (estimatedResidentBytes() > budget && guard++ < 128) {
            long current = estimatedResidentBytes();
            boolean emergency = current > budget + budget / 10L;
            EntryCandidate victim = chooseGlobalVictim(protectedKey, emergency);
            if (victim == null) {
                budgetFailures.incrementAndGet();
                return false;
            }
            Entry entry = entries.get(victim.key);
            EvictionHandler handler = entry == null ? null : entry.evictionHandler;
            boolean evicted = false;
            try {
                evicted = handler != null && handler.evict();
            } catch (RuntimeException ignored) {
                evicted = false;
            }
            if (evicted) {
                if (entries.remove(victim.key, entry)) {
                    topologyRevision.incrementAndGet();
                    contentRevision.incrementAndGet();
                }
                globalEvictions.incrementAndGet();
            } else {
                // Do not spin forever on a manager that cannot evict during an
                // active render batch. Mark it unavailable for this enforcement.
                if (entry != null) entry.blockedUntilNanos =
                        System.nanoTime() + 100_000_000L;
            }
        }
        return estimatedResidentBytes() <= budget;
    }

    private EntryCandidate chooseGlobalVictim(String protectedKey,
            boolean emergency) {
        long now = System.nanoTime();
        List<EntryCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, Entry> mapEntry : entries.entrySet()) {
            String key = mapEntry.getKey();
            Entry entry = mapEntry.getValue();
            if (key == null || key.equals(protectedKey)
                    || entry.evictionHandler == null
                    || entry.blockedUntilNanos > now) continue;
            boolean pinned = isPinned(key);
            if (pinned) {
                if (entry.strongestLaneRank >= MapRequestLane.MINIMAP.rank()) continue;
                if (!emergency) continue;
            }
            candidates.add(new EntryCandidate(key, evictionScore(key)));
        }
        return candidates.stream()
                .min(Comparator.comparingLong(EntryCandidate::score))
                .orElse(null);
    }

    private long estimatedResidentBytes() {
        long bytes = 0L;
        for (Entry entry : entries.values()) bytes += Math.max(0L, entry.estimatedBytes);
        return bytes;
    }

    public Snapshot snapshot() {
        long bytes = 0L;
        int pinned = 0;
        for (Map.Entry<String, Entry> mapEntry : entries.entrySet()) {
            bytes += Math.max(0L, mapEntry.getValue().estimatedBytes);
            if (isPinned(mapEntry.getKey())) pinned++;
        }
        return new Snapshot(entries.size(), pinned, bytes,
                MapMemoryBudgetPolicy.residentContentBudgetBytes(),
                globalEvictions.get(), budgetFailures.get());
    }

    private static final class Entry {
        private Kind kind = Kind.LEGACY;
        private long estimatedBytes;
        private long createdNanos;
        private long lastResidentNanos;
        private long lastTouchNanos;
        private long lastLaneNanos;
        private long blockedUntilNanos;
        private int strongestLaneRank;
        private EvictionHandler evictionHandler;
    }

    private record EntryCandidate(String key, long score) {
    }

    public record Snapshot(int residentEntries, int pinnedEntries,
            long estimatedBytes, long budgetBytes, long globalEvictions,
            long budgetFailures) {
    }
}
