package com.velorise.simplemap.client.cave;

public final class DenseCaveTileContentEqualityCheck {
    public static void main(String[] args) {
        DenseCaveTile.Builder firstBuilder = new DenseCaveTile.Builder();
        firstBuilder.beginColumn();
        firstBuilder.set(3, 5, 0xFF665544, 12, 20,
                DenseCaveTile.FLAG_WATER, 9);
        DenseCaveTile first = firstBuilder.build(7, -4, CaveView.LAYERED,
                32, 37, 10L, DenseCaveTile.Source.WORLD_SAVE);

        DenseCaveTile.Builder secondBuilder = new DenseCaveTile.Builder();
        secondBuilder.beginColumn();
        secondBuilder.set(3, 5, 0xFF665544, 12, 20,
                DenseCaveTile.FLAG_WATER, 9);
        DenseCaveTile newerSamePayload = secondBuilder.build(7, -4,
                CaveView.LAYERED, 32, 37, 999L,
                DenseCaveTile.Source.WORLD_SAVE);

        require(first.sameProjectionContent(newerSamePayload),
                "revision-only change must not invalidate a page");

        DenseCaveTile.Builder changedBuilder = new DenseCaveTile.Builder();
        changedBuilder.beginColumn();
        changedBuilder.set(3, 5, 0xFF665545, 12, 20,
                DenseCaveTile.FLAG_WATER, 9);
        DenseCaveTile changed = changedBuilder.build(7, -4,
                CaveView.LAYERED, 32, 37, 1000L,
                DenseCaveTile.Source.WORLD_SAVE);
        require(!first.sameProjectionContent(changed),
                "pixel change must invalidate a page");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
