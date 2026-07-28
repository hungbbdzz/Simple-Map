package com.velorise.simplemap.client.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reference-count-free exclusive staging buffer lease. */
public final class UploadBufferLease implements AutoCloseable {
    private final MapUploadEngine owner;
    private final ByteBuffer buffer;
    private final int sizeClass;
    private final AtomicBoolean closed = new AtomicBoolean();

    UploadBufferLease(MapUploadEngine owner, ByteBuffer buffer, int sizeClass) {
        this.owner = owner;
        this.buffer = buffer.order(ByteOrder.nativeOrder());
        this.sizeClass = sizeClass;
    }

    public ByteBuffer buffer() {
        if (closed.get()) throw new IllegalStateException("closed upload lease");
        buffer.clear();
        return buffer;
    }

    public int capacity() { return buffer.capacity(); }
    int sizeClass() { return sizeClass; }
    ByteBuffer raw() { return buffer; }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) owner.release(this);
    }
}
