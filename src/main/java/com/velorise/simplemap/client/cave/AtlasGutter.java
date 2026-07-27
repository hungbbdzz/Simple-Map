package com.velorise.simplemap.client.cave;

/**
 * Builds a one-texel replicated border around an atlas slot.
 *
 * <p>Linear minification samples slightly outside the nominal UV rectangle at
 * slot edges. Replicating the edge texels keeps that sample inside the same map
 * node instead of blending with an unrelated neighbouring atlas slot.</p>
 */
final class AtlasGutter {
    static final int SIZE = 1;

    private AtlasGutter() {
    }

    static int pitch(int contentSize) {
        return contentSize + SIZE * 2;
    }

    static void copyOnePixelBorder(int[] source, int contentSize, int[] destination) {
        int pitch = pitch(contentSize);
        if (source == null || source.length < contentSize * contentSize) {
            throw new IllegalArgumentException("Atlas gutter source is too small");
        }
        if (destination == null || destination.length < pitch * pitch) {
            throw new IllegalArgumentException("Atlas gutter destination is too small");
        }

        for (int y = 0; y < contentSize; y++) {
            int sourceRow = y * contentSize;
            int destinationRow = (y + SIZE) * pitch + SIZE;
            System.arraycopy(source, sourceRow, destination, destinationRow, contentSize);
            destination[destinationRow - 1] = source[sourceRow];
            destination[destinationRow + contentSize] = source[sourceRow + contentSize - 1];
        }

        int firstInteriorRow = SIZE * pitch;
        int lastInteriorRow = (SIZE + contentSize - 1) * pitch;
        System.arraycopy(destination, firstInteriorRow, destination, 0, pitch);
        System.arraycopy(destination, lastInteriorRow, destination,
                (pitch - 1) * pitch, pitch);
    }
}
