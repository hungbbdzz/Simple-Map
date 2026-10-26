package com.velorise.simplemap.client.gpu;

import java.nio.file.Files;
import java.nio.file.Path;

/** Static lifecycle guard that does not require platform LWJGL natives. */
public final class CaveDirectBufferPoolCheck {
    private CaveDirectBufferPoolCheck() { }

    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/gpu/CaveDirectBufferPool.java"));
        require(source.contains("MemoryUtil.memAlloc(BUFFER_BYTES)"),
                "pool must allocate bounded native buffers");
        require(source.contains("available.pollFirst()")
                        && source.contains("available.addFirst(buffer)"),
                "pool must reuse retained buffers");
        require(source.contains("available.size() >= maximumRetained"),
                "pool must enforce its retention bound");
        require(source.contains("MemoryUtil.memFree(buffer)"),
                "pool must free overflow and closed buffers");
        require(source.contains("requiredBytes > BUFFER_BYTES"),
                "pool must reject oversized atlas transfers");
        System.out.println("CAVE_DIRECT_BUFFER_POOL_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
