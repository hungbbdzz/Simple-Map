package com.velorise.simplemap.client.lod;

import com.velorise.simplemap.client.pipeline.RevisionStamp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable region-centric LOD authority introduced for M4.
 *
 * <p>Level 0 represents one 512x512 surface region and owns 64 exact-leaf
 * versions. Every higher level groups 8x8 direct children. Queue entries are
 * disposable hints: dirty child masks, version sums and publication state remain
 * in this graph and can reconstruct work after queue reset or admission denial.</p>
 */
public final class RegionLodGraph {
    public static final int CHILDREN_PER_AXIS = 8;
    public static final int CHILD_COUNT = CHILDREN_PER_AXIS * CHILDREN_PER_AXIS;
    public static final long ALL_CHILDREN = -1L;
    public static final int DEFAULT_MAX_LEVEL = 3;

    public enum State {
        CLEAN,
        DIRTY,
        RUNNING,
        PREPARED,
        PUBLISHED,
        CANCELLED
    }

    public record NodeKey(long sessionId, int projectionId, int level,
            int nodeX, int nodeZ) {
        public NodeKey {
            if (sessionId <= 0L) throw new IllegalArgumentException("sessionId");
            if (level < 0) throw new IllegalArgumentException("level");
        }
    }

    public record Lease(NodeKey key, RevisionStamp stamp, long revision,
            long dirtyChildMask, long knownMask, long completeMask,
            long[] childVersionSums) {
        public Lease {
            childVersionSums = Arrays.copyOf(childVersionSums,
                    childVersionSums.length);
        }
        @Override public long[] childVersionSums() {
            return Arrays.copyOf(childVersionSums, childVersionSums.length);
        }
    }

    public record NodeSnapshot(NodeKey key, RevisionStamp stamp,
            long targetRevision, long runningRevision,
            long preparedRevision, long publishedRevision,
            long aggregateVersionSum, long dirtyChildMask,
            long knownMask, long completeMask, boolean gpuResident,
            State state, long[] childVersionSums) {
        public NodeSnapshot {
            childVersionSums = Arrays.copyOf(childVersionSums,
                    childVersionSums.length);
        }
        @Override public long[] childVersionSums() {
            return Arrays.copyOf(childVersionSums, childVersionSums.length);
        }
    }


    public record Summary(int nodes, int dirty, int running, int prepared,
            int published, int resident, int level0Nodes, int coarseNodes) {
        public static Summary empty() {
            return new Summary(0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private final int maxLevel;
    private final Map<NodeKey, NodeRecord> nodes = new HashMap<>();

    public RegionLodGraph() {
        this(DEFAULT_MAX_LEVEL);
    }

    public RegionLodGraph(int maxLevel) {
        this.maxLevel = Math.max(0, maxLevel);
    }

    public synchronized void updateLeaf(RevisionStamp stamp,
            int projectionId, int regionX, int regionZ, int leafIndex,
            long leafVersion, boolean known, boolean complete,
            boolean gpuResident) {
        if (stamp == null || leafIndex < 0 || leafIndex >= CHILD_COUNT) return;
        NodeKey key = new NodeKey(stamp.sessionId(), projectionId, 0,
                regionX, regionZ);
        NodeRecord node = node(key, stamp);
        long normalized = Math.max(1L, leafVersion);
        long bit = 1L << leafIndex;
        long previous = node.childVersionSums[leafIndex];
        // A late exact callback must never roll a region version backwards.
        // Stale output may still be rendered by its exact owner, but it cannot
        // become the durable M4 hierarchy authority.
        if (normalized < previous) return;
        node.childVersionSums[leafIndex] = normalized;
        node.aggregateVersionSum = safeAdd(node.aggregateVersionSum,
                normalized - previous);
        node.knownMask = setBit(node.knownMask, bit, known);
        node.completeMask = setBit(node.completeMask, bit, complete);
        node.gpuChildMask = setBit(node.gpuChildMask, bit, gpuResident);
        node.dirtyChildMask |= bit;
        node.targetRevision = revisionFor(node);
        markDirty(node);
        propagate(node);
    }


    /**
     * Seeds or refreshes one level-0 region directly from the source database.
     * All 64 leaf slots share the region source watermark until exact leaf
     * revisions arrive and refine them independently. This allows coarse-first
     * work to exist before any exact texture has been built.
     */
    public synchronized NodeKey requestRegion(RevisionStamp stamp,
            int projectionId, int regionX, int regionZ,
            long sourceWatermark) {
        if (stamp == null) return null;
        NodeKey key = new NodeKey(stamp.sessionId(), projectionId, 0,
                regionX, regionZ);
        NodeRecord node = node(key, stamp);
        long normalized = Math.max(1L, sourceWatermark);
        boolean changed = false;
        for (int child = 0; child < CHILD_COUNT; child++) {
            if (node.childVersionSums[child] >= normalized) continue;
            node.childVersionSums[child] = normalized;
            node.dirtyChildMask |= 1L << child;
            changed = true;
        }
        if (changed || node.publishedRevision == 0L) {
            recomputeAggregate(node);
            if (node.dirtyChildMask == 0L) node.dirtyChildMask = ALL_CHILDREN;
            node.targetRevision = revisionFor(node);
            markDirty(node);
            propagate(node);
        }
        return key;
    }

    /** Returns a running lease to durable DIRTY state after admission/source wait. */
    public synchronized void defer(Lease lease) {
        if (lease == null) return;
        NodeRecord node = nodes.get(lease.key());
        if (node == null || node.cancelled) return;
        if (node.runningRevision == lease.revision()) node.runningRevision = 0L;
        node.dirtyChildMask |= lease.dirtyChildMask();
        node.state = State.DIRTY;
    }

    public int maxLevel() {
        return maxLevel;
    }

    public synchronized void markLeafDirty(RevisionStamp stamp,
            int projectionId, int regionX, int regionZ, int leafIndex,
            long targetVersion) {
        if (stamp == null || leafIndex < 0 || leafIndex >= CHILD_COUNT) return;
        NodeRecord node = node(new NodeKey(stamp.sessionId(), projectionId, 0,
                regionX, regionZ), stamp);
        long bit = 1L << leafIndex;
        node.childVersionSums[leafIndex] = Math.max(
                node.childVersionSums[leafIndex], Math.max(1L, targetVersion));
        recomputeAggregate(node);
        node.dirtyChildMask |= bit;
        node.targetRevision = revisionFor(node);
        markDirty(node);
        propagate(node);
    }

    public synchronized void markExactLeafEvicted(long sessionId,
            int projectionId, int regionX, int regionZ, int leafIndex) {
        NodeRecord node = nodes.get(new NodeKey(sessionId, projectionId, 0,
                regionX, regionZ));
        if (node == null || leafIndex < 0 || leafIndex >= CHILD_COUNT) return;
        node.gpuChildMask &= ~(1L << leafIndex);
    }

    public synchronized Lease tryBegin(NodeKey key) {
        NodeRecord node = nodes.get(key);
        if (node == null || node.cancelled || node.state != State.DIRTY
                || node.dirtyChildMask == 0L) return null;
        node.state = State.RUNNING;
        node.runningRevision = node.targetRevision;
        return new Lease(key, node.stamp, node.runningRevision,
                node.dirtyChildMask, node.knownMask, node.completeMask,
                node.childVersionSums);
    }

    public synchronized List<Lease> claimCoarseFirst(long sessionId,
            int projectionId, int limit) {
        if (limit <= 0) return List.of();
        List<NodeRecord> candidates = new ArrayList<>();
        for (NodeRecord node : nodes.values()) {
            if (node.key.sessionId() != sessionId
                    || node.key.projectionId() != projectionId
                    || node.cancelled || node.state != State.DIRTY
                    || node.dirtyChildMask == 0L) continue;
            candidates.add(node);
        }
        candidates.sort(Comparator
                .comparing((NodeRecord node) -> node.gpuResident)
                .thenComparing((NodeRecord node) -> -node.key.level())
                .thenComparingLong(node -> node.publishedRevision)
                .thenComparingInt(node -> node.key.nodeZ())
                .thenComparingInt(node -> node.key.nodeX()));
        List<Lease> leases = new ArrayList<>(Math.min(limit, candidates.size()));
        for (NodeRecord node : candidates) {
            if (leases.size() >= limit) break;
            Lease lease = tryBegin(node.key);
            if (lease != null) leases.add(lease);
        }
        return List.copyOf(leases);
    }

    public synchronized boolean markPrepared(Lease lease,
            PreparedBranch prepared) {
        if (!matches(lease, prepared)) return false;
        NodeRecord node = nodes.get(lease.key());
        if (node == null || node.cancelled) return false;
        node.runningRevision = 0L;
        node.preparedRevision = Math.max(node.preparedRevision,
                prepared.revision());
        boolean current = lease.revision() == node.targetRevision
                && Arrays.equals(lease.childVersionSums(),
                        node.childVersionSums);
        if (current) {
            node.knownMask = prepared.knownMask();
            node.completeMask = prepared.completeMask();
            node.dirtyChildMask &= ~lease.dirtyChildMask();
        }
        node.state = node.dirtyChildMask == 0L
                && node.preparedRevision >= node.targetRevision
                ? State.PREPARED : State.DIRTY;
        if (current) propagate(node);
        return true;
    }

    public synchronized boolean markPublished(PreparedBranch prepared) {
        if (prepared == null) return false;
        NodeRecord node = nodes.get(prepared.key());
        if (node == null || node.cancelled
                || !sameStamp(node.stamp, prepared.stamp())) return false;
        if (prepared.revision() < node.targetRevision
                || !Arrays.equals(prepared.childVersionSums(),
                        node.childVersionSums)) return false;
        node.preparedRevision = Math.max(node.preparedRevision,
                prepared.revision());
        node.publishedRevision = Math.max(node.publishedRevision,
                prepared.revision());
        node.knownMask = prepared.knownMask();
        node.completeMask = prepared.completeMask();
        node.gpuResident = prepared.knownMask() != 0L;
        node.dirtyChildMask = 0L;
        node.state = State.PUBLISHED;
        propagate(node);
        return true;
    }

    public synchronized void markBranchEvicted(NodeKey key, long revision) {
        NodeRecord node = nodes.get(key);
        if (node == null || node.cancelled) return;
        if (revision >= node.publishedRevision) node.gpuResident = false;
        node.state = node.preparedRevision >= node.targetRevision
                ? State.PREPARED : State.DIRTY;
    }

    public synchronized boolean hasPublishedReplacement(long sessionId,
            int projectionId, int regionX, int regionZ, long minimumRevision) {
        int childX = regionX;
        int childZ = regionZ;
        for (int level = 0; level <= maxLevel; level++) {
            NodeKey key = new NodeKey(sessionId, projectionId, level,
                    childX, childZ);
            NodeRecord node = nodes.get(key);
            if (node != null && node.gpuResident
                    && node.publishedRevision >= minimumRevision
                    && node.knownMask != 0L) return true;
            childX = Math.floorDiv(childX, CHILDREN_PER_AXIS);
            childZ = Math.floorDiv(childZ, CHILDREN_PER_AXIS);
        }
        return false;
    }

    public synchronized Summary summary(long sessionId, int projectionId) {
        int dirty = 0;
        int running = 0;
        int prepared = 0;
        int published = 0;
        int resident = 0;
        int level0 = 0;
        int coarse = 0;
        int count = 0;
        for (NodeRecord node : nodes.values()) {
            if (node.key.sessionId() != sessionId
                    || node.key.projectionId() != projectionId) continue;
            count++;
            if (node.key.level() == 0) level0++;
            else coarse++;
            if (node.state == State.DIRTY) dirty++;
            else if (node.state == State.RUNNING) running++;
            else if (node.state == State.PREPARED) prepared++;
            else if (node.state == State.PUBLISHED) published++;
            if (node.gpuResident) resident++;
        }
        return new Summary(count, dirty, running, prepared, published,
                resident, level0, coarse);
    }

    public synchronized void clear() {
        nodes.clear();
    }

    public synchronized NodeSnapshot snapshot(NodeKey key) {
        NodeRecord node = nodes.get(key);
        return node == null ? null : snapshot(node);
    }

    public synchronized List<NodeSnapshot> snapshots(long sessionId,
            int projectionId) {
        List<NodeSnapshot> result = new ArrayList<>();
        for (NodeRecord node : nodes.values()) {
            if (node.key.sessionId() == sessionId
                    && node.key.projectionId() == projectionId) {
                result.add(snapshot(node));
            }
        }
        result.sort(Comparator.comparingInt((NodeSnapshot node) -> node.key().level())
                .thenComparingInt(node -> node.key().nodeZ())
                .thenComparingInt(node -> node.key().nodeX()));
        return List.copyOf(result);
    }

    /** Marks every resident node for a newer style/projection tuple dirty. */
    public synchronized int invalidate(RevisionStamp stamp, int projectionId) {
        if (stamp == null) return 0;
        int invalidated = 0;
        for (NodeRecord node : nodes.values()) {
            if (node.key.sessionId() != stamp.sessionId()
                    || node.key.projectionId() != projectionId
                    || node.cancelled || !isNewer(stamp, node.stamp)) continue;
            node.stamp = stamp;
            long affected = node.knownMask != 0L
                    ? node.knownMask : node.dirtyChildMask;
            if (affected == 0L) continue;
            node.dirtyChildMask |= affected;
            node.targetRevision = revisionFor(node);
            markDirty(node);
            invalidated++;
        }
        return invalidated;
    }

    public synchronized void cancelSession(long sessionId) {
        var iterator = nodes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<NodeKey, NodeRecord> entry = iterator.next();
            if (entry.getKey().sessionId() != sessionId) continue;
            entry.getValue().cancelled = true;
            entry.getValue().state = State.CANCELLED;
            iterator.remove();
        }
    }

    private NodeRecord node(NodeKey key, RevisionStamp stamp) {
        NodeRecord existing = nodes.get(key);
        if (existing != null) {
            if (isNewer(stamp, existing.stamp)) existing.stamp = stamp;
            return existing;
        }
        NodeRecord created = new NodeRecord(key, stamp);
        nodes.put(key, created);
        return created;
    }

    private void propagate(NodeRecord child) {
        NodeRecord current = child;
        for (int parentLevel = child.key.level() + 1;
                parentLevel <= maxLevel; parentLevel++) {
            int parentX = Math.floorDiv(current.key.nodeX(), CHILDREN_PER_AXIS);
            int parentZ = Math.floorDiv(current.key.nodeZ(), CHILDREN_PER_AXIS);
            int childIndex = Math.floorMod(current.key.nodeZ(), CHILDREN_PER_AXIS)
                    * CHILDREN_PER_AXIS
                    + Math.floorMod(current.key.nodeX(), CHILDREN_PER_AXIS);
            NodeRecord parent = node(new NodeKey(current.key.sessionId(),
                    current.key.projectionId(), parentLevel, parentX, parentZ),
                    current.stamp);
            long previous = parent.childVersionSums[childIndex];
            long aggregate = current.aggregateVersionSum;
            parent.childVersionSums[childIndex] = aggregate;
            parent.aggregateVersionSum = safeAdd(parent.aggregateVersionSum,
                    aggregate - previous);
            long bit = 1L << childIndex;
            parent.knownMask = setBit(parent.knownMask, bit,
                    current.knownMask != 0L);
            parent.completeMask = setBit(parent.completeMask, bit,
                    current.completeMask == ALL_CHILDREN);
            parent.dirtyChildMask |= bit;
            parent.targetRevision = revisionFor(parent);
            markDirty(parent);
            current = parent;
        }
    }

    private static boolean matches(Lease lease, PreparedBranch prepared) {
        return lease != null && prepared != null
                && lease.key().equals(prepared.key())
                && sameStamp(lease.stamp(), prepared.stamp())
                && lease.revision() == prepared.revision()
                && Arrays.equals(lease.childVersionSums(),
                        prepared.childVersionSums());
    }

    private static boolean sameStamp(RevisionStamp left, RevisionStamp right) {
        return left != null && left.equals(right);
    }

    private static boolean isNewer(RevisionStamp candidate,
            RevisionStamp current) {
        if (current == null) return true;
        boolean nonDecreasing = candidate.sourceGeneration()
                        >= current.sourceGeneration()
                && candidate.styleGeneration() >= current.styleGeneration()
                && candidate.projectionGeneration()
                        >= current.projectionGeneration();
        boolean changed = candidate.sourceGeneration() != current.sourceGeneration()
                || candidate.styleGeneration() != current.styleGeneration()
                || candidate.projectionGeneration()
                        != current.projectionGeneration();
        return nonDecreasing && changed;
    }

    private static long setBit(long mask, long bit, boolean value) {
        return value ? mask | bit : mask & ~bit;
    }

    private static long revisionFor(NodeRecord node) {
        long generation = safeAdd(node.stamp.sourceGeneration(),
                node.stamp.styleGeneration());
        generation = safeAdd(generation,
                node.stamp.projectionGeneration());
        return Math.max(1L, safeAdd(node.aggregateVersionSum, generation));
    }

    private static long safeAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0L && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }

    private static void recomputeAggregate(NodeRecord node) {
        long sum = 0L;
        for (long version : node.childVersionSums) sum = safeAdd(sum, version);
        node.aggregateVersionSum = sum;
    }

    private static void markDirty(NodeRecord node) {
        if (node.state != State.RUNNING) node.state = State.DIRTY;
    }

    private static NodeSnapshot snapshot(NodeRecord node) {
        return new NodeSnapshot(node.key, node.stamp, node.targetRevision,
                node.runningRevision, node.preparedRevision,
                node.publishedRevision, node.aggregateVersionSum,
                node.dirtyChildMask, node.knownMask, node.completeMask,
                node.gpuResident, node.state, node.childVersionSums);
    }

    private static final class NodeRecord {
        private final NodeKey key;
        private RevisionStamp stamp;
        private final long[] childVersionSums = new long[CHILD_COUNT];
        private long targetRevision = 1L;
        private long runningRevision;
        private long preparedRevision;
        private long publishedRevision;
        private long aggregateVersionSum;
        private long dirtyChildMask;
        private long knownMask;
        private long completeMask;
        private long gpuChildMask;
        private boolean gpuResident;
        private boolean cancelled;
        private State state = State.CLEAN;

        private NodeRecord(NodeKey key, RevisionStamp stamp) {
            this.key = key;
            this.stamp = stamp;
        }
    }
}
