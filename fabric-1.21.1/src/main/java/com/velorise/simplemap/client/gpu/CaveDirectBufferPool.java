package com.velorise.simplemap.client.gpu;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Bounded native-buffer pool used by cave texture transfers.
 *
 * <p>The largest exact-page upload includes the one-pixel atlas gutter
 * (66x66 RGBA). Buffers are allocated once and returned after the OpenGL command
 * has copied their contents into either a PBO or the driver upload path.</p>
 */
public final class CaveDirectBufferPool implements AutoCloseable {
    public static final int MAX_UPLOAD_EDGE = 66;
    public static final int BUFFER_BYTES =
            MAX_UPLOAD_EDGE * MAX_UPLOAD_EDGE * Integer.BYTES;
    private static final int DEFAULT_POOL_SIZE = 4;

    private final ArrayDeque<ByteBuffer> available = new ArrayDeque<>();
    private final int maximumRetained;
    private boolean closed;

    public CaveDirectBufferPool() {
        this(DEFAULT_POOL_SIZE);
    }

    public CaveDirectBufferPool(int maximumRetained) {
        if (maximumRetained <= 0) {
            throw new IllegalArgumentException("maximumRetained must be positive");
        }
        this.maximumRetained = maximumRetained;
    }

    public synchronized ByteBuffer acquire(int requiredBytes) {
        if (closed) throw new IllegalStateException("Cave buffer pool is closed");
        if (requiredBytes <= 0 || requiredBytes > BUFFER_BYTES) {
            throw new IllegalArgumentException(
                    "Invalid cave transfer size: " + requiredBytes);
        }
        ByteBuffer buffer = available.pollFirst();
        if (buffer == null) buffer = MemoryUtil.memAlloc(BUFFER_BYTES);
        buffer.clear();
        buffer.limit(requiredBytes);
        return buffer;
    }

    public synchronized void release(ByteBuffer buffer) {
        if (buffer == null) return;
        buffer.clear();
        if (closed || buffer.capacity() != BUFFER_BYTES
                || available.size() >= maximumRetained) {
            MemoryUtil.memFree(buffer);
            return;
        }
        available.addFirst(buffer);
    }

    public synchronized int retainedCount() {
        return available.size();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        ByteBuffer buffer;
        while ((buffer = available.pollFirst()) != null) {
            MemoryUtil.memFree(buffer);
        }
    }
}
