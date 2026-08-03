package com.velorise.simplemap.client;

import java.util.Collection;
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
        SURFACE_LEGACY(1),
        CAVE_LEGACY(1),
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
    /** O(1) resident byte accounting; never rescan the whole residency table in hot paths. */
    private final AtomicLong residentBytes = new AtomicLong();
    /** Changes only when existing atlas UVs can become unsafe. */
    private final AtomicLong topologyRevision = new AtomicLong();
    /** Changes whenever visible resident coverage is added, updated or removed. */
    private final AtomicLong contentRevision = new AtomicLong();
    /** Family-local coverage revisions prevent unrelated surface/cave churn rebuilding plans. */
    private final AtomicLong surfaceContentRevision = new AtomicLong();
    private final AtomicLong caveContentRevision = new AtomicLong();
    /**
     * GPU pixel publication revisions are intentionally separate from coverage.
     * A page can upload changed texels into an already-resident atlas slot without
     * altering render-plan geometry. Retained composition targets still need to
     * know that their copied pixels are stale, so every exact atlas write advances
     * the corresponding family revision.
     */
    private final AtomicLong surfacePixelRevision = new AtomicLong();
    private final AtomicLong cavePixelRevision = new AtomicLong();

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
        long effectiveBytes = Math.max(0L, estimatedBytes);

        while (true) {
            Entry entry = entries.get(key);
            boolean created = false;
            if (entry == null) {
                Entry candidate = new Entry();
                Entry raced = entries.putIfAbsent(key, candidate);
                entry = raced == null ? candidate : raced;
                created = raced == null;
            }

            Kind oldKind;
            long oldBytes;
            synchronized (entry) {
                // A concurrent eviction may have detached this entry after get().
                // Retry rather than updating an orphan and corrupting byte accounting.
                if (entries.get(key) != entry) continue;
                oldKind = entry.kind;
                oldBytes = entry.estimatedBytes;
                entry.kind = effectiveKind;
                entry.estimatedBytes = effectiveBytes;
                if (evictionHandler != null) entry.evictionHandler = evictionHandler;
                if (entry.createdNanos == 0L) entry.createdNanos = now;
                entry.lastResidentNanos = now;
                if (entry.lastTouchNanos == 0L) entry.lastTouchNanos = now;
            }

            long delta = effectiveBytes - oldBytes;
            if (delta != 0L) residentBytes.addAndGet(delta);
            if (created || oldKind != effectiveKind) {
                contentRevision.incrementAndGet();
                if (!created) incrementFamilyRevision(oldKind);
                incrementFamilyRevision(effectiveKind);
            }
            return;
        }
    }

    public long topologyRevision() {
        return topologyRevision.get();
    }

    public long contentRevision() {
        return contentRevision.get();
    }

    public long surfaceContentRevision() {
        return surfaceContentRevision.get();
    }

    public long caveContentRevision() {
        return caveContentRevision.get();
    }

    public long surfacePixelRevision() {
        return surfacePixelRevision.get();
    }

    public long cavePixelRevision() {
        return cavePixelRevision.get();
    }

    /** Records an atlas texel mutation without forcing render-plan reconstruction. */
    public void markPixelsChanged(Kind kind) {
        Kind effective = kind == null ? Kind.LEGACY : kind;
        switch (effective) {
            case SURFACE_EXACT, SURFACE_BRANCH, SURFACE_LEGACY ->
                    surfacePixelRevision.incrementAndGet();
            case CAVE_EXACT, CAVE_BRANCH, CAVE_LEGACY ->
                    cavePixelRevision.incrementAndGet();
            case LEGACY -> {
                surfacePixelRevision.incrementAndGet();
                cavePixelRevision.incrementAndGet();
            }
        }
    }

    /** Call when an atlas object/storage is recreated and all cached UV plans are unsafe. */
    public void markTopologyChanged() {
        topologyRevision.incrementAndGet();
        contentRevision.incrementAndGet();
        surfaceContentRevision.incrementAndGet();
        caveContentRevision.incrementAndGet();
        surfacePixelRevision.incrementAndGet();
        cavePixelRevision.incrementAndGet();
    }

    /**
     * Marks a visible coverage/mask transition without invalidating atlas UVs.
     * Pixel-only updates inside an already visible slot deliberately do not call
     * this method.
     */
    public void markCoverageChanged() {
        contentRevision.incrementAndGet();
        surfaceContentRevision.incrementAndGet();
        caveContentRevision.incrementAndGet();
    }

    public void markCoverageChanged(Kind kind) {
        contentRevision.incrementAndGet();
        incrementFamilyRevision(kind);
    }

    private void incrementFamilyRevision(Kind kind) {
        if (kind == Kind.SURFACE_EXACT || kind == Kind.SURFACE_BRANCH
                || kind == Kind.SURFACE_LEGACY) {
            surfaceContentRevision.incrementAndGet();
        } else if (kind == Kind.CAVE_EXACT || kind == Kind.CAVE_BRANCH
                || kind == Kind.CAVE_LEGACY) {
            caveContentRevision.incrementAndGet();
        } else {
            // Legacy entries can participate in either projection.
            surfaceContentRevision.incrementAndGet();
            caveContentRevision.incrementAndGet();
        }
    }

    public void touch(String key) {
        touch(key, currentRenderLane());
    }

    public void touch(String key, MapRequestLane lane) {
        if (key == null) return;
        Entry entry = entries.get(key);
        if (entry == null) return;
        long now = System.nanoTime();
        MapRequestLane effective = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        entry.lastTouchNanos = now;
        if (effective.rank() >= entry.strongestLaneRank
                || now - entry.lastLaneNanos > 3_000_000_000L) {
            entry.strongestLaneRank = effective.rank();
            entry.lastLaneNanos = now;
        }
    }

    public void remove(String key) {
        if (key == null) return;
        Entry entry = entries.get(key);
        if (entry == null) return;
        synchronized (entry) {
            if (!entries.remove(key, entry)) return;
            residentBytes.addAndGet(-Math.max(0L, entry.estimatedBytes));
        }
        contentRevision.incrementAndGet();
        incrementFamilyRevision(entry.kind);
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
        while (residentBytes.get() > budget && guard++ < 128) {
            long current = residentBytes.get();
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
                // Handlers normally call remove() themselves. If one does not,
                // retire it here exactly once and keep byte accounting coherent.
                if (entry != null) {
                    synchronized (entry) {
                        if (entries.remove(victim.key, entry)) {
                            residentBytes.addAndGet(-Math.max(0L, entry.estimatedBytes));
                            contentRevision.incrementAndGet();
                            incrementFamilyRevision(entry.kind);
                        }
                    }
                }
                globalEvictions.incrementAndGet();
            } else if (entry != null) {
                // Do not spin forever on a manager that cannot evict during an
                // active render batch. Mark it unavailable for this enforcement.
                entry.blockedUntilNanos = System.nanoTime() + 100_000_000L;
            }
        }
        return residentBytes.get() <= budget;
    }

    private EntryCandidate chooseGlobalVictim(String protectedKey,
            boolean emergency) {
        long now = System.nanoTime();
        String bestKey = null;
        long bestScore = Long.MAX_VALUE;
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
            long score = evictionScore(key);
            if (bestKey == null || score < bestScore) {
                bestKey = key;
                bestScore = score;
            }
        }
        return bestKey == null ? null : new EntryCandidate(bestKey, bestScore);
    }

    private long estimatedResidentBytes() {
        return Math.max(0L, residentBytes.get());
    }

    public Snapshot snapshot() {
        int pinned = 0;
        for (Map.Entry<String, Entry> mapEntry : entries.entrySet()) {
            if (isPinned(mapEntry.getKey())) pinned++;
        }
        return new Snapshot(entries.size(), pinned, estimatedResidentBytes(),
                MapMemoryBudgetPolicy.residentContentBudgetBytes(),
                globalEvictions.get(), budgetFailures.get());
    }

    private static final class Entry {
        private volatile Kind kind = Kind.LEGACY;
        private volatile long estimatedBytes;
        private volatile long createdNanos;
        private volatile long lastResidentNanos;
        private volatile long lastTouchNanos;
        private volatile long lastLaneNanos;
        private volatile long blockedUntilNanos;
        private volatile int strongestLaneRank;
        private volatile EvictionHandler evictionHandler;
    }

    private record EntryCandidate(String key, long score) {
    }

    public record Snapshot(int residentEntries, int pinnedEntries,
            long estimatedBytes, long budgetBytes, long globalEvictions,
            long budgetFailures) {
    }
}
