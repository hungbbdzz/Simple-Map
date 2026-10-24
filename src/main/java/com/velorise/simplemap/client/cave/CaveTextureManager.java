package com.velorise.simplemap.client.cave;
import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph.RevisionStamp;
import com.velorise.simplemap.client.MapWorkScheduler.MapProcessor;
import com.velorise.simplemap.client.MapViewportCoordinator.MapViewLoadPlanner;
import com.velorise.simplemap.client.cave.CaveMode.CaveType;
import com.velorise.simplemap.client.color.SurfaceColorizer;
import com.velorise.simplemap.client.color.SurfaceColorizer.BlockTintPolicy;
import com.velorise.simplemap.client.color.SurfaceColorizer.SurfaceTintData;
import com.velorise.simplemap.client.session.MapSessionManager;
import com.velorise.simplemap.client.session.MapSessionManager.MapSession;
import net.minecraft.world.level.ChunkPos;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.velorise.simplemap.client.MapManager.DimensionMapProfile;
import com.velorise.simplemap.client.MapManager.MapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.PushReaction;
import java.util.HashMap;

import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph;
import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph.MapWorkKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.RandomAccessFile;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.world.level.material.MapColor;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.locks.ReentrantLock;
import com.velorise.simplemap.client.gpu.GpuPageTable.TileKey;

import com.velorise.simplemap.client.*;
import com.velorise.simplemap.client.color.*;
import com.velorise.simplemap.client.gui.*;
import com.velorise.simplemap.client.lod.*;
import com.velorise.simplemap.client.renderer.*;
import com.velorise.simplemap.client.waypoint.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.velorise.simplemap.client.cave.CaveTextureAtlas.CaveAtlasRegion;
import com.velorise.simplemap.client.cave.CaveMode.CaveLayerBand;
import com.velorise.simplemap.client.cave.CaveTextureAtlas;
import com.velorise.simplemap.client.cave.CaveMode.CaveLayerBand.CaveView;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Direct GPU page atlas texture manager for layered and full cave maps.
 * Manages 64x64 pixel pages copied from CaveRegion and uploaded to CaveTextureAtlas
 * under a strict per-frame time budget without blocking render plan construction.
 */
public final class CaveTextureManager {
    private static final CaveTextureManager INSTANCE = new CaveTextureManager();

    private final CaveTextureAtlas atlas = new CaveTextureAtlas();
    private final Map<PageKey, PageEntry> pages = new LinkedHashMap<>(256, 0.75f, true);
    private final Map<BranchKey, BranchEntry> branchPages = new LinkedHashMap<>(128, 0.75f, true);
    private final Set<PageKey> dirtyPageKeys = new LinkedHashSet<>();
    private final Set<BranchKey> dirtyBranchKeys = new LinkedHashSet<>();
    private final AtomicLong contentRevision = new AtomicLong(1L);

    private volatile int activeLayerY = Integer.MIN_VALUE;
    private volatile int previousLayerY = Integer.MIN_VALUE;

    private volatile int focusPageX = 0;
    private volatile int focusPageZ = 0;
    private volatile int minVisiblePageX = Integer.MIN_VALUE;
    private volatile int maxVisiblePageX = Integer.MAX_VALUE;
    private volatile int minVisiblePageZ = Integer.MIN_VALUE;
    private volatile int maxVisiblePageZ = Integer.MAX_VALUE;
    private volatile float preferredCaveScale = 1.0f;

    private final int[][] uploadBuffers = new int[CaveTextureAtlas.LOD_COUNT][];

    private static final long NORMAL_UPLOAD_BUDGET_NANOS = 900_000L;
    private static final long FORCE_UPLOAD_BUDGET_NANOS = 1_800_000L;

    public static CaveTextureManager getInstance() {
        return INSTANCE;
    }

    public void setPreferredCaveView(int minPageX, int maxPageX,
            int minPageZ, int maxPageZ, int focusPageX, int focusPageZ) {
        this.minVisiblePageX = minPageX;
        this.maxVisiblePageX = maxPageX;
        this.minVisiblePageZ = minPageZ;
        this.maxVisiblePageZ = maxPageZ;
        this.focusPageX = focusPageX;
        this.focusPageZ = focusPageZ;
    }

    /**
     * Unified vertical height offset for cave floors, independent of whether the player
     * is viewing LAYERED cave or FULL cave. Subtle depth brightness
     * while keeping colors 100% consistent across modes and Top-Y slider movements.
     */
    public static int computeCaveVerticalOffset(int y) {
        return Math.max(-3, Math.min(3, Math.round((y - 16) / 24.0f)));
    }

    /**
     * Unified deterministic ABGR base color resolver for any cave BlockState.
     * Used identically by both live ChunkScanner and offline Anvil (.mca) DecodedSection.
     */
    public static int resolveCaveBlockBaseColor(BlockState state) {
        return CaveMapManager.resolveCaveBlockBaseColor(state);
    }

    public static int resolveCaveBlockBaseColor(BlockState state, int grassTint, int foliageTint) {
        return CaveMapManager.resolveCaveBlockBaseColor(state, grassTint, foliageTint);
    }

    public static boolean requiresCaveBiomeTint(BlockState state) {
        return CaveMapManager.requiresCaveBiomeTint(state);
    }

    /**
     * Unified deterministic ABGR water blend over a cave basin floor.
     * Used identically by both live ChunkScanner and offline Anvil (.mca) water columns.
     */
    public static int blendCaveWater(int floorAbgr, int depth) {
        return CaveMapManager.blendCaveWater(floorAbgr, depth, 0xFF3F76E4);
    }

    public static int blendCaveWater(int floorAbgr, int depth, int waterTintArgb) {
        return CaveMapManager.blendCaveWater(floorAbgr, depth, waterTintArgb);
    }

    public static int styleCavePixel(int litAbgr, float slopeShade,
            int worldX, int worldZ, int reliefY, boolean isFluid, boolean isLeaves) {
        return CaveMapManager.styleCavePixel(litAbgr, slopeShade, worldX, worldZ, reliefY, isFluid, isLeaves, false);
    }

    public static int styleCavePixel(int litAbgr, float slopeShade,
            int worldX, int worldZ, int reliefY, boolean isFluid, boolean isLeaves, boolean isGlowing) {
        return CaveMapManager.styleCavePixel(litAbgr, slopeShade, worldX, worldZ, reliefY, isFluid, isLeaves, isGlowing);
    }

    public static int applyCaveLighting(int abgr, int light, int verticalOffset) {
        return CaveMapManager.applyCaveLighting(abgr, light, 0, verticalOffset, false);
    }

    public static int applyCaveLighting(int abgr, int light, int verticalOffset, boolean isGlowing) {
        return CaveMapManager.applyCaveLighting(abgr, light, 0, verticalOffset, isGlowing);
    }

    public static int applyCaveLighting(int abgr, int blockLight, int skyLight, int verticalOffset, boolean isGlowing) {
        return CaveMapManager.applyCaveLighting(abgr, blockLight, skyLight, verticalOffset, isGlowing);
    }

    private CaveTextureManager() {
        for (int lod = 0; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            int size = CaveTextureAtlas.lodSize(lod);
            uploadBuffers[lod] = new int[size * size];
        }
    }

    public void markPageTextureDirty(int layerY, int globalPageX, int globalPageZ) {
        int band = layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY);
        int rx = Math.floorDiv(globalPageX, 8);
        int rz = Math.floorDiv(globalPageZ, 8);
        PageKey key = new PageKey(band, globalPageX, globalPageZ);
        synchronized (pages) {
            PageEntry entry = pages.computeIfAbsent(key, PageEntry::new);
            entry.dirty = true;
            dirtyPageKeys.add(key);
        }
        synchronized (branchPages) {
            BranchKey k3 = new BranchKey(band, 3, rx, rz);
            BranchEntry l3 = branchPages.computeIfAbsent(k3, BranchEntry::new);
            l3.dirty = true;
            dirtyBranchKeys.add(k3);

            BranchKey k2 = new BranchKey(band, 2, Math.floorDiv(globalPageX, 4), Math.floorDiv(globalPageZ, 4));
            BranchEntry l2 = branchPages.get(k2);
            if (l2 != null) {
                l2.dirty = true;
                dirtyBranchKeys.add(k2);
            }
            BranchKey k1 = new BranchKey(band, 1, Math.floorDiv(globalPageX, 2), Math.floorDiv(globalPageZ, 2));
            BranchEntry l1 = branchPages.get(k1);
            if (l1 != null) {
                l1.dirty = true;
                dirtyBranchKeys.add(k1);
            } else if (preferredCaveScale < 0.52f) {
                l1 = branchPages.computeIfAbsent(k1, BranchEntry::new);
                l1.dirty = true;
                dirtyBranchKeys.add(k1);
            }
        }
        contentRevision.incrementAndGet();
    }

    public void markRegionTextureDirty(int layerY, int rx, int rz) {
        CaveMapManager.CaveRegion region = CaveMapManager.getInstance().getRegion(layerY, rx, rz, false);
        if (region == null || !region.hasContent()) {
            return;
        }
        int band = layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY);
        int firstGlobalX = rx << 3;
        int firstGlobalZ = rz << 3;
        int minPx = minVisiblePageX;
        int maxPx = maxVisiblePageX;
        int minPz = minVisiblePageZ;
        int maxPz = maxVisiblePageZ;
        int fpx = focusPageX;
        int fpz = focusPageZ;
        boolean allowLeafPrecreate = preferredCaveScale >= 0.35f;
        synchronized (pages) {
            for (int pz = 0; pz < 8; pz++) {
                for (int px = 0; px < 8; px++) {
                    int gpx = firstGlobalX + px;
                    int gpz = firstGlobalZ + pz;
                    PageKey key = new PageKey(band, gpx, gpz);
                    PageEntry existing = pages.get(key);
                    if (existing != null) {
                        existing.dirty = true;
                        dirtyPageKeys.add(key);
                    } else if (allowLeafPrecreate && minPx != Integer.MIN_VALUE
                            && gpx >= minPx - 1 && gpx <= maxPx + 1
                            && gpz >= minPz - 1 && gpz <= maxPz + 1
                            && Math.abs(gpx - fpx) <= 12 && Math.abs(gpz - fpz) <= 12) {
                        PageEntry entry = pages.computeIfAbsent(key, PageEntry::new);
                        entry.dirty = true;
                        dirtyPageKeys.add(key);
                    }
                }
            }
        }
        synchronized (branchPages) {
            BranchKey k3 = new BranchKey(band, 3, rx, rz);
            BranchEntry l3 = branchPages.computeIfAbsent(k3, BranchEntry::new);
            l3.dirty = true;
            dirtyBranchKeys.add(k3);

            int baseNodeX2 = rx * 2;
            int baseNodeZ2 = rz * 2;
            for (int dz = 0; dz < 2; dz++) {
                for (int dx = 0; dx < 2; dx++) {
                    BranchKey k2 = new BranchKey(band, 2, baseNodeX2 + dx, baseNodeZ2 + dz);
                    BranchEntry l2 = branchPages.get(k2);
                    if (l2 != null) {
                        l2.dirty = true;
                        dirtyBranchKeys.add(k2);
                    }
                }
            }
            int baseNodeX1 = rx * 4;
            int baseNodeZ1 = rz * 4;
            for (int dz = 0; dz < 4; dz++) {
                for (int dx = 0; dx < 4; dx++) {
                    BranchKey k1 = new BranchKey(band, 1, baseNodeX1 + dx, baseNodeZ1 + dz);
                    BranchEntry l1 = branchPages.get(k1);
                    if (l1 != null) {
                        l1.dirty = true;
                        dirtyBranchKeys.add(k1);
                    }
                }
            }
        }
        contentRevision.incrementAndGet();
    }

    public void requestVisiblePages(int layerY, double minX, double maxX,
            double minZ, double maxZ) {
        requestVisiblePages(layerY, minX, maxX, minZ, maxZ, 1.0f);
    }

    public void requestVisiblePages(int layerY, double minX, double maxX,
            double minZ, double maxZ, float scale) {
        requestVisiblePages(layerY, minX, maxX, minZ, maxZ, scale,
                MapRequestLane.FULLSCREEN);
    }

    public void requestVisiblePages(int layerY, double minX, double maxX,
            double minZ, double maxZ, float scale, MapRequestLane lane) {
        requestVisiblePages(layerY, minX, maxX, minZ, maxZ, scale,
                (minX + maxX) * 0.5, (minZ + maxZ) * 0.5, lane);
    }

    public void suspendLane(MapRequestLane lane) {
        // Direct leaf caching does not require lane-based suspension.
    }

    public void requestVisiblePages(int layerY, double minX, double maxX,
            double minZ, double maxZ, float scale,
            double focusX, double focusZ, MapRequestLane lane) {
        if (activeLayerY != layerY) onLayerActivated(layerY);
        this.preferredCaveScale = Math.max(0.01f, scale);
        this.focusPageX = (int) Math.floor(focusX / CaveTextureAtlas.PAGE_SIZE);
        this.focusPageZ = (int) Math.floor(focusZ / CaveTextureAtlas.PAGE_SIZE);
        this.minVisiblePageX = (int) Math.floor(Math.min(minX, maxX) / CaveTextureAtlas.PAGE_SIZE);
        this.maxVisiblePageX = (int) Math.floor(Math.max(minX, maxX) / CaveTextureAtlas.PAGE_SIZE);
        this.minVisiblePageZ = (int) Math.floor(Math.min(minZ, maxZ) / CaveTextureAtlas.PAGE_SIZE);
        this.maxVisiblePageZ = (int) Math.floor(Math.max(minZ, maxZ) / CaveTextureAtlas.PAGE_SIZE);
    }

    public void requestVisibleRegion(int layerY, int rx, int rz) {
        if (activeLayerY != layerY) onLayerActivated(layerY);
    }

    private static int sameBand(int oldLayer, int layerY) {
        if (oldLayer == Integer.MIN_VALUE || layerY == Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return oldLayer;
    }

    public synchronized void onLayerActivated(int layerY) {
        if (activeLayerY == layerY) return;
        int oldLayer = activeLayerY;
        if (oldLayer != Integer.MIN_VALUE && layerY != Integer.MIN_VALUE) {
            // Retain previous layer for atomic whole-page swap during band/layer transitions
            previousLayerY = sameBand(oldLayer, layerY);
            CaveMode.beginTransition();
        } else {
            previousLayerY = Integer.MIN_VALUE;
        }
        activeLayerY = layerY;
        int activeBand = layerY == Integer.MIN_VALUE
                ? Integer.MIN_VALUE
                : CaveLayerBand.key(CaveView.LAYERED, layerY);
        // Keep resident GPU slots of FULL cave and recent LAYERED bands in memory
        // so switching OFF <-> LAYERED <-> FULL is instant (0ms reload); evictOldestPage()
        // automatically reclaims off-band slots only when the 576-slot atlas is full.
        boolean needsIntraBandRefresh = (layerY != Integer.MIN_VALUE && oldLayer != Integer.MIN_VALUE
                && CaveLayerBand.key(CaveView.LAYERED, oldLayer) == activeBand);
        synchronized (pages) {
            for (PageEntry entry : pages.values()) {
                if (entry.key.band() == activeBand) {
                    if (needsIntraBandRefresh || entry.slot == -1 || entry.dirty) {
                        entry.dirty = true;
                        dirtyPageKeys.add(entry.key);
                    }
                }
            }
        }
        synchronized (branchPages) {
            for (BranchEntry entry : branchPages.values()) {
                if (entry.key.band() == activeBand) {
                    if (needsIntraBandRefresh || entry.slot == -1 || entry.dirty) {
                        entry.dirty = true;
                        dirtyBranchKeys.add(entry.key);
                    }
                }
            }
        }
        CaveMapManager.getInstance().notifyLayerActivated(layerY);
        MapMemoryBudgetPolicy.MapResidencyManager.getInstance()
                .markCoverageChanged(MapMemoryBudgetPolicy.MapResidencyManager.Kind.CAVE_EXACT);
        contentRevision.incrementAndGet();
    }

    public long contentRevision() {
        return contentRevision.get();
    }

    public long branchContentRevision() {
        return contentRevision.get();
    }

    public void beginRenderBatch() {
    }

    public void endRenderBatch() {
        atlas.onPageTableFrameBoundary();
    }

    public CaveAtlasRegion peekPageRegion(int layerY, int rx, int rz,
            int pageX, int pageZ, float scale) {
        return peekPageRegion(layerY, rx, rz, pageX, pageZ, scale,
                MapRequestLane.FULLSCREEN);
    }

    public CaveAtlasRegion peekPageRegion(int layerY, int rx, int rz,
            int pageX, int pageZ, float scale, MapRequestLane lane) {
        int globalPageX = (rx << 3) + pageX;
        int globalPageZ = (rz << 3) + pageZ;
        int band = layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY);
        PageKey key = new PageKey(band, globalPageX, globalPageZ);

        PageEntry entry;
        synchronized (pages) {
            entry = pages.get(key);
            if (entry == null) {
                CaveMapManager.CaveRegion region = CaveMapManager.getInstance().getRegion(layerY, rx, rz, false);
                if (region == null || !region.hasContent()) {
                    return null;
                }
                entry = new PageEntry(key);
                pages.put(key, entry);
                dirtyPageKeys.add(key);
            } else if (entry.slot == -1 && entry.hasContent && !entry.dirty) {
                entry.dirty = true;
                dirtyPageKeys.add(key);
            }
            entry.lastAccessTime = System.currentTimeMillis();
        }

        if (entry.slot < 0 || !entry.hasContent) return null;
        return atlas.region(entry.slot, 1.0f);
    }

    public PageSelection peekFallbackPage(int layerY, int rx, int rz,
            int pageX, int pageZ, float scale, MapRequestLane lane) {
        if (layerY == Integer.MIN_VALUE || previousLayerY == Integer.MIN_VALUE) {
            previousLayerY = Integer.MIN_VALUE;
            return null;
        }
        if (Math.abs(previousLayerY - layerY) > 16) {
            previousLayerY = Integer.MIN_VALUE;
            return null;
        }
        int globalPageX = (rx << 3) + pageX;
        int globalPageZ = (rz << 3) + pageZ;
        int currentBand = CaveLayerBand.key(CaveView.LAYERED, layerY);

        if (CaveMode.isTransitionActive()) {
            int prevBand = CaveLayerBand.key(CaveView.LAYERED, previousLayerY);
            if (prevBand != currentBand) {
                PageSelection sel = tryGetFallbackPage(prevBand, previousLayerY, globalPageX, globalPageZ, scale, lane);
                if (sel != null) return sel;
            }
        } else {
            previousLayerY = Integer.MIN_VALUE;
        }

        return null;
    }

    private PageSelection tryGetFallbackPage(int band, int representativeLayerY,
            int globalPageX, int globalPageZ, float scale, MapRequestLane lane) {
        PageKey key = new PageKey(band, globalPageX, globalPageZ);
        PageEntry entry;
        synchronized (pages) {
            entry = pages.get(key);
            if (entry != null) {
                entry.lastAccessTime = System.currentTimeMillis();
            }
        }
        if (entry != null && entry.slot >= 0 && entry.hasContent) {
            CaveAtlasRegion reg = atlas.region(entry.slot, 1.0f);
            if (reg != null) {
                return new PageSelection(reg, pageTileKey(representativeLayerY, globalPageX, globalPageZ, scale, lane));
            }
        }
        return null;
    }

    public TileKey pageTileKey(int layerY, int globalPageX,
            int globalPageZ, float scale) {
        return pageTileKey(layerY, globalPageX, globalPageZ, scale,
                MapRequestLane.FULLSCREEN);
    }

    public TileKey pageTileKey(int layerY, int globalPageX,
            int globalPageZ, float scale, MapRequestLane lane) {
        com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph.RevisionStamp stamp =
                com.velorise.simplemap.client.session.MapSessionManager.getInstance().activeStamp();
        long sessionId = stamp == null ? 1L : Math.max(1L, stamp.sessionId());
        int band = layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY);
        int lod = CaveTextureAtlas.lodForScale(scale);
        int variant = layerY == Integer.MIN_VALUE ? TileKey.VARIANT_CAVE_FULL : TileKey.VARIANT_CAVE_LAYERED;
        return new TileKey(sessionId, band, lod,
                globalPageX, globalPageZ, variant);
    }

    public CaveAtlasRegion peekBranchRegion(int layerY, int rx, int rz) {
        return peekBranchRegion(layerY, 3, rx, rz, MapRequestLane.FULLSCREEN);
    }

    public CaveAtlasRegion peekBranchRegion(int layerY, int level,
            int nodeX, int nodeZ) {
        return peekBranchRegion(layerY, level, nodeX, nodeZ, MapRequestLane.FULLSCREEN);
    }

    public CaveAtlasRegion peekBranchRegion(int layerY, int level,
            int nodeX, int nodeZ, MapRequestLane lane) {
        return peekBranchRegion(layerY, level, nodeX, nodeZ, lane, true);
    }

    /**
     * Queries a branch node, optionally suppressing new L1/L2 allocation when only
     * probing for an optional underlay or coarse ancestor at exact L0 zoom.
     */
    public CaveAtlasRegion peekBranchRegion(int layerY, int level,
            int nodeX, int nodeZ, MapRequestLane lane, boolean createIfMissing) {
        if (level <= 0 || level > 3) return null;
        int band = layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY);
        BranchKey key = new BranchKey(band, level, nodeX, nodeZ);

        BranchEntry entry;
        synchronized (branchPages) {
            entry = branchPages.get(key);
            if (entry == null) {
                if (!createIfMissing && level < 3) {
                    return null;
                }
                int pageSpan = MapViewportCoordinator.MapLodPolicy.pageSpanForBranch(level);
                int firstPageX = nodeX * pageSpan;
                int firstPageZ = nodeZ * pageSpan;
                int rx = Math.floorDiv(firstPageX, 8);
                int rz = Math.floorDiv(firstPageZ, 8);
                CaveMapManager.CaveRegion region = CaveMapManager.getInstance().getRegion(layerY, rx, rz, false);
                if (region == null || !region.hasContent()) {
                    return null;
                }
                entry = new BranchEntry(key);
                branchPages.put(key, entry);
                dirtyBranchKeys.add(key);
            } else if (entry.slot == -1 && entry.hasContent && !entry.dirty) {
                entry.dirty = true;
                dirtyBranchKeys.add(key);
            }
            if (entry != null) {
                entry.lastAccessTime = System.currentTimeMillis();
            }
        }

        if (entry == null || entry.slot < 0 || !entry.hasContent) return null;
        int pageSpan = MapViewportCoordinator.MapLodPolicy.pageSpanForBranch(level);
        int worldSize = pageSpan * CaveTextureAtlas.PAGE_SIZE;
        CaveAtlasRegion reg = atlas.region(entry.slot, 1.0f);
        if (reg == null) return null;
        return new CaveAtlasRegion(reg.texture(), reg.sourceX(), reg.sourceY(), 64,
                reg.atlasSize(), level, worldSize, entry.knownMask, entry.completeMask);
    }

    public boolean hasBranchData(int layerY, int level, int nodeX, int nodeZ) {
        return hasBranchData(layerY, level, nodeX, nodeZ, MapRequestLane.FULLSCREEN);
    }

    public boolean hasBranchData(int layerY, int level, int nodeX, int nodeZ,
            MapRequestLane lane) {
        int band = layerY == Integer.MIN_VALUE
                ? Integer.MIN_VALUE
                : CaveMode.CaveLayerBand.key(CaveMode.CaveLayerBand.CaveView.LAYERED, layerY);
        BranchKey key = new BranchKey(band, level, nodeX, nodeZ);
        synchronized (branchPages) {
            BranchEntry entry = branchPages.get(key);
            if (entry != null && !entry.dirty) {
                return entry.hasContent;
            }
        }
        int pageSpan = MapViewportCoordinator.MapLodPolicy.pageSpanForBranch(level);
        int firstPageX = nodeX * pageSpan;
        int firstPageZ = nodeZ * pageSpan;
        int rx = Math.floorDiv(firstPageX, 8);
        int rz = Math.floorDiv(firstPageZ, 8);
        CaveMapManager cmm = CaveMapManager.getInstance();
        CaveMapManager.CaveRegion region = cmm.getRegion(layerY, rx, rz, false);
        if (region != null && region.isLoaded()) {
            if (!region.hasContent()) return false;
            if (level <= 3) {
                int localPageX = Math.floorMod(firstPageX, 8);
                int localPageZ = Math.floorMod(firstPageZ, 8);
                return region.hasNodeContent(localPageX, localPageZ, Math.min(8, pageSpan));
            }
            return true;
        }
        if (lane == MapRequestLane.MINIMAP) {
            return false;
        }
        return cmm.hasRegionFile(layerY, rx, rz);
    }

    public boolean hasResidentPageInNode(int layerY, int level,
            int nodeX, int nodeZ) {
        return hasResidentPageInNode(layerY, level, nodeX, nodeZ, MapRequestLane.FULLSCREEN);
    }

    public boolean hasResidentPageInNode(int layerY, int level,
            int nodeX, int nodeZ, MapRequestLane lane) {
        int pageSpan = MapViewportCoordinator.MapLodPolicy.pageSpanForBranch(level);
        int firstPageX = nodeX * pageSpan;
        int firstPageZ = nodeZ * pageSpan;
        int lastPageX = firstPageX + pageSpan - 1;
        int lastPageZ = firstPageZ + pageSpan - 1;

        int band = layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveMode.CaveLayerBand.key(CaveMode.CaveLayerBand.CaveView.LAYERED, layerY);

        // 1. Check GPU resident pages for active band
        synchronized (pages) {
            for (int pz = firstPageZ; pz <= lastPageZ; pz++) {
                for (int px = firstPageX; px <= lastPageX; px++) {
                    PageEntry entry = pages.get(new PageKey(band, px, pz));
                    if (entry != null && entry.slot >= 0 && entry.hasContent) {
                        return true;
                    }
                }
            }
        }

        // 2. Check fallback band if layer recently changed during transition
        if (previousLayerY != Integer.MIN_VALUE && CaveMode.isTransitionActive()) {
            int prevBand = CaveMode.CaveLayerBand.key(CaveMode.CaveLayerBand.CaveView.LAYERED, previousLayerY);
            if (prevBand != band) {
                synchronized (pages) {
                    for (int pz = firstPageZ; pz <= lastPageZ; pz++) {
                        for (int px = firstPageX; px <= lastPageX; px++) {
                            PageEntry entry = pages.get(new PageKey(prevBand, px, pz));
                            if (entry != null && entry.slot >= 0 && entry.hasContent) {
                                return true;
                            }
                        }
                    }
                }
            }
        }

        // 3. Check GPU-resident child branch pages (L1..L(level-1))
        synchronized (branchPages) {
            for (int childLevel = level - 1; childLevel >= 1; childLevel--) {
                int childSpan = MapViewportCoordinator.MapLodPolicy.pageSpanForBranch(childLevel);
                int minNodeX = Math.floorDiv(firstPageX, childSpan);
                int maxNodeX = Math.floorDiv(lastPageX, childSpan);
                int minNodeZ = Math.floorDiv(firstPageZ, childSpan);
                int maxNodeZ = Math.floorDiv(lastPageZ, childSpan);
                for (int nz = minNodeZ; nz <= maxNodeZ; nz++) {
                    for (int nx = minNodeX; nx <= maxNodeX; nx++) {
                        BranchEntry bEntry = branchPages.get(new BranchKey(band, childLevel, nx, nz));
                        if (bEntry != null && bEntry.slot >= 0 && bEntry.hasContent) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    public boolean allowFullscreenExact(int layerY,
            int globalPageX, int globalPageZ) {
        return true;
    }

    public ResourceLocation peekPageTexture(int layerY, int rx, int rz,
            int pageX, int pageZ) {
        CaveAtlasRegion region = peekPageRegion(layerY, rx, rz, pageX, pageZ, 1.0f);
        return region == null ? null : region.texture();
    }

    public boolean hasAnyPageTexture(int layerY, int rx, int rz) {
        int band = layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY);
        int firstGlobalX = rx << 3;
        int firstGlobalZ = rz << 3;
        synchronized (pages) {
            for (int pz = 0; pz < 8; pz++) {
                for (int px = 0; px < 8; px++) {
                    PageEntry entry = pages.get(new PageKey(band, firstGlobalX + px, firstGlobalZ + pz));
                    if (entry != null && entry.slot >= 0 && entry.hasContent) return true;
                }
            }
        }
        return false;
    }

    public boolean hasResidentCoverageForRegion(int layerY, int rx, int rz) {
        if (hasAnyPageTexture(layerY, rx, rz)) return true;
        int band = layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY);
        int firstPageX = rx << 3;
        int lastPageX = firstPageX + 7;
        int firstPageZ = rz << 3;
        int lastPageZ = firstPageZ + 7;
        synchronized (branchPages) {
            for (int level = 1; level <= 3; level++) {
                int span = MapViewportCoordinator.MapLodPolicy.pageSpanForBranch(level);
                int minNodeX = Math.floorDiv(firstPageX, span);
                int maxNodeX = Math.floorDiv(lastPageX, span);
                int minNodeZ = Math.floorDiv(firstPageZ, span);
                int maxNodeZ = Math.floorDiv(lastPageZ, span);
                for (int nz = minNodeZ; nz <= maxNodeZ; nz++) {
                    for (int nx = minNodeX; nx <= maxNodeX; nx++) {
                        BranchEntry bEntry = branchPages.get(new BranchKey(band, level, nx, nz));
                        if (bEntry != null && bEntry.slot >= 0 && bEntry.hasContent) return true;
                    }
                }
            }
        }
        return false;
    }

    public ResourceLocation getRegionTexture(int layerY, int rx, int rz) {
        requestVisibleRegion(layerY, rx, rz);
        return null;
    }

    public ResourceLocation peekRegionTexture(int layerY, int rx, int rz) {
        CaveAtlasRegion reg = peekBranchRegion(layerY, 3, rx, rz, MapRequestLane.FULLSCREEN);
        return reg == null ? null : reg.texture();
    }

    public void uploadDirtyTextures() {
        uploadDirtyTextures(false);
    }

    public void uploadDirtyTextures(boolean force) {
        if (MapActivityGate.getInstance().blocksForegroundStreaming()) return;

        RenderSystem.assertOnRenderThreadOrInit();
        atlas.onPageTableFrameBoundary();

        final int activeBand = activeLayerY == Integer.MIN_VALUE
                ? Integer.MIN_VALUE
                : CaveLayerBand.key(CaveView.LAYERED, activeLayerY);
        List<PageEntry> dirtyList = new ArrayList<>();
        synchronized (pages) {
            Iterator<PageKey> it = dirtyPageKeys.iterator();
            while (it.hasNext()) {
                PageKey key = it.next();
                PageEntry entry = pages.get(key);
                if (entry == null || !entry.dirty) {
                    it.remove();
                    continue;
                }
                if (key.band() == activeBand) {
                    dirtyList.add(entry);
                }
            }
        }

        final int fpx = focusPageX;
        final int fpz = focusPageZ;
        final int minPx = minVisiblePageX;
        final int maxPx = maxVisiblePageX;
        final int minPz = minVisiblePageZ;
        final int maxPz = maxVisiblePageZ;

        if (dirtyList.size() > 1) {
            dirtyList.sort((a, b) -> {
                boolean inViewA = a.key.globalPageX() >= minPx && a.key.globalPageX() <= maxPx
                        && a.key.globalPageZ() >= minPz && a.key.globalPageZ() <= maxPz;
                boolean inViewB = b.key.globalPageX() >= minPx && b.key.globalPageX() <= maxPx
                        && b.key.globalPageZ() >= minPz && b.key.globalPageZ() <= maxPz;
                if (inViewA != inViewB) return inViewA ? -1 : 1;

                int dxA = Math.abs(a.key.globalPageX() - fpx);
                int dzA = Math.abs(a.key.globalPageZ() - fpz);
                int ringA = Math.max(dxA, dzA);

                int dxB = Math.abs(b.key.globalPageX() - fpx);
                int dzB = Math.abs(b.key.globalPageZ() - fpz);
                int ringB = Math.max(dxB, dzB);

                if (ringA != ringB) return Integer.compare(ringA, ringB);

                long distSqA = (long) dxA * dxA + (long) dzA * dzA;
                long distSqB = (long) dxB * dxB + (long) dzB * dzB;
                if (distSqA != distSqB) return Long.compare(distSqA, distSqB);

                int cmpZ = Integer.compare(a.key.globalPageZ(), b.key.globalPageZ());
                if (cmpZ != 0) return cmpZ;
                return Integer.compare(a.key.globalPageX(), b.key.globalPageX());
            });
        }

        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + (force ? FORCE_UPLOAD_BUDGET_NANOS : NORMAL_UPLOAD_BUDGET_NANOS);
        int limit = force ? 8 : (MapPerformanceGovernor.getInstance().hasForegroundUploadHeadroom() ? 6 : 3);
        int uploadedCount = 0;

        // 1. Upload dirty branch nodes first (up to half the budget or all if no dirty leaves)
        // so overview zooms (0.1x..0.5x) and L1 underlays never starve behind 64-leaf bursts.
        List<BranchEntry> dirtyBranches = new ArrayList<>();
        synchronized (branchPages) {
            Iterator<BranchKey> it = dirtyBranchKeys.iterator();
            while (it.hasNext()) {
                BranchKey key = it.next();
                BranchEntry entry = branchPages.get(key);
                if (entry == null || !entry.dirty) {
                    it.remove();
                    continue;
                }
                if (key.band() == activeBand) {
                    dirtyBranches.add(entry);
                }
            }
        }
        if (dirtyBranches.size() > 1) {
            dirtyBranches.sort((a, b) -> {
                if (a.key.level() != b.key.level()) {
                    return Integer.compare(b.key.level(), a.key.level());
                }
                int spanA = MapViewportCoordinator.MapLodPolicy.pageSpanForBranch(a.key.level());
                int cpxA = a.key.nodeX() * spanA + (spanA >> 1);
                int cpzA = a.key.nodeZ() * spanA + (spanA >> 1);

                int spanB = MapViewportCoordinator.MapLodPolicy.pageSpanForBranch(b.key.level());
                int cpxB = b.key.nodeX() * spanB + (spanB >> 1);
                int cpzB = b.key.nodeZ() * spanB + (spanB >> 1);

                int dxA = Math.abs(cpxA - fpx);
                int dzA = Math.abs(cpzA - fpz);
                int ringA = Math.max(dxA, dzA);

                int dxB = Math.abs(cpxB - fpx);
                int dzB = Math.abs(cpzB - fpz);
                int ringB = Math.max(dxB, dzB);

                if (ringA != ringB) return Integer.compare(ringA, ringB);

                long distSqA = (long) dxA * dxA + (long) dzA * dzA;
                long distSqB = (long) dxB * dxB + (long) dzB * dzB;
                if (distSqA != distSqB) return Long.compare(distSqA, distSqB);

                int cmpZ = Integer.compare(a.key.nodeZ(), b.key.nodeZ());
                if (cmpZ != 0) return cmpZ;
                return Integer.compare(a.key.nodeX(), b.key.nodeX());
            });
        }
        int branchLimit = dirtyList.isEmpty() ? limit : Math.max(2, limit >> 1);
        for (BranchEntry entry : dirtyBranches) {
            if (uploadedCount >= branchLimit || (uploadedCount > 0 && System.nanoTime() >= deadlineNanos)) break;
            int targetLayer = entry.key.band() != Integer.MIN_VALUE && activeLayerY != Integer.MIN_VALUE
                    && CaveLayerBand.key(CaveView.LAYERED, activeLayerY) == entry.key.band()
                    ? activeLayerY
                    : entry.key.band();
            if (uploadBranchNode(entry, targetLayer, entry.key.level(), entry.key.nodeX(), entry.key.nodeZ())) {
                uploadedCount++;
            }
            if (!entry.dirty) {
                synchronized (branchPages) {
                    dirtyBranchKeys.remove(entry.key);
                }
            }
        }

        // 2. Upload visible/near-viewport exact 64x64 pages within remaining budget.
        for (PageEntry entry : dirtyList) {
            if (uploadedCount >= limit || (uploadedCount > 0 && System.nanoTime() >= deadlineNanos)) break;
            int rx = Math.floorDiv(entry.key.globalPageX(), 8);
            int rz = Math.floorDiv(entry.key.globalPageZ(), 8);
            int px = Math.floorMod(entry.key.globalPageX(), 8);
            int pz = Math.floorMod(entry.key.globalPageZ(), 8);
            if (uploadPage(entry, rx, rz, px, pz)) {
                uploadedCount++;
            }
            if (!entry.dirty) {
                synchronized (pages) {
                    dirtyPageKeys.remove(entry.key);
                }
            }
        }

        if (uploadedCount > 0) {
            contentRevision.incrementAndGet();
            MapMemoryBudgetPolicy.MapResidencyManager residency =
                    MapMemoryBudgetPolicy.MapResidencyManager.getInstance();
            residency.markCoverageChanged(
                    MapMemoryBudgetPolicy.MapResidencyManager.Kind.CAVE_EXACT);
            residency.markPixelsChanged(
                    MapMemoryBudgetPolicy.MapResidencyManager.Kind.CAVE_EXACT);
        }
    }

    public void invalidateStyle() {
        CaveMapManager.getInstance().invalidateStyle();
        synchronized (pages) {
            for (PageEntry entry : pages.values()) {
                entry.dirty = true;
                dirtyPageKeys.add(entry.key);
            }
        }
        synchronized (branchPages) {
            for (BranchEntry entry : branchPages.values()) {
                entry.dirty = true;
                dirtyBranchKeys.add(entry.key);
            }
        }
        contentRevision.incrementAndGet();
        MapMemoryBudgetPolicy.MapResidencyManager.getInstance()
                .markPixelsChanged(MapMemoryBudgetPolicy.MapResidencyManager.Kind.CAVE_EXACT);
    }

    public synchronized void clearCache() {
        synchronized (pages) {
            for (PageEntry entry : pages.values()) {
                if (entry.slot != -1) {
                    atlas.releaseSlot(entry.slot);
                }
            }
            pages.clear();
            dirtyPageKeys.clear();
        }
        synchronized (branchPages) {
            for (BranchEntry entry : branchPages.values()) {
                if (entry.slot != -1) {
                    atlas.releaseSlot(entry.slot);
                }
            }
            branchPages.clear();
            dirtyBranchKeys.clear();
        }
        atlas.resetSlots();
        activeLayerY = Integer.MIN_VALUE;
        previousLayerY = Integer.MIN_VALUE;
        contentRevision.incrementAndGet();
        MapMemoryBudgetPolicy.MapResidencyManager.getInstance().markTopologyChanged();
    }

    public void suspendForDimensionSwitch() {
        clearCache();
    }

    public int projectionLayerForLane(int requestedLayerY, MapRequestLane lane) {
        return requestedLayerY;
    }

    private synchronized boolean uploadBranchNode(BranchEntry entry, int layerY, int level, int nodeX, int nodeZ) {
        int pageSpan = MapViewportCoordinator.MapLodPolicy.pageSpanForBranch(level);
        int firstPageX = nodeX * pageSpan;
        int firstPageZ = nodeZ * pageSpan;
        int rx = Math.floorDiv(firstPageX, 8);
        int rz = Math.floorDiv(firstPageZ, 8);

        CaveMapManager.CaveRegion region = CaveMapManager.getInstance().getRegion(layerY, rx, rz, false);
        if (region == null || !region.hasContent()) {
            entry.dirty = false;
            return false;
        }

        RenderSystem.assertOnRenderThreadOrInit();
        int[] regionPixels = region.getPixelsDirect();
        int localStartX = (firstPageX & 7) * 64;
        int localStartZ = (firstPageZ & 7) * 64;
        int step = pageSpan;
        int totalSamples = Math.max(1, step * step);
        boolean hasContent = false;

        region.lock();
        try {
            for (int y = 0; y < 64; y++) {
                int srcY = localStartZ + y * step;
                int dstRow = y * 64;
                for (int x = 0; x < 64; x++) {
                    int srcX = localStartX + x * step;
                    long weightedRed = 0L;
                    long weightedGreen = 0L;
                    long weightedBlue = 0L;
                    long totalWeight = 0L;
                    int count = 0;
                    int maxLum = 0;
                    for (int dy = 0; dy < step; dy++) {
                        int currentY = srcY + dy;
                        if (currentY >= 512) break;
                        int off = currentY * 512 + srcX;
                        for (int dx = 0; dx < step; dx++) {
                            int currentX = srcX + dx;
                            if (currentX >= 512) break;
                            int c = regionPixels[off + dx];
                            int a = (c >>> 24) & 0xFF;
                            if (a != 0 && c != 0) {
                                int r = c & 0xFF;
                                int g = (c >>> 8) & 0xFF;
                                int b = (c >>> 16) & 0xFF;
                                int lum = Math.max(r, Math.max(g, b));
                                if (lum > maxLum) maxLum = lum;
                                int w = (r + g + b >= 380 || lum >= 195) ? (420 + lum * 3) : (48 + lum);
                                weightedRed += (long) r * w;
                                weightedGreen += (long) g * w;
                                weightedBlue += (long) b * w;
                                totalWeight += w;
                                count++;
                            }
                        }
                    }
                    int color = 0;
                    if (count > 0 && totalWeight > 0L) {
                        int avgR = (int) (weightedRed / totalWeight);
                        int avgG = (int) (weightedGreen / totalWeight);
                        int avgB = (int) (weightedBlue / totalWeight);
                        if (count < totalSamples) {
                            float rawCoverage = (float) count / (float) totalSamples;
                            float coverage = (float) Math.pow(rawCoverage, 0.52);
                            if (maxLum > 180) {
                                coverage = Math.min(1.0f, coverage * 1.25f);
                            }
                            avgR = Math.min(255, Math.round(avgR * coverage));
                            avgG = Math.min(255, Math.round(avgG * coverage));
                            avgB = Math.min(255, Math.round(avgB * coverage));
                        }
                        if ((avgR | avgG | avgB) != 0) {
                            color = 0xFF000000 | (avgB << 16) | (avgG << 8) | avgR;
                            hasContent = true;
                        }
                    }
                    uploadBuffers[0][dstRow + x] = color;
                }
            }
        } finally {
            region.unlock();
        }

        long knownMask = 0L;
        long completeMask = 0L;
        if (hasContent) {
            for (int qz = 0; qz < 2; qz++) {
                for (int qx = 0; qx < 2; qx++) {
                    int childIndex = qz * 2 + qx;
                    int nonZero = 0;
                    for (int y = qz * 32; y < (qz + 1) * 32; y++) {
                        int row = y * 64;
                        for (int x = qx * 32; x < (qx + 1) * 32; x++) {
                            if (uploadBuffers[0][row + x] != 0) {
                                nonZero++;
                            }
                        }
                    }
                    if (nonZero > 0) {
                        knownMask |= (1L << childIndex);
                        if (region.isAnvilSliced() || nonZero >= 64) {
                            completeMask |= (1L << childIndex);
                        }
                    }
                }
            }
        }

        entry.hasContent = hasContent;
        entry.knownMask = knownMask;
        entry.completeMask = completeMask;
        if (!hasContent) {
            entry.dirty = false;
            return false;
        }

        if (entry.slot == -1) {
            int slot = atlas.acquireSlot();
            if (slot == -1) {
                evictOldestPage();
                slot = atlas.acquireSlot();
            }
            if (slot == -1) return false;
            entry.slot = slot;
        }

        // Branch textures (L1, L2, L3) are always sampled at lod 0 (64x64).
        int branchSize = CaveTextureAtlas.lodSize(0);
        atlas.upload(entry.slot, 0, uploadBuffers[0],
                new CaveTextureAtlas.DirtyRect(0, 0, branchSize - 1, branchSize - 1));

        entry.dirty = false;
        return true;
    }

    private synchronized boolean uploadPage(PageEntry entry, int rx, int rz, int pageX, int pageZ) {
        int requestedLayer = entry.key.band();
        if (requestedLayer != Integer.MIN_VALUE && activeLayerY != Integer.MIN_VALUE
                && CaveLayerBand.key(CaveView.LAYERED, activeLayerY) == requestedLayer) {
            requestedLayer = activeLayerY;
        }
        CaveMapManager.CaveRegion region = CaveMapManager.getInstance().getRegion(requestedLayer, rx, rz, false);
        if (region == null || !region.hasContent()) {
            entry.dirty = false;
            return false;
        }

        RenderSystem.assertOnRenderThreadOrInit();
        int[] regionPixels = region.getPixelsDirect();
        int sourceX = pageX * CaveTextureAtlas.PAGE_SIZE;
        int sourceZ = pageZ * CaveTextureAtlas.PAGE_SIZE;
        boolean hasContent = false;

        region.lock();
        try {
            for (int row = 0; row < CaveTextureAtlas.PAGE_SIZE; row++) {
                int srcOff = (sourceZ + row) * 512 + sourceX;
                int dstOff = row * CaveTextureAtlas.PAGE_SIZE;
                System.arraycopy(regionPixels, srcOff, uploadBuffers[0], dstOff, CaveTextureAtlas.PAGE_SIZE);
                if (!hasContent) {
                    for (int c = 0; c < CaveTextureAtlas.PAGE_SIZE; c++) {
                        if (uploadBuffers[0][dstOff + c] != 0) {
                            hasContent = true;
                            break;
                        }
                    }
                }
            }
        } finally {
            region.unlock();
        }

        entry.hasContent = hasContent;
        if (!hasContent) {
            entry.dirty = false;
            return false;
        }

        if (entry.slot == -1) {
            int slot = atlas.acquireSlot();
            if (slot == -1) {
                evictOldestPage();
                slot = atlas.acquireSlot();
            }
            if (slot == -1) return false;
            entry.slot = slot;
        }

        // Exact L0 pages sample lod 0 (64x64) with hardware GL_LINEAR + 1px gutter;
        // coarser zooms use L1..L3 branch nodes. Uploading only lod 0 cuts GL transfers in half.
        int maxLeafLod = 1;
        for (int lod = 1; lod < maxLeafLod; lod++) {
            downsample(uploadBuffers[lod - 1], CaveTextureAtlas.lodSize(lod - 1),
                    uploadBuffers[lod], CaveTextureAtlas.lodSize(lod));
        }

        for (int lod = 0; lod < maxLeafLod; lod++) {
            int size = CaveTextureAtlas.lodSize(lod);
            atlas.upload(entry.slot, lod, uploadBuffers[lod],
                    new CaveTextureAtlas.DirtyRect(0, 0, size - 1, size - 1));
        }

        entry.dirty = false;
        return true;
    }

    private void evictOldestPage() {
        if (atlas.availableSlotCount() > 0) return;

        final int activeBand = activeLayerY == Integer.MIN_VALUE
                ? Integer.MIN_VALUE
                : CaveLayerBand.key(CaveView.LAYERED, activeLayerY);
        final int minPx = minVisiblePageX;
        final int maxPx = maxVisiblePageX;
        final int minPz = minVisiblePageZ;
        final int maxPz = maxVisiblePageZ;
        final long nowMs = System.currentTimeMillis();

        PageEntry oldestPage = null;
        BranchEntry oldestBranch = null;
        long oldestScore = Long.MAX_VALUE;
        boolean oldestInView = false;

        synchronized (pages) {
            for (PageEntry e : pages.values()) {
                if (e.slot == -1) continue;
                boolean inActiveBand = e.key.band() == activeBand;
                boolean inView = inActiveBand
                        && e.key.globalPageX() >= minPx - 1 && e.key.globalPageX() <= maxPx + 1
                        && e.key.globalPageZ() >= minPz - 1 && e.key.globalPageZ() <= maxPz + 1;
                long score = e.lastAccessTime
                        + (inActiveBand ? 600_000L : 0L)
                        + (inView ? 10_000_000L : 0L);
                if (score < oldestScore) {
                    oldestScore = score;
                    oldestPage = e;
                    oldestBranch = null;
                    oldestInView = inView;
                }
            }
        }
        synchronized (branchPages) {
            for (BranchEntry b : branchPages.values()) {
                if (b.slot == -1) continue;
                boolean inActiveBand = b.key.band() == activeBand;
                int span = MapViewportCoordinator.MapLodPolicy.pageSpanForBranch(b.key.level());
                int firstPx = b.key.nodeX() * span;
                int lastPx = firstPx + span - 1;
                int firstPz = b.key.nodeZ() * span;
                int lastPz = firstPz + span - 1;
                boolean inView = inActiveBand
                        && lastPx >= minPx - 1 && firstPx <= maxPx + 1
                        && lastPz >= minPz - 1 && firstPz <= maxPz + 1;
                long score = b.lastAccessTime
                        + (inActiveBand ? 600_000L : 0L)
                        + (b.key.level() == 3 && inActiveBand ? 300_000L : 0L)
                        + (inView ? 10_000_000L : 0L);
                if (score < oldestScore) {
                    oldestScore = score;
                    oldestBranch = b;
                    oldestPage = null;
                    oldestInView = inView;
                }
            }
        }

        // Never evict an active-band tile that is currently visible on screen
        if (oldestInView && (oldestPage != null && nowMs - oldestPage.lastAccessTime < 2_500L
                || oldestBranch != null && nowMs - oldestBranch.lastAccessTime < 2_500L)) {
            return;
        }

        if (oldestPage != null && oldestPage.slot != -1) {
            atlas.releaseSlot(oldestPage.slot);
            oldestPage.slot = -1;
            oldestPage.dirty = false;
            synchronized (pages) {
                dirtyPageKeys.remove(oldestPage.key);
                pages.remove(oldestPage.key, oldestPage);
            }
            MapMemoryBudgetPolicy.MapResidencyManager.getInstance().markTopologyChanged();
            contentRevision.incrementAndGet();
        } else if (oldestBranch != null && oldestBranch.slot != -1) {
            atlas.releaseSlot(oldestBranch.slot);
            oldestBranch.slot = -1;
            oldestBranch.dirty = false;
            synchronized (branchPages) {
                dirtyBranchKeys.remove(oldestBranch.key);
                branchPages.remove(oldestBranch.key, oldestBranch);
            }
            MapMemoryBudgetPolicy.MapResidencyManager.getInstance().markTopologyChanged();
            contentRevision.incrementAndGet();
        }
    }

    private static void downsample(int[] source, int sourceSize, int[] target, int targetSize) {
        for (int z = 0; z < targetSize; z++) {
            int sz = z << 1;
            for (int x = 0; x < targetSize; x++) {
                int sx = x << 1;
                int p0 = source[sz * sourceSize + sx];
                int p1 = source[sz * sourceSize + sx + 1];
                int p2 = source[(sz + 1) * sourceSize + sx];
                int p3 = source[(sz + 1) * sourceSize + sx + 1];
                target[z * targetSize + x] = averageAbgr(p0, p1, p2, p3);
            }
        }
    }

    private static int averageAbgr(int p0, int p1, int p2, int p3) {
        int[] values = { p0, p1, p2, p3 };
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        int colored = 0;
        int alpha = 0;
        for (int value : values) {
            int childAlpha = (value >>> 24) & 0xFF;
            if (childAlpha == 0 || value == 0) continue;
            int r = value & 0xFF;
            int g = (value >>> 8) & 0xFF;
            int b = (value >>> 16) & 0xFF;
            int lum = Math.max(r, Math.max(g, b));
            int w = (r + g + b >= 380 || lum >= 195) ? 4 : 1;
            red += (long) r * w;
            green += (long) g * w;
            blue += (long) b * w;
            alpha = Math.max(alpha, childAlpha);
            colored += w;
        }
        if (colored == 0) return 0;
        float coverage = colored == 4 ? 1.0f : (float) Math.pow(colored * 0.25, 0.52);
        int r = Math.min(255, Math.round((red / (float) colored) * coverage));
        int g = Math.min(255, Math.round((green / (float) colored) * coverage));
        int b = Math.min(255, Math.round((blue / (float) colored) * coverage));
        if ((r | g | b) == 0) return 0;
        return (alpha << 24) | (b << 16) | (g << 8) | r;
    }

    public record PageSelection(CaveAtlasRegion region, TileKey tileKey) {
    }

    public record PageKey(int band, int globalPageX, int globalPageZ) {
    }

    private static final class PageEntry {
        final PageKey key;
        int slot = -1;
        boolean dirty = true;
        boolean hasContent = false;
        long lastAccessTime;

        PageEntry(PageKey key) {
            this.key = key;
        }
    }

    public record BranchKey(int band, int level, int nodeX, int nodeZ) {
    }

    private static final class BranchEntry {
        final BranchKey key;
        int slot = -1;
        boolean dirty = true;
        boolean hasContent = false;
        long knownMask = -1L;
        long completeMask = -1L;
        long lastAccessTime;

        BranchEntry(BranchKey key) {
            this.key = key;
        }
    }

    public record DebugSnapshot(int pages, int requests, int pendingBuilds,
            int completedBuilds, int initializedPages, int partialPages,
            int knownEmptyPages, int residentPages, int fullscreenSlice,
            int fullscreenPlanPages) {
        public static DebugSnapshot empty() {
            return new DebugSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    public DebugSnapshot debugSnapshot() {
        int count;
        synchronized (pages) {
            count = pages.size();
        }
        return new DebugSnapshot(count, 0, 0, 0, count, 0, 0, count, 0, 0);
    }

    /** Applies stable height-based relief to cached cave pixels on the CPU worker. */
    public static final class CaveReliefColorizer {
        private static final int SIZE = 512;

        private CaveReliefColorizer() {
        }

        public static int[] colorize(int[] source, short[] heights, int terrainSlopes,
                int profile, java.util.function.BooleanSupplier stillValid) {
            int[] output = new int[source.length];
            for (int z = 0; z < SIZE; z++) {
                if ((z & 31) == 0 && !stillValid.getAsBoolean()) {
                    throw new java.util.concurrent.CancellationException("Stale cave relief job");
                }
                for (int x = 0; x < SIZE; x++) {
                    int index = z * SIZE + x;
                    int color = source[index];
                    if (color == 0) continue;
                    output[index] = stylePixel(color, heights, x, z, terrainSlopes, profile);
                }
            }
            return output;
        }

        /**
         * Styles one 64x64 page directly from a 512x512 immutable region snapshot.
         */
        public static int[] colorizePage(int[] source, short[] heights, int pageX, int pageZ,
                int terrainSlopes, int profile, java.util.function.BooleanSupplier stillValid) {
            int pageSize = com.velorise.simplemap.client.MapPageLayout.PAGE_SIZE;
            int[] output = new int[pageSize * pageSize];
            int startX = pageX * pageSize;
            int startZ = pageZ * pageSize;

            for (int localZ = 0; localZ < pageSize; localZ++) {
                if ((localZ & 15) == 0 && !stillValid.getAsBoolean()) {
                    throw new java.util.concurrent.CancellationException("Stale cave page job");
                }
                int z = startZ + localZ;
                int sourceRow = z * SIZE;
                int outputRow = localZ * pageSize;
                for (int localX = 0; localX < pageSize; localX++) {
                    int x = startX + localX;
                    int color = source[sourceRow + x];
                    if (color == 0) continue;
                    output[outputRow + localX] = stylePixel(
                            color, heights, x, z, terrainSlopes, profile);
                }
            }
            return output;
        }

        private static int stylePixel(int color, short[] heights, int x, int z,
                int terrainSlopes, int profile) {
            float shade = terrainSlopes <= 0 || heights == null
                    ? 1.0f : shade(heights, x, z, terrainSlopes);
            int red = clamp(Math.round((color & 0xFF) * shade));
            int green = clamp(Math.round(((color >>> 8) & 0xFF) * shade));
            int blue = clamp(Math.round(((color >>> 16) & 0xFF) * shade));
            int styled = (color & 0xFF000000) | (blue << 16) | (green << 8) | red;
            return com.velorise.simplemap.client.color.SurfaceColorizer.MapColorProfile.apply(styled, profile);
        }

        private static float shade(short[] heights, int x, int z, int mode) {
            int index = z * SIZE + x;
            int center = heights[index];
            if (center == CaveMapManager.CaveRegion.NO_SURFACE) return 1.0f;
            int north = height(heights, x, z - 1, center);
            if (mode == 1) {
                int delta = center - north;
                return clamp(1.0f + delta * 0.045f, 0.70f, 1.20f);
            }
            int south = height(heights, x, z + 1, center);
            int west = height(heights, x - 1, z, center);
            int east = height(heights, x + 1, z, center);
            float dx = clamp((west - east) * 0.5f, -14.0f, 14.0f);
            float dz = clamp((north - south) * 0.5f, -14.0f, 14.0f);
            int rim = Math.max(Math.max(north, south), Math.max(west, east));
            int floor = Math.min(Math.min(north, south), Math.min(west, east));
            float directional = dx * 0.043f + dz * 0.060f;
            float pit = Math.min(0.38f, Math.max(0, rim - center) * 0.060f);
            float ridge = Math.min(0.10f, Math.max(0, center - floor) * 0.018f);
            float edge = Math.min(0.18f,
                    (Math.abs(west - east) + Math.abs(north - south)) * 0.012f);
            float depthOcclusion = multiScaleDepthOcclusion(heights, x, z, center);
            return clamp(1.0f + directional + ridge - pit - edge - depthOcclusion, 0.46f, 1.24f);
        }

        private static float multiScaleDepthOcclusion(short[] heights, int x, int z, int center) {
            float shadow = depthAtRadius(heights, x, z, center, 2, 0.018f)
                    + depthAtRadius(heights, x, z, center, 8, 0.010f)
                    + depthAtRadius(heights, x, z, center, 24, 0.0035f);
            return Math.min(0.24f, shadow);
        }

        private static float depthAtRadius(short[] heights, int x, int z,
                int center, int radius, float weight) {
            int sum = Math.max(0, height(heights, x - radius, z, center) - center)
                    + Math.max(0, height(heights, x + radius, z, center) - center)
                    + Math.max(0, height(heights, x, z - radius, center) - center)
                    + Math.max(0, height(heights, x, z + radius, center) - center)
                    + Math.max(0, height(heights, x - radius, z - radius, center) - center)
                    + Math.max(0, height(heights, x + radius, z - radius, center) - center)
                    + Math.max(0, height(heights, x - radius, z + radius, center) - center)
                    + Math.max(0, height(heights, x + radius, z + radius, center) - center);
            return Math.min(0.16f, (sum * 0.125f) * weight);
        }

        private static int height(short[] heights, int x, int z, int fallback) {
            if (x < 0 || x >= SIZE || z < 0 || z >= SIZE) return fallback;
            int value = heights[z * SIZE + x];
            return value == CaveMapManager.CaveRegion.NO_SURFACE ? fallback : value;
        }

        private static int clamp(int value) {
            return Math.max(0, Math.min(255, value));
        }

        private static float clamp(float value, float minimum, float maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }
    }

    /**
     * Direct in-memory pixel region manager for layered and full cave maps.
     */
    public static final class CaveMapManager {
        private static final CaveMapManager INSTANCE = new CaveMapManager();
        private static final int MAX_COMPAT_REGIONS = 128;
        private static final int SCDAT_MAGIC = 0x53434445; // 'SCDE' (dimension + block/biome palette unified cave archive)

        private final ExecutorService diskExecutor = Executors.newFixedThreadPool(
                Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)), r -> {
            Thread t = new Thread(r, "SimpleMap-CaveIO");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        private volatile File baseDirectory;
        private volatile File surfaceDirectory;
        private final Map<String, Boolean> mcaExistsCache = new ConcurrentHashMap<>();
        private final Map<String, Boolean> scdatExistsCache = new ConcurrentHashMap<>();
        private static final Map<String, Integer> BLOCK_UNTINTED_ARGB_CACHE = new ConcurrentHashMap<>();
        private static final Map<String, Byte> BLOCK_TINT_KIND_CACHE = new ConcurrentHashMap<>();
        private static final Map<String, Byte> BLOCK_KIND_CACHE = new ConcurrentHashMap<>();
        private static final Map<String, Byte> BLOCK_EMISSION_CACHE = new ConcurrentHashMap<>();
        private static final Map<String, int[]> BIOME_TINT_CACHE = new ConcurrentHashMap<>();
        private static volatile int cachedColourMode = -1;

        private static final byte TINT_NONE = 0;
        private static final byte TINT_GRASS = 1;
        private static final byte TINT_FOLIAGE = 2;
        private static final byte TINT_LEAVES_FOLIAGE = 3;

        private static final int DEFAULT_GRASS_TINT = 0xFF79C05A;
        private static final int DEFAULT_FOLIAGE_TINT = 0xFF59AE30;
        private static final int DEFAULT_WATER_TINT = 0xFF3F76E4;

        /** Cache phân loại block theo tên để tái sử dụng: false = solid terrain, true = passable/foliage */
        private static final Map<String, Boolean> FOLIAGE_KIND_CACHE = new ConcurrentHashMap<>();

        private static final class AnvilSliceBuffers {
            final short[] heights = new short[512 * 512];
            final int[] rawColors = new int[512 * 512];
            final byte[] kinds = new byte[512 * 512];
            final byte[] blockLights = new byte[512 * 512];
            final byte[] skyLights = new byte[512 * 512];
            final short[] blockIds = new short[512 * 512];
            final byte[] biomeIds = new byte[512 * 512];
            final int[] loadPixels = new int[512 * 512];
            final short[] loadHeights = new short[512 * 512];
            final short[] loadBlockIds = new short[512 * 512];
            final byte[] loadBiomeIds = new byte[512 * 512];
            final byte[] ioByteBuffer = new byte[65536];
            byte[] chunkDataBuffer = new byte[65536];
        }
        private static final ThreadLocal<AnvilSliceBuffers> SLICE_BUFFERS = ThreadLocal.withInitial(AnvilSliceBuffers::new);

        public static void clearColorCache() {
            BLOCK_UNTINTED_ARGB_CACHE.clear();
            BLOCK_TINT_KIND_CACHE.clear();
            BLOCK_KIND_CACHE.clear();
            BLOCK_EMISSION_CACHE.clear();
            BIOME_TINT_CACHE.clear();
        }

        public void invalidateStyle() {
            clearColorCache();
            List<CaveRegion> toReslice = new ArrayList<>();
            int currentLayer = activeLayerY;
            String dimId = currentDimensionId();
            synchronized (regions) {
                for (RegionHolder holder : regions.values()) {
                    CaveRegion reg = holder.region;
                    if (reg != null && !reg.isClosed()) {
                        reg.resetForStyleRefresh();
                        if (dimId.equals(reg.dimensionResourceId)) {
                            toReslice.add(reg);
                        }
                    }
                }
            }
            for (CaveRegion reg : toReslice) {
                int targetLayer = reg.band == Integer.MIN_VALUE
                        ? Integer.MIN_VALUE
                        : (currentLayer != Integer.MIN_VALUE
                                && CaveLayerBand.key(CaveView.LAYERED, currentLayer) == reg.band
                                ? currentLayer : reg.band);
                materialize(targetLayer, reg.rx, reg.rz, false, true);
            }
        }

        /** Kiểm tra xem block có phải là khối đặc thực sự của lòng đất (làm tắt shouldEnterGround ở Full Cave). */
        private static boolean isBlockPassable(String blockName) {
            return FOLIAGE_KIND_CACHE.computeIfAbsent(blockName, name -> {
                try {
                    net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(name);
                    if (rl == null) return false;
                    net.minecraft.world.level.block.Block blk = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(rl);
                    if (blk == null) return false;
                    net.minecraft.world.level.block.state.BlockState state = blk.defaultBlockState();
                    return !CaveMode.isUndergroundSolidRoof(state);
                } catch (Throwable ignored) {
                    return false;
                }
            });
        }

        private final AtomicLong layerGeneration = new AtomicLong(1L);
        private final Map<String, RegionHolder> regions = new LinkedHashMap<>(16, 0.75f, true);
        private final List<ProjectionCompletion> projectionCompletions = new ArrayList<>();
        private volatile int activeLayerY = Integer.MIN_VALUE;

        private CaveMapManager() {
        }

        public static CaveMapManager getInstance() {
            return INSTANCE;
        }

        public static final short NO_SURFACE = CaveRegion.NO_SURFACE;
        public static final int EPOCH = 11;
        public static final int LOD_BRANCH_VERSION = 20;

        public long getLayerGeneration() {
            return layerGeneration.get();
        }

        public long getGeneration() {
            return layerGeneration.get();
        }

        public boolean isGenerationCurrent(long value) {
            return layerGeneration.get() == value;
        }

        public boolean isLayerGenerationCurrent(long generation, int layerY) {
            if (layerY == Integer.MIN_VALUE) return generation == layerGeneration.get();
            return generation == layerGeneration.get() && layerY == activeLayerY;
        }

        public synchronized void setCacheDirectory(File directory) {
            setBaseDirectory(directory, null);
        }

        public synchronized void setBaseDirectory(File directory) {
            setBaseDirectory(directory, null);
        }

        private static String currentDimensionId() {
            String dim = MapManager.getInstance().getCurrentDimensionResourceId();
            return (dim == null || dim.isBlank()) ? "minecraft:overworld" : dim;
        }

        private static String regionCacheKey(String dimId, int band, int rx, int rz) {
            String safeDim = (dimId == null || dimId.isBlank()) ? "minecraft:overworld" : dimId;
            return safeDim + ":" + (band == Integer.MIN_VALUE ? "full" : String.valueOf(band)) + ":" + rx + "," + rz;
        }

        public synchronized void setBaseDirectory(File directory, File surfaceDirectory) {
            saveAllDirtyRegions();
            this.baseDirectory = directory;
            this.surfaceDirectory = surfaceDirectory;
            this.mcaExistsCache.clear();
            this.scdatExistsCache.clear();
            deferProjectionCompletions();
            layerGeneration.incrementAndGet();
        }

        public void notifyLayerActivated(int layerY) {
            String dimId = currentDimensionId();
            int band = layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY);
            List<CaveRegion> matching = new ArrayList<>();
            synchronized (regions) {
                for (RegionHolder holder : regions.values()) {
                    CaveRegion reg = holder.region;
                    if (reg != null && !reg.isClosed()
                            && dimId.equals(reg.dimensionResourceId)
                            && reg.band == band
                            && reg.hasContent()) {
                        matching.add(reg);
                    }
                }
            }
            for (CaveRegion reg : matching) {
                CaveTextureManager.getInstance().markRegionTextureDirty(layerY, reg.rx, reg.rz);
            }
        }

        public boolean hasAnyMcaFilesForCurrentDimension() {
            String dimId = currentDimensionId();
            synchronized (regions) {
                for (RegionHolder holder : regions.values()) {
                    CaveRegion reg = holder.region;
                    if (reg != null && !reg.isClosed()
                            && dimId.equals(reg.dimensionResourceId)
                            && reg.hasContent()) {
                        return true;
                    }
                }
            }
            File mcaDir = getMcaDirectory(dimId);
            if (mcaDir != null && mcaDir.isDirectory()) {
                String[] list = mcaDir.list();
                if (list != null) {
                    for (String name : list) {
                        if (name.endsWith(".mca")) return true;
                    }
                }
            }
            return false;
        }

        public synchronized void setActiveLayer(int layerY) {
            if (activeLayerY == layerY) return;
            activeLayerY = layerY;
            deferProjectionCompletions();
            layerGeneration.incrementAndGet();
            CaveTextureManager.getInstance().onLayerActivated(layerY);
            if (layerY != Integer.MIN_VALUE) {
                String dimId = currentDimensionId();
                int band = CaveLayerBand.key(CaveView.LAYERED, layerY);
                List<CaveRegion> toReslice = new ArrayList<>();
                synchronized (regions) {
                    for (RegionHolder holder : regions.values()) {
                        CaveRegion reg = holder.region;
                        if (reg != null && !reg.isClosed()
                                && dimId.equals(reg.dimensionResourceId)
                                && reg.band == band
                                && reg.isAnvilSliced() && reg.getWrittenAnvilLayerY() != layerY) {
                            toReslice.add(reg);
                        }
                    }
                }
                for (CaveRegion reg : toReslice) {
                    materialize(layerY, reg.rx, reg.rz, false, true);
                }
            }
        }

        public int getActiveLayerY() {
            return activeLayerY;
        }

        public void requestVisibleRegion(int layerY, int rx, int rz) {
            setActiveLayer(layerY);
            CaveTextureManager.getInstance().requestVisibleRegion(layerY, rx, rz);
            materialize(layerY, rx, rz, true, true);
        }

        public boolean requestVisibleRegion(MapWorkKey key, long revision, int layerY) {
            if (key == null || !isLayerGenerationCurrent(revision, layerY)) return false;
            requestVisibleRegion(layerY, key.regionX(), key.regionZ());
            MapWorkGraph.getInstance().complete(key, revision);
            return true;
        }

        public boolean requestRegionLoad(MapWorkKey key, long revision) {
            return requestVisibleRegion(key, revision, Integer.MIN_VALUE);
        }

        public void tickWorkGraphCompletions() {
            List<ProjectionCompletion> pending;
            synchronized (projectionCompletions) {
                if (projectionCompletions.isEmpty()) return;
                pending = new ArrayList<>(projectionCompletions);
                projectionCompletions.clear();
            }
            for (ProjectionCompletion completion : pending) {
                if (isLayerGenerationCurrent(completion.revision(), completion.layerY())) {
                    MapWorkGraph.getInstance().complete(completion.key(), completion.revision());
                } else {
                    MapWorkGraph.getInstance().defer(completion.key());
                }
            }
        }

        public void mergeCandidate(int blockX, int blockZ, int abgrColor,
                int surfaceY, int observationTopY) {
            setPixel(Integer.MIN_VALUE, blockX, blockZ, abgrColor, surfaceY, abgrColor != 0);
        }

        public void setColor(int blockX, int blockZ, int abgrColor) {
            setColor(activeLayerY, blockX, blockZ, abgrColor);
        }

        public void setColor(int layerY, int blockX, int blockZ, int abgrColor) {
            int existingHeight = getSurfaceY(layerY, blockX, blockZ);
            int heightToStore = abgrColor == 0 ? NO_SURFACE : existingHeight;
            setPixel(layerY, blockX, blockZ, abgrColor, heightToStore, abgrColor != 0);
        }

        public void setPixel(int layerY, int blockX, int blockZ, int abgrColor, int surfaceY, boolean markLive) {
            setPixel(layerY, blockX, blockZ, abgrColor, surfaceY, markLive, null, null);
        }

        public void setPixel(int layerY, int blockX, int blockZ, int abgrColor, int surfaceY, boolean markLive,
                String blockId, String biomeId) {
            int rx = blockX >> 9;
            int rz = blockZ >> 9;
            int px = blockX & 511;
            int pz = blockZ & 511;
            CaveRegion region = materialize(layerY, rx, rz, true);
            if (region != null && region.setPixel(px, pz, abgrColor, surfaceY, layerY, markLive, blockId, biomeId)) {
                markPageDirty(layerY, blockX >> 6, blockZ >> 6);
            }
        }

        public void setChunkPixels(int layerY, int chunkX, int chunkZ, int[] chunkColors, short[] chunkHeights) {
            setChunkPixels(layerY, chunkX, chunkZ, chunkColors, chunkHeights, null, null);
        }

        public void setChunkPixels(int layerY, int chunkX, int chunkZ, int[] chunkColors, short[] chunkHeights,
                String[] chunkBlockIds, String[] chunkBiomeIds) {
            int rx = chunkX >> 5;
            int rz = chunkZ >> 5;
            int cxInRegion = chunkX & 31;
            int czInRegion = chunkZ & 31;
            CaveRegion region = materialize(layerY, rx, rz, true);
            if (region != null) {
                boolean changed = region.setChunkPixels(cxInRegion, czInRegion, chunkColors, chunkHeights,
                        chunkBlockIds, chunkBiomeIds, layerY);
                if (changed || region.hasContent()) {
                    markPageDirty(layerY, chunkX >> 2, chunkZ >> 2);
                }
            }
        }

        public void markPageDirty(int layerY, int globalPageX, int globalPageZ) {
            CaveTextureManager.getInstance().markPageTextureDirty(layerY, globalPageX, globalPageZ);
        }

        public void markRegionDirty(int rx, int rz) {
            markRegionDirty(activeLayerY, rx, rz);
        }

        public void markRegionDirty(int layerY, int rx, int rz) {
            CaveTextureManager.getInstance().markRegionTextureDirty(layerY, rx, rz);
        }

        public int getColor(int blockX, int blockZ) {
            return getColor(activeLayerY, blockX, blockZ);
        }

        public int getColor(int layerY, int blockX, int blockZ) {
            int rx = blockX >> 9;
            int rz = blockZ >> 9;
            CaveRegion region = getRegion(layerY, rx, rz, false);
            if (region != null) {
                return region.getColor(blockX & 511, blockZ & 511);
            }
            return 0;
        }

        public int getSurfaceY(int blockX, int blockZ) {
            Minecraft mc = Minecraft.getInstance();
            int targetLayer = (mc != null && CaveMode.isFullView(mc)) ? Integer.MIN_VALUE : activeLayerY;
            return getSurfaceY(targetLayer, blockX, blockZ);
        }

        public int getSurfaceY(int layerY, int blockX, int blockZ) {
            int rx = blockX >> 9;
            int rz = blockZ >> 9;
            CaveRegion region = getRegion(layerY, rx, rz, false);
            if (region == null && hasRegionFile(layerY, rx, rz)) {
                region = materialize(layerY, rx, rz, true, true);
            }
            if (region != null && region.hasContent()) {
                return region.getHeight(blockX & 511, blockZ & 511);
            }
            return NO_SURFACE;
        }

        public String getBlockId(int layerY, int blockX, int blockZ) {
            int rx = blockX >> 9;
            int rz = blockZ >> 9;
            CaveRegion region = getRegion(layerY, rx, rz, false);
            if (region == null && hasRegionFile(layerY, rx, rz)) {
                region = materialize(layerY, rx, rz, true, true);
            }
            if (region != null && region.hasContent()) {
                return region.getBlockId(blockX & 511, blockZ & 511);
            }
            return null;
        }

        public String getBiomeId(int layerY, int blockX, int blockZ) {
            int rx = blockX >> 9;
            int rz = blockZ >> 9;
            CaveRegion region = getRegion(layerY, rx, rz, false);
            if (region == null && hasRegionFile(layerY, rx, rz)) {
                region = materialize(layerY, rx, rz, true, true);
            }
            if (region != null && region.hasContent()) {
                return region.getBiomeId(blockX & 511, blockZ & 511);
            }
            return null;
        }

        public CaveRegion getRegion(int rx, int rz, boolean loadIfMissing) {
            return materialize(activeLayerY, rx, rz, loadIfMissing);
        }

        public CaveRegion getRegion(int layerY, int rx, int rz, boolean loadIfMissing) {
            return materialize(layerY, rx, rz, loadIfMissing);
        }

        public boolean hasRegionFile(int rx, int rz) {
            return hasRegionFile(activeLayerY, rx, rz);
        }

        public boolean hasRegionFile(int layerY, int rx, int rz) {
            String dimId = currentDimensionId();
            int band = (layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY));
            String key = regionCacheKey(dimId, band, rx, rz);
            synchronized (regions) {
                RegionHolder holder = regions.get(key);
                if (holder != null) {
                    if (holder.region.hasContent()) return true;
                    if (holder.region.isLoaded()
                            && !holder.region.isAnvilSlicing()
                            && !holder.region.hasPendingAnvilSlice()) {
                        return false;
                    }
                }
            }
            Boolean cachedScdat = scdatExistsCache.get(key);
            if (cachedScdat == null) {
                File scdat = getRegionDiskFile(this.baseDirectory, band, rx, rz);
                cachedScdat = (scdat != null && scdat.exists());
                scdatExistsCache.put(key, cachedScdat);
            }
            if (cachedScdat) return true;

            String mcaKey = dimId + ":" + rx + "," + rz;
            Boolean cachedMca = mcaExistsCache.get(mcaKey);
            if (cachedMca != null) return cachedMca;

            File mcaDir = getMcaDirectory(dimId);
            if (mcaDir != null) {
                File mca = new File(mcaDir, "r." + rx + "." + rz + ".mca");
                boolean exists = mca.exists();
                mcaExistsCache.put(mcaKey, exists);
                return exists;
            }
            return false;
        }

        public boolean isRegionPending(int layerY, int rx, int rz) {
            if (CaveTextureManager.getInstance().hasResidentCoverageForRegion(layerY, rx, rz)) {
                return false;
            }
            String dimId = currentDimensionId();
            int band = (layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY));
            String key = regionCacheKey(dimId, band, rx, rz);
            synchronized (regions) {
                RegionHolder holder = regions.get(key);
                if (holder != null) {
                    CaveRegion reg = holder.region;
                    return reg.isAnvilSlicing() || reg.hasPendingAnvilSlice() || !reg.isLoaded();
                }
            }
            return hasRegionFile(layerY, rx, rz);
        }

        public boolean isChunkLiveAtLayer(int layerY, int chunkX, int chunkZ) {
            int rx = chunkX >> 5;
            int rz = chunkZ >> 5;
            CaveRegion reg = getRegion(layerY, rx, rz, false);
            if (reg == null) return false;
            return reg.isChunkLiveAtLayer(chunkX & 31, chunkZ & 31, layerY);
        }

        public boolean isRegionAnvilSlicedAtLayer(int layerY, int rx, int rz) {
            CaveRegion region = getRegion(layerY, rx, rz, false);
            if (region == null || !region.isAnvilSliced()) return false;
            return layerY == Integer.MIN_VALUE || region.getWrittenAnvilLayerY() == layerY;
        }

        public boolean isRegionLoaded(int rx, int rz) {
            return isRegionLoaded(activeLayerY, rx, rz);
        }

        public boolean isRegionLoaded(int layerY, int rx, int rz) {
            CaveRegion region = getRegion(layerY, rx, rz, false);
            return region != null && (region.isLoaded() || region.hasContent());
        }

        private final AtomicBoolean asyncSaveRunning = new AtomicBoolean(false);

        public void tickSave() {
            if (!asyncSaveRunning.compareAndSet(false, true)) return;
            diskExecutor.execute(() -> {
                try {
                    List<CaveRegion> dirtySnapshot = new ArrayList<>();
                    synchronized (regions) {
                        for (RegionHolder holder : regions.values()) {
                            if (holder.region.isDirty() && holder.region.hasContent()) {
                                holder.region.setDirty(false);
                                dirtySnapshot.add(holder.region);
                                if (dirtySnapshot.size() >= 2) break;
                            }
                        }
                    }
                    for (CaveRegion reg : dirtySnapshot) {
                        saveRegionToDisk(reg, reg.band, reg.rx, reg.rz);
                    }
                } finally {
                    asyncSaveRunning.set(false);
                }
            });
        }

        public synchronized void flushAndClear() {
            saveAllDirtyRegions();
            deferProjectionCompletions();
            clearCompatRegions();
            layerGeneration.incrementAndGet();
        }

        public synchronized void flushDataForDimensionSwitch() {
            saveAllDirtyRegions();
            deferProjectionCompletions();
            layerGeneration.incrementAndGet();
        }

        public synchronized void clearCache() {
            saveAllDirtyRegions();
            deferProjectionCompletions();
            clearCompatRegions();
            layerGeneration.incrementAndGet();
        }

        public synchronized void deactivate() {
            saveAllDirtyRegions();
        }

        public File getRegionDiskFile(int layerY, int rx, int rz) {
            return getRegionDiskFile(this.baseDirectory, layerY, rx, rz);
        }

        public File getRegionDiskFile(File targetBaseDir, int layerY, int rx, int rz) {
            if (targetBaseDir == null) return null;
            int band = (layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY));
            String folder = band == Integer.MIN_VALUE ? "full" : ("y_" + band);
            File bandDir = new File(targetBaseDir, folder);
            return new File(bandDir, "r." + rx + "." + rz + ".scdat");
        }

        private void saveRegionToDisk(CaveRegion region, int layerY, int rx, int rz) {
            if (region == null || !region.hasSavedContent() || region.regionBaseDir == null) return;
            int band = (layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY));
            File file = getRegionDiskFile(region.regionBaseDir, band, rx, rz);
            if (file == null) return;
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File temp = new File(parent, file.getName() + "." + System.nanoTime() + ".tmp");

            AnvilSliceBuffers buffers = SLICE_BUFFERS.get();
            int[] snapPixels = buffers.loadPixels;
            short[] snapHeights = buffers.loadHeights;
            short[] snapBlockIds = buffers.loadBlockIds;
            byte[] snapBiomeIds = buffers.loadBiomeIds;
            byte[] ioBuf = buffers.ioByteBuffer;
            boolean anvilSliced;
            int writtenLayerY;
            String[] snapBlockPalette;
            String[] snapBiomePalette;

            // Snapshot arrays under region.lock() in < 0.15 ms so GZIP compression
            // never holds region.lock() or blocks Render-thread texture uploads.
            region.lock();
            try {
                System.arraycopy(region.getPixelsDirect(), 0, snapPixels, 0, 512 * 512);
                System.arraycopy(region.getHeightsDirect(), 0, snapHeights, 0, 512 * 512);
                System.arraycopy(region.getBlockIdsDirect(), 0, snapBlockIds, 0, 512 * 512);
                System.arraycopy(region.getBiomeIdsDirect(), 0, snapBiomeIds, 0, 512 * 512);
                snapBlockPalette = region.snapshotBlockPaletteLocked();
                snapBiomePalette = region.snapshotBiomePaletteLocked();
                anvilSliced = region.hasAnvilSliceFlag();
                writtenLayerY = region.getWrittenAnvilLayerY();
            } finally {
                region.unlock();
            }

            try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(temp), 32768), 32768))) {
                dos.writeInt(SCDAT_MAGIC);
                dos.writeUTF(region.dimensionResourceId);
                dos.writeInt(band);
                dos.writeInt(rx);
                dos.writeInt(rz);
                dos.writeBoolean(anvilSliced);
                dos.writeInt(writtenLayerY);

                dos.writeInt(snapBlockPalette.length);
                for (String s : snapBlockPalette) {
                    dos.writeUTF(s != null ? s : "");
                }
                dos.writeInt(snapBiomePalette.length);
                for (String s : snapBiomePalette) {
                    dos.writeUTF(s != null ? s : "");
                }

                int idx = 0;
                while (idx < 512 * 512) {
                    int count = Math.min(16384, (512 * 512) - idx);
                    int bPos = 0;
                    for (int i = 0; i < count; i++) {
                        int p = snapPixels[idx++];
                        ioBuf[bPos++] = (byte) (p >>> 24);
                        ioBuf[bPos++] = (byte) (p >>> 16);
                        ioBuf[bPos++] = (byte) (p >>> 8);
                        ioBuf[bPos++] = (byte) p;
                    }
                    dos.write(ioBuf, 0, bPos);
                }

                idx = 0;
                while (idx < 512 * 512) {
                    int count = Math.min(32768, (512 * 512) - idx);
                    int bPos = 0;
                    for (int i = 0; i < count; i++) {
                        short h = snapHeights[idx++];
                        ioBuf[bPos++] = (byte) (h >>> 8);
                        ioBuf[bPos++] = (byte) h;
                    }
                    dos.write(ioBuf, 0, bPos);
                }

                idx = 0;
                while (idx < 512 * 512) {
                    int count = Math.min(32768, (512 * 512) - idx);
                    int bPos = 0;
                    for (int i = 0; i < count; i++) {
                        short bId = snapBlockIds[idx++];
                        ioBuf[bPos++] = (byte) (bId >>> 8);
                        ioBuf[bPos++] = (byte) bId;
                    }
                    dos.write(ioBuf, 0, bPos);
                }

                dos.write(snapBiomeIds, 0, 512 * 512);
                dos.flush();
            } catch (Throwable t) {
                temp.delete();
                return;
            }
            if (file.exists()) file.delete();
            if (temp.renameTo(file)) {
                String key = regionCacheKey(region.dimensionResourceId, band, rx, rz);
                scdatExistsCache.put(key, true);
            } else {
                temp.delete();
            }
        }

        private boolean loadRegionFromDisk(CaveRegion region, int layerY, int rx, int rz) {
            if (region == null || region.regionBaseDir == null) return false;
            int band = (layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY));
            File file = getRegionDiskFile(region.regionBaseDir, band, rx, rz);
            if (file == null || !file.exists() || file.length() < 16) return false;
            AnvilSliceBuffers buffers = SLICE_BUFFERS.get();
            int[] tempPixels = buffers.loadPixels;
            short[] tempHeights = buffers.loadHeights;
            short[] tempBlockIds = buffers.loadBlockIds;
            byte[] tempBiomeIds = buffers.loadBiomeIds;
            byte[] ioBuf = buffers.ioByteBuffer;
            boolean anvilSliced;
            int savedAnvilLayerY;
            String[] loadedBlockPalette;
            String[] loadedBiomePalette;
            boolean hasContent = false;
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file), 32768), 32768))) {
                int magic = dis.readInt();
                if (magic != SCDAT_MAGIC) return false;
                String savedDim = dis.readUTF();
                if (!region.dimensionResourceId.equals(savedDim)) return false;
                int savedLayer = dis.readInt();
                if (savedLayer != band) return false;
                int savedRx = dis.readInt();
                int savedRz = dis.readInt();
                if (savedRx != rx || savedRz != rz) return false;
                anvilSliced = dis.readBoolean();
                savedAnvilLayerY = dis.readInt();

                int blockPalLen = dis.readInt();
                if (blockPalLen < 0 || blockPalLen > 65535) return false;
                loadedBlockPalette = new String[blockPalLen];
                for (int i = 0; i < blockPalLen; i++) {
                    loadedBlockPalette[i] = dis.readUTF();
                }

                int biomePalLen = dis.readInt();
                if (biomePalLen < 0 || biomePalLen > 255) return false;
                loadedBiomePalette = new String[biomePalLen];
                for (int i = 0; i < biomePalLen; i++) {
                    loadedBiomePalette[i] = dis.readUTF();
                }

                int idx = 0;
                while (idx < 512 * 512) {
                    int count = Math.min(16384, (512 * 512) - idx);
                    dis.readFully(ioBuf, 0, count * 4);
                    int bPos = 0;
                    for (int i = 0; i < count; i++) {
                        int c = ((ioBuf[bPos] & 0xFF) << 24)
                                | ((ioBuf[bPos + 1] & 0xFF) << 16)
                                | ((ioBuf[bPos + 2] & 0xFF) << 8)
                                | (ioBuf[bPos + 3] & 0xFF);
                        bPos += 4;
                        tempPixels[idx++] = c;
                        if (c != 0) hasContent = true;
                    }
                }

                idx = 0;
                while (idx < 512 * 512) {
                    int count = Math.min(32768, (512 * 512) - idx);
                    dis.readFully(ioBuf, 0, count * 2);
                    int bPos = 0;
                    for (int i = 0; i < count; i++) {
                        short h = (short) (((ioBuf[bPos] & 0xFF) << 8) | (ioBuf[bPos + 1] & 0xFF));
                        bPos += 2;
                        tempHeights[idx++] = h;
                    }
                }

                idx = 0;
                while (idx < 512 * 512) {
                    int count = Math.min(32768, (512 * 512) - idx);
                    dis.readFully(ioBuf, 0, count * 2);
                    int bPos = 0;
                    for (int i = 0; i < count; i++) {
                        short bId = (short) (((ioBuf[bPos] & 0xFF) << 8) | (ioBuf[bPos + 1] & 0xFF));
                        bPos += 2;
                        tempBlockIds[idx++] = bId;
                    }
                }

                dis.readFully(tempBiomeIds, 0, 512 * 512);
            } catch (Throwable t) {
                return false;
            }

            if (!hasContent) return false;

            int[] pixels = region.getPixelsDirect();
            short[] heights = region.getHeightsDirect();
            short[] blockIds = region.getBlockIdsDirect();
            byte[] biomeIds = region.getBiomeIdsDirect();
            region.lock();
            try {
                short[] blockRemap = new short[loadedBlockPalette.length + 1];
                for (int i = 0; i < loadedBlockPalette.length; i++) {
                    blockRemap[i + 1] = region.getOrAddBlockIndexLocked(loadedBlockPalette[i]);
                }
                byte[] biomeRemap = new byte[loadedBiomePalette.length + 1];
                for (int i = 0; i < loadedBiomePalette.length; i++) {
                    biomeRemap[i + 1] = region.getOrAddBiomeIndexLocked(loadedBiomePalette[i]);
                }
                for (int i = 0; i < 512 * 512; i++) {
                    if (!region.isLivePixelLocked(i)) {
                        pixels[i] = tempPixels[i];
                        heights[i] = tempHeights[i];
                        int bIdx = tempBlockIds[i] & 0xFFFF;
                        blockIds[i] = (bIdx > 0 && bIdx < blockRemap.length) ? blockRemap[bIdx] : 0;
                        int bioIdx = tempBiomeIds[i] & 0xFF;
                        biomeIds[i] = (bioIdx > 0 && bioIdx < biomeRemap.length) ? biomeRemap[bioIdx] : 0;
                    }
                }
                region.recomputePageContentMaskLocked();
                region.markHasContent();
                if (anvilSliced) {
                    region.setAnvilSliced(true);
                    region.setWrittenAnvilLayerY(savedAnvilLayerY);
                }
                region.markExactSnapshotLoaded();
            } finally {
                region.unlock();
            }
            return true;
        }

        private File getMcaDirectory() {
            return getMcaDirectory(currentDimensionId());
        }

        private File getMcaDirectory(String targetDimId) {
            Minecraft mc = Minecraft.getInstance();
            if (!mc.isLocalServer() || mc.getSingleplayerServer() == null) return null;
            try {
                var server = mc.getSingleplayerServer();
                var root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
                String currentDimId = (targetDimId == null || targetDimId.isBlank())
                        ? currentDimensionId() : targetDimId;
                if (currentDimId.equals("minecraft:overworld")) {
                    return root.resolve("region").toFile();
                } else if (currentDimId.equals("minecraft:the_nether")) {
                    return root.resolve("DIM-1").resolve("region").toFile();
                } else if (currentDimId.equals("minecraft:the_end")) {
                    return root.resolve("DIM1").resolve("region").toFile();
                } else {
                    ResourceLocation rl = ResourceLocation.tryParse(currentDimId);
                    if (rl != null) {
                        return root.resolve("dimensions")
                                .resolve(rl.getNamespace())
                                .resolve(rl.getPath())
                                .resolve("region").toFile();
                    }
                }
                return null;
            } catch (Throwable t) {
                return null;
            }
        }

        private void sliceAnvilRegion(CaveRegion region, int layerY, int rx, int rz) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || region == null) return;

            File mcaDir = region.mcaDir != null ? region.mcaDir : getMcaDirectory(region.dimensionResourceId);
            if (mcaDir == null) return;
            File mcaFile = new File(mcaDir, "r." + rx + "." + rz + ".mca");
            if (!mcaFile.exists() || mcaFile.length() < 8192) {
                region.setAnvilSliced(true);
                region.setWrittenAnvilLayerY(layerY);
                region.markExactSnapshotLoaded();
                return;
            }

            try (RandomAccessFile raf = new RandomAccessFile(mcaFile, "r")) {
                byte[] header = new byte[4096];
                raf.readFully(header);

                AnvilSliceBuffers buffers = SLICE_BUFFERS.get();
                short[] regionHeights = buffers.heights;
                Arrays.fill(regionHeights, CaveRegion.NO_SURFACE);
                int[] regionRawColors = buffers.rawColors;
                Arrays.fill(regionRawColors, 0);
                byte[] regionKinds = buffers.kinds;
                Arrays.fill(regionKinds, (byte) 0);
                byte[] regionBlockLights = buffers.blockLights;
                Arrays.fill(regionBlockLights, (byte) 0);
                byte[] regionSkyLights = buffers.skyLights;
                Arrays.fill(regionSkyLights, (byte) 0);
                short[] regionBlockIds = buffers.blockIds;
                Arrays.fill(regionBlockIds, (short) 0);
                byte[] regionBiomeIds = buffers.biomeIds;
                Arrays.fill(regionBiomeIds, (byte) 0);

                List<String> localBlockPalette = new ArrayList<>();
                Map<String, Short> localBlockMap = new HashMap<>();
                List<String> localBiomePalette = new ArrayList<>();
                Map<String, Byte> localBiomeMap = new HashMap<>();

                // Seed heights from already-live chunks so border slope calculations are seamless
                short[] existingHeights = region.getHeightsDirect();
                region.lock();
                try {
                    for (int cz = 0; cz < 32; cz++) {
                        for (int cx = 0; cx < 32; cx++) {
                            if (region.isChunkLiveAtLayerLocked(cx, cz, layerY)) {
                                int basePx = cx << 4;
                                int basePz = cz << 4;
                                for (int lz = 0; lz < 16; lz++) {
                                    int srcOff = (basePz + lz) * 512 + basePx;
                                    System.arraycopy(existingHeights, srcOff, regionHeights, srcOff, 16);
                                }
                            }
                        }
                    }
                } finally {
                    region.unlock();
                }

                int baseChunkX = rx << 5;
                int baseChunkZ = rz << 5;
                boolean hasAnyChunk = false;

                for (int cz = 0; cz < 32; cz++) {
                    if (region.isClosed() || region.hasNewerPendingAnvilSlice(layerY)) return;
                    for (int cx = 0; cx < 32; cx++) {
                        if (region.isClosed() || region.hasNewerPendingAnvilSlice(layerY)) return;
                        if (region.isChunkLiveAtLayer(cx, cz, layerY)) {
                            hasAnyChunk = true;
                            continue;
                        }
                        int chunkIdx = (cz << 5) | cx;
                        int offset = chunkIdx * 4;
                        int sectorOffset = ((header[offset] & 0xFF) << 16) | ((header[offset + 1] & 0xFF) << 8) | (header[offset + 2] & 0xFF);
                        int sectorCount = header[offset + 3] & 0xFF;
                        if (sectorOffset < 2 || sectorCount == 0) continue;

                        long byteOffset = (long) sectorOffset * 4096L;
                        if (byteOffset + 5 > raf.length()) continue;

                        raf.seek(byteOffset);
                        int length = raf.readInt();
                        if (length <= 1 || length > sectorCount * 4096) continue;

                        byte compressionType = raf.readByte();
                        int dataLength = length - 1;
                        if (buffers.chunkDataBuffer.length < dataLength) {
                            buffers.chunkDataBuffer = new byte[Math.max(dataLength, buffers.chunkDataBuffer.length * 2)];
                        }
                        byte[] data = buffers.chunkDataBuffer;
                        raf.readFully(data, 0, dataLength);

                        InputStream in;
                        if (compressionType == 2) {
                            in = new InflaterInputStream(new ByteArrayInputStream(data, 0, dataLength));
                        } else if (compressionType == 1) {
                            in = new GZIPInputStream(new ByteArrayInputStream(data, 0, dataLength));
                        } else if (compressionType == 3) {
                            in = new ByteArrayInputStream(data, 0, dataLength);
                        } else {
                            continue;
                        }

                        CompoundTag tag;
                        try (DataInputStream dis = new DataInputStream(in)) {
                            tag = net.minecraft.nbt.NbtIo.read(dis, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                        } catch (Throwable t) {
                            continue;
                        }

                        if (tag != null) {
                            int chunkX = baseChunkX + cx;
                            int chunkZ = baseChunkZ + cz;
                            if (sliceChunkNbt(region, regionHeights, regionRawColors, regionKinds,
                                    regionBlockLights, regionSkyLights, regionBlockIds, regionBiomeIds,
                                    localBlockPalette, localBlockMap, localBiomePalette, localBiomeMap,
                                    tag, cx, cz, chunkX, chunkZ, layerY, mc.level)) {
                                hasAnyChunk = true;
                            }
                        }
                    }
                }

                if (!hasAnyChunk) {
                    region.setAnvilSliced(true);
                    region.setWrittenAnvilLayerY(layerY);
                    region.markExactSnapshotLoaded();
                    return;
                }

                // Propagate 0..15 block-light levels (using Math.max, never compounding RGB!)
                // so unlit .mca sections near glowing blocks get natural single-pass lighting
                // identical to live chunks near the player.
                propagateAnvilBlockLight(regionBlockLights, regionHeights, regionKinds);

                int[] regPixels = region.getPixelsDirect();
                short[] regHeights = region.getHeightsDirect();
                short[] regBlockIds = region.getBlockIdsDirect();
                byte[] regBiomeIds = region.getBiomeIdsDirect();
                boolean modified = false;
                int worldBaseX = rx << 9;
                int worldBaseZ = rz << 9;

                region.lock();
                try {
                    short[] blockRemap = new short[localBlockPalette.size() + 1];
                    for (int i = 0; i < localBlockPalette.size(); i++) {
                        blockRemap[i + 1] = region.getOrAddBlockIndexLocked(localBlockPalette.get(i));
                    }
                    byte[] biomeRemap = new byte[localBiomePalette.size() + 1];
                    for (int i = 0; i < localBiomePalette.size(); i++) {
                        biomeRemap[i + 1] = region.getOrAddBiomeIndexLocked(localBiomePalette.get(i));
                    }

                    for (int pz = 0; pz < 512; pz++) {
                        int rowOffset = pz * 512;
                        for (int px = 0; px < 512; px++) {
                            int index = rowOffset + px;
                            if (region.isChunkLiveAtLayerLocked(px >> 4, pz >> 4, layerY)
                                    || region.isLivePixelAtLayerLocked(px, pz, layerY)) {
                                continue;
                            }
                            int rawColor = regionRawColors[index];
                            if (rawColor == 0) {
                                if (regPixels[index] != 0) {
                                    regPixels[index] = 0;
                                    regHeights[index] = CaveRegion.NO_SURFACE;
                                    regBlockIds[index] = 0;
                                    regBiomeIds[index] = 0;
                                    modified = true;
                                }
                                continue;
                            }
                            short surfaceY = regionHeights[index];
                            if (surfaceY == CaveRegion.NO_SURFACE) continue;

                            byte kind = regionKinds[index];
                            boolean isFluid = (kind == DecodedSection.KIND_WATER);
                            boolean isLeaves = (kind == DecodedSection.KIND_LEAVES);
                            boolean isGlowing = (kind == DecodedSection.KIND_LAVA || (kind & 0x40) != 0);
                            int blockLight = isGlowing ? 15 : (regionBlockLights[index] & 0xFF);
                            int skyLight = isGlowing ? 0 : (regionSkyLights[index] & 0xFF);
                            int shadeOffset = computeCaveVerticalOffset(surfaceY);
                            int litColor = applyCaveLighting(rawColor, blockLight, skyLight, shadeOffset, isGlowing);
                            float slope = isFluid ? 1.0f : calculateRegionCaveShade(regionHeights, px, pz, surfaceY);
                            int displayedColor = styleCavePixel(litColor, slope,
                                    worldBaseX + px, worldBaseZ + pz, surfaceY, isFluid, isLeaves, isGlowing);

                            regPixels[index] = displayedColor;
                            regHeights[index] = surfaceY;
                            int localBlk = regionBlockIds[index] & 0xFFFF;
                            regBlockIds[index] = (localBlk > 0 && localBlk < blockRemap.length) ? blockRemap[localBlk] : 0;
                            int localBio = regionBiomeIds[index] & 0xFF;
                            regBiomeIds[index] = (localBio > 0 && localBio < biomeRemap.length) ? biomeRemap[localBio] : 0;
                            modified = true;
                        }
                    }
                    if (modified) {
                        region.recomputePageContentMaskLocked();
                        region.markHasContent();
                        region.setDirty(true);
                    }
                    region.setAnvilSliced(true);
                    region.setWrittenAnvilLayerY(layerY);
                    region.markExactSnapshotLoaded();
                } finally {
                    region.unlock();
                }
            } catch (Throwable ignored) {
            }
        }

        private static short internLocalBlock(String blockName, List<String> palette, Map<String, Short> map) {
            if (blockName == null || blockName.isEmpty()) return 0;
            Short existing = map.get(blockName);
            if (existing != null) return existing;
            if (palette.size() >= 65534) return (short) 65534;
            palette.add(blockName);
            short idx = (short) palette.size();
            map.put(blockName, idx);
            return idx;
        }

        private static byte internLocalBiome(String biomeName, List<String> palette, Map<String, Byte> map) {
            if (biomeName == null || biomeName.isEmpty()) return 0;
            Byte existing = map.get(biomeName);
            if (existing != null) return existing;
            if (palette.size() >= 254) return (byte) 254;
            palette.add(biomeName);
            byte idx = (byte) palette.size();
            map.put(biomeName, idx);
            return idx;
        }

        private static void propagateAnvilBlockLight(byte[] regionBlockLights, short[] regionHeights, byte[] regionKinds) {
            final int radius = 5;
            for (int pz = 0; pz < 512; pz++) {
                int row = pz * 512;
                for (int px = 0; px < 512; px++) {
                    int idx = row + px;
                    byte kind = regionKinds[idx];
                    boolean isGlowing = (kind == DecodedSection.KIND_LAVA || (kind & 0x40) != 0);
                    if (!isGlowing) continue;
                    short srcY = regionHeights[idx];
                    if (srcY == CaveRegion.NO_SURFACE) continue;
                    int srcLight = Math.max(14, regionBlockLights[idx] & 0xFF);
                    int minZ = Math.max(0, pz - radius);
                    int maxZ = Math.min(511, pz + radius);
                    int minX = Math.max(0, px - radius);
                    int maxX = Math.min(511, px + radius);
                    for (int nz = minZ; nz <= maxZ; nz++) {
                        int dz = Math.abs(nz - pz);
                        int nRow = nz * 512;
                        for (int nx = minX; nx <= maxX; nx++) {
                            int dx = Math.abs(nx - px);
                            int dist = dx + dz;
                            if (dist == 0 || dist > radius) continue;
                            int nIdx = nRow + nx;
                            byte nKind = regionKinds[nIdx];
                            if (nKind == DecodedSection.KIND_LAVA || (nKind & 0x40) != 0) continue;
                            short nY = regionHeights[nIdx];
                            if (nY == CaveRegion.NO_SURFACE || Math.abs(nY - srcY) > 6) continue;
                            int candidateLight = srcLight - dist * 2;
                            if (candidateLight > (regionBlockLights[nIdx] & 0xFF)) {
                                regionBlockLights[nIdx] = (byte) candidateLight;
                            }
                        }
                    }
                }
            }
        }

        private boolean sliceChunkNbt(CaveRegion region, short[] regionHeights, int[] regionRawColors, byte[] regionKinds,
                byte[] regionBlockLights, byte[] regionSkyLights, short[] regionBlockIds, byte[] regionBiomeIds,
                List<String> localBlockPalette, Map<String, Short> localBlockMap,
                List<String> localBiomePalette, Map<String, Byte> localBiomeMap,
                CompoundTag tag, int cx, int cz, int chunkX, int chunkZ, int layerY, Level level) {
            if (!tag.contains("sections", Tag.TAG_LIST)) return false;
            ListTag sections = tag.getList("sections", Tag.TAG_COMPOUND);
            if (sections.isEmpty()) return false;

            DimensionMapProfile profile = region != null && region.dimensionProfile != null
                    ? region.dimensionProfile
                    : DimensionMapProfile.fallback(region != null ? region.dimensionResourceId : "minecraft:overworld");
            boolean hasCeiling = profile.hasCeiling();
            boolean hasSkyLight = profile.hasSkyLight() && !hasCeiling;
            boolean isCaveDim = hasCeiling || !profile.hasSkyLight()
                    || "minecraft:the_nether".equals(profile.resourceId());
            int worldMinY = profile.minY();
            int maxBuildY = profile.maxY();

            boolean isFullCave = (layerY == Integer.MIN_VALUE);
            int dimensionCeilingCap = hasCeiling ? Math.min(122, maxBuildY) : maxBuildY;
            int clampedLayerY = Math.max(worldMinY, Math.min(dimensionCeilingCap, layerY));
            int scanMaximum = isFullCave ? dimensionCeilingCap : clampedLayerY;
            int scanMinimum = isFullCave ? worldMinY : Math.max(worldMinY, clampedLayerY - 31);
            if (scanMaximum < scanMinimum) return false;
            int minSecY = Math.max(worldMinY, scanMinimum - 16) >> 4;
            int maxSecY = Math.min(maxBuildY, scanMaximum + 1) >> 4;
            int secSpan = maxSecY - minSecY + 1;
            if (secSpan <= 0 || secSpan > 64) return false;
            CompoundTag[] rawSectionTags = new CompoundTag[secSpan];
            DecodedSection[] sectionArray = new DecodedSection[secSpan];
            boolean[] sectionDecoded = new boolean[secSpan];
            int highestPresentSecY = Integer.MIN_VALUE;

            for (int i = 0; i < sections.size(); i++) {
                CompoundTag secTag = sections.getCompound(i);
                int secY = secTag.getByte("Y");
                if (secY >= minSecY && secY <= maxSecY) {
                    rawSectionTags[secY - minSecY] = secTag;
                    if (secY > highestPresentSecY) {
                        highestPresentSecY = secY;
                    }
                }
            }
            if (highestPresentSecY == Integer.MIN_VALUE) return false;
            if (isFullCave) {
                scanMaximum = Math.min(scanMaximum, (highestPresentSecY << 4) + 15);
            }

            boolean chunkHadContent = false;

            int basePx = cx << 4;
            int basePz = cz << 4;

            for (int lz = 0; lz < 16; lz++) {
                int pzInRegion = basePz + lz;
                int rowOffset = pzInRegion * 512;
                for (int lx = 0; lx < 16; lx++) {
                    int pxInRegion = basePx + lx;
                    int regIdx = rowOffset + pxInRegion;

                    boolean shouldEnterGround = isFullCave && !isCaveDim;
                    boolean underair = isFullCave && !hasCeiling;
                    boolean passedSolidRoof = isFullCave || isCaveDim;

                    for (int y = scanMaximum; y >= scanMinimum; y--) {
                        int secY = y >> 4;
                        int secIdx = secY - minSecY;
                        DecodedSection sec = null;
                        if (secIdx >= 0 && secIdx < secSpan) {
                            if (!sectionDecoded[secIdx]) {
                                sectionDecoded[secIdx] = true;
                                CompoundTag rawTag = rawSectionTags[secIdx];
                                if (rawTag != null) {
                                    sectionArray[secIdx] = DecodedSection.decode(rawTag, level);
                                }
                            }
                            sec = sectionArray[secIdx];
                        }
                        if (sec == null || (sec.singleBlock && sec.paletteKinds[0] == DecodedSection.KIND_AIR)) {
                            if (!shouldEnterGround) {
                                underair = true;
                            }
                            int secBottom = secY << 4;
                            if (y > secBottom) {
                                y = Math.max(secBottom, scanMinimum);
                            }
                            continue;
                        }
                        if (sec.singleBlock && sec.paletteKinds[0] == DecodedSection.KIND_SOLID_GROUND) {
                            if (shouldEnterGround) {
                                shouldEnterGround = false;
                                underair = false;
                                passedSolidRoof = true;
                                int secBottom = secY << 4;
                                if (y > secBottom) {
                                    y = Math.max(secBottom, scanMinimum);
                                }
                                continue;
                            } else if (!underair) {
                                passedSolidRoof = true;
                                int secBottom = secY << 4;
                                if (y > secBottom) {
                                    y = Math.max(secBottom, scanMinimum);
                                }
                                continue;
                            }
                        }
                        int kind = sec.getKind(lx, y & 15, lz);

                        // 1. In Full Cave, skip surface water, leaves, trees, glass roofs, and giant mushrooms
                        // until entering the first real underground solid terrain block (KIND_SOLID_GROUND).
                        if (shouldEnterGround) {
                            if (kind == DecodedSection.KIND_SOLID_GROUND) {
                                shouldEnterGround = false;
                                underair = false;
                                passedSolidRoof = true;
                            }
                            continue;
                        }

                        // 2. Ceiling-penetration phase (!underair):
                        if (!underair) {
                            if (kind == DecodedSection.KIND_AIR || kind == DecodedSection.KIND_INVISIBLE_PASS) {
                                underair = true;
                                continue;
                            }
                            // In Layered Cave, if scanMaximum starts inside open-sky surface foliage/trees
                            // (no solid roof above), treat it as open air immediately so tree canopies are never cut off.
                            if (!isFullCave && !passedSolidRoof
                                    && (kind == DecodedSection.KIND_LEAVES
                                            || kind == DecodedSection.KIND_PASSABLE_SOLID
                                            || kind == DecodedSection.KIND_THIN_FLOOR)) {
                                underair = true;
                            } else {
                                passedSolidRoof = true;
                                continue;
                            }
                        }

                        // 3. Inside cave/open space (underair == true): pass through air & invisible decorations.
                        if (kind == DecodedSection.KIND_AIR
                                || kind == DecodedSection.KIND_INVISIBLE_PASS
                                || (isFullCave && kind == DecodedSection.KIND_LEAVES)) {
                            continue;
                        }

                        boolean exposedToSky = hasSkyLight && !isFullCave && !passedSolidRoof;

                        // 4. Water column inside cave or surface layer (underair == true): resolve down to basin floor.
                        if (kind == DecodedSection.KIND_WATER) {
                            resolveWaterColumn(sectionArray, rawSectionTags, sectionDecoded, level, minSecY, secSpan, lx, lz, y, maxBuildY, scanMinimum,
                                    exposedToSky, hasSkyLight && !isFullCave, regionHeights, regionRawColors, regionKinds,
                                    regionBlockLights, regionSkyLights, regionBlockIds, regionBiomeIds,
                                    localBlockPalette, localBlockMap, localBiomePalette, localBiomeMap, regIdx);
                            chunkHadContent = true;
                            break;
                        }

                        // 5. Lava or fire inside cave (underair == true):
                        if (kind == DecodedSection.KIND_LAVA) {
                            int rawCol = sec.getColor(lx, y & 15, lz);
                            regionHeights[regIdx] = (short) y;
                            regionRawColors[regIdx] = rawCol;
                            regionBlockLights[regIdx] = 15;
                            regionSkyLights[regIdx] = 0;
                            regionKinds[regIdx] = DecodedSection.KIND_LAVA;
                            regionBlockIds[regIdx] = internLocalBlock(sec.getBlockName(lx, y & 15, lz), localBlockPalette, localBlockMap);
                            regionBiomeIds[regIdx] = internLocalBiome(sec.getBiomeName(lx, y & 15, lz), localBiomePalette, localBiomeMap);
                            chunkHadContent = true;
                            break;
                        }

                        // 6. Thin floor block (carpet, rail, redstone_wire, glow_lichen, snow, repeater, flower):
                        if (kind == DecodedSection.KIND_THIN_FLOOR) {
                            int belowY = y - 1;
                            int belowKind = DecodedSection.KIND_AIR;
                            if (belowY >= worldMinY) {
                                int bSecIdx = (belowY >> 4) - minSecY;
                                DecodedSection bSec = getOrDecodeSection(sectionArray, rawSectionTags, sectionDecoded, bSecIdx, secSpan, level);
                                belowKind = (bSec == null) ? DecodedSection.KIND_AIR : bSec.getKind(lx, belowY & 15, lz);
                            }
                            if (belowKind == DecodedSection.KIND_AIR || belowKind == DecodedSection.KIND_INVISIBLE_PASS) {
                                continue;
                            }
                            int rawCol = sec.getColor(lx, y & 15, lz);
                            int lightY = Math.min(maxBuildY, y + 1);
                            int lightSecIdx = (lightY >> 4) - minSecY;
                            DecodedSection lightSec = getOrDecodeSection(sectionArray, rawSectionTags, sectionDecoded, lightSecIdx, secSpan, level);
                            int blockLight = Math.max(
                                    lightSec != null ? lightSec.getBlockLight(lx, lightY & 15, lz) : 0,
                                    sec.getBlockLight(lx, y & 15, lz));
                            int skyLight = exposedToSky
                                    ? 15
                                    : (hasSkyLight && !isFullCave
                                            ? Math.max(
                                                    lightSec != null ? lightSec.getSkyLight(lx, lightY & 15, lz) : 0,
                                                    sec.getSkyLight(lx, y & 15, lz))
                                            : 0);
                            boolean isGlowing = sec.isGlowing(lx, y & 15, lz);
                            int effectiveY = Math.max(worldMinY, y - 1);
                            regionHeights[regIdx] = (short) effectiveY;
                            regionRawColors[regIdx] = rawCol;
                            regionBlockLights[regIdx] = (byte) (isGlowing ? 15 : blockLight);
                            regionSkyLights[regIdx] = (byte) skyLight;
                            regionKinds[regIdx] = (byte) (DecodedSection.KIND_SOLID_GROUND | (isGlowing ? 0x40 : 0));
                            regionBlockIds[regIdx] = internLocalBlock(sec.getBlockName(lx, y & 15, lz), localBlockPalette, localBlockMap);
                            regionBiomeIds[regIdx] = internLocalBiome(sec.getBiomeName(lx, y & 15, lz), localBiomePalette, localBiomeMap);
                            chunkHadContent = true;
                            break;
                        }

                        // 7. Solid floor block or tree canopy (KIND_SOLID_GROUND, KIND_PASSABLE_SOLID, KIND_LEAVES):
                        if (kind == DecodedSection.KIND_SOLID_GROUND
                                || kind == DecodedSection.KIND_PASSABLE_SOLID
                                || kind == DecodedSection.KIND_LEAVES) {
                            int rawCol = sec.getColor(lx, y & 15, lz);
                            boolean isGlowing = sec.isGlowing(lx, y & 15, lz);
                            int lightY = Math.min(maxBuildY, y + 1);
                            int lightSecIdx = (lightY >> 4) - minSecY;
                            DecodedSection lightSec = getOrDecodeSection(sectionArray, rawSectionTags, sectionDecoded, lightSecIdx, secSpan, level);
                            int blockLight = Math.max(
                                    lightSec != null ? lightSec.getBlockLight(lx, lightY & 15, lz) : 0,
                                    sec.getBlockLight(lx, y & 15, lz));
                            int skyLight = exposedToSky
                                    ? 15
                                    : (hasSkyLight && !isFullCave
                                            ? Math.max(
                                                    lightSec != null ? lightSec.getSkyLight(lx, lightY & 15, lz) : 0,
                                                    sec.getSkyLight(lx, y & 15, lz))
                                            : 0);

                            regionHeights[regIdx] = (short) y;
                            regionRawColors[regIdx] = rawCol;
                            regionBlockLights[regIdx] = (byte) (isGlowing ? 15 : blockLight);
                            regionSkyLights[regIdx] = (byte) skyLight;
                            byte baseKind = (kind == DecodedSection.KIND_LEAVES)
                                    ? DecodedSection.KIND_LEAVES
                                    : DecodedSection.KIND_SOLID_GROUND;
                            regionKinds[regIdx] = (byte) (baseKind | (isGlowing ? 0x40 : 0));
                            regionBlockIds[regIdx] = internLocalBlock(sec.getBlockName(lx, y & 15, lz), localBlockPalette, localBlockMap);
                            regionBiomeIds[regIdx] = internLocalBiome(sec.getBiomeName(lx, y & 15, lz), localBiomePalette, localBiomeMap);
                            chunkHadContent = true;
                            break;
                        }
                    }
                }
            }

            return chunkHadContent;
        }

        private static DecodedSection getOrDecodeSection(DecodedSection[] sectionArray, CompoundTag[] rawSectionTags,
                boolean[] sectionDecoded, int secIdx, int secSpan, Level level) {
            if (sectionArray == null || secIdx < 0 || secIdx >= secSpan) return null;
            if (!sectionDecoded[secIdx]) {
                sectionDecoded[secIdx] = true;
                CompoundTag raw = rawSectionTags[secIdx];
                if (raw != null) {
                    sectionArray[secIdx] = DecodedSection.decode(raw, level);
                }
            }
            return sectionArray[secIdx];
        }

        private static void resolveWaterColumn(DecodedSection[] sectionArray, CompoundTag[] rawSectionTags,
                boolean[] sectionDecoded, Level level, int minSecY, int secSpan, int lx, int lz,
                int waterSurfaceY, int maxBuildY, int scanMinimum, boolean exposedToSky, boolean sampleSkyLight,
                short[] regionHeights, int[] regionRawColors, byte[] regionKinds,
                byte[] regionBlockLights, byte[] regionSkyLights, short[] regionBlockIds, byte[] regionBiomeIds,
                List<String> localBlockPalette, Map<String, Short> localBlockMap,
                List<String> localBiomePalette, Map<String, Byte> localBiomeMap, int regIdx) {
            int floorY = CaveRegion.NO_SURFACE;
            int floorRawColor = 0;
            int depth = 1;
            String floorBlockName = "minecraft:water";

            int surfSecIdx = (waterSurfaceY >> 4) - minSecY;
            DecodedSection surfSec = getOrDecodeSection(sectionArray, rawSectionTags, sectionDecoded, surfSecIdx, secSpan, level);
            int waterTintArgb = surfSec != null ? surfSec.getWaterTint(lx, waterSurfaceY & 15, lz) : DEFAULT_WATER_TINT;
            String surfaceBiomeName = surfSec != null ? surfSec.getBiomeName(lx, waterSurfaceY & 15, lz) : null;

            for (int wy = waterSurfaceY - 1; wy >= scanMinimum; wy--) {
                int wSecY = wy >> 4;
                int wSecIdx = wSecY - minSecY;
                DecodedSection wSec = getOrDecodeSection(sectionArray, rawSectionTags, sectionDecoded, wSecIdx, secSpan, level);
                int wk = (wSec == null) ? DecodedSection.KIND_AIR : wSec.getKind(lx, wy & 15, lz);
                if (wk == DecodedSection.KIND_WATER) {
                    depth++;
                    continue;
                }
                if (wk == DecodedSection.KIND_AIR) {
                    break;
                }
                if (wk == DecodedSection.KIND_INVISIBLE_PASS) {
                    continue;
                }
                floorY = wy;
                floorRawColor = wSec.getColor(lx, wy & 15, lz);
                floorBlockName = wSec.getBlockName(lx, wy & 15, lz);
                if (surfaceBiomeName == null) {
                    surfaceBiomeName = wSec.getBiomeName(lx, wy & 15, lz);
                }
                break;
            }

            if (floorY == CaveRegion.NO_SURFACE) {
                floorY = Math.max(scanMinimum, waterSurfaceY - depth);
                floorRawColor = 0xFF636B5E;
                floorBlockName = "minecraft:gravel";
            }

            int waterBlended = blendCaveWater(floorRawColor, depth, waterTintArgb);

            int lightY = Math.min(maxBuildY, waterSurfaceY + 1);
            int lightSecY = lightY >> 4;
            int lightSecIdx = lightSecY - minSecY;
            DecodedSection lightSec = getOrDecodeSection(sectionArray, rawSectionTags, sectionDecoded, lightSecIdx, secSpan, level);
            int blockLight = (lightSec != null) ? lightSec.getBlockLight(lx, lightY & 15, lz) : 0;
            int skyLight = exposedToSky
                    ? 15
                    : ((sampleSkyLight && lightSec != null)
                            ? lightSec.getSkyLight(lx, lightY & 15, lz)
                            : 0);

            regionHeights[regIdx] = (short) floorY;
            regionRawColors[regIdx] = waterBlended;
            regionBlockLights[regIdx] = (byte) blockLight;
            regionSkyLights[regIdx] = (byte) skyLight;
            regionKinds[regIdx] = DecodedSection.KIND_WATER;
            regionBlockIds[regIdx] = internLocalBlock(floorBlockName, localBlockPalette, localBlockMap);
            regionBiomeIds[regIdx] = internLocalBiome(surfaceBiomeName, localBiomePalette, localBiomeMap);
        }

        public static int blendCaveWater(int floorAbgr, int depth) {
            return blendCaveWater(floorAbgr, depth, DEFAULT_WATER_TINT);
        }

        public static int blendCaveWater(int floorAbgr, int depth, int waterTintArgb) {
            if (floorAbgr == 0) floorAbgr = 0xFF636B5E;
            int floorR = floorAbgr & 0xFF;
            int floorG = (floorAbgr >>> 8) & 0xFF;
            int floorB = (floorAbgr >>> 16) & 0xFF;
            int floorArgb = 0xFF000000 | (floorR << 16) | (floorG << 8) | floorB;
            int tintArgb = (waterTintArgb == 0 || waterTintArgb == -1) ? DEFAULT_WATER_TINT : (0xFF000000 | (waterTintArgb & 0x00FFFFFF));
            int composedArgb = SurfaceColorizer.composeWaterOverlay(
                    tintArgb, floorArgb, Math.max(1, depth), MapConfig.blockColourMode == 1);
            int r = (composedArgb >>> 16) & 0xFF;
            int g = (composedArgb >>> 8) & 0xFF;
            int b = composedArgb & 0xFF;
            return 0xFF000000 | (b << 16) | (g << 8) | r;
        }

        private static int[] resolveBiomeTints(String biomeName, Level level) {
            if (biomeName == null || biomeName.isEmpty()) {
                return new int[]{ DEFAULT_GRASS_TINT, DEFAULT_FOLIAGE_TINT, DEFAULT_WATER_TINT };
            }
            int[] cached = BIOME_TINT_CACHE.get(biomeName);
            if (cached != null) return cached;
            int[] resolved = new int[]{ DEFAULT_GRASS_TINT, DEFAULT_FOLIAGE_TINT, DEFAULT_WATER_TINT };
            try {
                if (level != null) {
                    ResourceLocation rl = ResourceLocation.tryParse(biomeName);
                    if (rl != null) {
                        var reg = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
                        net.minecraft.world.level.biome.Biome biome = reg.get(rl);
                        if (biome != null) {
                            resolved[0] = SurfaceColorizer.BiomeColors.getGrassColor(biome);
                            resolved[1] = SurfaceColorizer.BiomeColors.getFoliageColor(biome);
                            resolved[2] = SurfaceColorizer.BiomeColors.getWaterColor(biome);
                            BIOME_TINT_CACHE.put(biomeName, resolved);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return resolved;
        }

        private static final class DecodedSection {
            static final byte KIND_AIR = 0;
            static final byte KIND_SOLID_GROUND = 1;
            static final byte KIND_WATER = 2;
            static final byte KIND_LAVA = 3;
            static final byte KIND_PASSABLE_SOLID = 4;
            static final byte KIND_THIN_FLOOR = 5;
            static final byte KIND_INVISIBLE_PASS = 6;
            static final byte KIND_LEAVES = 7;

            final String[] paletteBlockNames;
            final int[] paletteUntintedArgb;
            final byte[] paletteTintKinds;
            final byte[] paletteKinds;
            final byte[] paletteEmissions;
            final byte[] blockLight;
            final byte[] skyLight;
            final long[] data;
            final int bitsPerEntry;
            final int valuesPerLong;
            final long mask;
            final boolean singleBlock;

            final String[] biomeNames;
            final int[] biomeGrassTints;
            final int[] biomeFoliageTints;
            final int[] biomeWaterTints;
            final long[] biomeData;
            final int biomeBits;
            final int biomesPerLong;
            final long biomeMask;
            final boolean singleBiome;

            private DecodedSection(String[] paletteBlockNames, int[] paletteUntintedArgb, byte[] paletteTintKinds,
                    byte[] paletteKinds, byte[] paletteEmissions,
                    byte[] blockLight, byte[] skyLight,
                    long[] data, int bitsPerEntry, int valuesPerLong, long mask, boolean singleBlock,
                    String[] biomeNames, int[] biomeGrassTints, int[] biomeFoliageTints, int[] biomeWaterTints,
                    long[] biomeData, int biomeBits, int biomesPerLong, long biomeMask, boolean singleBiome) {
                this.paletteBlockNames = paletteBlockNames;
                this.paletteUntintedArgb = paletteUntintedArgb;
                this.paletteTintKinds = paletteTintKinds;
                this.paletteKinds = paletteKinds;
                this.paletteEmissions = paletteEmissions;
                this.blockLight = blockLight;
                this.skyLight = skyLight;
                this.data = data;
                this.bitsPerEntry = bitsPerEntry;
                this.valuesPerLong = valuesPerLong;
                this.mask = mask;
                this.singleBlock = singleBlock;
                this.biomeNames = biomeNames;
                this.biomeGrassTints = biomeGrassTints;
                this.biomeFoliageTints = biomeFoliageTints;
                this.biomeWaterTints = biomeWaterTints;
                this.biomeData = biomeData;
                this.biomeBits = biomeBits;
                this.biomesPerLong = biomesPerLong;
                this.biomeMask = biomeMask;
                this.singleBiome = singleBiome;
            }

            static DecodedSection decode(CompoundTag secTag, Level level) {
                if (!secTag.contains("block_states", Tag.TAG_COMPOUND)) return null;
                CompoundTag bs = secTag.getCompound("block_states");
                if (!bs.contains("palette", Tag.TAG_LIST)) return null;
                ListTag pal = bs.getList("palette", Tag.TAG_COMPOUND);
                int size = pal.size();
                if (size == 0) return null;

                byte[] blockLight = null;
                if (secTag.contains("BlockLight", Tag.TAG_BYTE_ARRAY)) {
                    byte[] raw = secTag.getByteArray("BlockLight");
                    if (raw.length == 2048) {
                        blockLight = raw;
                    }
                }
                byte[] skyLight = null;
                if (secTag.contains("SkyLight", Tag.TAG_BYTE_ARRAY)) {
                    byte[] raw = secTag.getByteArray("SkyLight");
                    if (raw.length == 2048) {
                        skyLight = raw;
                    }
                }

                String[] blockNames = new String[size];
                int[] untintedArgb = new int[size];
                byte[] tintKinds = new byte[size];
                byte[] kinds = new byte[size];
                byte[] emissions = new byte[size];
                boolean needsBiomes = false;
                for (int i = 0; i < size; i++) {
                    String name = pal.getCompound(i).getString("Name");
                    blockNames[i] = name;
                    if (name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air")) {
                        kinds[i] = KIND_AIR;
                        untintedArgb[i] = 0;
                        tintKinds[i] = TINT_NONE;
                        emissions[i] = 0;
                    } else if (name.equals("minecraft:water") || name.equals("minecraft:bubble_column")) {
                        kinds[i] = KIND_WATER;
                        untintedArgb[i] = DEFAULT_WATER_TINT;
                        tintKinds[i] = TINT_NONE;
                        emissions[i] = 0;
                        needsBiomes = true;
                    } else if (name.equals("minecraft:lava") || name.equals("minecraft:fire") || name.equals("minecraft:soul_fire")) {
                        kinds[i] = KIND_LAVA;
                        untintedArgb[i] = resolveUntintedBlockArgb(name, null, null);
                        tintKinds[i] = TINT_NONE;
                        emissions[i] = 15;
                    } else {
                        byte classifiedKind = classifyBlockNameKind(name);
                        kinds[i] = classifiedKind;
                        if (classifiedKind == KIND_INVISIBLE_PASS) {
                            untintedArgb[i] = 0;
                            tintKinds[i] = TINT_NONE;
                        } else {
                            untintedArgb[i] = resolveUntintedBlockArgb(name, null, null);
                            byte tk = resolveBlockTintKind(name, null, null);
                            tintKinds[i] = tk;
                            if (tk != TINT_NONE) {
                                needsBiomes = true;
                            }
                        }
                        emissions[i] = resolveBlockNameEmission(name);
                    }
                }

                // Always decode 4x4x4 section biome palette names for cursor biome inspection;
                // only resolve grass/foliage/water RGB tints when needsBiomes is true.
                String[] bNames = null;
                int[] bGrass = null;
                int[] bFoliage = null;
                int[] bWater = null;
                long[] bData = null;
                int bBits = 0;
                int bPerLong = 1;
                long bMask = 0L;
                boolean singleBio = true;
                if (secTag.contains("biomes", Tag.TAG_COMPOUND)) {
                    CompoundTag bioTag = secTag.getCompound("biomes");
                    if (bioTag.contains("palette", Tag.TAG_LIST)) {
                        ListTag bioPal = bioTag.getList("palette", Tag.TAG_STRING);
                        int bSize = bioPal.size();
                        if (bSize > 0) {
                            bNames = new String[bSize];
                            if (needsBiomes) {
                                bGrass = new int[bSize];
                                bFoliage = new int[bSize];
                                bWater = new int[bSize];
                            }
                            for (int i = 0; i < bSize; i++) {
                                String bName = bioPal.getString(i);
                                bNames[i] = bName;
                                if (needsBiomes) {
                                    int[] tints = resolveBiomeTints(bName, level);
                                    bGrass[i] = tints[0];
                                    bFoliage[i] = tints[1];
                                    bWater[i] = tints[2];
                                }
                            }
                            if (bSize > 1 && bioTag.contains("data", Tag.TAG_LONG_ARRAY)) {
                                long[] rawBio = bioTag.getLongArray("data");
                                if (rawBio.length > 0) {
                                    bData = rawBio;
                                    bBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(bSize - 1));
                                    bPerLong = 64 / bBits;
                                    bMask = (1L << bBits) - 1L;
                                    singleBio = false;
                                }
                            }
                        }
                    }
                }
                if (bGrass == null) {
                    bGrass = new int[]{ DEFAULT_GRASS_TINT };
                    bFoliage = new int[]{ DEFAULT_FOLIAGE_TINT };
                    bWater = new int[]{ DEFAULT_WATER_TINT };
                }

                if (size == 1) {
                    return new DecodedSection(blockNames, untintedArgb, tintKinds, kinds, emissions, blockLight, skyLight,
                            null, 0, 0, 0L, true, bNames, bGrass, bFoliage, bWater, bData, bBits, bPerLong, bMask, singleBio);
                }

                long[] data = bs.getLongArray("data");
                if (data.length == 0) return null;
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(size - 1));
                int valuesPerLong = 64 / bits;
                long mask = (1L << bits) - 1L;
                return new DecodedSection(blockNames, untintedArgb, tintKinds, kinds, emissions, blockLight, skyLight,
                        data, bits, valuesPerLong, mask, false, bNames, bGrass, bFoliage, bWater, bData, bBits, bPerLong, bMask, singleBio);
            }

            private static byte classifyBlockNameKind(String name) {
                String cacheKey = MapConfig.displayFlowers ? name : (name + "#nf");
                Byte cached = BLOCK_KIND_CACHE.get(cacheKey);
                if (cached != null) return cached;
                byte resolved = computeBlockNameKind(name);
                BLOCK_KIND_CACHE.put(cacheKey, resolved);
                return resolved;
            }

            private static byte computeBlockNameKind(String name) {
                try {
                    ResourceLocation rl = ResourceLocation.tryParse(name);
                    if (rl != null) {
                        Block blk = BuiltInRegistries.BLOCK.get(rl);
                        if (blk != null) {
                            BlockState state = blk.defaultBlockState();
                            if (state.isAir()) return KIND_AIR;
                            if (state.getFluidState().is(FluidTags.WATER)) return KIND_WATER;
                            if (!state.getFluidState().isEmpty()) return KIND_LAVA;
                            if (CaveMode.isCaveOpenOrInvisible(state, MapConfig.displayFlowers)) {
                                return KIND_INVISIBLE_PASS;
                            }
                            if (state.is(BlockTags.LEAVES)) {
                                return KIND_LEAVES;
                            }
                            if (CaveMode.isShortFloorBlock(state)) {
                                return KIND_THIN_FLOOR;
                            }
                            if (CaveMode.isUndergroundSolidRoof(state)) {
                                return KIND_SOLID_GROUND;
                            }
                            return KIND_PASSABLE_SOLID;
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (name.endsWith("_leaves")) return KIND_LEAVES;
                return isBlockPassable(name) ? KIND_PASSABLE_SOLID : KIND_SOLID_GROUND;
            }

            private static byte resolveBlockNameEmission(String name) {
                Byte cached = BLOCK_EMISSION_CACHE.get(name);
                if (cached != null) return cached;
                byte resolved = computeBlockNameEmission(name);
                BLOCK_EMISSION_CACHE.put(name, resolved);
                return resolved;
            }

            private static byte computeBlockNameEmission(String name) {
                try {
                    ResourceLocation rl = ResourceLocation.tryParse(name);
                    if (rl != null) {
                        Block blk = BuiltInRegistries.BLOCK.get(rl);
                        if (blk != null) {
                            return (byte) Math.max(0, Math.min(15, blk.defaultBlockState().getLightEmission()));
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (name.contains("glowstone") || name.contains("shroomlight")
                        || name.contains("froglight") || name.contains("sea_lantern")
                        || name.contains("lantern") || name.contains("campfire")) return 15;
                if (name.contains("torch")) return 14;
                if (name.contains("crying_obsidian")) return 10;
                if (name.contains("glow_lichen")) return 7;
                return 0;
            }

            int getPaletteIndex(int lx, int ly, int lz) {
                if (singleBlock) return 0;
                int blockIdx = (ly << 8) | (lz << 4) | lx;
                int longIdx = blockIdx / valuesPerLong;
                if (longIdx < 0 || longIdx >= data.length) return 0;
                int bitOff = (blockIdx % valuesPerLong) * bitsPerEntry;
                int idx = (int) ((data[longIdx] >>> bitOff) & mask);
                return (idx >= 0 && idx < paletteKinds.length) ? idx : 0;
            }

            int getBiomePaletteIndex(int lx, int ly, int lz) {
                if (singleBiome || biomeData == null) return 0;
                int quartIdx = ((ly >> 2) << 4) | ((lz >> 2) << 2) | (lx >> 2);
                int longIdx = quartIdx / biomesPerLong;
                if (longIdx < 0 || longIdx >= biomeData.length) return 0;
                int bitOff = (quartIdx % biomesPerLong) * biomeBits;
                int idx = (int) ((biomeData[longIdx] >>> bitOff) & biomeMask);
                int maxLen = biomeNames != null ? biomeNames.length : biomeGrassTints.length;
                return (idx >= 0 && idx < maxLen) ? idx : 0;
            }

            String getBlockName(int lx, int ly, int lz) {
                int pIdx = getPaletteIndex(lx, ly, lz);
                return (paletteBlockNames != null && pIdx >= 0 && pIdx < paletteBlockNames.length)
                        ? paletteBlockNames[pIdx] : "minecraft:air";
            }

            String getBiomeName(int lx, int ly, int lz) {
                int bIdx = getBiomePaletteIndex(lx, ly, lz);
                return (biomeNames != null && bIdx >= 0 && bIdx < biomeNames.length)
                        ? biomeNames[bIdx] : null;
            }

            int getKind(int lx, int ly, int lz) {
                return paletteKinds[getPaletteIndex(lx, ly, lz)];
            }

            int getWaterTint(int lx, int ly, int lz) {
                int bIdx = getBiomePaletteIndex(lx, ly, lz);
                return (bIdx >= 0 && bIdx < biomeWaterTints.length) ? biomeWaterTints[bIdx] : DEFAULT_WATER_TINT;
            }

            int getColor(int lx, int ly, int lz) {
                int pIdx = getPaletteIndex(lx, ly, lz);
                int argb = paletteUntintedArgb[pIdx];
                if (argb == 0) return 0;
                byte tintKind = paletteTintKinds[pIdx];
                if (tintKind != TINT_NONE && MapConfig.blockColourMode == 0) {
                    int bIdx = getBiomePaletteIndex(lx, ly, lz);
                    if (tintKind == TINT_GRASS) {
                        argb = SurfaceColorizer.applyBiomeTint(argb, biomeGrassTints[bIdx], 0.90f);
                    } else if (tintKind == TINT_LEAVES_FOLIAGE) {
                        argb = SurfaceColorizer.applyBiomeTint(argb, biomeFoliageTints[bIdx], 0.95f);
                    } else if (tintKind == TINT_FOLIAGE) {
                        argb = SurfaceColorizer.applyBiomeTint(argb, biomeFoliageTints[bIdx], 0.92f);
                    }
                }
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                return 0xFF000000 | (b << 16) | (g << 8) | r;
            }

            boolean isGlowing(int lx, int ly, int lz) {
                int pIdx = getPaletteIndex(lx, ly, lz);
                return paletteEmissions != null && pIdx < paletteEmissions.length && (paletteEmissions[pIdx] & 0xFF) > 0;
            }

            int getBlockLight(int lx, int ly, int lz) {
                int pIdx = getPaletteIndex(lx, ly, lz);
                int emission = (paletteEmissions != null && pIdx < paletteEmissions.length) ? (paletteEmissions[pIdx] & 0xFF) : 0;
                if (blockLight == null || blockLight.length < 2048) return emission;
                int blockIdx = (ly << 8) | (lz << 4) | lx;
                int byteIdx = blockIdx >> 1;
                if (byteIdx < 0 || byteIdx >= blockLight.length) return emission;
                int b = blockLight[byteIdx] & 0xFF;
                int light = (blockIdx & 1) == 0 ? (b & 0x0F) : (b >>> 4);
                return Math.max(light, emission);
            }

            int getSkyLight(int lx, int ly, int lz) {
                if (skyLight == null || skyLight.length < 2048) return 0;
                int blockIdx = (ly << 8) | (lz << 4) | lx;
                int byteIdx = blockIdx >> 1;
                if (byteIdx < 0 || byteIdx >= skyLight.length) return 0;
                int b = skyLight[byteIdx] & 0xFF;
                return (blockIdx & 1) == 0 ? (b & 0x0F) : (b >>> 4);
            }
        }

        public static boolean requiresCaveBiomeTint(BlockState state) {
            if (state == null || state.isAir() || MapConfig.blockColourMode != 0) return false;
            SurfaceColorizer.MapVisualClassifier.VisualInfo visual =
                    SurfaceColorizer.MapVisualClassifier.getInstance().info(state);
            return resolveBlockTintKind(visual.blockId(), state, visual) != TINT_NONE;
        }

        public static int resolveCaveBlockBaseColor(BlockState state) {
            return resolveCaveBlockBaseColor(state, DEFAULT_GRASS_TINT, DEFAULT_FOLIAGE_TINT);
        }

        public static int resolveCaveBlockBaseColor(BlockState state, int grassTint, int foliageTint) {
            if (state == null || state.isAir()) return 0;
            SurfaceColorizer.MapVisualClassifier visualClassifier = SurfaceColorizer.MapVisualClassifier.getInstance();
            SurfaceColorizer.MapVisualClassifier.VisualInfo visual = visualClassifier.info(state);
            String name = visual.blockId();
            int argb = resolveUntintedBlockArgb(name, state, visual);
            if (argb == 0) return 0;
            byte tintKind = resolveBlockTintKind(name, state, visual);
            if (tintKind != TINT_NONE && MapConfig.blockColourMode == 0) {
                int gTint = (grassTint == 0 || grassTint == -1) ? DEFAULT_GRASS_TINT : grassTint;
                int fTint = (foliageTint == 0 || foliageTint == -1) ? DEFAULT_FOLIAGE_TINT : foliageTint;
                if (tintKind == TINT_GRASS) {
                    argb = SurfaceColorizer.applyBiomeTint(argb, gTint, 0.90f);
                } else if (tintKind == TINT_LEAVES_FOLIAGE) {
                    argb = SurfaceColorizer.applyBiomeTint(argb, fTint, 0.95f);
                } else if (tintKind == TINT_FOLIAGE) {
                    argb = SurfaceColorizer.applyBiomeTint(argb, fTint, 0.92f);
                }
            }
            int r = (argb >>> 16) & 0xFF;
            int g = (argb >>> 8) & 0xFF;
            int b = argb & 0xFF;
            return 0xFF000000 | (b << 16) | (g << 8) | r;
        }

        private static byte resolveBlockTintKind(String name, BlockState state,
                SurfaceColorizer.MapVisualClassifier.VisualInfo visual) {
            if (name == null || name.isEmpty()) return TINT_NONE;
            Byte cached = BLOCK_TINT_KIND_CACHE.get(name);
            if (cached != null) return cached;
            byte kind = TINT_NONE;
            try {
                if (state == null) {
                    ResourceLocation rl = ResourceLocation.tryParse(name);
                    if (rl != null) {
                        Block blk = BuiltInRegistries.BLOCK.get(rl);
                        if (blk != null) state = blk.defaultBlockState();
                    }
                }
                if (visual == null && state != null) {
                    visual = SurfaceColorizer.MapVisualClassifier.getInstance().info(state);
                }
                boolean fixedColor = (visual != null && visual.fixedTextureColor())
                        || (state != null && state.is(Blocks.CHERRY_LEAVES))
                        || name.contains("cherry");
                if (!fixedColor) {
                    boolean isLeaves = (visual != null && visual.leaves())
                            || (state != null && state.is(BlockTags.LEAVES))
                            || name.endsWith("_leaves");
                    BlockTintPolicy policy = visual != null ? visual.tintPolicy() : BlockTintPolicy.NONE;
                    if (policy == BlockTintPolicy.NONE) {
                        policy = MapTextureManager.getInstance().resolveTintPolicy(name);
                    }
                    if (policy == BlockTintPolicy.NONE) {
                        if (isLeaves || name.contains("vine")) {
                            policy = BlockTintPolicy.FOLIAGE;
                        } else if (name.contains("grass") || name.contains("fern")) {
                            policy = BlockTintPolicy.GRASS;
                        }
                    }
                    if (policy == BlockTintPolicy.GRASS) {
                        kind = TINT_GRASS;
                    } else if (policy == BlockTintPolicy.FOLIAGE
                            || policy == BlockTintPolicy.SPRUCE
                            || policy == BlockTintPolicy.BIRCH) {
                        kind = isLeaves ? TINT_LEAVES_FOLIAGE : TINT_FOLIAGE;
                    }
                }
            } catch (Throwable ignored) {
            }
            BLOCK_TINT_KIND_CACHE.put(name, kind);
            return kind;
        }

        private static int resolveUntintedBlockArgb(String name, BlockState state,
                SurfaceColorizer.MapVisualClassifier.VisualInfo visual) {
            if (name == null || name.isEmpty()) return 0xFF808080;
            int mode = MapConfig.blockColourMode;
            if (cachedColourMode != mode) {
                cachedColourMode = mode;
                BLOCK_UNTINTED_ARGB_CACHE.clear();
            }
            Integer override = MapConfig.blockColorOverrides.get(name);
            if (override != null) {
                return 0xFF000000 | (override & 0x00FFFFFF);
            }
            Integer cached = BLOCK_UNTINTED_ARGB_CACHE.get(name);
            if (cached != null) return cached;

            int argb = 0xFF808080;
            try {
                if (state == null) {
                    ResourceLocation rl = ResourceLocation.tryParse(name);
                    if (rl != null) {
                        Block blk = BuiltInRegistries.BLOCK.get(rl);
                        if (blk != null) state = blk.defaultBlockState();
                    }
                }
                if (visual == null && state != null) {
                    visual = SurfaceColorizer.MapVisualClassifier.getInstance().info(state);
                }
                boolean isLava = name.equals("minecraft:lava")
                        || (state != null && (state.is(Blocks.LAVA) || state.getFluidState().is(FluidTags.LAVA)));
                int emission = state != null ? state.getLightEmission() : DecodedSection.resolveBlockNameEmission(name);
                MapColor mapColor = state != null ? state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) : null;

                if (isLava) {
                    int sampled = MapTextureManager.getInstance().resolveBlockColor(name, mode);
                    int rgb = (sampled == 0 || sampled == 0xFFFFFFFF) ? 0xD46414 : (sampled & 0x00FFFFFF);
                    argb = 0xFF000000 | rgb;
                } else if (mode == 1 && mapColor != null && mapColor != MapColor.NONE) {
                    argb = 0xFF000000 | (mapColor.col & 0x00FFFFFF);
                } else {
                    int sampled = MapTextureManager.getInstance().resolveBlockColor(name, 0);
                    if (sampled == 0 || sampled == 0xFFFFFFFF) {
                        if (state != null && state.is(Blocks.CHERRY_LEAVES)) {
                            sampled = 0xFFE0A1B8;
                        } else if (mapColor != null && mapColor != MapColor.NONE) {
                            sampled = 0xFF000000 | (mapColor.col & 0x00FFFFFF);
                        } else {
                            sampled = 0xFF7F8588;
                        }
                    }
                    argb = 0xFF000000 | (sampled & 0x00FFFFFF);
                }

                if (!isLava && emission > 0) {
                    float boost = 1.0f + 0.15f * Math.min(15, emission) / 15.0f;
                    int r = Math.min(255, Math.round(((argb >>> 16) & 0xFF) * boost));
                    int g = Math.min(255, Math.round(((argb >>> 8) & 0xFF) * boost));
                    int b = Math.min(255, Math.round((argb & 0xFF) * boost));
                    argb = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            } catch (Throwable ignored) {
            }
            if ((argb == 0xFF808080 || argb == 0) && name.equals("minecraft:lava")) {
                argb = 0xFFD46414;
            }
            BLOCK_UNTINTED_ARGB_CACHE.put(name, argb);
            return argb;
        }

        private static int neighborRegionY(short[] heights, int px, int pz, int fallback) {
            if (px < 0 || px >= 512 || pz < 0 || pz >= 512) return fallback;
            short h = heights[pz * 512 + px];
            return h == CaveRegion.NO_SURFACE ? fallback : h;
        }

        private static float calculateRegionCaveShade(short[] heights, int px, int pz, int centerY) {
            int terrainSlopes = MapConfig.terrainSlopes;
            if (terrainSlopes <= 0) return 1.0f;
            int index = pz * 512 + px;
            int north = (pz > 0 && heights[index - 512] != CaveRegion.NO_SURFACE) ? heights[index - 512] : CaveRegion.NO_SURFACE;
            int south = (pz < 511 && heights[index + 512] != CaveRegion.NO_SURFACE) ? heights[index + 512] : CaveRegion.NO_SURFACE;
            int west = (px > 0 && heights[index - 1] != CaveRegion.NO_SURFACE) ? heights[index - 1] : CaveRegion.NO_SURFACE;
            int east = (px < 511 && heights[index + 1] != CaveRegion.NO_SURFACE) ? heights[index + 1] : CaveRegion.NO_SURFACE;

            if (north == CaveRegion.NO_SURFACE) {
                north = (south != CaveRegion.NO_SURFACE) ? (2 * centerY - south) : centerY;
            }
            if (south == CaveRegion.NO_SURFACE) {
                south = (north != CaveRegion.NO_SURFACE) ? (2 * centerY - north) : centerY;
            }
            if (west == CaveRegion.NO_SURFACE) {
                west = (east != CaveRegion.NO_SURFACE) ? (2 * centerY - east) : centerY;
            }
            if (east == CaveRegion.NO_SURFACE) {
                east = (west != CaveRegion.NO_SURFACE) ? (2 * centerY - west) : centerY;
            }

            if (terrainSlopes == 1) {
                int delta = centerY - north;
                float stepShadow = delta < 0 ? Math.max(-0.34f, delta * 0.060f)
                        : Math.min(0.20f, delta * 0.040f);
                return Math.max(0.58f, Math.min(1.28f, 1.0f + stepShadow));
            }
            float dx = Math.max(-16.0f, Math.min(16.0f, (west - east) * 0.5f));
            float dz = Math.max(-16.0f, Math.min(16.0f, (north - south) * 0.5f));
            float directional = dx * 0.045f + dz * 0.062f;

            int highest = Math.max(Math.max(north, south), Math.max(west, east));
            int lowest = Math.min(Math.min(north, south), Math.min(west, east));
            int dropBelowRim = Math.max(0, highest - centerY);
            int riseAboveNeighbours = Math.max(0, centerY - lowest);
            float pitShadow = Math.min(0.46f, dropBelowRim * 0.065f);
            float ridgeLight = Math.min(0.10f, riseAboveNeighbours * 0.018f);
            float roughness = Math.min(0.22f, (Math.abs(west - east) + Math.abs(north - south)) * 0.014f);

            int sumR2 = Math.max(0, neighborRegionY(heights, px - 2, pz, centerY) - centerY)
                    + Math.max(0, neighborRegionY(heights, px + 2, pz, centerY) - centerY)
                    + Math.max(0, neighborRegionY(heights, px, pz - 2, centerY) - centerY)
                    + Math.max(0, neighborRegionY(heights, px, pz + 2, centerY) - centerY)
                    + Math.max(0, neighborRegionY(heights, px - 2, pz - 2, centerY) - centerY)
                    + Math.max(0, neighborRegionY(heights, px + 2, pz - 2, centerY) - centerY)
                    + Math.max(0, neighborRegionY(heights, px - 2, pz + 2, centerY) - centerY)
                    + Math.max(0, neighborRegionY(heights, px + 2, pz + 2, centerY) - centerY);
            float depthOcclusion = Math.min(0.12f, (sumR2 * 0.125f) * 0.012f);

            float shade = 1.0f + directional + ridgeLight - pitShadow - roughness - depthOcclusion;
            return Math.max(0.42f, Math.min(1.28f, shade));
        }

        private static float caveMicroNoise(int worldX, int worldZ, int y, boolean isLeaves) {
            int regionLocalX = Math.floorMod(worldX, 512);
            int regionLocalZ = Math.floorMod(worldZ, 512);
            int linearIndex = regionLocalZ * 512 + regionLocalX;
            long hash = ((long) linearIndex * 312251L) ^ ((long) y * 4390321L);
            hash = (hash ^ (hash >>> 16)) * 0x85ebca6bL;
            hash = (hash ^ (hash >>> 13)) * 0xc2b2ae35L;
            float noise = ((hash & 0xFFFFL) / 65535.0f) * 2.0f - 1.0f;
            return isLeaves ? (noise * 0.075f) : (noise * 0.022f);
        }

        private static int caveAccurateFilter(int argb, boolean leaves, boolean fluid) {
            float red = ((argb >>> 16) & 0xFF) / 255.0f;
            float green = ((argb >>> 8) & 0xFF) / 255.0f;
            float blue = (argb & 0xFF) / 255.0f;

            float luminance = red * 0.2126f + green * 0.7152f + blue * 0.0722f;
            float saturation = fluid ? 1.00f : (leaves ? 1.04f : 1.02f);
            red = luminance + (red - luminance) * saturation;
            green = luminance + (green - luminance) * saturation;
            blue = luminance + (blue - luminance) * saturation;

            float gamma = fluid ? 1.04f : (leaves ? 1.14f : 1.10f);
            red = (float) Math.pow(Math.max(0.0f, Math.min(1.0f, red)), gamma);
            green = (float) Math.pow(Math.max(0.0f, Math.min(1.0f, green)), gamma);
            blue = (float) Math.pow(Math.max(0.0f, Math.min(1.0f, blue)), gamma);

            float contrast = fluid ? 1.02f : (leaves ? 1.08f : 1.05f);
            float brightness = fluid ? 0.97f : (leaves ? 0.90f : 0.94f);
            red = ((red - 0.5f) * contrast + 0.5f) * brightness;
            green = ((green - 0.5f) * contrast + 0.5f) * brightness;
            blue = ((blue - 0.5f) * contrast + 0.5f) * brightness;

            int r = Math.max(0, Math.min(255, Math.round(Math.max(0.0f, Math.min(1.0f, red)) * 255.0f)));
            int g = Math.max(0, Math.min(255, Math.round(Math.max(0.0f, Math.min(1.0f, green)) * 255.0f)));
            int b = Math.max(0, Math.min(255, Math.round(Math.max(0.0f, Math.min(1.0f, blue)) * 255.0f)));
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        public static int styleCavePixel(int litAbgr, float slopeShade,
                int worldX, int worldZ, int reliefY, boolean isFluid, boolean isLeaves) {
            return styleCavePixel(litAbgr, slopeShade, worldX, worldZ, reliefY, isFluid, isLeaves, false);
        }

        public static int styleCavePixel(int litAbgr, float slopeShade,
                int worldX, int worldZ, int reliefY, boolean isFluid, boolean isLeaves, boolean isGlowing) {
            if (litAbgr == 0) return 0;
            float shade;
            if (isFluid) {
                shade = 1.0f;
            } else if (isGlowing) {
                // Glowing block lighting: glowing blocks have ambientLightWhite = 1.0f,
                // ambientLightColored = 0.0f, maxDirectLight = 0.22222224f (never darkened by slope shadows).
                float highlight = slopeShade > 1.0f
                        ? Math.min(0.22222224f, (slopeShade - 1.0f) * 0.35f)
                        : 0.0f;
                shade = 1.0f + highlight;
            } else {
                shade = slopeShade * (1.0f + caveMicroNoise(worldX, worldZ, reliefY, isLeaves));
            }
            int red = Math.max(0, Math.min(255, Math.round((litAbgr & 0xFF) * shade)));
            int green = Math.max(0, Math.min(255, Math.round(((litAbgr >>> 8) & 0xFF) * shade)));
            int blue = Math.max(0, Math.min(255, Math.round(((litAbgr >>> 16) & 0xFF) * shade)));

            int argb = 0xFF000000 | (red << 16) | (green << 8) | blue;
            if (MapConfig.blockColourMode == 0 && !isGlowing) {
                argb = caveAccurateFilter(argb, isLeaves, isFluid);
            }
            int abgr = 0xFF000000 | ((argb & 0xFF) << 16) | (argb & 0x0000FF00) | ((argb >>> 16) & 0xFF);
            return SurfaceColorizer.MapColorProfile.apply(abgr, MapConfig.mapColorProfile);
        }

        public static int applyCaveLighting(int abgr, int light, int verticalOffset) {
            return applyCaveLighting(abgr, light, 0, verticalOffset, false);
        }

        public static int applyCaveLighting(int abgr, int light, int verticalOffset, boolean isGlowing) {
            return applyCaveLighting(abgr, light, 0, verticalOffset, isGlowing);
        }

        public static int applyCaveLighting(int abgr, int blockLight, int skyLight, int verticalOffset, boolean isGlowing) {
            int r0 = abgr & 0xFF;
            int g0 = (abgr >>> 8) & 0xFF;
            int b0 = (abgr >>> 16) & 0xFF;
            if (isGlowing) {
                // Emissive block color enhancement:
                // Glowing blocks boost dark textures proportionally to minBrightness = 407.0f,
                // set topLightValue = 15, and bypass depth/cave darkening completely.
                int total = r0 + g0 + b0;
                if (total > 0) {
                    float brightener = Math.max(1.0f, 407.0f / (float) total);
                    r0 = Math.max(0, Math.min(255, Math.round(r0 * brightener)));
                    g0 = Math.max(0, Math.min(255, Math.round(g0 * brightener)));
                    b0 = Math.max(0, Math.min(255, Math.round(b0 * brightener)));
                }
                return 0xFF000000 | (b0 << 16) | (g0 << 8) | r0;
            }
            int nightMode = MapConfig.minimapNightMode; // 0 = DAY/BRIGHT, 1 = AUTO, 2 = NIGHT/DARK
            int clampedBlock = Math.max(0, Math.min(15, blockLight));
            int clampedSky = Math.max(0, Math.min(15, skyLight));
            if (clampedSky >= 15 && clampedBlock <= 4 && nightMode == 0) {
                // Open-sky surface layer in LAYERED mode during Day mode:
                // preserve 100% of Surface mode's natural brightness.
                return abgr;
            }
            int adjustedSky = nightMode == 2 ? Math.round(clampedSky * 0.28f) : clampedSky;
            int effectiveLight = Math.max(clampedBlock, adjustedSky);
            float standardBlockBrightness = (9.0f + effectiveLight) / 24.0f;
            float heightShade = Math.max(0.92f, Math.min(1.05f, 1.0f + verticalOffset * 0.016f));
            float lightCurve;
            float minFloor;
            if (nightMode == 0) {
                // Day / Bright mode: lift dark cave rock so unlit passages are bright and easy to read
                lightCurve = 0.56f + 0.46f * standardBlockBrightness;
                minFloor = 0.72f;
            } else if (nightMode == 2) {
                // Night / Dark mode: darken unlit rock while keeping block-lit areas at 100% full brightness
                lightCurve = 0.10f + 0.90f * standardBlockBrightness;
                minFloor = 0.30f;
            } else {
                // Auto mode: balanced reference cave legibility curve
                lightCurve = 0.22f + 0.78f * standardBlockBrightness;
                minFloor = 0.48f;
            }
            float brightness = Math.max(minFloor, Math.min(1.06f, lightCurve * heightShade));

            int red = Math.round(r0 * brightness);
            int green = Math.round(g0 * brightness);
            int blue = Math.round(b0 * brightness);

            if (clampedBlock > 4 && clampedBlock >= clampedSky) {
                float warmth = Math.min(0.11f, ((clampedBlock - 4) / 11.0f) * 0.11f);
                red = Math.round(red + (255 - red) * warmth);
                green = Math.round(green + (190 - green) * warmth);
                blue = Math.round(blue + (90 - blue) * warmth);
            }
            red = Math.max(0, Math.min(255, red));
            green = Math.max(0, Math.min(255, green));
            blue = Math.max(0, Math.min(255, blue));
            return 0xFF000000 | (blue << 16) | (green << 8) | red;
        }

        private static boolean isCaveDimension(Level level) {
            return level != null && (level.dimension() == Level.NETHER
                    || level.dimensionType().hasCeiling()
                    || !level.dimensionType().hasSkyLight());
        }

        private void saveAllDirtyRegions() {
            List<CaveRegion> dirtySnapshot = new ArrayList<>();
            synchronized (regions) {
                for (RegionHolder holder : regions.values()) {
                    if (holder.region.isDirty() && holder.region.hasContent()) {
                        holder.region.setDirty(false);
                        dirtySnapshot.add(holder.region);
                    }
                }
            }
            if (dirtySnapshot.isEmpty()) return;
            diskExecutor.execute(() -> {
                for (CaveRegion reg : dirtySnapshot) {
                    saveRegionToDisk(reg, reg.band, reg.rx, reg.rz);
                }
            });
        }

        private CaveRegion materialize(int layerY, int rx, int rz, boolean createIfMissing) {
            return materialize(layerY, rx, rz, createIfMissing, false);
        }

        private CaveRegion materialize(int layerY, int rx, int rz, boolean createIfMissing, boolean allowAnvilSlice) {
            String dimId = currentDimensionId();
            int band = (layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY));
            String key = regionCacheKey(dimId, band, rx, rz);
            CaveRegion target;
            boolean startWorker = false;
            synchronized (regions) {
                RegionHolder holder = regions.get(key);
                if (holder != null) {
                    target = holder.region;
                } else {
                    if (!createIfMissing) return null;
                    if (allowAnvilSlice && !hasRegionFile(layerY, rx, rz)) {
                        return null;
                    }
                    File regBaseDir = this.baseDirectory;
                    File regMcaDir = getMcaDirectory(dimId);
                    DimensionMapProfile regProfile = MapManager.getInstance().getDimensionProfile(dimId);
                    target = new CaveRegion(rx, rz, band, layerGeneration.get(),
                            dimId, regBaseDir, regMcaDir, regProfile);
                    regions.put(key, new RegionHolder(target, 0L));
                    trimCompatRegions();
                }
                if (allowAnvilSlice) {
                    boolean needsSlice = !target.isAnvilSliced()
                            || (layerY != Integer.MIN_VALUE && target.getWrittenAnvilLayerY() != layerY);
                    if (needsSlice) {
                        target.requestAnvilSlice(layerY);
                    }
                }
                if (!target.isDiskChecked() || target.hasPendingAnvilSlice()) {
                    startWorker = target.tryBeginIo();
                }
            }

            if (startWorker) {
                CaveRegion asyncTarget = target;
                diskExecutor.execute(() -> runRegionIoWorker(asyncTarget, band, rx, rz, layerY));
            }
            return target;
        }

        private void runRegionIoWorker(CaveRegion asyncTarget, int band, int rx, int rz, int fallbackLayerY) {
            boolean keepRunning = true;
            while (keepRunning && !asyncTarget.isClosed()) {
                int dirtyLayerY = fallbackLayerY;
                try {
                    if (!asyncTarget.isDiskChecked()) {
                        if (loadRegionFromDisk(asyncTarget, band, rx, rz)) {
                            if (asyncTarget.dimensionResourceId.equals(currentDimensionId()) && !asyncTarget.isClosed()) {
                                CaveTextureManager.getInstance().markRegionTextureDirty(dirtyLayerY, rx, rz);
                            }
                        }
                        asyncTarget.markDiskChecked();
                    }
                    while (asyncTarget.hasPendingAnvilSlice() && !asyncTarget.isClosed()) {
                        int requestedLayer = asyncTarget.consumePendingAnvilSlice();
                        dirtyLayerY = requestedLayer;
                        boolean needsSlice = !asyncTarget.isAnvilSliced()
                                || (requestedLayer != Integer.MIN_VALUE
                                        && asyncTarget.getWrittenAnvilLayerY() != requestedLayer);
                        if (needsSlice && asyncTarget.markAnvilSlicing()) {
                            try {
                                sliceAnvilRegion(asyncTarget, requestedLayer, rx, rz);
                                if (asyncTarget.hasContent() && asyncTarget.isAnvilSliced()
                                        && (requestedLayer == Integer.MIN_VALUE
                                                || asyncTarget.getWrittenAnvilLayerY() == requestedLayer)) {
                                    asyncTarget.setDirty(false);
                                    saveRegionToDisk(asyncTarget, band, rx, rz);
                                    if (asyncTarget.dimensionResourceId.equals(currentDimensionId()) && !asyncTarget.isClosed()) {
                                        CaveTextureManager.getInstance().markRegionTextureDirty(requestedLayer, rx, rz);
                                    }
                                }
                            } finally {
                                asyncTarget.finishAnvilSlicing();
                            }
                        }
                    }
                    if (asyncTarget.hasContent()
                            && asyncTarget.dimensionResourceId.equals(currentDimensionId())
                            && !asyncTarget.isClosed()) {
                        CaveTextureManager.getInstance().markRegionTextureDirty(dirtyLayerY, rx, rz);
                    }
                } finally {
                    asyncTarget.markLoaded();
                    asyncTarget.endIo();
                }
                if (asyncTarget.hasPendingAnvilSlice() && !asyncTarget.isClosed()) {
                    keepRunning = asyncTarget.tryBeginIo();
                } else {
                    keepRunning = false;
                }
            }
        }

        private void trimCompatRegions() {
            while (regions.size() > MAX_COMPAT_REGIONS) {
                var iterator = regions.entrySet().iterator();
                if (!iterator.hasNext()) break;
                RegionHolder holder = iterator.next().getValue();
                iterator.remove();
                if (holder.region.isDirty() && holder.region.hasContent()) {
                    holder.region.setDirty(false);
                    CaveRegion toSave = holder.region;
                    diskExecutor.execute(() -> saveRegionToDisk(toSave, toSave.band, toSave.rx, toSave.rz));
                }
                holder.region.close();
            }
        }

        private void clearCompatRegions() {
            synchronized (regions) {
                for (RegionHolder holder : regions.values()) {
                    if (holder.region.isDirty() && holder.region.hasContent()) {
                        holder.region.setDirty(false);
                        CaveRegion toSave = holder.region;
                        diskExecutor.execute(() -> saveRegionToDisk(toSave, toSave.band, toSave.rx, toSave.rz));
                    }
                    holder.region.close();
                }
                regions.clear();
            }
        }

        private void deferProjectionCompletions() {
            List<ProjectionCompletion> pending;
            synchronized (projectionCompletions) {
                if (projectionCompletions.isEmpty()) return;
                pending = new ArrayList<>(projectionCompletions);
                projectionCompletions.clear();
            }
            for (ProjectionCompletion completion : pending) {
                MapWorkGraph.getInstance().defer(completion.key());
            }
        }

        private record RegionHolder(CaveRegion region, long sourceRevision) {
        }

        private record ProjectionCompletion(MapWorkKey key, long revision, int layerY) {
        }

        /** In-memory color and height region for one bounded cave Top-Y layer. */
        public static final class CaveRegion {
            public static final short NO_SURFACE = Short.MIN_VALUE;
            private static final short UNWRITTEN_LAYER = Short.MIN_VALUE;
            private static final short FULL_CAVE_LAYER_TAG = Short.MIN_VALUE + 1;

            public final int rx;
            public final int rz;
            public final int band;
            public final String dimensionResourceId;
            public final File regionBaseDir;
            public final File mcaDir;
            public final DimensionMapProfile dimensionProfile;
            private final long generation;
            private final int[] pixels = new int[512 * 512];
            private final short[] heights = new short[512 * 512];
            private final short[] blockIds = new short[512 * 512];
            private final byte[] biomeIds = new byte[512 * 512];
            private final List<String> blockPalette = new ArrayList<>();
            private final Map<String, Short> blockIndex = new HashMap<>();
            private final List<String> biomePalette = new ArrayList<>();
            private final Map<String, Byte> biomeIndex = new HashMap<>();
            /** Per-chunk (32x32) written Top-Y layer so intra-band layer changes trigger rescans. */
            private final short[] writtenChunkLayerY = new short[32 * 32];
            /** Pixels written from currently loaded Minecraft chunks; archive jobs may not replace them. */
            private final BitSet livePixels = new BitSet(512 * 512);
            private final BitSet liveChunks = new BitSet(32 * 32);
            private final ReentrantLock lock = new ReentrantLock();
            private volatile boolean loaded;
            private volatile boolean diskChecked;
            private volatile boolean exactSnapshot;
            private volatile boolean closed;
            private volatile boolean hasContent;
            private volatile long pageContentMask;
            private volatile boolean dirty;
            private volatile boolean anvilSliced;
            private volatile int writtenAnvilLayerY = Integer.MAX_VALUE;
            private volatile boolean pendingAnvilSlice;
            private volatile int pendingAnvilLayerY = Integer.MIN_VALUE;
            private final AtomicBoolean anvilSlicing = new AtomicBoolean(false);
            private final AtomicBoolean ioRunning = new AtomicBoolean(false);

            public CaveRegion(int rx, int rz) {
                this(rx, rz, Integer.MIN_VALUE, 0L);
            }

            public CaveRegion(int rx, int rz, int band) {
                this(rx, rz, band, 0L);
            }

            public CaveRegion(int rx, int rz, int band, long generation) {
                this(rx, rz, band, generation, "minecraft:overworld", null, null,
                        DimensionMapProfile.fallback("minecraft:overworld"));
            }

            public CaveRegion(int rx, int rz, int band, long generation,
                    String dimensionResourceId, File regionBaseDir, File mcaDir,
                    DimensionMapProfile dimensionProfile) {
                this.rx = rx;
                this.rz = rz;
                this.band = band;
                this.generation = generation;
                this.dimensionResourceId = (dimensionResourceId == null || dimensionResourceId.isBlank())
                        ? "minecraft:overworld" : dimensionResourceId;
                this.regionBaseDir = regionBaseDir;
                this.mcaDir = mcaDir;
                this.dimensionProfile = dimensionProfile != null
                        ? dimensionProfile : DimensionMapProfile.fallback(this.dimensionResourceId);
                Arrays.fill(this.heights, NO_SURFACE);
                Arrays.fill(this.writtenChunkLayerY, UNWRITTEN_LAYER);
            }

            private static short encodeLayerY(int layerY) {
                if (layerY == Integer.MIN_VALUE) return FULL_CAVE_LAYER_TAG;
                return (short) Math.max(-32760, Math.min(32760, layerY));
            }

            public int getBand() {
                return band;
            }

            public boolean isLoaded() { return loaded && !closed; }
            public boolean isDiskChecked() { return diskChecked; }
            public void markDiskChecked() { this.diskChecked = true; }
            public boolean hasExactSnapshot() { return exactSnapshot && !closed; }
            public boolean hasContent() { return hasContent && !closed; }
            public boolean hasSavedContent() { return hasContent; }

            public boolean hasNodeContent(int firstLocalPageX, int firstLocalPageZ, int pageSpan) {
                if (!hasContent || closed) return false;
                long mask = pageContentMask;
                if (mask == 0L) return false;
                int endX = Math.min(8, Math.max(0, firstLocalPageX) + Math.max(1, pageSpan));
                int endZ = Math.min(8, Math.max(0, firstLocalPageZ) + Math.max(1, pageSpan));
                for (int pz = Math.max(0, firstLocalPageZ); pz < endZ; pz++) {
                    for (int px = Math.max(0, firstLocalPageX); px < endX; px++) {
                        if ((mask & (1L << ((pz << 3) | px))) != 0L) {
                            return true;
                        }
                    }
                }
                return false;
            }

            void recomputePageContentMaskLocked() {
                long mask = 0L;
                for (int pageZ = 0; pageZ < 8; pageZ++) {
                    int baseZ = pageZ << 6;
                    for (int pageX = 0; pageX < 8; pageX++) {
                        int baseX = pageX << 6;
                        boolean pageHas = false;
                        for (int dz = 0; dz < 64 && !pageHas; dz++) {
                            int row = (baseZ + dz) * 512 + baseX;
                            for (int dx = 0; dx < 64; dx++) {
                                if (pixels[row + dx] != 0) {
                                    pageHas = true;
                                    break;
                                }
                            }
                        }
                        if (pageHas) {
                            mask |= (1L << ((pageZ << 3) | pageX));
                        }
                    }
                }
                pageContentMask = mask;
            }
            public boolean isDirty() { return dirty && !closed; }
            public void setDirty(boolean dirty) { this.dirty = dirty; }
            public void markLoaded() { if (!closed) loaded = true; }
            public void markExactSnapshotLoaded() {
                if (!closed) {
                    exactSnapshot = true;
                    loaded = true;
                }
            }
            public boolean isAnvilSliced() { return anvilSliced && !closed; }
            public boolean hasAnvilSliceFlag() { return anvilSliced; }
            public void setAnvilSliced(boolean sliced) { if (!closed) this.anvilSliced = sliced; }
            public int getWrittenAnvilLayerY() { return writtenAnvilLayerY; }
            public void setWrittenAnvilLayerY(int layerY) { this.writtenAnvilLayerY = layerY; }
            public boolean isAnvilSlicing() { return anvilSlicing.get() && !closed; }
            public boolean markAnvilSlicing() { return anvilSlicing.compareAndSet(false, true); }
            public void finishAnvilSlicing() { anvilSlicing.set(false); }
            public boolean tryBeginIo() { return ioRunning.compareAndSet(false, true); }
            public void endIo() { ioRunning.set(false); }
            public synchronized void requestAnvilSlice(int layerY) {
                this.pendingAnvilLayerY = layerY;
                this.pendingAnvilSlice = true;
            }
            public synchronized boolean hasPendingAnvilSlice() {
                return pendingAnvilSlice;
            }
            public synchronized boolean hasNewerPendingAnvilSlice(int currentLayerY) {
                return pendingAnvilSlice && pendingAnvilLayerY != currentLayerY;
            }
            public synchronized int consumePendingAnvilSlice() {
                this.pendingAnvilSlice = false;
                return this.pendingAnvilLayerY;
            }
            public void markHasContent() { if (!closed) this.hasContent = true; }
            public BitSet getLivePixelsDirect() { return livePixels; }
            public long getGeneration() { return generation; }
            public boolean isClosed() { return closed; }
            public void lock() { lock.lock(); }
            public void unlock() { lock.unlock(); }
            public void close() { closed = true; }
            public int[] getPixelsDirect() { return pixels; }
            public short[] getHeightsDirect() { return heights; }
            public short[] getBlockIdsDirect() { return blockIds; }
            public byte[] getBiomeIdsDirect() { return biomeIds; }

            short getOrAddBlockIndexLocked(String blockId) {
                if (blockId == null || blockId.isBlank() || "minecraft:air".equals(blockId)) return 0;
                Short existing = blockIndex.get(blockId);
                if (existing != null) return existing;
                if (blockPalette.size() >= 65534) return (short) 65534;
                blockPalette.add(blockId);
                short idx = (short) blockPalette.size();
                blockIndex.put(blockId, idx);
                return idx;
            }

            byte getOrAddBiomeIndexLocked(String biomeId) {
                if (biomeId == null || biomeId.isBlank()) return 0;
                Byte existing = biomeIndex.get(biomeId);
                if (existing != null) return existing;
                if (biomePalette.size() >= 254) return (byte) 254;
                biomePalette.add(biomeId);
                byte idx = (byte) biomePalette.size();
                biomeIndex.put(biomeId, idx);
                return idx;
            }

            String[] snapshotBlockPaletteLocked() {
                return blockPalette.toArray(new String[0]);
            }

            String[] snapshotBiomePaletteLocked() {
                return biomePalette.toArray(new String[0]);
            }

            public int[] snapshotPixels() {
                lock.lock();
                try { return Arrays.copyOf(pixels, pixels.length); }
                finally { lock.unlock(); }
            }

            public void resetForStyleRefresh() {
                lock.lock();
                try {
                    if (closed) return;
                    livePixels.clear();
                    liveChunks.clear();
                    Arrays.fill(writtenChunkLayerY, UNWRITTEN_LAYER);
                    anvilSliced = false;
                    writtenAnvilLayerY = Integer.MAX_VALUE;
                } finally {
                    lock.unlock();
                }
            }

            public void replacePixels(int[] source) {
                lock.lock();
                try {
                    if (!closed) {
                        System.arraycopy(source, 0, pixels, 0, Math.min(source.length, pixels.length));
                        recomputePageContentMaskLocked();
                        hasContent = pageContentMask != 0L;
                        loaded = true;
                    }
                } finally {
                    lock.unlock();
                }
            }

            public void mergeMissingPixels(int[] source) {
                mergeMissingPixels(source, null);
            }

            public void mergeMissingPixels(int[] source, short[] sourceHeights) {
                lock.lock();
                try {
                    if (closed) return;
                    int length = Math.min(source.length, pixels.length);
                    long mask = pageContentMask;
                    for (int i = 0; i < length; i++) {
                        if (!livePixels.get(i) && pixels[i] == 0 && source[i] != 0) {
                            pixels[i] = source[i];
                            if (sourceHeights != null && i < sourceHeights.length) {
                                heights[i] = sourceHeights[i];
                            }
                            int px = i & 511;
                            int pz = i >>> 9;
                            mask |= (1L << (((pz >> 6) << 3) | (px >> 6)));
                            hasContent = true;
                        }
                    }
                    pageContentMask = mask;
                } finally {
                    lock.unlock();
                }
            }

            public int getColor(int px, int pz) {
                lock.lock();
                try { return pixels[pz * 512 + px]; }
                finally { lock.unlock(); }
            }

            public int getHeight(int px, int pz) {
                lock.lock();
                try { return heights[pz * 512 + px]; }
                finally { lock.unlock(); }
            }

            public String getBlockId(int px, int pz) {
                lock.lock();
                try {
                    int idx = blockIds[pz * 512 + px] & 0xFFFF;
                    if (idx <= 0 || idx > blockPalette.size()) return null;
                    return blockPalette.get(idx - 1);
                } finally {
                    lock.unlock();
                }
            }

            public String getBiomeId(int px, int pz) {
                lock.lock();
                try {
                    int idx = biomeIds[pz * 512 + px] & 0xFF;
                    if (idx <= 0 || idx > biomePalette.size()) return null;
                    return biomePalette.get(idx - 1);
                } finally {
                    lock.unlock();
                }
            }

            public boolean setColor(int px, int pz, int abgrColor) {
                int currentHeight = getHeight(px, pz);
                return setPixel(px, pz, abgrColor,
                        abgrColor == 0 ? NO_SURFACE : currentHeight,
                        band, abgrColor != 0);
            }

            private void clearChunkLivePixelsLocked(int cxInRegion, int czInRegion) {
                int basePx = cxInRegion << 4;
                int basePz = czInRegion << 4;
                for (int lz = 0; lz < 16; lz++) {
                    int rowStart = (basePz + lz) * 512 + basePx;
                    livePixels.clear(rowStart, rowStart + 16);
                }
            }

            public boolean setChunkPixels(int cxInRegion, int czInRegion,
                    int[] chunkColors, short[] chunkHeights, int layerY) {
                return setChunkPixels(cxInRegion, czInRegion, chunkColors, chunkHeights, null, null, layerY);
            }

            public boolean setChunkPixels(int cxInRegion, int czInRegion,
                    int[] chunkColors, short[] chunkHeights,
                    String[] chunkBlockIds, String[] chunkBiomeIds, int layerY) {
                lock.lock();
                try {
                    if (closed) return false;
                    int chunkIdx = (czInRegion << 5) | cxInRegion;
                    short encodedLayer = encodeLayerY(layerY);
                    int basePx = cxInRegion << 4;
                    int basePz = czInRegion << 4;
                    boolean anyChanged = false;
                    boolean chunkHasContent = false;

                    for (int lz = 0; lz < 16; lz++) {
                        int rowOffset = (basePz + lz) * 512 + basePx;
                        int pOffset = lz << 4;
                        for (int lx = 0; lx < 16; lx++) {
                            int index = rowOffset + lx;
                            int p = pOffset + lx;
                            int abgrColor = chunkColors[p];
                            short surfaceY = chunkHeights[p];
                            short clampedHeight = (abgrColor == 0 || surfaceY == NO_SURFACE)
                                    ? NO_SURFACE
                                    : (short) Math.max(-32760, Math.min(32760, surfaceY));
                            if (abgrColor != 0) {
                                livePixels.set(index);
                                chunkHasContent = true;
                                if (chunkBlockIds != null && p < chunkBlockIds.length) {
                                    blockIds[index] = getOrAddBlockIndexLocked(chunkBlockIds[p]);
                                }
                                if (chunkBiomeIds != null && p < chunkBiomeIds.length) {
                                    biomeIds[index] = getOrAddBiomeIndexLocked(chunkBiomeIds[p]);
                                }
                            } else {
                                livePixels.clear(index);
                                blockIds[index] = 0;
                                biomeIds[index] = 0;
                            }
                            if (pixels[index] != abgrColor || heights[index] != clampedHeight) {
                                pixels[index] = abgrColor;
                                heights[index] = clampedHeight;
                                anyChanged = true;
                            }
                        }
                    }

                    liveChunks.set(chunkIdx);
                    writtenChunkLayerY[chunkIdx] = encodedLayer;
                    if (chunkHasContent) {
                        hasContent = true;
                        pageContentMask |= (1L << (((czInRegion >> 2) << 3) | (cxInRegion >> 2)));
                    }
                    loaded = true;
                    if (anyChanged) {
                        dirty = true;
                    }
                    return anyChanged;
                } finally {
                    lock.unlock();
                }
            }

            public boolean setPixel(int px, int pz, int abgrColor, int surfaceY, int layerY, boolean markLive) {
                return setPixel(px, pz, abgrColor, surfaceY, layerY, markLive, null, null);
            }

            public boolean setPixel(int px, int pz, int abgrColor, int surfaceY, int layerY, boolean markLive,
                    String blockId, String biomeId) {
                lock.lock();
                try {
                    if (closed) return false;
                    int index = pz * 512 + px;
                    short clampedHeight = (abgrColor == 0 || surfaceY == NO_SURFACE)
                            ? NO_SURFACE
                            : (short) Math.max(-32760, Math.min(32760, surfaceY));
                    if (markLive) {
                        int cxInRegion = px >> 4;
                        int czInRegion = pz >> 4;
                        int chunkIdx = (czInRegion << 5) | cxInRegion;
                        short encodedLayer = encodeLayerY(layerY);
                        if (writtenChunkLayerY[chunkIdx] != encodedLayer) {
                            clearChunkLivePixelsLocked(cxInRegion, czInRegion);
                            liveChunks.clear(chunkIdx);
                            writtenChunkLayerY[chunkIdx] = encodedLayer;
                        }
                        if (abgrColor != 0) {
                            livePixels.set(index);
                        } else {
                            livePixels.clear(index);
                        }
                    } else if (abgrColor == 0) {
                        livePixels.clear(index);
                    }
                    if (abgrColor != 0) {
                        hasContent = true;
                        loaded = true;
                        pageContentMask |= (1L << (((pz >> 6) << 3) | (px >> 6)));
                        if (blockId != null) {
                            blockIds[index] = getOrAddBlockIndexLocked(blockId);
                        }
                        if (biomeId != null) {
                            biomeIds[index] = getOrAddBiomeIndexLocked(biomeId);
                        }
                    } else {
                        blockIds[index] = 0;
                        biomeIds[index] = 0;
                    }
                    boolean colorChanged = pixels[index] != abgrColor;
                    boolean heightChanged = heights[index] != clampedHeight;
                    pixels[index] = abgrColor;
                    heights[index] = clampedHeight;
                    if (colorChanged || heightChanged) {
                        dirty = true;
                    }
                    return colorChanged;
                } finally {
                    lock.unlock();
                }
            }

            public boolean isLivePixel(int px, int pz) {
                lock.lock();
                try {
                    return livePixels.get(pz * 512 + px);
                } finally {
                    lock.unlock();
                }
            }

            public boolean isLivePixelAtLayer(int px, int pz, int layerY) {
                lock.lock();
                try {
                    return isLivePixelAtLayerLocked(px, pz, layerY);
                } finally {
                    lock.unlock();
                }
            }

            public boolean isChunkLive(int cxInRegion, int czInRegion) {
                lock.lock();
                try {
                    return liveChunks.get(czInRegion * 32 + cxInRegion);
                } finally {
                    lock.unlock();
                }
            }

            public boolean isChunkLiveAtLayer(int cxInRegion, int czInRegion, int layerY) {
                lock.lock();
                try {
                    return isChunkLiveAtLayerLocked(cxInRegion, czInRegion, layerY);
                } finally {
                    lock.unlock();
                }
            }

            boolean isChunkLiveAtLayerLocked(int cxInRegion, int czInRegion, int layerY) {
                int chunkIdx = czInRegion * 32 + cxInRegion;
                return liveChunks.get(chunkIdx)
                        && writtenChunkLayerY[chunkIdx] == encodeLayerY(layerY);
            }

            public void markChunkLive(int cxInRegion, int czInRegion) {
                markChunkLive(cxInRegion, czInRegion, band);
            }

            public void markChunkLive(int cxInRegion, int czInRegion, int layerY) {
                lock.lock();
                try {
                    if (!closed) {
                        int chunkIdx = czInRegion * 32 + cxInRegion;
                        liveChunks.set(chunkIdx);
                        writtenChunkLayerY[chunkIdx] = encodeLayerY(layerY);
                        loaded = true;
                    }
                } finally {
                    lock.unlock();
                }
            }

            /** Caller must hold this region's lock. */
            boolean isLivePixelLocked(int index) {
                return livePixels.get(index);
            }

            /** Caller must hold this region's lock. */
            boolean isLivePixelAtLayerLocked(int px, int pz, int layerY) {
                int index = pz * 512 + px;
                if (!livePixels.get(index)) return false;
                int chunkIdx = ((pz >> 4) << 5) | (px >> 4);
                return writtenChunkLayerY[chunkIdx] == encodeLayerY(layerY);
            }
        }

        /**
         * Registry-indexed cache for cave-scanning BlockState geometry and opacity.
         */
        public static final class CaveStateClassifier {
            public static final byte AIR = 0;
            public static final byte WATER = 1;
            public static final byte OTHER_FLUID = 2;
            /** A full collision cube that can be skipped by section-palette fast paths. */
            public static final byte SOLID_FAST = 3;
            /** Empty or partial geometry whose cached/fallback shape must be consulted. */
            public static final byte DYNAMIC = 4;

            private static final CaveStateClassifier INSTANCE = new CaveStateClassifier();
            private static final BlockGetter EMPTY_LEVEL = EmptyBlockGetter.INSTANCE;
            private static final BlockPos ORIGIN = BlockPos.ZERO;

            private volatile AtomicReferenceArray<StateInfo> byStateId =
                    new AtomicReferenceArray<>(4096);
            private final Map<BlockState, StateInfo> fallbackCache = new IdentityHashMap<>();

            private CaveStateClassifier() {
            }

            public static CaveStateClassifier getInstance() {
                return INSTANCE;
            }

            public byte classify(BlockState state) {
                return info(state).kind();
            }

            public boolean isCollisionEmpty(BlockGetter level, BlockPos pos, BlockState state) {
                StateInfo info = info(state);
                if (!info.contextDependent()) return info.collisionEmpty();
                try {
                    return state.getCollisionShape(level, pos).isEmpty();
                } catch (Throwable ignored) {
                    return info.collisionEmpty();
                }
            }

            public boolean isFullCollisionCube(BlockState state) {
                return info(state).collisionFull();
            }

            public boolean isFullOcclusionCube(BlockState state) {
                return info(state).occlusionFull();
            }

            public boolean isSolidRender(BlockState state) {
                return info(state).solidRender();
            }

            public int lightBlock(BlockState state) {
                return info(state).lightBlock();
            }

            public float collisionTop(BlockGetter level, BlockPos pos, BlockState state) {
                StateInfo info = info(state);
                if (!info.contextDependent()) return info.collisionTop();
                try {
                    VoxelShape shape = state.getCollisionShape(level, pos);
                    return shape.isEmpty() ? 0.0f : (float) shape.max(Direction.Axis.Y);
                } catch (Throwable ignored) {
                    return info.collisionTop();
                }
            }

            public StateInfo info(BlockState state) {
                int stateId = Block.BLOCK_STATE_REGISTRY.getId(state);
                StateInfo cached = null;
                if (stateId >= 0) {
                    AtomicReferenceArray<StateInfo> local = byStateId;
                    if (stateId < local.length()) cached = local.get(stateId);
                } else {
                    synchronized (this) {
                        cached = fallbackCache.get(state);
                    }
                }
                if (cached != null) return cached;

                synchronized (this) {
                    if (stateId >= 0) {
                        ensureStateCapacityLocked(stateId + 1);
                        cached = byStateId.get(stateId);
                    } else {
                        cached = fallbackCache.get(state);
                    }
                    if (cached != null) return cached;
                    StateInfo resolved = resolve(state);
                    if (stateId >= 0) byStateId.set(stateId, resolved);
                    else fallbackCache.put(state, resolved);
                    return resolved;
                }
            }

            public synchronized void clear() {
                AtomicReferenceArray<StateInfo> current = byStateId;
                for (int i = 0; i < current.length(); i++) current.set(i, null);
                fallbackCache.clear();
            }

            private void ensureStateCapacityLocked(int required) {
                AtomicReferenceArray<StateInfo> current = byStateId;
                if (required <= current.length()) return;
                int next = current.length();
                while (next < required) next = Math.max(next + 1, next << 1);
                AtomicReferenceArray<StateInfo> expanded = new AtomicReferenceArray<>(next);
                for (int i = 0; i < current.length(); i++) expanded.set(i, current.get(i));
                byStateId = expanded;
            }

            private StateInfo resolve(BlockState state) {
                if (state.isAir()) return StateInfo.air();
                if (state.getFluidState().is(FluidTags.WATER)) return StateInfo.water();
                if (!state.getFluidState().isEmpty()) return StateInfo.otherFluid();

                boolean vanilla = "minecraft".equals(
                        BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace());
                boolean collisionEmpty = false;
                boolean collisionFull = false;
                boolean occlusionEmpty = false;
                boolean occlusionFull = false;
                VoxelShape collisionShape = null;
                VoxelShape occlusionShape = null;
                boolean solidRender = false;
                boolean canOcclude = state.canOcclude();
                boolean skylightPasses = false;
                boolean usesShapeForLightOcclusion = state.useShapeForLightOcclusion();
                boolean largeCollisionShape = state.hasLargeCollisionShape();
                int lightBlock = 0;
                float collisionTop = 1.0f;
                boolean failed = false;

                try {
                    collisionShape = state.getCollisionShape(EMPTY_LEVEL, ORIGIN);
                    collisionEmpty = collisionShape.isEmpty();
                    collisionFull = !collisionEmpty && Block.isShapeFullBlock(collisionShape);
                    collisionTop = collisionEmpty ? 0.0f
                            : (float) collisionShape.max(Direction.Axis.Y);

                    occlusionShape = state.getOcclusionShape(EMPTY_LEVEL, ORIGIN);
                    occlusionEmpty = occlusionShape.isEmpty();
                    occlusionFull = !occlusionEmpty && Block.isShapeFullBlock(occlusionShape);
                    solidRender = state.isSolidRender(EMPTY_LEVEL, ORIGIN);
                    skylightPasses = state.propagatesSkylightDown(EMPTY_LEVEL, ORIGIN);
                    lightBlock = state.getLightBlock(EMPTY_LEVEL, ORIGIN);
                } catch (Throwable ignored) {
                    failed = true;
                    collisionEmpty = !state.blocksMotion();
                    collisionFull = state.blocksMotion();
                    occlusionEmpty = !canOcclude;
                    occlusionFull = canOcclude && state.blocksMotion();
                    solidRender = occlusionFull;
                    lightBlock = occlusionFull ? 15 : 0;
                    collisionTop = collisionEmpty ? 0.0f : 1.0f;
                }

                boolean contextDependent = failed || (!vanilla && !collisionEmpty && !collisionFull);
                byte kind = collisionFull && state.blocksMotion()
                        ? SOLID_FAST : DYNAMIC;
                return new StateInfo(kind, collisionEmpty, collisionFull,
                        occlusionEmpty, occlusionFull, solidRender, canOcclude,
                        skylightPasses, usesShapeForLightOcclusion, largeCollisionShape,
                        Math.max(0, Math.min(15, lightBlock)), collisionTop,
                        contextDependent, collisionShape, occlusionShape);
            }

            public record StateInfo(byte kind, boolean collisionEmpty,
                    boolean collisionFull, boolean occlusionEmpty,
                    boolean occlusionFull, boolean solidRender, boolean canOcclude,
                    boolean skylightPasses, boolean usesShapeForLightOcclusion,
                    boolean largeCollisionShape, int lightBlock, float collisionTop,
                    boolean contextDependent, VoxelShape collisionShape,
                    VoxelShape occlusionShape) {
                private static StateInfo air() {
                    return new StateInfo(AIR, true, false, true, false,
                            false, false, true, false, false,
                            0, 0.0f, false, null, null);
                }

                private static StateInfo water() {
                    return new StateInfo(WATER, true, false, true, false,
                            false, false, true, false, false,
                            0, 0.0f, false, null, null);
                }

                private static StateInfo otherFluid() {
                    return new StateInfo(OTHER_FLUID, true, false, true, false,
                            false, false, true, false, false,
                            0, 0.0f, false, null, null);
                }
            }
        }
    }
}