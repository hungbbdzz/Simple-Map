package com.velorise.simplemap.client.gpu;


import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph.RevisionStamp;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapDebugRecorder.MapPipelineTelemetry;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceLocation;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Double-buffered logical page table.
 *
 * <p>Writers mutate only the back table. At a frame boundary the two maps are
 * swapped atomically and slots replaced by the new front generation become
 * eligible for retirement. The renderer never observes a half-published entry.</p>
 */
public final class GpuPageTable {

    /** Immutable front-page-table entry consumed by the renderer. */
public static record PageTableEntry(int storageId, int slot, long storageGeneration,
        long contentRevision, int level, int flags,
        float sourceX, float sourceY, int sourceSize, int atlasSize) {
    public static final int FLAG_RESIDENT = 1;
    public static final int FLAG_COMPLETE = 1 << 1;
    public static final int FLAG_PROTECTED = 1 << 2;
    public static final int FLAG_LINEAR = 1 << 3;
    /** Surface exact pages encode their 4x4 complete-subtile coverage here. */
    public static final int COVERAGE_SHIFT = 8;
    public static final int COVERAGE_BITS = 0xFFFF << COVERAGE_SHIFT;

    public PageTableEntry {
        if (storageId < 0) throw new IllegalArgumentException("storageId");
        if (slot < 0) throw new IllegalArgumentException("slot");
        if (sourceSize <= 0 || atlasSize <= 0) {
            throw new IllegalArgumentException("texture dimensions");
        }
    }

    public boolean resident() {
        return (flags & FLAG_RESIDENT) != 0;
    }

    public int coverageMask() {
        return (flags >>> COVERAGE_SHIFT) & 0xFFFF;
    }

    public static int withCoverageMask(int flags, int coverageMask) {
        return (flags & ~COVERAGE_BITS)
                | ((coverageMask & 0xFFFF) << COVERAGE_SHIFT);
    }
}

    /**
 * Stable logical identity for one renderable map tile.
 *
 * <p>The key never contains an atlas slot. GPU storage can therefore move or be
 * recreated without invalidating world geometry. Variant distinguishes exact,
 * glow, branch, minimap and cave projections that share coordinates.</p>
 */
public static record TileKey(long sessionId, int projectionId, int level,
        int tileX, int tileZ, int variant) {
    public static final int VARIANT_SURFACE_EXACT = 1;
    public static final int VARIANT_SURFACE_GLOW = 2;
    public static final int VARIANT_SURFACE_BRANCH = 3;
    public static final int VARIANT_MINIMAP = 4;
    public static final int VARIANT_CAVE_LAYERED = 5;
    public static final int VARIANT_CAVE_FULL = 6;

    public TileKey {
        if (sessionId <= 0L) throw new IllegalArgumentException("sessionId");
        if (level < 0) throw new IllegalArgumentException("level");
    }
}


    public record RetiredSlot(int storageId, int slot, long generation) { }

    private Map<TileKey, PageTableEntry> front = new HashMap<>();
    private Map<TileKey, PageTableEntry> back = new HashMap<>();
    private long frontGeneration;
    private boolean backPrepared;
    /** Keys changed since the last frame boundary. Both maps are kept identical
     * after every swap, so staging only records deltas instead of cloning every
     * resident page-table entry into the back map. */
    private final Set<TileKey> changedKeys = new HashSet<>();

    public synchronized long frontGeneration() {
        return frontGeneration;
    }

    public synchronized PageTableEntry resolve(TileKey key) {
        return key == null ? null : front.get(key);
    }

    public synchronized int frontSize() {
        return front.size();
    }

    /**
     * Render-thread snapshot view. The front map is never mutated between frame
     * boundary swaps; writers touch only {@code back}. Callers must not retain the
     * returned reference across a later swap and must never mutate it.
     */
    synchronized Map<TileKey, PageTableEntry> frontView() {
        return front;
    }

    public synchronized void beginUpdate() {
        if (backPrepared) return;
        // After swapAtFrameBoundary both buffers contain the same entries. The
        // back buffer can therefore accept incremental mutations immediately.
        backPrepared = true;
    }

    public synchronized void stage(TileKey key, PageTableEntry entry) {
        if (key == null || entry == null) return;
        beginUpdate();
        PageTableEntry previous = back.get(key);
        if (previous != null && previous.contentRevision() > entry.contentRevision()) {
            return;
        }
        back.put(key, entry);
        changedKeys.add(key);
    }

    public synchronized void remove(TileKey key) {
        if (key == null) return;
        beginUpdate();
        back.remove(key);
        changedKeys.add(key);
    }

    public synchronized List<RetiredSlot> swapAtFrameBoundary() {
        if (!backPrepared) return List.of();
        List<RetiredSlot> retired = null;
        // Only changed keys can retire a slot. The old implementation scanned the
        // entire front map and rebuilt the entire back HashMap every publication
        // frame, even when one page changed.
        for (TileKey key : changedKeys) {
            PageTableEntry previous = front.get(key);
            if (previous == null) continue;
            PageTableEntry replacement = back.get(key);
            if (replacement == null
                    || replacement.storageId() != previous.storageId()
                    || replacement.slot() != previous.slot()
                    || replacement.storageGeneration()
                            != previous.storageGeneration()) {
                if (retired == null) retired = new ArrayList<>();
                retired.add(new RetiredSlot(previous.storageId(), previous.slot(),
                        previous.storageGeneration()));
            }
        }
        Map<TileKey, PageTableEntry> oldFront = front;
        front = back;
        back = oldFront;

        // Bring the old front (now back) to the new authoritative state using the
        // same small delta. This preserves the invariant required by beginUpdate
        // without allocating one HashMap node per resident tile every frame.
        for (TileKey key : changedKeys) {
            PageTableEntry replacement = front.get(key);
            if (replacement == null) back.remove(key);
            else back.put(key, replacement);
        }
        changedKeys.clear();
        backPrepared = false;
        frontGeneration++;
        return retired == null ? List.of() : retired;
    }

    public synchronized void clear() {
        front.clear();
        back.clear();
        changedKeys.clear();
        backPrepared = false;
        frontGeneration++;
    }

    public synchronized Map<TileKey, PageTableEntry> snapshotFront() {
        return Map.copyOf(front);
    }

    public static final class MapGpuPageTableService {
        public record Resolved(PageTableEntry entry, ResourceLocation texture) { }

        public static final class RenderView {
            private final Map<TileKey, PageTableEntry> entries;
            private final Map<Integer, Storage> storages;
            private final long generation;

            private RenderView(Map<TileKey, PageTableEntry> entries,
                    Map<Integer, Storage> storages, long generation) {
                this.entries = entries;
                this.storages = storages;
                this.generation = generation;
            }

            public long generation() {
                return generation;
            }

            public PageTableEntry entry(TileKey key) {
                PageTableEntry entry = key == null ? null : entries.get(key);
                return entry != null && entry.resident() ? entry : null;
            }

            public ResourceLocation texture(PageTableEntry entry) {
                if (entry == null) return null;
                Storage storage = storages.get(entry.storageId());
                return storage != null
                        && storage.generation == entry.storageGeneration()
                                ? storage.texture : null;
            }
        }

        public record Summary(long generation, int entries, int storages,
                long staged, long swaps, long generationMismatches) { }

        private static final MapGpuPageTableService INSTANCE =
                new MapGpuPageTableService();

        private final GpuPageTable table = new GpuPageTable();
        private final AtomicInteger nextStorageId = new AtomicInteger(1);
        private final Map<ResourceLocation, Integer> storageIds = new HashMap<>();
        private final Map<Integer, Storage> storages = new HashMap<>();
        private long staged;
        private long swaps;
        private long generationMismatches;
        private volatile RenderView renderView = new RenderView(
                Map.of(), Map.of(), 0L);

        private MapGpuPageTableService() { }

        public static MapGpuPageTableService getInstance() {
            return INSTANCE;
        }

        public synchronized int storageId(ResourceLocation texture,
                long storageGeneration) {
            if (texture == null) return -1;
            Integer existing = storageIds.get(texture);
            if (existing != null) {
                Storage storage = storages.get(existing);
                if (storage != null && storage.generation != storageGeneration) {
                    storages.put(existing, new Storage(texture, storageGeneration));
                }
                return existing;
            }
            int id = nextStorageId.getAndIncrement();
            storageIds.put(texture, id);
            storages.put(id, new Storage(texture, storageGeneration));
            return id;
        }

        public synchronized void stage(TileKey key, ResourceLocation texture,
                int slot, long storageGeneration, long contentRevision,
                int flags, float sourceX, float sourceY,
                int sourceSize, int atlasSize) {
            if (key == null || texture == null || slot < 0) return;
            int storageId = storageId(texture, storageGeneration);
            table.stage(key, new PageTableEntry(storageId, slot,
                    storageGeneration, Math.max(1L, contentRevision), key.level(),
                    flags | PageTableEntry.FLAG_RESIDENT,
                    sourceX, sourceY, sourceSize, atlasSize));
            staged++;
        }

        public synchronized void remove(TileKey key) {
            table.remove(key);
        }

        public synchronized Resolved resolve(TileKey key) {
            PageTableEntry entry = table.resolve(key);
            if (entry == null || !entry.resident()) return null;
            Storage storage = storages.get(entry.storageId());
            if (storage == null || storage.generation != entry.storageGeneration()) {
                generationMismatches++;
                return null;
            }
            return new Resolved(entry, storage.texture);
        }

        public RenderView renderView() {
            return renderView;
        }

        public synchronized List<GpuPageTable.RetiredSlot> swapAtFrameBoundary() {
            long before = table.frontGeneration();
            List<GpuPageTable.RetiredSlot> retired = table.swapAtFrameBoundary();
            long generation = table.frontGeneration();
            if (generation != before) {
                swaps++;
                renderView = new RenderView(table.frontView(),
                        Map.copyOf(storages), generation);
            }
            return retired;
        }

        public synchronized Summary summary() {
            return new Summary(table.frontGeneration(), table.frontSize(),
                    storages.size(), staged, swaps, generationMismatches);
        }

        public synchronized void clear() {
            table.clear();
            storageIds.clear();
            storages.clear();
            nextStorageId.set(1);
            renderView = new RenderView(Map.of(), Map.of(),
                    table.frontGeneration());
        }

        private record Storage(ResourceLocation texture, long generation) { }
    }


    public static final class MapGpuInstancePlan {

    /** M5 page-table-backed primitive instance replay. */
public static final class MapGpuInstanceRenderer {
    private static final int PHASE_GLOW = 128;

    private MapGpuInstanceRenderer() { }

    public static void draw(GuiGraphics graphics, MapGpuInstancePlan plan,
            boolean glow) {
        drawFiltered(graphics, plan, glow, null);
    }

    /** Replays exactly one global render phase so page-table instances can be
     * interleaved with raw atlas batches without losing coarse-to-fine order. */
    public static void drawPhase(GuiGraphics graphics, MapGpuInstancePlan plan,
            int targetPhase) {
        if (plan == null || !plan.hasPhase(targetPhase)) return;
        drawFiltered(graphics, plan, targetPhase >= PHASE_GLOW, targetPhase);
    }

    /** Draws one phase inside a state scope owned by MapRenderPlan. */
    public static void drawPhasePrepared(MapGpuInstancePlan plan,
            int targetPhase, Matrix4f matrix) {
        if (plan == null || plan.size() == 0 || matrix == null
                || !plan.hasPhase(targetPhase)) return;
        drawPrepared(plan, targetPhase >= PHASE_GLOW, targetPhase, matrix);
    }

    private static void drawFiltered(GuiGraphics graphics,
            MapGpuInstancePlan plan, boolean glow, Integer targetPhase) {
        if (graphics == null || plan == null || plan.size() == 0) return;
        graphics.flush();
        boolean depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        RenderSystem.disableDepthTest();
        try {
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            Matrix4f matrix = graphics.pose().last().pose();
            drawPrepared(plan, glow, targetPhase == null ? -1 : targetPhase,
                    matrix);
        } finally {
            if (depthWasEnabled) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
        }
    }

    private static void drawPrepared(MapGpuInstancePlan plan, boolean glow,
            int targetPhase, Matrix4f matrix) {
        int start;
        int end;
        if (targetPhase >= 0) {
            start = plan.firstIndexAtOrAfter(targetPhase);
            end = plan.firstIndexAfter(targetPhase);
        } else if (glow) {
            start = plan.firstIndexAtOrAfter(PHASE_GLOW);
            end = plan.size();
        } else {
            start = 0;
            end = plan.firstIndexAtOrAfter(PHASE_GLOW);
        }
        if (start >= end) return;

        ResourceLocation activeTexture = null;
        BufferBuilder buffer = null;
        int submissions = 0;
        GpuPageTable.MapGpuPageTableService.RenderView pageTable =
                GpuPageTable.MapGpuPageTableService.getInstance().renderView();
        for (int index = start; index < end; index++) {
            PageTableEntry entry = pageTable.entry(plan.key(index));
            ResourceLocation texture = pageTable.texture(entry);
            // A keyed instance is authoritative only while its page-table entry is
            // resident. Replaying cached fallback UVs after eviction can sample a
            // slot that has already been reused by another page. Skip it and let
            // the coverage revision rebuild the plan instead.
            if (entry == null || texture == null || entry.atlasSize() <= 0) continue;
            int requiredCoverage = plan.requiredCoverageMask(index);
            int coverage = entry.coverageMask();
            boolean dynamicSurfaceCoverage =
                    requiredCoverage == MapGpuInstancePlan.DYNAMIC_SURFACE_SUBTILES;
            if (!dynamicSurfaceCoverage && requiredCoverage != 0
                    && (coverage & requiredCoverage) != requiredCoverage) continue;
            if (dynamicSurfaceCoverage && coverage == 0) continue;

            if (!texture.equals(activeTexture)) {
                if (buffer != null) {
                    BufferUploader.drawWithShader(buffer.buildOrThrow());
                    submissions++;
                }
                activeTexture = texture;
                RenderSystem.setShaderTexture(0, texture);
                buffer = Tesselator.getInstance().begin(
                        VertexFormat.Mode.QUADS,
                        DefaultVertexFormat.POSITION_TEX);
            }

            float inverse = 1.0f / entry.atlasSize();
            float sourceSize = entry.sourceSize();
            float localU0 = plan.localU0(index);
            float localV0 = plan.localV0(index);
            float localU1 = plan.localU1(index);
            float localV1 = plan.localV1(index);
            float x0 = plan.x(index);
            float y0 = plan.y(index);
            float width = plan.width(index);
            float height = plan.height(index);

            if (dynamicSurfaceCoverage && coverage != 0xFFFF) {
                /*
                 * Partial Surface page: split only at replay time. This keeps the
                 * immutable plan one-entry-per-page while preserving the rule that
                 * zero-filled unknown cells never cover the retained root underlay.
                 */
                float uvWidth = localU1 - localU0;
                float uvHeight = localV1 - localV0;
                float cellWidth = width * 0.25f;
                float cellHeight = height * 0.25f;
                for (int cellZ = 0; cellZ < 4; cellZ++) {
                    for (int cellX = 0; cellX < 4; cellX++) {
                        int bit = 1 << (cellZ * 4 + cellX);
                        if ((coverage & bit) == 0) continue;
                        float cellLocalU0 = localU0 + uvWidth * cellX * 0.25f;
                        float cellLocalV0 = localV0 + uvHeight * cellZ * 0.25f;
                        float cellLocalU1 = cellLocalU0 + uvWidth * 0.25f;
                        float cellLocalV1 = cellLocalV0 + uvHeight * 0.25f;
                        float u0 = (entry.sourceX()
                                + cellLocalU0 * sourceSize) * inverse;
                        float v0 = (entry.sourceY()
                                + cellLocalV0 * sourceSize) * inverse;
                        float u1 = (entry.sourceX()
                                + cellLocalU1 * sourceSize) * inverse;
                        float v1 = (entry.sourceY()
                                + cellLocalV1 * sourceSize) * inverse;
                        float cellX0 = x0 + cellX * cellWidth;
                        float cellY0 = y0 + cellZ * cellHeight;
                        float cellX1 = cellX0 + cellWidth;
                        float cellY1 = cellY0 + cellHeight;
                        addQuad(buffer, matrix, cellX0, cellY0, cellX1, cellY1,
                                u0, v0, u1, v1);
                    }
                }
                continue;
            }

            float u0 = (entry.sourceX() + localU0 * sourceSize) * inverse;
            float v0 = (entry.sourceY() + localV0 * sourceSize) * inverse;
            float u1 = (entry.sourceX() + localU1 * sourceSize) * inverse;
            float v1 = (entry.sourceY() + localV1 * sourceSize) * inverse;
            addQuad(buffer, matrix, x0, y0, x0 + width, y0 + height,
                    u0, v0, u1, v1);
        }
        if (buffer != null) {
            BufferUploader.drawWithShader(buffer.buildOrThrow());
            submissions++;
        }
        MapPipelineTelemetry.getInstance().recordRawBatchSubmissions(submissions);
    }

    private static void addQuad(BufferBuilder buffer, Matrix4f matrix,
            float x0, float y0, float x1, float y1,
            float u0, float v0, float u1, float v1) {
        buffer.addVertex(matrix, x0, y0, 0.0f).setUv(u0, v0);
        buffer.addVertex(matrix, x0, y1, 0.0f).setUv(u0, v1);
        buffer.addVertex(matrix, x1, y1, 0.0f).setUv(u1, v1);
        buffer.addVertex(matrix, x1, y0, 0.0f).setUv(u1, v0);
    }

}


    /**
     * Resolve the current 4x4 Surface completion mask at replay time. A complete
     * page emits one quad; a partial page emits only its resident 16x16 cells.
     */
    public static final int DYNAMIC_SURFACE_SUBTILES = -1;

    private final TileKey[] keys;
    private final int[] phases;
    private final float[] rects;
    /** Local UV rectangle inside the logical page-table entry. */
    private final float[] localUvs;
    /**
     * Required complete-subtile bits. Zero means no predicate; -1 means dynamic
     * Surface 4x4 coverage resolved by the replay renderer.
     */
    private final int[] requiredCoverageMasks;
    private final int size;

    private MapGpuInstancePlan(TileKey[] keys, int[] phases,
            float[] rects, float[] localUvs, int[] requiredCoverageMasks,
            int size) {
        this.keys = keys;
        this.phases = phases;
        this.rects = rects;
        this.localUvs = localUvs;
        this.requiredCoverageMasks = requiredCoverageMasks;
        this.size = size;
    }

    public int size() { return size; }
    public TileKey key(int index) { return keys[index]; }
    public int phase(int index) { return phases[index]; }

    public boolean hasPhase(int phase) {
        int start = firstIndexAtOrAfter(phase);
        return start < size && phases[start] == phase;
    }

    /** First sorted instance whose phase is greater than or equal to target. */
    public int firstIndexAtOrAfter(int targetPhase) {
        int low = 0;
        int high = size;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (phases[middle] < targetPhase) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    /** Exclusive end of one sorted phase range. */
    public int firstIndexAfter(int targetPhase) {
        int low = 0;
        int high = size;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (phases[middle] <= targetPhase) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    public float x(int index) { return rects[index * 4]; }
    public float y(int index) { return rects[index * 4 + 1]; }
    public float width(int index) { return rects[index * 4 + 2]; }
    public float height(int index) { return rects[index * 4 + 3]; }
    public float localU0(int index) { return localUvs[index * 4]; }
    public float localV0(int index) { return localUvs[index * 4 + 1]; }
    public float localU1(int index) { return localUvs[index * 4 + 2]; }
    public float localV1(int index) { return localUvs[index * 4 + 3]; }
    public int requiredCoverageMask(int index) {
        return requiredCoverageMasks[index];
    }

    public static final class Builder {
        private TileKey[] keys = new TileKey[128];
        private int[] phases = new int[128];
        private float[] rects = new float[512];
        private float[] localUvs = new float[512];
        private int[] requiredCoverageMasks = new int[128];
        private int size;

        public boolean add(TileKey key, int phase,
                int x, int y, int width, int height) {
            return add(key, phase, x, y, width, height,
                    0.0f, 0.0f, 1.0f, 1.0f, 0);
        }

        public boolean add(TileKey key, int phase,
                int x, int y, int width, int height,
                float localU0, float localV0, float localU1, float localV1,
                int requiredCoverageMask) {
            if (key == null || width <= 0 || height <= 0
                    || localU0 < 0.0f || localV0 < 0.0f
                    || localU1 > 1.0f || localV1 > 1.0f
                    || localU1 <= localU0 || localV1 <= localV0) return false;
            ensure(size + 1);
            keys[size] = key;
            phases[size] = phase;
            int offset = size * 4;
            rects[offset] = x;
            rects[offset + 1] = y;
            rects[offset + 2] = width;
            rects[offset + 3] = height;
            localUvs[offset] = localU0;
            localUvs[offset + 1] = localV0;
            localUvs[offset + 2] = localU1;
            localUvs[offset + 3] = localV1;
            requiredCoverageMasks[size] = requiredCoverageMask;
            size++;
            return true;
        }

        public MapGpuInstancePlan build() {
            if (size == 0) {
                return new MapGpuInstancePlan(new TileKey[0], new int[0],
                        new float[0], new float[0], new int[0], 0);
            }
            // Primitive stable phase order. Actual texture grouping happens after
            // page-table resolution, so sorting by a stale fallback texture only
            // increased retained memory and could not guarantee fewer submissions.
            int[] order = new int[size];
            for (int i = 0; i < size; i++) order[i] = i;
            sortOrder(order, 0, size - 1);
            TileKey[] outKeys = new TileKey[size];
            int[] outPhases = new int[size];
            float[] outRects = new float[size * 4];
            float[] outLocalUvs = new float[size * 4];
            int[] outRequiredCoverageMasks = new int[size];
            for (int target = 0; target < size; target++) {
                int source = order[target];
                outKeys[target] = keys[source];
                outPhases[target] = phases[source];
                System.arraycopy(rects, source * 4, outRects, target * 4, 4);
                System.arraycopy(localUvs, source * 4, outLocalUvs, target * 4, 4);
                outRequiredCoverageMasks[target] = requiredCoverageMasks[source];
            }
            return new MapGpuInstancePlan(outKeys, outPhases, outRects,
                    outLocalUvs, outRequiredCoverageMasks, size);
        }

        private void sortOrder(int[] order, int low, int high) {
            while (low < high) {
                if (high - low < 16) {
                    insertionSort(order, low, high);
                    return;
                }
                int left = low;
                int right = high;
                int pivot = order[(low + high) >>> 1];
                while (left <= right) {
                    while (compare(order[left], pivot) < 0) left++;
                    while (compare(order[right], pivot) > 0) right--;
                    if (left <= right) {
                        int swap = order[left];
                        order[left++] = order[right];
                        order[right--] = swap;
                    }
                }
                if (right - low < high - left) {
                    if (low < right) sortOrder(order, low, right);
                    low = left;
                } else {
                    if (left < high) sortOrder(order, left, high);
                    high = right;
                }
            }
        }

        private void insertionSort(int[] order, int low, int high) {
            for (int index = low + 1; index <= high; index++) {
                int value = order[index];
                int cursor = index - 1;
                while (cursor >= low && compare(order[cursor], value) > 0) {
                    order[cursor + 1] = order[cursor--];
                }
                order[cursor + 1] = value;
            }
        }

        private int compare(int left, int right) {
            int phase = Integer.compare(phases[left], phases[right]);
            return phase != 0 ? phase : Integer.compare(left, right);
        }

        private void ensure(int capacity) {
            if (capacity <= keys.length) return;
            int next = Math.max(capacity, keys.length * 2);
            keys = Arrays.copyOf(keys, next);
            phases = Arrays.copyOf(phases, next);
            rects = Arrays.copyOf(rects, next * 4);
            localUvs = Arrays.copyOf(localUvs, next * 4);
            requiredCoverageMasks = Arrays.copyOf(requiredCoverageMasks, next);
        }
    }
}


    public static final class MapUploadEngine {

    /** One shared upload-engine transaction. */
public static record UploadCommand(TileKey key, RevisionStamp stamp,
        MapRequestLane lane, int byteCount, long contentRevision,
        UploadBufferLease payload, Runnable uploadAction,
        Runnable committed, Runnable rejected) {
    public UploadCommand {
        if (lane == null) lane = MapRequestLane.BACKGROUND;
        if (byteCount < 0) throw new IllegalArgumentException("byteCount");
        if (uploadAction == null) throw new IllegalArgumentException("uploadAction");
    }

    public boolean current() {
        return stamp == null || stamp.isCurrent();
    }
}


    public record Summary(int queued, int inFlight, long queuedBytes,
            long stagingBytes, long submitted, long committed, long rejected,
            long stale, long oversized) { }

    private static final int[] SIZE_CLASSES = {
            16 * 1024, 64 * 1024, 256 * 1024, 1024 * 1024, 4 * 1024 * 1024
    };
    private static final int MAX_COMMANDS = 2048;
    private static final long MAX_QUEUED_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_BUFFERS_PER_CLASS = 8;
    private static final MapUploadEngine INSTANCE = new MapUploadEngine();

    private final EnumMap<MapRequestLane, ArrayDeque<UploadCommand>> queues =
            new EnumMap<>(MapRequestLane.class);
    private final Map<Integer, ArrayDeque<ByteBuffer>> pools =
            new java.util.HashMap<>();
    private final AtomicLong stagingBytes = new AtomicLong();
    private long queuedBytes;
    private int queued;
    private int inFlight;
    private long submitted;
    private long committed;
    private long rejected;
    private long stale;
    private long oversized;

    private MapUploadEngine() {
        for (MapRequestLane lane : MapRequestLane.values()) {
            queues.put(lane, new ArrayDeque<>());
        }
        for (int size : SIZE_CLASSES) pools.put(size, new ArrayDeque<>());
    }

    public static MapUploadEngine getInstance() { return INSTANCE; }

    public synchronized UploadBufferLease acquire(int bytes) {
        int sizeClass = sizeClass(bytes);
        ArrayDeque<ByteBuffer> pool = pools.get(sizeClass);
        ByteBuffer buffer = pool == null ? null : pool.pollFirst();
        if (buffer == null) {
            buffer = ByteBuffer.allocateDirect(sizeClass);
            stagingBytes.addAndGet(sizeClass);
        }
        return new UploadBufferLease(this, buffer, sizeClass);
    }

    synchronized void release(UploadBufferLease lease) {
        if (lease == null) return;
        ArrayDeque<ByteBuffer> pool = pools.get(lease.sizeClass());
        if (pool != null && pool.size() < MAX_BUFFERS_PER_CLASS) {
            lease.raw().clear();
            pool.addLast(lease.raw());
        } else {
            stagingBytes.addAndGet(-lease.capacity());
        }
    }

    public synchronized boolean submit(UploadCommand command) {
        if (command == null) return false;
        if (queued >= MAX_COMMANDS
                || queuedBytes + command.byteCount() > MAX_QUEUED_BYTES) {
            rejected++;
            reject(command);
            return false;
        }
        queues.get(command.lane()).addLast(command);
        queued++;
        queuedBytes += command.byteCount();
        submitted++;
        if (command.byteCount() > 4 * 1024 * 1024) oversized++;
        return true;
    }

    /** Executes bounded commands on the render thread until the deadline. */
    public int drain(long deadlineNanos, int byteBudget) {
        int completedNow = 0;
        int remainingBytes = Math.max(0, byteBudget);
        while (System.nanoTime() < deadlineNanos) {
            UploadCommand command;
            synchronized (this) {
                command = pollNext();
                if (command == null) break;
                if (completedNow > 0 && command.byteCount() > remainingBytes) {
                    queues.get(command.lane()).addFirst(command);
                    queued++;
                    queuedBytes += command.byteCount();
                    break;
                }
                inFlight++;
            }
            try {
                if (!command.current()) {
                    synchronized (this) { stale++; }
                    reject(command);
                    continue;
                }
                command.uploadAction().run();
                if (command.committed() != null) command.committed().run();
                synchronized (this) { committed++; }
                completedNow++;
                remainingBytes = Math.max(0, remainingBytes - command.byteCount());
            } catch (RuntimeException exception) {
                synchronized (this) { rejected++; }
                reject(command);
            } finally {
                if (command.payload() != null) command.payload().close();
                synchronized (this) { inFlight--; }
            }
        }
        return completedNow;
    }

    /** Compatibility bridge used while old atlas owners are migrated. */
    public void executeInline(UploadCommand command) {
        if (command == null) return;
        synchronized (this) {
            submitted++;
            inFlight++;
        }
        try {
            if (!command.current()) {
                synchronized (this) { stale++; }
                reject(command);
                return;
            }
            command.uploadAction().run();
            if (command.committed() != null) command.committed().run();
            synchronized (this) { committed++; }
        } catch (RuntimeException exception) {
            synchronized (this) { rejected++; }
            reject(command);
            throw exception;
        } finally {
            if (command.payload() != null) command.payload().close();
            synchronized (this) { inFlight--; }
        }
    }

    public synchronized Summary summary() {
        return new Summary(queued, inFlight, queuedBytes, stagingBytes.get(),
                submitted, committed, rejected, stale, oversized);
    }

    public synchronized void clear() {
        for (ArrayDeque<UploadCommand> queue : queues.values()) {
            UploadCommand command;
            while ((command = queue.pollFirst()) != null) reject(command);
        }
        queued = 0;
        queuedBytes = 0L;
    }

    private UploadCommand pollNext() {
        MapRequestLane[] order = {
                MapRequestLane.MINIMAP, MapRequestLane.FULLSCREEN,
                MapRequestLane.BACKGROUND, MapRequestLane.PREFETCH
        };
        for (MapRequestLane lane : order) {
            UploadCommand command = queues.get(lane).pollFirst();
            if (command == null) continue;
            queued--;
            queuedBytes -= command.byteCount();
            return command;
        }
        return null;
    }

    private void reject(UploadCommand command) {
        if (command.rejected() != null) command.rejected().run();
        if (command.payload() != null) command.payload().close();
    }

    private static int sizeClass(int bytes) {
        int requested = Math.max(1, bytes);
        for (int size : SIZE_CLASSES) if (requested <= size) return size;
        return Integer.highestOneBit(requested - 1) << 1;
    }

    public static final class UploadBufferLease implements AutoCloseable {
        private final MapUploadEngine owner;
        private final ByteBuffer buffer;
        private final int sizeClass;
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

        UploadBufferLease(MapUploadEngine owner, ByteBuffer buffer, int sizeClass) {
            this.owner = owner;
            this.buffer = buffer.order(java.nio.ByteOrder.nativeOrder());
            this.sizeClass = sizeClass;
        }

        public ByteBuffer buffer() {
            if (closed.get()) throw new IllegalStateException("closed upload lease");
            buffer.clear();
            return buffer;
        }

        public int capacity() { return buffer.capacity(); }
        int sizeClass() { return sizeClass; }
        ByteBuffer raw() { return buffer; }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) owner.release(this);
        }
    }
}

}
