package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Regression guard for the singleton/static-array initialization order. */
public final class CaveTextureManagerStaticInitCheck {
    private CaveTextureManagerStaticInitCheck() {}

    public static void main(String[] args) throws Exception {
        Path source = Path.of("src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java");
        String text = Files.readString(source);
        int lanes = text.indexOf("private static final MapRequestLane[] REQUEST_LANES");
        int singleton = text.indexOf("private static final UnifiedCaveTextureManager INSTANCE");
        if (lanes < 0 || singleton < 0 || lanes > singleton) {
            throw new AssertionError("REQUEST_LANES must initialize before INSTANCE");
        }
        System.out.println("CAVE_TEXTURE_MANAGER_STATIC_INIT_PASS");
    }
}
