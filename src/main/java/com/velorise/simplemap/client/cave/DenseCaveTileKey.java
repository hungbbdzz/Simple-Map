package com.velorise.simplemap.client.cave;

record DenseCaveTileKey(int chunkX, int chunkZ, CaveView view, int layerY) {
    DenseCaveTileKey {
        layerY = DenseCaveTile.normalizeLayer(view, layerY);
    }

    static DenseCaveTileKey of(DenseCaveTile tile) {
        return new DenseCaveTileKey(tile.chunkX(), tile.chunkZ(),
                tile.view(), tile.layerY());
    }
}
