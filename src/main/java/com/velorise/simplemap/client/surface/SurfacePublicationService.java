package com.velorise.simplemap.client.surface;

import com.velorise.simplemap.client.gpu.MapGpuPageTableService;
import com.velorise.simplemap.client.gpu.MapUploadEngine;
import com.velorise.simplemap.client.gpu.PageTableEntry;
import com.velorise.simplemap.client.gpu.TileKey;
import com.velorise.simplemap.client.gpu.UploadCommand;
import net.minecraft.resources.ResourceLocation;

/** Shared upload + page-table publication boundary for exact and coarse surface. */
public final class SurfacePublicationService {
    private static final SurfacePublicationService INSTANCE =
            new SurfacePublicationService();
    private SurfacePublicationService() { }
    public static SurfacePublicationService getInstance() { return INSTANCE; }

    public void executeInline(UploadCommand command) {
        MapUploadEngine.getInstance().executeInline(command);
    }

    public void stage(TileKey key, ResourceLocation texture, int slot,
            long storageGeneration, long contentRevision, int flags,
            float sourceX, float sourceY, int sourceSize, int atlasSize) {
        MapGpuPageTableService.getInstance().stage(key, texture, slot,
                storageGeneration, contentRevision, flags,
                sourceX, sourceY, sourceSize, atlasSize);
    }

    public void remove(TileKey key) {
        MapGpuPageTableService.getInstance().remove(key);
    }

    public PageTableEntry current(TileKey key) {
        MapGpuPageTableService.Resolved resolved =
                MapGpuPageTableService.getInstance().resolve(key);
        return resolved == null ? null : resolved.entry();
    }
}
