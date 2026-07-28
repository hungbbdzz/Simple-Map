package com.velorise.simplemap.client.cave.v2;

import com.velorise.simplemap.client.cave.UnifiedCaveTextureManager;

/** Cave publication boundary; upload implementation is replaceable by M9 backend. */
public final class CavePublicationService {
    private static final CavePublicationService INSTANCE =
            new CavePublicationService();
    private CavePublicationService() { }
    public static CavePublicationService getInstance() { return INSTANCE; }

    public void publish(boolean force) {
        UnifiedCaveTextureManager.getInstance().upload(force);
    }
}
