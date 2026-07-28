package com.velorise.simplemap.client.gpu;

import com.velorise.simplemap.client.MapRequestLane;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared bounded upload admission and staging-buffer pool for every map
 * projection. The current backend executes upload actions on the render thread;
 * PBO/fence implementations can replace the backend without changing callers.
 */
public final class MapUploadEngine {
    public record Summary(int queued, int inFlight, long queuedBytes,
            long stagingBytes, long submitted, long committed, long rejected,
            long stale, long oversized) { }

    private static final int[] SIZE_CLASSES = {
            16 * 1024, 64 * 1024, 256 * 1024, 1024 * 1024, 4 * 1024 * 1024
    };
    private static final int MAX_COMMANDS = 2048;
    private static final long MAX_QUEUED_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_BUFFERS_PER_CLASS = 8;
    private static final MapUploadEngine INSTANCE = new MapUploadEngine();

    private final EnumMap<MapRequestLane, ArrayDeque<UploadCommand>> queues =
            new EnumMap<>(MapRequestLane.class);
    private final Map<Integer, ArrayDeque<ByteBuffer>> pools =
            new java.util.HashMap<>();
    private final AtomicLong stagingBytes = new AtomicLong();
    private long queuedBytes;
    private int queued;
    private int inFlight;
    private long submitted;
    private long committed;
    private long rejected;
    private long stale;
    private long oversized;

    private MapUploadEngine() {
        for (MapRequestLane lane : MapRequestLane.values()) {
            queues.put(lane, new ArrayDeque<>());
        }
        for (int size : SIZE_CLASSES) pools.put(size, new ArrayDeque<>());
    }

    public static MapUploadEngine getInstance() { return INSTANCE; }

    public synchronized UploadBufferLease acquire(int bytes) {
        int sizeClass = sizeClass(bytes);
        ArrayDeque<ByteBuffer> pool = pools.get(sizeClass);
        ByteBuffer buffer = pool == null ? null : pool.pollFirst();
        if (buffer == null) {
            buffer = ByteBuffer.allocateDirect(sizeClass);
            stagingBytes.addAndGet(sizeClass);
        }
        return new UploadBufferLease(this, buffer, sizeClass);
    }

    synchronized void release(UploadBufferLease lease) {
        if (lease == null) return;
        ArrayDeque<ByteBuffer> pool = pools.get(lease.sizeClass());
        if (pool != null && pool.size() < MAX_BUFFERS_PER_CLASS) {
            lease.raw().clear();
            pool.addLast(lease.raw());
        } else {
            stagingBytes.addAndGet(-lease.capacity());
        }
    }

    public synchronized boolean submit(UploadCommand command) {
        if (command == null) return false;
        if (queued >= MAX_COMMANDS
                || queuedBytes + command.byteCount() > MAX_QUEUED_BYTES) {
            rejected++;
            reject(command);
            return false;
        }
        queues.get(command.lane()).addLast(command);
        queued++;
        queuedBytes += command.byteCount();
        submitted++;
        if (command.byteCount() > 4 * 1024 * 1024) oversized++;
        return true;
    }

    /** Executes bounded commands on the render thread until the deadline. */
    public int drain(long deadlineNanos, int byteBudget) {
        int completedNow = 0;
        int remainingBytes = Math.max(0, byteBudget);
        while (System.nanoTime() < deadlineNanos) {
            UploadCommand command;
            synchronized (this) {
                command = pollNext();
                if (command == null) break;
                if (completedNow > 0 && command.byteCount() > remainingBytes) {
                    queues.get(command.lane()).addFirst(command);
                    queued++;
                    queuedBytes += command.byteCount();
                    break;
                }
                inFlight++;
            }
            try {
                if (!command.current()) {
                    synchronized (this) { stale++; }
                    reject(command);
                    continue;
                }
                command.uploadAction().run();
                if (command.committed() != null) command.committed().run();
                synchronized (this) { committed++; }
                completedNow++;
                remainingBytes = Math.max(0, remainingBytes - command.byteCount());
            } catch (RuntimeException exception) {
                synchronized (this) { rejected++; }
                reject(command);
            } finally {
                if (command.payload() != null) command.payload().close();
                synchronized (this) { inFlight--; }
            }
        }
        return completedNow;
    }

    /** Compatibility bridge used while old atlas owners are migrated. */
    public void executeInline(UploadCommand command) {
        if (command == null) return;
        synchronized (this) {
            submitted++;
            inFlight++;
        }
        try {
            if (!command.current()) {
                synchronized (this) { stale++; }
                reject(command);
                return;
            }
            command.uploadAction().run();
            if (command.committed() != null) command.committed().run();
            synchronized (this) { committed++; }
        } catch (RuntimeException exception) {
            synchronized (this) { rejected++; }
            reject(command);
            throw exception;
        } finally {
            if (command.payload() != null) command.payload().close();
            synchronized (this) { inFlight--; }
        }
    }

    public synchronized Summary summary() {
        return new Summary(queued, inFlight, queuedBytes, stagingBytes.get(),
                submitted, committed, rejected, stale, oversized);
    }

    public synchronized void clear() {
        for (ArrayDeque<UploadCommand> queue : queues.values()) {
            UploadCommand command;
            while ((command = queue.pollFirst()) != null) reject(command);
        }
        queued = 0;
        queuedBytes = 0L;
    }

    private UploadCommand pollNext() {
        MapRequestLane[] order = {
                MapRequestLane.MINIMAP, MapRequestLane.FULLSCREEN,
                MapRequestLane.BACKGROUND, MapRequestLane.PREFETCH
        };
        for (MapRequestLane lane : order) {
            UploadCommand command = queues.get(lane).pollFirst();
            if (command == null) continue;
            queued--;
            queuedBytes -= command.byteCount();
            return command;
        }
        return null;
    }

    private void reject(UploadCommand command) {
        if (command.rejected() != null) command.rejected().run();
        if (command.payload() != null) command.payload().close();
    }

    private static int sizeClass(int bytes) {
        int requested = Math.max(1, bytes);
        for (int size : SIZE_CLASSES) if (requested <= size) return size;
        return Integer.highestOneBit(requested - 1) << 1;
    }
}
