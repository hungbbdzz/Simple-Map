package com.velorise.simplemap.client;

import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.locks.ReentrantLock;

/** In-memory color region for one bounded cave Top-Y layer. */
public final class CaveRegion {
    public final int rx;
    public final int rz;
    private final long generation;
    private final int[] pixels = new int[512 * 512];
    /** Pixels written from currently loaded Minecraft chunks; archive jobs may not replace them. */
    private final BitSet livePixels = new BitSet(512 * 512);
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean loaded;
    private volatile boolean exactSnapshot;
    private volatile boolean closed;

    public CaveRegion(int rx, int rz) {
        this(rx, rz, 0L);
    }

    public CaveRegion(int rx, int rz, long generation) {
        this.rx = rx;
        this.rz = rz;
        this.generation = generation;
    }

    public boolean isLoaded() { return loaded && !closed; }
    public boolean hasExactSnapshot() { return exactSnapshot && !closed; }
    public void markLoaded() { if (!closed) loaded = true; }
    public void markExactSnapshotLoaded() {
        if (!closed) {
            exactSnapshot = true;
            loaded = true;
        }
    }
    public long getGeneration() { return generation; }
    public boolean isClosed() { return closed; }
    public void lock() { lock.lock(); }
    public void unlock() { lock.unlock(); }
    public void close() { closed = true; }
    public int[] getPixelsDirect() { return pixels; }

    public int[] snapshotPixels() {
        lock.lock();
        try { return Arrays.copyOf(pixels, pixels.length); }
        finally { lock.unlock(); }
    }

    public void replacePixels(int[] source) {
        lock.lock();
        try {
            if (!closed) System.arraycopy(source, 0, pixels, 0, Math.min(source.length, pixels.length));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Merges an asynchronously loaded cache snapshot without overwriting pixels
     * already produced by the live scanner. Layer switches mark the placeholder
     * region usable immediately, so this merge is what prevents a late disk load
     * from replacing fresh cave data with an older black/sparse snapshot.
     */
    public void mergeMissingPixels(int[] source) {
        lock.lock();
        try {
            if (closed) return;
            int length = Math.min(source.length, pixels.length);
            for (int i = 0; i < length; i++) {
                if (pixels[i] == 0 && source[i] != 0) pixels[i] = source[i];
            }
        } finally {
            lock.unlock();
        }
    }

    public int getColor(int px, int pz) {
        lock.lock();
        try { return pixels[pz * 512 + px]; }
        finally { lock.unlock(); }
    }

    public boolean setColor(int px, int pz, int abgrColor) {
        lock.lock();
        try {
            if (closed) return false;
            int index = pz * 512 + px;
            livePixels.set(index);
            if (pixels[index] == abgrColor) return false;
            pixels[index] = abgrColor;
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean isLivePixel(int px, int pz) {
        lock.lock();
        try {
            return livePixels.get(pz * 512 + px);
        } finally {
            lock.unlock();
        }
    }

    /** Caller must hold this region's lock. */
    boolean isLivePixelLocked(int index) {
        return livePixels.get(index);
    }
}
