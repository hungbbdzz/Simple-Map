package com.velorise.simplemap.client;

import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hard byte admission for map work that retains source, projection, LOD, upload
 * or IO buffers. Queue cost remains useful for scheduling pressure, but it is not
 * a memory bound. A task must own a lease before it is allowed to allocate its
 * estimated retained payload.
 */
public final class MapMemoryLeaseManager {
    public enum Category {
        PENDING_SOURCE,
        PENDING_PROJECTION,
        PENDING_LOD,
        PENDING_UPLOAD,
        IO_BUFFER
    }

    public record CategorySnapshot(long usedBytes, long foregroundLimitBytes,
            long hardLimitBytes) { }

    public record Snapshot(EnumMap<Category, CategorySnapshot> categories,
            long deniedCount) { }

    public static final class Lease implements AutoCloseable {
        private final Category category;
        private final long bytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(Category category, long bytes) {
            this.category = category;
            this.bytes = bytes;
        }

        public Category category() { return category; }
        public long bytes() { return bytes; }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) return;
            USED.get(category).addAndGet(-bytes);
        }
    }

    private record Budget(long foregroundLimitBytes, long hardLimitBytes) { }

    private static final EnumMap<Category, AtomicLong> USED =
            new EnumMap<>(Category.class);
    private static final EnumMap<Category, Budget> BUDGETS =
            new EnumMap<>(Category.class);
    private static final AtomicLong DENIED = new AtomicLong();

    static {
        long heap = Runtime.getRuntime().maxMemory();
        put(Category.PENDING_SOURCE,
                bounded(heap / 48L, 24L << 20, 128L << 20));
        put(Category.PENDING_PROJECTION,
                bounded(heap / 40L, 24L << 20, 160L << 20));
        put(Category.PENDING_LOD,
                bounded(heap / 96L, 12L << 20, 80L << 20));
        put(Category.PENDING_UPLOAD,
                Math.max(8L << 20, MapMemoryBudgetPolicy.pendingUploadBudgetBytes()));
        put(Category.IO_BUFFER,
                bounded(heap / 128L, 8L << 20, 64L << 20));
        for (Category category : Category.values()) {
            USED.put(category, new AtomicLong());
        }
    }

    private MapMemoryLeaseManager() { }

    /**
     * Attempts to reserve bytes. The last 15% of each category is protected for
     * minimap work so fullscreen reconstruction and Full Cave cannot starve it.
     */
    public static Lease tryAcquire(Category category, long requestedBytes,
            MapRequestLane lane) {
        if (category == null) return null;
        long bytes = Math.max(1L, requestedBytes);
        Budget budget = BUDGETS.get(category);
        long limit = lane == MapRequestLane.MINIMAP
                ? budget.hardLimitBytes : budget.foregroundLimitBytes;
        AtomicLong used = USED.get(category);
        while (true) {
            long current = used.get();
            if (bytes > limit || current > limit - bytes) {
                DENIED.incrementAndGet();
                return null;
            }
            if (used.compareAndSet(current, current + bytes)) {
                return new Lease(category, bytes);
            }
        }
    }

    public static Snapshot snapshot() {
        EnumMap<Category, CategorySnapshot> categories =
                new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            Budget budget = BUDGETS.get(category);
            categories.put(category, new CategorySnapshot(USED.get(category).get(),
                    budget.foregroundLimitBytes, budget.hardLimitBytes));
        }
        return new Snapshot(categories, DENIED.get());
    }

    private static void put(Category category, long hardLimitBytes) {
        long reserve = Math.max(1L << 20, hardLimitBytes * 15L / 100L);
        BUDGETS.put(category,
                new Budget(Math.max(1L, hardLimitBytes - reserve), hardLimitBytes));
    }

    private static long bounded(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
