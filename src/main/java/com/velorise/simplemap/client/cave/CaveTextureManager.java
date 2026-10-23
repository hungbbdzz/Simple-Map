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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Direct GPU page atlas texture manager for layered cave maps.
 * Manages 64x64 pixel pages copied directly from CaveRegion and uploaded to CaveTextureAtlas.
 */
public final class CaveTextureManager {
    private static final CaveTextureManager INSTANCE = new CaveTextureManager();

    private final CaveTextureAtlas atlas = new CaveTextureAtlas();
    private final Map<PageKey, PageEntry> pages = new LinkedHashMap<>(128, 0.75f, true);
    private final Map<BranchKey, BranchEntry> branchPages = new LinkedHashMap<>(64, 0.75f, true);
    private final AtomicLong contentRevision = new AtomicLong(1L);

    private volatile int activeLayerY = Integer.MIN_VALUE;
    private volatile int previousLayerY = Integer.MIN_VALUE;

    private volatile int focusPageX = 0;
    private volatile int focusPageZ = 0;
    private volatile int minVisiblePageX = Integer.MIN_VALUE;
    private volatile int maxVisiblePageX = Integer.MAX_VALUE;
    private volatile int minVisiblePageZ = Integer.MIN_VALUE;
    private volatile int maxVisiblePageZ = Integer.MAX_VALUE;

    private final int[][] uploadBuffers = new int[CaveTextureAtlas.LOD_COUNT][];

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

    public static int applyCaveLighting(int abgr, int light, int verticalOffset) {
        return CaveMapManager.applyCaveLighting(abgr, light, 0, verticalOffset, false);
    }

    public static int applyCaveLighting(int abgr, int light, int verticalOffset, boolean isLava) {
        return CaveMapManager.applyCaveLighting(abgr, light, 0, verticalOffset, isLava);
    }

    public static int applyCaveLighting(int abgr, int blockLight, int skyLight, int verticalOffset, boolean isLava) {
        return CaveMapManager.applyCaveLighting(abgr, blockLight, skyLight, verticalOffset, isLava);
    }

    private CaveTextureManager() {
        for (int lod = 0; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            int size = CaveTextureAtlas.lodSize(lod);
            uploadBuffers[lod] = new int[size * size];
        }
    }

    public void markPageTextureDirty(int layerY, int globalPageX, int globalPageZ) {
        int band = layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY);
        PageKey key = new PageKey(band, globalPageX, globalPageZ);
        synchronized (pages) {
            PageEntry entry = pages.computeIfAbsent(key, PageEntry::new);
            entry.dirty = true;
        }
        contentRevision.incrementAndGet();
    }

    public void markRegionTextureDirty(int layerY, int rx, int rz) {
        int band = layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY);
        int firstGlobalX = rx << 3;
        int firstGlobalZ = rz << 3;
        synchronized (pages) {
            for (int pz = 0; pz < 8; pz++) {
                for (int px = 0; px < 8; px++) {
                    PageKey key = new PageKey(band, firstGlobalX + px, firstGlobalZ + pz);
                    PageEntry entry = pages.computeIfAbsent(key, PageEntry::new);
                    entry.dirty = true;
                }
            }
        }
        synchronized (branchPages) {
            BranchEntry l3 = branchPages.get(new BranchKey(band, 3, rx, rz));
            if (l3 != null) l3.dirty = true;
            int baseNodeX2 = rx * 2;
            int baseNodeZ2 = rz * 2;
            for (int dz = 0; dz < 2; dz++) {
                for (int dx = 0; dx < 2; dx++) {
                    BranchEntry l2 = branchPages.get(new BranchKey(band, 2, baseNodeX2 + dx, baseNodeZ2 + dz));
                    if (l2 != null) l2.dirty = true;
                }
            }
            int baseNodeX1 = rx * 4;
            int baseNodeZ1 = rz * 4;
            for (int dz = 0; dz < 4; dz++) {
                for (int dx = 0; dx < 4; dx++) {
                    BranchEntry l1 = branchPages.get(new BranchKey(band, 1, baseNodeX1 + dx, baseNodeZ1 + dz));
                    if (l1 != null) l1.dirty = true;
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
        }
        activeLayerY = layerY;
        int activeBand = layerY == Integer.MIN_VALUE
                ? Integer.MIN_VALUE
                : CaveLayerBand.key(CaveView.LAYERED, layerY);
        synchronized (pages) {
            for (PageEntry entry : pages.values()) {
                if (entry.key.band() == activeBand) {
                    entry.dirty = true;
                }
            }
        }
        synchronized (branchPages) {
            for (BranchEntry entry : branchPages.values()) {
                if (entry.key.band() == activeBand) {
                    entry.dirty = true;
                }
            }
        }
        contentRevision.incrementAndGet();
    }

    public long contentRevision() {
        return contentRevision.get();
    }

    public long branchContentRevision() {
        return contentRevision.get();
    }

    private int uploadsThisFrame = 0;
    private static final int MAX_UPLOADS_PER_FRAME = 24;

    public void beginRenderBatch() {
        uploadsThisFrame = 0;
    }

    public void endRenderBatch() {
        uploadsThisFrame = 0;
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
            entry = pages.computeIfAbsent(key, PageEntry::new);
            entry.lastAccessTime = System.currentTimeMillis();
        }

        if (entry.slot == -1 || entry.dirty) {
            if (uploadsThisFrame < MAX_UPLOADS_PER_FRAME) {
                if (uploadPage(entry, rx, rz, pageX, pageZ)) {
                    uploadsThisFrame++;
                }
            }
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
        int rx = Math.floorDiv(globalPageX, 8);
        int rz = Math.floorDiv(globalPageZ, 8);
        int pageX = Math.floorMod(globalPageX, 8);
        int pageZ = Math.floorMod(globalPageZ, 8);
        PageKey key = new PageKey(band, globalPageX, globalPageZ);
        PageEntry entry;
        synchronized (pages) {
            entry = pages.get(key);
            if (entry == null) {
                CaveMapManager.CaveRegion region = CaveMapManager.getInstance().getRegion(band, rx, rz, false);
                if (region != null && region.isLoaded() && region.hasContent) {
                    entry = new PageEntry(key);
                    pages.put(key, entry);
                }
            }
        }
        if (entry != null) {
            if (entry.slot == -1 || entry.dirty) {
                if (uploadsThisFrame < MAX_UPLOADS_PER_FRAME) {
                    if (uploadPage(entry, rx, rz, pageX, pageZ)) {
                        uploadsThisFrame++;
                    }
                }
            }
            if (entry.slot >= 0 && entry.hasContent) {
                CaveAtlasRegion reg = atlas.region(entry.slot, scale);
                if (reg != null) {
                    return new PageSelection(reg, pageTileKey(representativeLayerY, globalPageX, globalPageZ, scale, lane));
                }
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
        if (level <= 0 || level > 3) return null;
        int band = layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY);
        BranchKey key = new BranchKey(band, level, nodeX, nodeZ);

        BranchEntry entry;
        synchronized (branchPages) {
            entry = branchPages.computeIfAbsent(key, BranchEntry::new);
            entry.lastAccessTime = System.currentTimeMillis();
        }

        if (entry.slot == -1 || entry.dirty) {
            if (uploadsThisFrame < MAX_UPLOADS_PER_FRAME) {
                if (uploadBranchNode(entry, layerY, level, nodeX, nodeZ)) {
                    uploadsThisFrame++;
                }
            }
        }

        if (entry.slot < 0 || !entry.hasContent) return null;
        int pageSpan = MapViewportCoordinator.MapLodPolicy.pageSpanForBranch(level);
        int worldSize = pageSpan * CaveTextureAtlas.PAGE_SIZE;
        CaveAtlasRegion reg = atlas.region(entry.slot, 1.0f);
        if (reg == null) return null;
        return new CaveAtlasRegion(reg.texture(), reg.sourceX(), reg.sourceY(), 64, reg.atlasSize(), level, worldSize, -1L, -1L);
    }

    public boolean hasBranchData(int layerY, int level, int nodeX, int nodeZ) {
        return hasBranchData(layerY, level, nodeX, nodeZ, MapRequestLane.FULLSCREEN);
    }

    public boolean hasBranchData(int layerY, int level, int nodeX, int nodeZ,
            MapRequestLane lane) {
        int pageSpan = MapViewportCoordinator.MapLodPolicy.pageSpanForBranch(level);
        int firstPageX = nodeX * pageSpan;
        int firstPageZ = nodeZ * pageSpan;
        int rx = Math.floorDiv(firstPageX, 8);
        int rz = Math.floorDiv(firstPageZ, 8);
        CaveMapManager cmm = CaveMapManager.getInstance();
        return cmm.hasRegionFile(layerY, rx, rz) || cmm.isRegionLoaded(layerY, rx, rz);
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

        // 3. Check if CaveMapManager has data for any region in this node
        int minRx = Math.floorDiv(firstPageX, MapPageLayout.PAGES_PER_REGION);
        int maxRx = Math.floorDiv(lastPageX, MapPageLayout.PAGES_PER_REGION);
        int minRz = Math.floorDiv(firstPageZ, MapPageLayout.PAGES_PER_REGION);
        int maxRz = Math.floorDiv(lastPageZ, MapPageLayout.PAGES_PER_REGION);

        CaveMapManager cmm = CaveMapManager.getInstance();
        for (int rz = minRz; rz <= maxRz; rz++) {
            for (int rx = minRx; rx <= maxRx; rx++) {
                CaveMapManager.CaveRegion region = cmm.getRegion(layerY, rx, rz, false);
                if (region != null && region.hasContent()) {
                    return true;
                }
                if (previousLayerY != Integer.MIN_VALUE && CaveMode.isTransitionActive()) {
                    CaveMapManager.CaveRegion prevRegion = cmm.getRegion(previousLayerY, rx, rz, false);
                    if (prevRegion != null && prevRegion.hasContent()) {
                        return true;
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

        final int activeBand = activeLayerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, activeLayerY);
        List<PageEntry> dirtyList = new ArrayList<>();
        synchronized (pages) {
            for (PageEntry entry : pages.values()) {
                if (entry.dirty && entry.key.band() == activeBand) {
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

        dirtyList.sort((a, b) -> {
            boolean activeA = a.key.band() == activeBand;
            boolean activeB = b.key.band() == activeBand;
            if (activeA != activeB) return activeA ? -1 : 1;

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

        int count = 0;
        int limit = force ? 128 : (MapPerformanceGovernor.getInstance().hasForegroundUploadHeadroom() ? 64 : 24);
        for (PageEntry entry : dirtyList) {
            if (count++ >= limit) break;
            int rx = Math.floorDiv(entry.key.globalPageX(), 8);
            int rz = Math.floorDiv(entry.key.globalPageZ(), 8);
            int px = Math.floorMod(entry.key.globalPageX(), 8);
            int pz = Math.floorMod(entry.key.globalPageZ(), 8);
            uploadPage(entry, rx, rz, px, pz);
        }

        if (count < limit) {
            List<BranchEntry> dirtyBranches = new ArrayList<>();
            synchronized (branchPages) {
                for (BranchEntry entry : branchPages.values()) {
                    if (entry.dirty && entry.key.band() == activeBand) dirtyBranches.add(entry);
                }
            }
            dirtyBranches.sort((a, b) -> {
                boolean activeA = a.key.band() == activeBand;
                boolean activeB = b.key.band() == activeBand;
                if (activeA != activeB) return activeA ? -1 : 1;

                if (a.key.level() != b.key.level()) {
                    return Integer.compare(a.key.level(), b.key.level());
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
            for (BranchEntry entry : dirtyBranches) {
                if (count++ >= limit) break;
                uploadBranchNode(entry, entry.key.band(), entry.key.level(), entry.key.nodeX(), entry.key.nodeZ());
            }
        }
    }

    public void invalidateStyle() {
        synchronized (pages) {
            for (PageEntry entry : pages.values()) {
                entry.dirty = true;
            }
        }
        contentRevision.incrementAndGet();
    }

    public synchronized void clearCache() {
        synchronized (pages) {
            for (PageEntry entry : pages.values()) {
                if (entry.slot != -1) {
                    atlas.releaseSlot(entry.slot);
                }
            }
            pages.clear();
        }
        synchronized (branchPages) {
            for (BranchEntry entry : branchPages.values()) {
                if (entry.slot != -1) {
                    atlas.releaseSlot(entry.slot);
                }
            }
            branchPages.clear();
        }
        atlas.resetSlots();
        activeLayerY = Integer.MIN_VALUE;
        previousLayerY = Integer.MIN_VALUE;
        contentRevision.incrementAndGet();
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
        if (region == null || !region.isLoaded() || !region.hasContent()) {
            return false;
        }

        RenderSystem.assertOnRenderThreadOrInit();
        int[] regionPixels = region.getPixelsDirect();
        int localStartX = (firstPageX & 7) * 64;
        int localStartZ = (firstPageZ & 7) * 64;
        int step = pageSpan;
        boolean hasContent = false;

        region.lock();
        try {
            for (int y = 0; y < 64; y++) {
                int srcY = localStartZ + y * step;
                int dstRow = y * 64;
                for (int x = 0; x < 64; x++) {
                    int srcX = localStartX + x * step;
                    long red = 0L;
                    long green = 0L;
                    long blue = 0L;
                    int count = 0;
                    int maxAlpha = 0;
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
                                red += c & 0xFF;
                                green += (c >>> 8) & 0xFF;
                                blue += (c >>> 16) & 0xFF;
                                maxAlpha = Math.max(maxAlpha, a);
                                count++;
                            }
                        }
                    }
                    int color = 0;
                    if (count > 0) {
                        color = (maxAlpha << 24)
                                | ((int) (blue / count) << 16)
                                | ((int) (green / count) << 8)
                                | (int) (red / count);
                        hasContent = true;
                    }
                    uploadBuffers[0][dstRow + x] = color;
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

        for (int lod = 1; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            downsample(uploadBuffers[lod - 1], CaveTextureAtlas.lodSize(lod - 1),
                    uploadBuffers[lod], CaveTextureAtlas.lodSize(lod));
        }

        for (int lod = 0; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            int size = CaveTextureAtlas.lodSize(lod);
            atlas.upload(entry.slot, lod, uploadBuffers[lod],
                    new CaveTextureAtlas.DirtyRect(0, 0, size - 1, size - 1));
        }

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
        if (region == null || !region.isLoaded()) {
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

        for (int lod = 1; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            downsample(uploadBuffers[lod - 1], CaveTextureAtlas.lodSize(lod - 1),
                    uploadBuffers[lod], CaveTextureAtlas.lodSize(lod));
        }

        for (int lod = 0; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            int size = CaveTextureAtlas.lodSize(lod);
            atlas.upload(entry.slot, lod, uploadBuffers[lod],
                    new CaveTextureAtlas.DirtyRect(0, 0, size - 1, size - 1));
        }

        entry.dirty = false;
        return true;
    }

    private void evictOldestPage() {
        PageEntry oldestPage = null;
        BranchEntry oldestBranch = null;
        long oldestTime = Long.MAX_VALUE;

        synchronized (pages) {
            for (PageEntry e : pages.values()) {
                if (e.slot != -1 && e.lastAccessTime < oldestTime) {
                    oldestTime = e.lastAccessTime;
                    oldestPage = e;
                    oldestBranch = null;
                }
            }
        }
        synchronized (branchPages) {
            for (BranchEntry b : branchPages.values()) {
                if (b.slot != -1 && b.lastAccessTime < oldestTime) {
                    oldestTime = b.lastAccessTime;
                    oldestBranch = b;
                    oldestPage = null;
                }
            }
        }

        if (oldestPage != null && oldestPage.slot != -1) {
            atlas.releasePublishedSlot(oldestPage.slot);
            oldestPage.slot = -1;
            oldestPage.dirty = true;
        } else if (oldestBranch != null && oldestBranch.slot != -1) {
            atlas.releasePublishedSlot(oldestBranch.slot);
            oldestBranch.slot = -1;
            oldestBranch.dirty = true;
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
            red += value & 0xFF;
            green += (value >>> 8) & 0xFF;
            blue += (value >>> 16) & 0xFF;
            alpha = Math.max(alpha, childAlpha);
            colored++;
        }
        if (colored == 0) return 0;
        return (alpha << 24) | ((int) (blue / colored) << 16) | ((int) (green / colored) << 8) | (int) (red / colored);
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
 * Direct in-memory pixel region manager for layered cave maps.
 */


    public static final class CaveMapManager {
    private static final CaveMapManager INSTANCE = new CaveMapManager();
    private static final int MAX_COMPAT_REGIONS = 64;

    private final ExecutorService diskExecutor = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)), r -> {
        Thread t = new Thread(r, "SimpleMap-CaveIO");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private File baseDirectory;
    private File surfaceDirectory;
    private final Map<Long, Boolean> mcaExistsCache = new ConcurrentHashMap<>();
    private static final Map<String, Integer> BLOCK_COLOR_CACHE = new ConcurrentHashMap<>();

    /** Cache phân loại block theo tên để tái sử dụng: false = solid terrain, true = passable/foliage */
    private static final Map<String, Boolean> FOLIAGE_KIND_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class AnvilSliceBuffers {
        final short[] heights = new short[512 * 512];
        final int[] rawColors = new int[512 * 512];
        final byte[] kinds = new byte[512 * 512];
        byte[] chunkDataBuffer = new byte[65536];
    }
    private static final ThreadLocal<AnvilSliceBuffers> SLICE_BUFFERS = ThreadLocal.withInitial(AnvilSliceBuffers::new);

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
    public static final int EPOCH = 8;
    public static final int LOD_BRANCH_VERSION = 16;

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

    public synchronized void setBaseDirectory(File directory, File surfaceDirectory) {
        saveAllDirtyRegions();
        this.baseDirectory = directory;
        this.surfaceDirectory = surfaceDirectory;
        this.mcaExistsCache.clear();
        deferProjectionCompletions();
        clearCompatRegions();
        layerGeneration.incrementAndGet();
    }

    public synchronized void setActiveLayer(int layerY) {
        if (activeLayerY == layerY) return;
        activeLayerY = layerY;
        deferProjectionCompletions();
        layerGeneration.incrementAndGet();
        CaveTextureManager.getInstance().onLayerActivated(layerY);
    }

    public int getActiveLayerY() {
        return activeLayerY;
    }

    public void requestVisibleRegion(int layerY, int rx, int rz) {
        if (layerY != Integer.MIN_VALUE) setActiveLayer(layerY);
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
        int rx = blockX >> 9;
        int rz = blockZ >> 9;
        int px = blockX & 511;
        int pz = blockZ & 511;
        CaveRegion region = materialize(layerY, rx, rz, true);
        if (region != null && region.setPixel(px, pz, abgrColor, surfaceY, layerY, markLive)) {
            markPageDirty(layerY, blockX >> 6, blockZ >> 6);
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
        if (region != null && region.isLoaded()) {
            return region.getHeight(blockX & 511, blockZ & 511);
        }
        return NO_SURFACE;
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
        int band = (layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY));
        String key = (band == Integer.MIN_VALUE ? "full" : String.valueOf(band)) + ":" + rx + ',' + rz;
        synchronized (regions) {
            RegionHolder holder = regions.get(key);
            if (holder != null && holder.region.hasContent()) return true;
        }
        File scdat = getRegionDiskFile(band, rx, rz);
        if (scdat != null && scdat.exists()) return true;

        if (surfaceDirectory != null) {
            File smdat = new File(surfaceDirectory, "r." + rx + "." + rz + ".smdat");
            if (smdat.exists()) return true;
            File smr2 = new File(surfaceDirectory, "containers-v2/r." + rx + "." + rz + ".smr2");
            if (smr2.exists()) return true;
        }

        long packed = (((long) rx) << 32) | (rz & 0xFFFFFFFFL);
        Boolean cachedMca = mcaExistsCache.get(packed);
        if (cachedMca != null) return cachedMca;

        File mcaDir = getMcaDirectory();
        if (mcaDir != null) {
            File mca = new File(mcaDir, "r." + rx + "." + rz + ".mca");
            boolean exists = mca.exists();
            mcaExistsCache.put(packed, exists);
            return exists;
        }
        return false;
    }

    public boolean isRegionLoaded(int rx, int rz) {
        return isRegionLoaded(activeLayerY, rx, rz);
    }

    public boolean isRegionLoaded(int layerY, int rx, int rz) {
        CaveRegion region = getRegion(layerY, rx, rz, false);
        return region != null && region.isLoaded();
    }

    public void tickSave() {
        saveAllDirtyRegions();
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
        clearCompatRegions();
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
        deferProjectionCompletions();
        activeLayerY = Integer.MIN_VALUE;
        clearCompatRegions();
        layerGeneration.incrementAndGet();
    }

    public File getRegionDiskFile(int layerY, int rx, int rz) {
        if (baseDirectory == null) return null;
        int band = (layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY));
        String folder = band == Integer.MIN_VALUE ? "full" : ("y_" + band);
        File bandDir = new File(baseDirectory, folder);
        return new File(bandDir, "r." + rx + "." + rz + ".scdat");
    }

    private void saveRegionToDisk(CaveRegion region, int layerY, int rx, int rz) {
        if (region == null || !region.hasContent()) return;
        int band = (layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY));
        File file = getRegionDiskFile(band, rx, rz);
        if (file == null) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File temp = new File(parent, file.getName() + ".tmp");
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(temp))))) {
            dos.writeInt(0x53434438); // 'SCD8' (includes heights & writtenAnvilLayerY)
            dos.writeInt(band);
            dos.writeInt(rx);
            dos.writeInt(rz);
            dos.writeBoolean(region.isAnvilSliced());
            dos.writeInt(region.getWrittenAnvilLayerY());
            int[] pixels = region.getPixelsDirect();
            short[] heights = region.getHeightsDirect();
            region.lock();
            try {
                for (int p : pixels) {
                    dos.writeInt(p);
                }
                for (short h : heights) {
                    dos.writeShort(h);
                }
            } finally {
                region.unlock();
            }
            dos.flush();
        } catch (Throwable t) {
            temp.delete();
            return;
        }
        if (file.exists()) file.delete();
        temp.renameTo(file);
    }

    private boolean loadRegionFromDisk(CaveRegion region, int layerY, int rx, int rz) {
        int band = (layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY));
        File file = getRegionDiskFile(band, rx, rz);
        if (file == null || !file.exists() || file.length() < 16) return false;
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            int magic = dis.readInt();
            if (magic != 0x53434437 && magic != 0x53434438) return false;
            int savedLayer = dis.readInt();
            if (savedLayer != band) return false;
            int savedRx = dis.readInt();
            int savedRz = dis.readInt();
            if (savedRx != rx || savedRz != rz) return false;
            boolean anvilSliced = dis.readBoolean();
            int savedAnvilLayerY = (magic == 0x53434438) ? dis.readInt() : band;
            int[] pixels = region.getPixelsDirect();
            short[] heights = region.getHeightsDirect();
            boolean hasContent = false;
            region.lock();
            try {
                for (int i = 0; i < 512 * 512; i++) {
                    int c = dis.readInt();
                    pixels[i] = c;
                    if (c != 0) hasContent = true;
                }
                if (magic == 0x53434438) {
                    for (int i = 0; i < 512 * 512; i++) {
                        heights[i] = dis.readShort();
                    }
                } else {
                    Arrays.fill(heights, CaveRegion.NO_SURFACE);
                }
            } finally {
                region.unlock();
            }
            if (hasContent) {
                if (anvilSliced) {
                    region.setAnvilSliced(true);
                    region.setWrittenAnvilLayerY(savedAnvilLayerY);
                }
                region.markExactSnapshotLoaded();
                return true;
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    private File getMcaDirectory() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isLocalServer() || mc.getSingleplayerServer() == null || mc.level == null) return null;
        try {
            var server = mc.getSingleplayerServer();
            var root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            var dim = mc.level.dimension();
            String currentDimId = MapManager.getInstance().getCurrentDimensionResourceId();
            if (currentDimId != null && !currentDimId.isEmpty()) {
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
            }
            if (dim == Level.OVERWORLD) {
                return root.resolve("region").toFile();
            } else if (dim == Level.NETHER) {
                return root.resolve("DIM-1").resolve("region").toFile();
            } else if (dim == Level.END) {
                return root.resolve("DIM1").resolve("region").toFile();
            } else {
                return root.resolve("dimensions")
                        .resolve(dim.location().getNamespace())
                        .resolve(dim.location().getPath())
                        .resolve("region").toFile();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private void sliceAnvilRegion(CaveRegion region, int layerY, int rx, int rz) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        File mcaDir = getMcaDirectory();
        if (mcaDir == null) return;
        File mcaFile = new File(mcaDir, "r." + rx + "." + rz + ".mca");
        if (!mcaFile.exists() || mcaFile.length() < 8192) return;

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

            int baseChunkX = rx << 5;
            int baseChunkZ = rz << 5;
            boolean hasAnyChunk = false;
            long taskGen = layerGeneration.get();

            for (int cz = 0; cz < 32; cz++) {
                if (taskGen != layerGeneration.get()) return;
                for (int cx = 0; cx < 32; cx++) {
                    if (taskGen != layerGeneration.get()) return;
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
                        if (sliceChunkNbt(regionHeights, regionRawColors, regionKinds, tag, cx, cz, chunkX, chunkZ, layerY, mc.level)) {
                            hasAnyChunk = true;
                        }
                    }
                }
            }

            if (!hasAnyChunk) return;

            int[] regPixels = region.getPixelsDirect();
            short[] regHeights = region.getHeightsDirect();
            boolean modified = false;

            region.lock();
            try {
                for (int pz = 0; pz < 512; pz++) {
                    int rowOffset = pz * 512;
                    for (int px = 0; px < 512; px++) {
                        int index = rowOffset + px;
                        if (region.isLivePixelAtLayerLocked(px, pz, layerY)) continue;
                        int rawColor = regionRawColors[index];
                        if (rawColor == 0) {
                            if (regPixels[index] != 0) {
                                regPixels[index] = 0;
                                regHeights[index] = CaveRegion.NO_SURFACE;
                                modified = true;
                            }
                            continue;
                        }
                        short surfaceY = regionHeights[index];
                        if (surfaceY == CaveRegion.NO_SURFACE) continue;

                        float slope = calculateRegionCaveShade(regionHeights, px, pz, surfaceY);
                        int displayedColor = applyAbgrShade(rawColor, slope);

                        regPixels[index] = displayedColor;
                        regHeights[index] = surfaceY;
                        modified = true;
                    }
                }
                if (modified) {
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

    private boolean sliceChunkNbt(short[] regionHeights, int[] regionRawColors, byte[] regionKinds,
            CompoundTag tag, int cx, int cz, int chunkX, int chunkZ, int layerY, Level level) {
        if (!tag.contains("sections", Tag.TAG_LIST)) return false;
        ListTag sections = tag.getList("sections", Tag.TAG_COMPOUND);
        if (sections.isEmpty()) return false;

        boolean isFullCave = (layerY == Integer.MIN_VALUE);
        int dimensionCeilingCap = level.dimensionType().hasCeiling() ? 122 : (level.getMaxBuildHeight() - 1);
        int scanMaximum = isFullCave ? dimensionCeilingCap : Math.min(dimensionCeilingCap, CaveMode.getScanMaximum(level, layerY));
        int scanMinimum = isFullCave ? level.getMinBuildHeight() : CaveMode.getScanMinimum(level, layerY);
        if (scanMaximum < scanMinimum) return false;
        int minSecY = level.getMinBuildHeight() >> 4;
        int maxSecY = scanMaximum >> 4;
        int secSpan = maxSecY - minSecY + 1;
        DecodedSection[] sectionArray = (secSpan > 0 && secSpan <= 64) ? new DecodedSection[secSpan] : null;

        for (int i = 0; i < sections.size(); i++) {
            CompoundTag secTag = sections.getCompound(i);
            int secY = secTag.getByte("Y");
            if (secY >= minSecY && secY <= maxSecY) {
                DecodedSection ds = DecodedSection.decode(secTag);
                if (ds != null && sectionArray != null) {
                    sectionArray[secY - minSecY] = ds;
                }
            }
        }

        boolean isCaveDim = isCaveDimension(level);
        boolean hasSkyLight = level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
        int bandCenterY = isFullCave ? (isCaveDim ? 64 : 0) : ((scanMaximum + scanMinimum) / 2);
        boolean chunkHadContent = false;

        int basePx = cx << 4;
        int basePz = cz << 4;
        int worldMinY = level.getMinBuildHeight();

        for (int lz = 0; lz < 16; lz++) {
            int pzInRegion = basePz + lz;
            int rowOffset = pzInRegion * 512;
            for (int lx = 0; lx < 16; lx++) {
                int pxInRegion = basePx + lx;
                int regIdx = rowOffset + pxInRegion;

                boolean shouldEnterGround = isFullCave && !isCaveDim;
                boolean underair = isFullCave;

                for (int y = scanMaximum; y >= scanMinimum; y--) {
                    int secY = y >> 4;
                    int secIdx = secY - minSecY;
                    DecodedSection sec = (sectionArray != null && secIdx >= 0 && secIdx < secSpan) ? sectionArray[secIdx] : null;
                    int kind = sec == null ? DecodedSection.KIND_AIR : sec.getKind(lx, y & 15, lz);

                    // 1. In Full Cave, skip surface water, leaves, trees, glass roofs, and giant mushrooms
                    // until entering the first real underground solid terrain block (KIND_SOLID_GROUND).
                    if (shouldEnterGround) {
                        if (kind == DecodedSection.KIND_SOLID_GROUND) {
                            shouldEnterGround = false;
                            underair = false;
                        }
                        continue;
                    }

                    // 2. While inside solid ceiling rock (!underair), skip all solid blocks, water, and lava
                    // until encountering actual cave air or open pass-through space.
                    if (!underair) {
                        if (kind == DecodedSection.KIND_AIR || kind == DecodedSection.KIND_INVISIBLE_PASS) {
                            underair = true;
                        }
                        continue;
                    }

                    // 3. Inside cave open space (underair == true): pass through air & invisible decorations.
                    if (kind == DecodedSection.KIND_AIR || kind == DecodedSection.KIND_INVISIBLE_PASS) {
                        continue;
                    }

                    // 4. Water column inside cave (underair == true): resolve down to basin floor.
                    if (kind == DecodedSection.KIND_WATER) {
                        resolveWaterColumn(sectionArray, minSecY, secSpan, lx, lz, y, scanMaximum, worldMinY, bandCenterY,
                                hasSkyLight, regionHeights, regionRawColors, regionKinds, regIdx);
                        chunkHadContent = true;
                        break;
                    }

                    // 5. Lava or fire inside cave (underair == true):
                    if (kind == DecodedSection.KIND_LAVA) {
                        int rawCol = sec.getColor(lx, y & 15, lz);
                        int shadeOffset = Math.round((y - bandCenterY) / 8.0f);
                        regionHeights[regIdx] = (short) y;
                        regionRawColors[regIdx] = applyCaveLighting(rawCol, 15, 0, shadeOffset, true);
                        regionKinds[regIdx] = DecodedSection.KIND_LAVA;
                        chunkHadContent = true;
                        break;
                    }

                    // 6. Thin floor block (carpet, rail, redstone_wire, glow_lichen, snow, repeater, flower):
                    if (kind == DecodedSection.KIND_THIN_FLOOR) {
                        int belowY = y - 1;
                        int belowKind = DecodedSection.KIND_AIR;
                        if (belowY >= worldMinY) {
                            int bSecIdx = (belowY >> 4) - minSecY;
                            DecodedSection bSec = (sectionArray != null && bSecIdx >= 0 && bSecIdx < secSpan) ? sectionArray[bSecIdx] : null;
                            belowKind = (bSec == null) ? DecodedSection.KIND_AIR : bSec.getKind(lx, belowY & 15, lz);
                        }
                        // Skip ceiling-clinging thin blocks (e.g. glow lichen with air below)
                        if (belowKind == DecodedSection.KIND_AIR || belowKind == DecodedSection.KIND_INVISIBLE_PASS) {
                            continue;
                        }
                        int rawCol = sec.getColor(lx, y & 15, lz);
                        int lightY = Math.min(scanMaximum, y + 1);
                        int lightSecIdx = (lightY >> 4) - minSecY;
                        DecodedSection lightSec = (sectionArray != null && lightSecIdx >= 0 && lightSecIdx < secSpan) ? sectionArray[lightSecIdx] : null;
                        int blockLight = Math.max(
                                lightSec != null ? lightSec.getBlockLight(lx, lightY & 15, lz) : 0,
                                sec.getBlockLight(lx, y & 15, lz));
                        int skyLight = hasSkyLight
                                ? Math.max(
                                        lightSec != null ? lightSec.getSkyLight(lx, lightY & 15, lz) : 0,
                                        sec.getSkyLight(lx, y & 15, lz))
                                : 0;
                        int effectiveY = Math.max(worldMinY, y - 1);
                        int shadeOffset = Math.round((effectiveY - bandCenterY) / 8.0f);
                        regionHeights[regIdx] = (short) effectiveY;
                        regionRawColors[regIdx] = applyCaveLighting(rawCol, blockLight, skyLight, shadeOffset, false);
                        regionKinds[regIdx] = DecodedSection.KIND_SOLID_GROUND;
                        chunkHadContent = true;
                        break;
                    }

                    // 7. Solid floor block (KIND_SOLID_GROUND or KIND_PASSABLE_SOLID such as mineshaft planks):
                    if (kind == DecodedSection.KIND_SOLID_GROUND || kind == DecodedSection.KIND_PASSABLE_SOLID) {
                        int rawCol = sec.getColor(lx, y & 15, lz);
                        int lightY = Math.min(scanMaximum, y + 1);
                        int lightSecIdx = (lightY >> 4) - minSecY;
                        DecodedSection lightSec = (sectionArray != null && lightSecIdx >= 0 && lightSecIdx < secSpan) ? sectionArray[lightSecIdx] : null;
                        int blockLight = Math.max(
                                lightSec != null ? lightSec.getBlockLight(lx, lightY & 15, lz) : 0,
                                sec.getBlockLight(lx, y & 15, lz));
                        int skyLight = hasSkyLight
                                ? Math.max(
                                        lightSec != null ? lightSec.getSkyLight(lx, lightY & 15, lz) : 0,
                                        sec.getSkyLight(lx, y & 15, lz))
                                : 0;
                        int shadeOffset = Math.round((y - bandCenterY) / 8.0f);

                        regionHeights[regIdx] = (short) y;
                        regionRawColors[regIdx] = applyCaveLighting(rawCol, blockLight, skyLight, shadeOffset, false);
                        regionKinds[regIdx] = DecodedSection.KIND_SOLID_GROUND;
                        chunkHadContent = true;
                        break;
                    }
                }
            }
        }

        return chunkHadContent;
    }

    private static void resolveWaterColumn(DecodedSection[] sectionArray, int minSecY, int secSpan, int lx, int lz,
            int waterSurfaceY, int scanMaximum, int scanMinimum, int bandCenterY, boolean hasSkyLight,
            short[] regionHeights, int[] regionRawColors, byte[] regionKinds, int regIdx) {
        int floorY = CaveRegion.NO_SURFACE;
        int floorRawColor = 0;
        int depth = 1;

        for (int wy = waterSurfaceY - 1; wy >= scanMinimum; wy--) {
            int wSecY = wy >> 4;
            int wSecIdx = wSecY - minSecY;
            DecodedSection wSec = (sectionArray != null && wSecIdx >= 0 && wSecIdx < secSpan) ? sectionArray[wSecIdx] : null;
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
            break;
        }

        if (floorY == CaveRegion.NO_SURFACE) {
            floorY = Math.max(scanMinimum, waterSurfaceY - depth);
            floorRawColor = 0xFF4D4D54;
        }

        float amount = Math.min(0.82f, 0.34f + Math.max(1, depth) * 0.055f);
        float attenuation = Math.max(0.72f, (float) Math.pow(0.982f, Math.max(0, depth - 2)));
        int floorRed = floorRawColor & 0xFF;
        int floorGreen = (floorRawColor >>> 8) & 0xFF;
        int floorBlue = (floorRawColor >>> 16) & 0xFF;
        int waterRed = 0x3F;
        int waterGreen = 0x76;
        int waterBlue = 0xE4;

        int red = Math.round((floorRed + (waterRed - floorRed) * amount) * attenuation);
        int green = Math.round((floorGreen + (waterGreen - floorGreen) * amount) * attenuation);
        int blue = Math.round((floorBlue + (waterBlue - floorBlue) * amount) * attenuation);
        int waterBlended = 0xFF000000 | (Math.min(255, blue) << 16) | (Math.min(255, green) << 8) | Math.min(255, red);

        int lightY = Math.min(scanMaximum, waterSurfaceY + 1);
        int lightSecY = lightY >> 4;
        int lightSecIdx = lightSecY - minSecY;
        DecodedSection lightSec = (sectionArray != null && lightSecIdx >= 0 && lightSecIdx < secSpan) ? sectionArray[lightSecIdx] : null;
        int blockLight = (lightSec != null) ? lightSec.getBlockLight(lx, lightY & 15, lz) : 0;
        int skyLight = (hasSkyLight && lightSec != null) ? lightSec.getSkyLight(lx, lightY & 15, lz) : 0;
        int shadeOffset = Math.round((floorY - bandCenterY) / 8.0f);

        regionHeights[regIdx] = (short) floorY;
        regionRawColors[regIdx] = applyCaveLighting(waterBlended, blockLight, skyLight, shadeOffset, false);
        regionKinds[regIdx] = DecodedSection.KIND_WATER;
    }

    private static final class DecodedSection {
        static final byte KIND_AIR = 0;
        static final byte KIND_SOLID_GROUND = 1;
        static final byte KIND_WATER = 2;
        static final byte KIND_LAVA = 3;
        static final byte KIND_PASSABLE_SOLID = 4;
        static final byte KIND_THIN_FLOOR = 5;
        static final byte KIND_INVISIBLE_PASS = 6;

        final int[] paletteColors;
        final byte[] paletteKinds;
        final byte[] paletteEmissions;
        final byte[] blockLight;
        final byte[] skyLight;
        final long[] data;
        final int bitsPerEntry;
        final int valuesPerLong;
        final long mask;
        final boolean singleBlock;

        private DecodedSection(int[] paletteColors, byte[] paletteKinds, byte[] paletteEmissions,
                byte[] blockLight, byte[] skyLight,
                long[] data, int bitsPerEntry, int valuesPerLong, long mask, boolean singleBlock) {
            this.paletteColors = paletteColors;
            this.paletteKinds = paletteKinds;
            this.paletteEmissions = paletteEmissions;
            this.blockLight = blockLight;
            this.skyLight = skyLight;
            this.data = data;
            this.bitsPerEntry = bitsPerEntry;
            this.valuesPerLong = valuesPerLong;
            this.mask = mask;
            this.singleBlock = singleBlock;
        }

        static DecodedSection decode(CompoundTag secTag) {
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

            int[] colors = new int[size];
            byte[] kinds = new byte[size];
            byte[] emissions = new byte[size];
            for (int i = 0; i < size; i++) {
                String name = pal.getCompound(i).getString("Name");
                if (name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air")) {
                    kinds[i] = KIND_AIR;
                    colors[i] = 0;
                    emissions[i] = 0;
                } else if (name.equals("minecraft:water") || name.equals("minecraft:bubble_column")) {
                    kinds[i] = KIND_WATER;
                    colors[i] = 0xFF8A4A1C;
                    emissions[i] = 0;
                } else if (name.equals("minecraft:lava")) {
                    kinds[i] = KIND_LAVA;
                    colors[i] = resolveBlockNameColor(name);
                    emissions[i] = 15;
                } else if (name.equals("minecraft:fire") || name.equals("minecraft:soul_fire")) {
                    kinds[i] = KIND_LAVA;
                    colors[i] = name.equals("minecraft:soul_fire") ? 0xFFFFAA00 : 0xFF00A2FF;
                    emissions[i] = 15;
                } else {
                    byte classifiedKind = classifyBlockNameKind(name);
                    kinds[i] = classifiedKind;
                    colors[i] = classifiedKind == KIND_INVISIBLE_PASS ? 0 : resolveBlockNameColor(name);
                    emissions[i] = resolveBlockNameEmission(name);
                }
            }

            if (size == 1) {
                return new DecodedSection(colors, kinds, emissions, blockLight, skyLight, null, 0, 0, 0L, true);
            }

            long[] data = bs.getLongArray("data");
            if (data.length == 0) return null;
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(size - 1));
            int valuesPerLong = 64 / bits;
            long mask = (1L << bits) - 1L;
            return new DecodedSection(colors, kinds, emissions, blockLight, skyLight, data, bits, valuesPerLong, mask, false);
        }

        private static byte classifyBlockNameKind(String name) {
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
                            return KIND_INVISIBLE_PASS;
                        }
                        if (CaveMode.isShortFloorBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, state)) {
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
            return isBlockPassable(name) ? KIND_PASSABLE_SOLID : KIND_SOLID_GROUND;
        }

        private static byte resolveBlockNameEmission(String name) {
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

        int getKind(int lx, int ly, int lz) {
            return paletteKinds[getPaletteIndex(lx, ly, lz)];
        }

        int getColor(int lx, int ly, int lz) {
            return paletteColors[getPaletteIndex(lx, ly, lz)];
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

    private static int resolveBlockNameColor(String name) {
        Integer cached = BLOCK_COLOR_CACHE.get(name);
        if (cached != null) return cached;
        int abgr = 0xFF808080; // Default stone
        try {
            int rgb = MapTextureManager.getInstance().resolveBlockColor(name, MapConfig.blockColourMode);
            if (rgb != 0 && rgb != 0xFFFFFFFF) {
                rgb &= 0x00FFFFFF;
                if (MapConfig.blockColourMode == 0) {
                    boolean isLava = name.equals("minecraft:lava");
                    boolean isLeaves = name.contains("leaves");
                    boolean isCherry = name.contains("cherry");
                    boolean isGrass = name.contains("grass") || name.equals("minecraft:tall_grass") || name.equals("minecraft:fern");
                    boolean isWood = name.contains("wood") || name.contains("log") || name.contains("planks");
                    if (isGrass) {
                        rgb = SurfaceColorizer.applyBiomeTint(0xFF000000 | rgb, 0xFF000000 | 0x7FB238, 0.90f) & 0x00FFFFFF;
                    } else if (isLeaves && !isCherry) {
                        rgb = SurfaceColorizer.applyBiomeTint(0xFF000000 | rgb, 0xFF000000 | 0x567D28, 0.95f) & 0x00FFFFFF;
                    }
                    if (!isLava) {
                        rgb = ChunkScanner.getInstance().makeColorRich(rgb, isLeaves, isCherry, isGrass, isWood);
                    }
                }
                int r = (rgb >>> 16) & 0xFF;
                int g = (rgb >>> 8) & 0xFF;
                int b = rgb & 0xFF;
                abgr = 0xFF000000 | (b << 16) | (g << 8) | r;
            } else {
                ResourceLocation rl = ResourceLocation.tryParse(name);
                if (rl != null) {
                    Block blk = BuiltInRegistries.BLOCK.get(rl);
                    if (blk != null) {
                        BlockState bs = blk.defaultBlockState();
                        MapColor mc = bs.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                        if (mc != null && mc != MapColor.NONE) {
                            int fallbackRgb = mc.col;
                            abgr = 0xFF000000 | ((fallbackRgb & 0xFF) << 16) | (fallbackRgb & 0x00FF00) | ((fallbackRgb >>> 16) & 0xFF);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        if ((abgr == 0xFF808080 || abgr == 0) && name.equals("minecraft:lava")) {
            abgr = 0xFF1464D4; // Warm textured lava in ABGR (RGB: 0xD46414)
        }
        BLOCK_COLOR_CACHE.put(name, abgr);
        return abgr;
    }

    private static float calculateRegionCaveShade(short[] heights, int px, int pz, int centerY) {
        if (MapConfig.terrainSlopes <= 0) return 1.0f;
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

        if (MapConfig.terrainSlopes == 1) {
            return Math.max(0.86f, Math.min(1.14f, 1.0f + (centerY - north) * 0.030f));
        }
        float gradientX = Math.max(-8.0f, Math.min(8.0f, (west - east) * 0.5f));
        float gradientZ = Math.max(-8.0f, Math.min(8.0f, (north - south) * 0.5f));
        float directional = gradientX * 0.045f + gradientZ * 0.062f;
        int rim = Math.max(Math.max(north, south), Math.max(west, east));
        float pit = Math.min(0.30f, Math.max(0, rim - centerY) * 0.050f);
        float edge = Math.min(0.16f, (Math.abs(west - east) + Math.abs(north - south)) * 0.012f);
        return Math.max(0.62f, Math.min(1.24f, 1.0f + directional - pit - edge));
    }

    public static int applyCaveLighting(int abgr, int light, int verticalOffset) {
        return applyCaveLighting(abgr, light, 0, verticalOffset, false);
    }

    public static int applyCaveLighting(int abgr, int light, int verticalOffset, boolean isLava) {
        return applyCaveLighting(abgr, light, 0, verticalOffset, isLava);
    }

    public static int applyCaveLighting(int abgr, int blockLight, int skyLight, int verticalOffset, boolean isLava) {
        int clampedBlock = Math.max(0, Math.min(15, blockLight));
        int clampedSky = Math.max(0, Math.min(15, skyLight));
        int effectiveLight = isLava ? 15 : Math.max(clampedBlock, clampedSky);
        float normalized = effectiveLight / 15.0f;
        float heightShade = Math.max(0.82f, Math.min(1.10f, 1.0f + verticalOffset * 0.025f));
        float lightCurve = 0.50f + 0.50f * (float) Math.pow(normalized, 0.85f);
        float brightness = Math.max(0.42f, Math.min(1.08f, lightCurve * heightShade));

        int red = Math.round((abgr & 0xFF) * brightness);
        int green = Math.round(((abgr >>> 8) & 0xFF) * brightness);
        int blue = Math.round(((abgr >>> 16) & 0xFF) * brightness);

        if (!isLava && clampedBlock > 5 && clampedBlock >= clampedSky) {
            float warmth = Math.min(0.24f, ((clampedBlock - 5) / 10.0f) * 0.24f);
            red = Math.round(red + (255 - red) * warmth);
            green = Math.round(green + (190 - green) * warmth);
            blue = Math.round(blue + (90 - blue) * warmth);
        }
        red = Math.max(0, Math.min(255, red));
        green = Math.max(0, Math.min(255, green));
        blue = Math.max(0, Math.min(255, blue));
        return 0xFF000000 | (blue << 16) | (green << 8) | red;
    }

    private static float calculateFastCaveShade(short[] heights, int pixel, int centerY) {
        if (MapConfig.terrainSlopes <= 0) return 1.0f;
        int ix = pixel & 15;
        int iz = pixel >>> 4;
        int north = (iz > 0 && heights[pixel - 16] != CaveRegion.NO_SURFACE) ? heights[pixel - 16] : centerY;
        if (MapConfig.terrainSlopes == 1) {
            return Math.max(0.86f, Math.min(1.14f, 1.0f + (centerY - north) * 0.030f));
        }
        int south = (iz < 15 && heights[pixel + 16] != CaveRegion.NO_SURFACE) ? heights[pixel + 16] : centerY;
        int west = (ix > 0 && heights[pixel - 1] != CaveRegion.NO_SURFACE) ? heights[pixel - 1] : centerY;
        int east = (ix < 15 && heights[pixel + 1] != CaveRegion.NO_SURFACE) ? heights[pixel + 1] : centerY;
        float gradientX = Math.max(-8.0f, Math.min(8.0f, (west - east) * 0.5f));
        float gradientZ = Math.max(-8.0f, Math.min(8.0f, (north - south) * 0.5f));
        float directional = gradientX * 0.045f + gradientZ * 0.062f;
        int rim = Math.max(Math.max(north, south), Math.max(west, east));
        float pit = Math.min(0.30f, Math.max(0, rim - centerY) * 0.050f);
        float edge = Math.min(0.16f, (Math.abs(west - east) + Math.abs(north - south)) * 0.012f);
        return Math.max(0.62f, Math.min(1.24f, 1.0f + directional - pit - edge));
    }

    private static int applyAbgrShade(int abgr, float shade) {
        int alpha = (abgr >>> 24) & 0xFF;
        int red = Math.max(0, Math.min(255, Math.round((abgr & 0xFF) * shade)));
        int green = Math.max(0, Math.min(255, Math.round(((abgr >>> 8) & 0xFF) * shade)));
        int blue = Math.max(0, Math.min(255, Math.round(((abgr >>> 16) & 0xFF) * shade)));
        return (alpha << 24) | (blue << 16) | (green << 8) | red;
    }

    private static int applyCaveShade(int abgr, int offset) {
        float shade = Math.max(0.60f, Math.min(1.20f, 1.0f + offset * 0.025f));
        int alpha = (abgr >>> 24) & 0xFF;
        int red = Math.max(0, Math.min(255, Math.round((abgr & 0xFF) * shade)));
        int green = Math.max(0, Math.min(255, Math.round(((abgr >>> 8) & 0xFF) * shade)));
        int blue = Math.max(0, Math.min(255, Math.round(((abgr >>> 16) & 0xFF) * shade)));
        return (alpha << 24) | (blue << 16) | (green << 8) | red;
    }

    private static boolean isCaveDimension(Level level) {
        return level != null && (level.dimension() == Level.NETHER || !level.dimensionType().hasSkyLight());
    }

    private void saveAllDirtyRegions() {
        synchronized (regions) {
            for (RegionHolder holder : regions.values()) {
                if (holder.region.isDirty() && holder.region.hasContent()) {
                    holder.region.setDirty(false);
                    saveRegionToDisk(holder.region, holder.region.band, holder.region.rx, holder.region.rz);
                }
            }
        }
    }

    private CaveRegion materialize(int layerY, int rx, int rz, boolean createIfMissing) {
        return materialize(layerY, rx, rz, createIfMissing, false);
    }

    private CaveRegion materialize(int layerY, int rx, int rz, boolean createIfMissing, boolean allowAnvilSlice) {
        int band = (layerY == Integer.MIN_VALUE ? Integer.MIN_VALUE : CaveLayerBand.key(CaveView.LAYERED, layerY));
        String key = (band == Integer.MIN_VALUE ? "full" : String.valueOf(band)) + ":" + rx + ',' + rz;
        CaveRegion target = null;
        boolean newlyCreated = false;
        boolean needsBackfill = false;
        synchronized (regions) {
            RegionHolder holder = regions.get(key);
            if (holder != null) {
                target = holder.region;
                boolean layerMismatch = layerY != Integer.MIN_VALUE
                        && target.isAnvilSliced()
                        && target.getWrittenAnvilLayerY() != layerY;
                if (allowAnvilSlice && (!target.isAnvilSliced() || layerMismatch) && target.markAnvilSlicing()) {
                    needsBackfill = true;
                } else {
                    return target;
                }
            } else {
                if (!createIfMissing) return null;
                target = new CaveRegion(rx, rz, band, layerGeneration.get());
                regions.put(key, new RegionHolder(target, 0L));
                newlyCreated = true;
                trimCompatRegions();
            }
        }

        if (needsBackfill) {
            CaveRegion asyncTarget = target;
            diskExecutor.execute(() -> {
                try {
                    File diskFile = getRegionDiskFile(band, rx, rz);
                    if (diskFile != null && diskFile.exists() && !asyncTarget.isAnvilSliced()) {
                        CaveRegion temp = new CaveRegion(rx, rz, band);
                        if (loadRegionFromDisk(temp, band, rx, rz)) {
                            asyncTarget.mergeMissingPixels(temp.getPixelsDirect(), temp.getHeightsDirect());
                            if (temp.isAnvilSliced() && temp.getWrittenAnvilLayerY() == layerY) {
                                asyncTarget.setAnvilSliced(true);
                                asyncTarget.setWrittenAnvilLayerY(temp.getWrittenAnvilLayerY());
                            }
                        }
                    }
                    if ((!asyncTarget.isAnvilSliced() || asyncTarget.getWrittenAnvilLayerY() != layerY) && allowAnvilSlice) {
                        sliceAnvilRegion(asyncTarget, layerY, rx, rz);
                        if (asyncTarget.hasContent()) {
                            saveRegionToDisk(asyncTarget, band, rx, rz);
                        }
                    }
                    if (asyncTarget.hasContent()) {
                        CaveTextureManager.getInstance().markRegionTextureDirty(layerY, rx, rz);
                    }
                } finally {
                    asyncTarget.finishAnvilSlicing();
                    asyncTarget.markLoaded();
                }
            });
            return target;
        }

        if (newlyCreated) {
            CaveRegion asyncTarget = target;
            diskExecutor.execute(() -> {
                try {
                    boolean loaded = loadRegionFromDisk(asyncTarget, band, rx, rz);
                    boolean needsSlice = !loaded || !asyncTarget.isAnvilSliced()
                            || (layerY != Integer.MIN_VALUE && asyncTarget.getWrittenAnvilLayerY() != layerY);
                    if (needsSlice && allowAnvilSlice) {
                        if (asyncTarget.markAnvilSlicing()) {
                            try {
                                sliceAnvilRegion(asyncTarget, layerY, rx, rz);
                                if (asyncTarget.hasContent()) {
                                    saveRegionToDisk(asyncTarget, band, rx, rz);
                                }
                            } finally {
                                asyncTarget.finishAnvilSlicing();
                            }
                        }
                    }
                    if (asyncTarget.hasContent()) {
                        CaveTextureManager.getInstance().markRegionTextureDirty(layerY, rx, rz);
                    }
                } finally {
                    asyncTarget.markLoaded();
                }
            });
        }
        return target;
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
        private final long generation;
        private final int[] pixels = new int[512 * 512];
        private final short[] heights = new short[512 * 512];
        /** Per-chunk (32x32) written Top-Y layer so intra-band layer changes trigger rescans. */
        private final short[] writtenChunkLayerY = new short[32 * 32];
        /** Pixels written from currently loaded Minecraft chunks; archive jobs may not replace them. */
        private final BitSet livePixels = new BitSet(512 * 512);
        private final BitSet liveChunks = new BitSet(32 * 32);
        private final ReentrantLock lock = new ReentrantLock();
        private volatile boolean loaded;
        private volatile boolean exactSnapshot;
        private volatile boolean closed;
        private volatile boolean hasContent;
        private volatile boolean dirty;
        private volatile boolean anvilSliced;
        private volatile int writtenAnvilLayerY = Integer.MAX_VALUE;
        private final AtomicBoolean anvilSlicing = new AtomicBoolean(false);

        public CaveRegion(int rx, int rz) {
            this(rx, rz, Integer.MIN_VALUE, 0L);
        }

        public CaveRegion(int rx, int rz, int band) {
            this(rx, rz, band, 0L);
        }

        public CaveRegion(int rx, int rz, int band, long generation) {
            this.rx = rx;
            this.rz = rz;
            this.band = band;
            this.generation = generation;
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
        public boolean hasExactSnapshot() { return exactSnapshot && !closed; }
        public boolean hasContent() { return hasContent && !closed; }
        public boolean isDirty() { return dirty && !closed; }
        public void setDirty(boolean dirty) { if (!closed) this.dirty = dirty; }
        public void markLoaded() { if (!closed) loaded = true; }
        public void markExactSnapshotLoaded() {
            if (!closed) {
                exactSnapshot = true;
                loaded = true;
            }
        }
        public boolean isAnvilSliced() { return anvilSliced && !closed; }
        public void setAnvilSliced(boolean sliced) { if (!closed) this.anvilSliced = sliced; }
        public int getWrittenAnvilLayerY() { return writtenAnvilLayerY; }
        public void setWrittenAnvilLayerY(int layerY) { this.writtenAnvilLayerY = layerY; }
        public boolean markAnvilSlicing() { return anvilSlicing.compareAndSet(false, true); }
        public void finishAnvilSlicing() { anvilSlicing.set(false); }
        public void markHasContent() { if (!closed) this.hasContent = true; }
        public BitSet getLivePixelsDirect() { return livePixels; }
        public long getGeneration() { return generation; }
        public boolean isClosed() { return closed; }
        public void lock() { lock.lock(); }
        public void unlock() { lock.unlock(); }
        public void close() { closed = true; }
        public int[] getPixelsDirect() { return pixels; }
        public short[] getHeightsDirect() { return heights; }

        public int[] snapshotPixels() {
            lock.lock();
            try { return Arrays.copyOf(pixels, pixels.length); }
            finally { lock.unlock(); }
        }

        public void replacePixels(int[] source) {
            lock.lock();
            try {
                if (!closed) {
                    System.arraycopy(source, 0, pixels, 0, Math.min(source.length, pixels.length));
                    hasContent = true;
                }
            } finally {
                lock.unlock();
            }
        }

        /**
         * Merges an asynchronously loaded cache snapshot without overwriting pixels
         * already produced by the live scanner.
         */
        public void mergeMissingPixels(int[] source) {
            mergeMissingPixels(source, null);
        }

        public void mergeMissingPixels(int[] source, short[] sourceHeights) {
            lock.lock();
            try {
                if (closed) return;
                int length = Math.min(source.length, pixels.length);
                for (int i = 0; i < length; i++) {
                    if (pixels[i] == 0 && source[i] != 0) {
                        pixels[i] = source[i];
                        if (sourceHeights != null && i < sourceHeights.length) {
                            heights[i] = sourceHeights[i];
                        }
                        hasContent = true;
                    }
                }
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

        public boolean setColor(int px, int pz, int abgrColor) {
            int currentHeight = getHeight(px, pz);
            return setPixel(px, pz, abgrColor,
                    abgrColor == 0 ? NO_SURFACE : currentHeight,
                    band, abgrColor != 0);
        }

        public boolean setPixel(int px, int pz, int abgrColor, int surfaceY, int layerY, boolean markLive) {
            lock.lock();
            try {
                if (closed) return false;
                int index = pz * 512 + px;
                short clampedHeight = (abgrColor == 0 || surfaceY == NO_SURFACE)
                        ? NO_SURFACE
                        : (short) Math.max(-32760, Math.min(32760, surfaceY));
                if (markLive && abgrColor != 0) {
                    livePixels.set(index);
                    int chunkIdx = ((pz >> 4) << 5) | (px >> 4);
                    if (writtenChunkLayerY[chunkIdx] == UNWRITTEN_LAYER) {
                        writtenChunkLayerY[chunkIdx] = encodeLayerY(layerY);
                    }
                } else if (abgrColor == 0) {
                    livePixels.clear(index);
                }
                if (abgrColor != 0) hasContent = true;
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
                int chunkIdx = czInRegion * 32 + cxInRegion;
                return liveChunks.get(chunkIdx)
                        && writtenChunkLayerY[chunkIdx] == encodeLayerY(layerY);
            } finally {
                lock.unlock();
            }
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
