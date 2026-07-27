package com.velorise.simplemap.client;

/**
 * Separate visible-demand policies for fullscreen maps and the minimap.
 *
 * <p>Fullscreen follows a stable viewport traversal split into small update
 * slices. Simple Map exposes its smaller 64-block leaves as screen rows so the
 * visible result advances left-to-right and top-to-bottom. Cursor movement is not
 * part of the load key or candidate priority.</p>
 *
 * <p>The minimap is intentionally different. It requests a compact exact-leaf
 * halo around the camera/player in centre-out order, allowing the small visible
 * area to become complete before unrelated fullscreen work.</p>
 */
public final class MapViewLoadPlanner {
    public static final int FULLSCREEN_SLICE_SIZE = 100;
    public static final int FULLSCREEN_SHORTLIST_SIZE = 10;
    public static final int MINIMAP_HALO_PAGES = 1;
    public static final int MINIMAP_MAX_RADIUS_PAGES = 3;

    private MapViewLoadPlanner() {
    }

    /** Persistent traversal state. Focus is deliberately not part of the key. */
    public static final class State {
        private String dimension = "";
        private int minX;
        private int maxX;
        private int minZ;
        private int maxZ;
        private int sliceIndex;
        private int sliceCount;
        private boolean configured;
        private long completedCycles;

        public boolean configure(String dimension, int minX, int maxX,
                int minZ, int maxZ) {
            String safeDimension = dimension == null ? "" : dimension;
            int safeMinX = Math.min(minX, maxX);
            int safeMaxX = Math.max(minX, maxX);
            int safeMinZ = Math.min(minZ, maxZ);
            int safeMaxZ = Math.max(minZ, maxZ);
            if (configured && this.dimension.equals(safeDimension)
                    && this.minX == safeMinX && this.maxX == safeMaxX
                    && this.minZ == safeMinZ && this.maxZ == safeMaxZ) return false;
            this.dimension = safeDimension;
            this.minX = safeMinX;
            this.maxX = safeMaxX;
            this.minZ = safeMinZ;
            this.maxZ = safeMaxZ;
            long total = totalPages();
            this.sliceCount = (int) Math.max(1L,
                    (total + FULLSCREEN_SLICE_SIZE - 1L)
                            / FULLSCREEN_SLICE_SIZE);
            this.sliceIndex = 0;
            this.completedCycles = 0L;
            this.configured = true;
            return true;
        }

        /**
         * Fills the current stable fullscreen update slice without advancing it.
         *
         * <p>Xaero's leaf unit is a large 512-block region. Simple Map's exact leaf
         * is only 64 blocks, so exposing the same column-major order directly would
         * create thin vertical fragments. The equivalent user-facing progression is
         * a stable screen-row traversal: left-to-right inside one row, then the next
         * row from top to bottom. The slice is held until its visible work settles.</p>
         */
        public int fillCurrentFullscreenSlice(Page[] output) {
            if (!configured || output == null || output.length == 0) return 0;
            int width = maxX - minX + 1;
            long total = totalPages();
            if (total <= 0L || width <= 0) return 0;
            long start = (long) sliceIndex * FULLSCREEN_SLICE_SIZE;
            long end = Math.min(total, start + FULLSCREEN_SLICE_SIZE);
            int count = 0;
            for (long ordinal = start; ordinal < end && count < output.length;
                    ordinal++) {
                int x = minX + (int) (ordinal % width);
                int z = minZ + (int) (ordinal / width);
                output[count++] = new Page(x, z, (int) ordinal);
            }
            return count;
        }

        /**
         * Advances only after the current slice has no missing or in-flight page.
         * This is the visible-load gate that prevents fast later tasks from making
         * the fullscreen map appear in random islands.
         */
        public void advanceFullscreenSlice() {
            if (!configured) return;
            sliceIndex++;
            if (sliceIndex >= sliceCount) {
                sliceIndex = 0;
                completedCycles++;
            }
        }

        /** Compatibility helper for tests/tools that intentionally consume a slice. */
        public int fillNextFullscreenSlice(Page[] output) {
            int count = fillCurrentFullscreenSlice(output);
            advanceFullscreenSlice();
            return count;
        }

        public long completedCycles() {
            return completedCycles;
        }

        public int currentSliceIndex() {
            return sliceIndex;
        }

        public int sliceCount() {
            return sliceCount;
        }

        public void clear() {
            dimension = "";
            minX = maxX = minZ = maxZ = 0;
            sliceIndex = 0;
            sliceCount = 0;
            completedCycles = 0L;
            configured = false;
        }

        private long totalPages() {
            return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        }
    }

    /**
     * Fills a bounded minimap exact-leaf halo in deterministic centre-out order.
     * The visible rectangle is expanded by one page, then capped to a small radius
     * so extreme minimap zoom cannot flood the exact pipeline.
     */
    public static int fillMinimapHalo(int visibleMinX, int visibleMaxX,
            int visibleMinZ, int visibleMaxZ, int centerX, int centerZ,
            Page[] output) {
        if (output == null || output.length == 0) return 0;
        int minX = Math.max(Math.min(visibleMinX, visibleMaxX) - MINIMAP_HALO_PAGES,
                centerX - MINIMAP_MAX_RADIUS_PAGES);
        int maxX = Math.min(Math.max(visibleMinX, visibleMaxX) + MINIMAP_HALO_PAGES,
                centerX + MINIMAP_MAX_RADIUS_PAGES);
        int minZ = Math.max(Math.min(visibleMinZ, visibleMaxZ) - MINIMAP_HALO_PAGES,
                centerZ - MINIMAP_MAX_RADIUS_PAGES);
        int maxZ = Math.min(Math.max(visibleMinZ, visibleMaxZ) + MINIMAP_HALO_PAGES,
                centerZ + MINIMAP_MAX_RADIUS_PAGES);
        int maximumRadius = Math.max(
                Math.max(Math.abs(centerX - minX), Math.abs(maxX - centerX)),
                Math.max(Math.abs(centerZ - minZ), Math.abs(maxZ - centerZ)));
        int count = 0;
        int ordinal = 0;
        for (int radius = 0; radius <= maximumRadius && count < output.length;
                radius++) {
            for (int z = centerZ - radius;
                    z <= centerZ + radius && count < output.length; z++) {
                for (int x = centerX - radius;
                        x <= centerX + radius && count < output.length; x++) {
                    if (Math.max(Math.abs(x - centerX), Math.abs(z - centerZ))
                            != radius) continue;
                    if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
                    output[count++] = new Page(x, z, ordinal++);
                }
            }
        }
        return count;
    }

    public record Page(int x, int z, int ordinal) {
        public int distanceSquaredTo(int focusX, int focusZ) {
            int dx = x - focusX;
            int dz = z - focusZ;
            return dx * dx + dz * dz;
        }
    }
}
