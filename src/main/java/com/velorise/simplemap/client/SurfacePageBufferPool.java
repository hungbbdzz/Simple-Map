package com.velorise.simplemap.client;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded reusable primitive storage for exact surface-page source snapshots.
 *
 * <p>The 68x68 halo snapshot is short-lived but relatively large. Allocating
 * three new arrays for every requested page creates young-generation pressure
 * exactly while Minecraft is also loading chunks. Ownership is transferred from
 * the render-thread capture stage to one CPU build and returned only after that
 * build reaches a terminal state.</p>
 */
final class SurfacePageBufferPool {
    private static final SurfacePageBufferPool INSTANCE = new SurfacePageBufferPool();
    private static final int MAX_RETAINED = 48;

    private final ConcurrentLinkedQueue<Buffer> available =
            new ConcurrentLinkedQueue<>();
    private final AtomicInteger retained = new AtomicInteger();

    private SurfacePageBufferPool() {
    }

    static SurfacePageBufferPool getInstance() {
        return INSTANCE;
    }

    Buffer acquire(int length) {
        int required = Math.max(1, length);
        Buffer buffer;
        while ((buffer = available.poll()) != null) {
            retained.decrementAndGet();
            if (buffer.length() == required) {
                buffer.prepare();
                return buffer;
            }
        }
        buffer = new Buffer(new long[required], new int[required], new byte[required]);
        buffer.prepare();
        return buffer;
    }

    void release(Buffer buffer) {
        if (buffer == null || !buffer.markReleased()) return;
        while (true) {
            int current = retained.get();
            if (current >= MAX_RETAINED) return;
            if (retained.compareAndSet(current, current + 1)) {
                available.offer(buffer);
                return;
            }
        }
    }

    static final class Buffer {
        private final long[] pixels;
        private final int[] tints;
        private final byte[] light;
        private boolean released;

        private Buffer(long[] pixels, int[] tints, byte[] light) {
            this.pixels = pixels;
            this.tints = tints;
            this.light = light;
        }

        long[] pixels() {
            return pixels;
        }

        int[] tints() {
            return tints;
        }

        byte[] light() {
            return light;
        }

        int length() {
            return pixels.length;
        }

        private synchronized void prepare() {
            released = false;
            Arrays.fill(pixels, MapBlockData.EMPTY_PACKED);
            Arrays.fill(tints, SurfaceTintData.UNKNOWN);
            Arrays.fill(light, (byte) 0);
        }

        private synchronized boolean markReleased() {
            if (released) return false;
            released = true;
            return true;
        }
    }
}
