package com.velorise.simplemap.client.gpu;

/**
 * Coverage-preserving geometry budget helper. When a fine tile cannot fit, the
 * caller promotes its ancestor instead of dropping coverage.
 */
public final class MapInstancePlanner {
    public interface AncestorResolver {
        TileKey ancestor(TileKey key);
    }

    private final int maximumInstances;

    public MapInstancePlanner(int maximumInstances) {
        this.maximumInstances = Math.max(1, maximumInstances);
    }

    public MapInstancePlan compact(MapInstancePlan input,
            AncestorResolver resolver) {
        if (input == null || input.size() <= maximumInstances) return input;
        MapInstancePlan.Builder output = new MapInstancePlan.Builder();
        java.util.HashSet<TileKey> emitted = new java.util.HashSet<>();
        for (int index = 0; index < input.size(); index++) {
            TileKey selected = input.key(index);
            while (emitted.size() >= maximumInstances && resolver != null) {
                TileKey parent = resolver.ancestor(selected);
                if (parent == null || parent.equals(selected)) break;
                selected = parent;
            }
            if (emitted.size() >= maximumInstances && !emitted.contains(selected)) {
                continue;
            }
            if (!emitted.add(selected)) continue;
            output.add(selected, input.phase(index), input.x(index), input.y(index),
                    input.width(index), input.height(index));
        }
        return output.build(input.viewportRevision());
    }
}
