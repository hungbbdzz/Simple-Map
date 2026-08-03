package com.velorise.simplemap.client;

import java.util.HashSet;
import java.util.Set;

/** Dependency-free primitive-buffer and centre-out viewport invariants. */
public final class MapViewLoadPlannerCheck {
    private MapViewLoadPlannerCheck() { }

    public static void main(String[] args) {
        fullscreenPlanIsCompleteAndAllocationFree();
        minimapHaloStartsAtFocus();
        System.out.println("Simple Map viewport load planner checks passed");
    }

    private static void fullscreenPlanIsCompleteAndAllocationFree() {
        MapViewLoadPlanner.State planner = new MapViewLoadPlanner.State();
        planner.configure("overworld", -50, 73, -31, 48);
        long[] pages = new long[MapViewLoadPlanner.FULLSCREEN_SLICE_SIZE];
        Set<Long> seen = new HashSet<>();
        int total = 0;
        int expected = 124 * 80;
        int slices = planner.sliceCount();
        for (int slice = 0; slice < slices; slice++) {
            int startOrdinal = planner.currentSliceStartOrdinal();
            int count = planner.fillCurrentFullscreenSlice(pages);
            for (int index = 0; index < count; index++) {
                require(startOrdinal + index == total,
                        "fullscreen ordinal authority has a gap");
                require(seen.add(pages[index]), "fullscreen plan contains duplicates");
                total++;
            }
            planner.advanceFullscreenSlice();
        }
        require(total == expected, "fullscreen plan lost visible pages");
        require(MapViewLoadPlanner.packedX(seen.iterator().next()) >= -50,
                "packed coordinate decode failed");
    }

    private static void minimapHaloStartsAtFocus() {
        long[] pages = new long[49];
        int count = MapViewLoadPlanner.fillMinimapHalo(
                0, 10, 0, 10, 5, 5, pages);
        require(count == pages.length, "minimap halo size changed");
        require(MapViewLoadPlanner.packedX(pages[0]) == 5
                        && MapViewLoadPlanner.packedZ(pages[0]) == 5,
                "minimap halo did not start at focus");
        for (int ordinal = 0; ordinal < count; ordinal++) {
            int x = MapViewLoadPlanner.packedX(pages[ordinal]);
            int z = MapViewLoadPlanner.packedZ(pages[ordinal]);
            int radius = Math.max(Math.abs(x - 5), Math.abs(z - 5));
            require(radius <= 3, "minimap halo escaped its exact-page cap");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
