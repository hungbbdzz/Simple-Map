package com.velorise.simplemap.client.cave.v2;

import com.velorise.simplemap.client.cave.UnifiedCaveTextureManager;

/** Cave GPU-residency facade during migration from the unified god manager. */
public final class CaveResidencyService {
    private static final CaveResidencyService INSTANCE = new CaveResidencyService();
    private CaveResidencyService() { }
    public static CaveResidencyService getInstance() { return INSTANCE; }

    public UnifiedCaveTextureManager.DebugSnapshot summary() {
        return UnifiedCaveTextureManager.getInstance().debugSnapshot();
    }

    public void clear() { UnifiedCaveTextureManager.getInstance().clear(); }
}
