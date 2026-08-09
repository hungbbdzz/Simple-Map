package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** Regression check for Minecraft 1.21 RenderTarget.clear() unbinding the FBO. */
public final class RetainedFramebufferBindingCheck {
    private RetainedFramebufferBindingCheck() { }

    public static void main(String[] args) throws Exception {
        check("MinimapFramebufferRenderer.java");
        check("FullscreenMapFramebufferRenderer.java");
        System.out.println("RETAINED_FRAMEBUFFER_BINDING_PASS");
    }

    private static void check(String file) throws Exception {
        Path path = Path.of("src/main/java/com/velorise/simplemap/client", file);
        String source = Files.readString(path);
        String targetName = "backTarget";
        int clear = source.indexOf(targetName + ".clear(Minecraft.ON_OSX);");
        if (clear < 0) throw new AssertionError(file + " has no retained target clear");
        int projection = source.indexOf("RenderSystem.setProjectionMatrix", clear);
        int rebind = source.indexOf(targetName + ".bindWrite(true);", clear + 1);
        if (rebind < 0 || projection < 0 || rebind > projection) {
            throw new AssertionError(file
                    + " must rebind the retained framebuffer after clear() and before drawing");
        }
        if (!source.contains("result.drewAnyMapContent()")) {
            throw new AssertionError(file
                    + " must reject a clear-only retained frame and use direct fallback");
        }
        if (!source.contains("frontTarget")
                || !source.contains("backTarget")
                || !source.contains("drawRetainedUnderlay")
                || !source.contains("swapTargets()")) {
            throw new AssertionError(file
                    + " must retain a front frame while composing the loading frame");
        }
        int identity = source.indexOf("modelViewStack.identity()", clear);
        int apply = source.indexOf("RenderSystem.applyModelViewMatrix()", identity);
        if (identity < 0 || apply < 0 || identity > projection) {
            throw new AssertionError(file
                    + " must use an identity model-view during off-screen terrain replay");
        }
    }
}
