package com.velorise.simplemap.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Best-effort average colour and tint metadata from the actual block model. */
final class BlockTextureColorSampler {
    private static final int MAX_MODEL_DEPTH = 12;
    private static final Map<ResourceLocation, Sample> CACHE = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> FAILED = ConcurrentHashMap.newKeySet();

    private BlockTextureColorSampler() {
    }

    static Integer sampleArgb(ResourceLocation blockId) {
        Sample sample = sample(blockId);
        return sample == null ? null : sample.argb();
    }

    static boolean modelUsesTint(ResourceLocation blockId) {
        Sample sample = sample(blockId);
        return sample != null && sample.usesTintIndex();
    }

    static Sample sample(ResourceLocation blockId) {
        Sample cached = CACHE.get(blockId);
        if (cached != null) return cached;
        if (FAILED.contains(blockId)) return null;

        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) return null;

        try {
            ResourceLocation model = findModel(minecraft, blockId);
            if (model != null) {
                ModelInfo info = resolveModelInfo(minecraft, model);
                Integer argb = info.texture() == null ? null : sampleTexture(minecraft, info.texture());
                if (argb != null) {
                    Sample result = new Sample(argb, info.usesTintIndex());
                    CACHE.put(blockId, result);
                    return result;
                }
            }
        } catch (Throwable ignored) {
        }

        for (ResourceLocation texture : directCandidates(blockId)) {
            Integer argb = sampleTexture(minecraft, texture);
            if (argb != null) {
                Sample result = new Sample(argb, false);
                CACHE.put(blockId, result);
                return result;
            }
        }
        FAILED.add(blockId);
        return null;
    }

    static void clearCache() {
        CACHE.clear();
        FAILED.clear();
    }

    private static ResourceLocation findModel(Minecraft minecraft, ResourceLocation blockId) {
        ResourceLocation blockState = ResourceLocation.fromNamespaceAndPath(blockId.getNamespace(),
                "blockstates/" + blockId.getPath() + ".json");
        JsonObject json = readJson(minecraft, blockState);
        if (json == null) return null;

        if (json.has("variants") && json.get("variants").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("variants").entrySet()) {
                ResourceLocation model = modelFromElement(blockId.getNamespace(), entry.getValue());
                if (model != null) return model;
            }
        }
        if (json.has("multipart") && json.get("multipart").isJsonArray()) {
            for (JsonElement part : json.getAsJsonArray("multipart")) {
                if (!part.isJsonObject()) continue;
                ResourceLocation model = modelFromElement(blockId.getNamespace(),
                        part.getAsJsonObject().get("apply"));
                if (model != null) return model;
            }
        }
        return null;
    }

    private static ResourceLocation modelFromElement(String defaultNamespace, JsonElement element) {
        if (element == null) return null;
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement item : array) {
                ResourceLocation model = modelFromElement(defaultNamespace, item);
                if (model != null) return model;
            }
            return null;
        }
        if (!element.isJsonObject()) return null;
        JsonElement modelElement = element.getAsJsonObject().get("model");
        if (modelElement == null || !modelElement.isJsonPrimitive()) return null;
        return parseLocation(defaultNamespace, modelElement.getAsString());
    }

    private static ModelInfo resolveModelInfo(Minecraft minecraft, ResourceLocation initialModel) {
        Map<String, String> textures = new LinkedHashMap<>();
        ResourceLocation model = initialModel;
        String namespace = model.getNamespace();
        Set<ResourceLocation> visited = new LinkedHashSet<>();
        boolean usesTint = false;

        for (int depth = 0; depth < MAX_MODEL_DEPTH && model != null && visited.add(model); depth++) {
            ResourceLocation modelJson = ResourceLocation.fromNamespaceAndPath(model.getNamespace(),
                    "models/" + model.getPath() + ".json");
            JsonObject json = readJson(minecraft, modelJson);
            if (json == null) break;
            namespace = model.getNamespace();
            usesTint |= containsTintIndex(json);
            if (json.has("textures") && json.get("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("textures").entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        textures.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            if (!json.has("parent") || !json.get("parent").isJsonPrimitive()) break;
            model = parseLocation(namespace, json.get("parent").getAsString());
        }

        String[] preferred = { "top", "up", "all", "end", "particle", "side", "texture" };
        for (String key : preferred) {
            ResourceLocation location = resolveTextureReference(namespace, textures, textures.get(key));
            if (location != null) return new ModelInfo(toTextureFile(location), usesTint);
        }
        for (String value : textures.values()) {
            ResourceLocation location = resolveTextureReference(namespace, textures, value);
            if (location != null) return new ModelInfo(toTextureFile(location), usesTint);
        }
        return new ModelInfo(null, usesTint);
    }

    private static boolean containsTintIndex(JsonObject model) {
        if (!model.has("elements") || !model.get("elements").isJsonArray()) return false;
        for (JsonElement element : model.getAsJsonArray("elements")) {
            if (!element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            if (!object.has("faces") || !object.get("faces").isJsonObject()) continue;
            for (Map.Entry<String, JsonElement> face : object.getAsJsonObject("faces").entrySet()) {
                if (face.getValue().isJsonObject()
                        && face.getValue().getAsJsonObject().has("tintindex")) return true;
            }
        }
        return false;
    }

    private static ResourceLocation resolveTextureReference(String namespace,
            Map<String, String> textures, String value) {
        Set<String> visited = new LinkedHashSet<>();
        while (value != null && value.startsWith("#") && visited.add(value)) {
            value = textures.get(value.substring(1));
        }
        if (value == null || value.startsWith("#")) return null;
        return parseLocation(namespace, value);
    }

    private static ResourceLocation toTextureFile(ResourceLocation textureId) {
        String path = textureId.getPath();
        if (path.startsWith("textures/") && path.endsWith(".png")) return textureId;
        return ResourceLocation.fromNamespaceAndPath(textureId.getNamespace(),
                "textures/" + path + ".png");
    }

    private static JsonObject readJson(Minecraft minecraft, ResourceLocation location) {
        try {
            Optional<Resource> resource = minecraft.getResourceManager().getResource(location);
            if (resource.isEmpty()) return null;
            try (InputStream stream = resource.get().open();
                    InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonElement element = JsonParser.parseReader(reader);
                return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Integer sampleTexture(Minecraft minecraft, ResourceLocation texture) {
        try {
            Optional<Resource> resource = minecraft.getResourceManager().getResource(texture);
            if (resource.isEmpty()) return null;
            try (InputStream stream = resource.get().open(); NativeImage image = NativeImage.read(stream)) {
                return average(image);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<ResourceLocation> directCandidates(ResourceLocation blockId) {
        String path = blockId.getPath();
        List<ResourceLocation> result = new ArrayList<>(12);
        addCandidate(result, blockId, path + "_top");
        addCandidate(result, blockId, path + "_up");
        if (path.equals("grass_block")) addCandidate(result, blockId, "grass_block_top");
        if (path.equals("dirt_path")) addCandidate(result, blockId, "dirt_path_top");
        if (path.equals("farmland")) {
            addCandidate(result, blockId, "farmland_moist");
            addCandidate(result, blockId, "farmland");
        }
        addCandidate(result, blockId, path);
        if (path.endsWith("_block")) {
            String stripped = path.substring(0, path.length() - "_block".length());
            addCandidate(result, blockId, stripped + "_block_top");
            addCandidate(result, blockId, stripped);
        }
        if (path.endsWith("_wall") || path.endsWith("_stairs") || path.endsWith("_slab")) {
            int separator = path.lastIndexOf('_');
            if (separator > 0) addCandidate(result, blockId, path.substring(0, separator));
        }
        return result;
    }

    private static void addCandidate(List<ResourceLocation> output,
            ResourceLocation blockId, String texturePath) {
        ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(blockId.getNamespace(),
                "textures/block/" + texturePath + ".png");
        if (!output.contains(candidate)) output.add(candidate);
    }

    private static ResourceLocation parseLocation(String defaultNamespace, String text) {
        if (text == null || text.isBlank()) return null;
        return text.indexOf(':') >= 0
                ? ResourceLocation.parse(text)
                : ResourceLocation.fromNamespaceAndPath(defaultNamespace, text);
    }

    private static Integer average(NativeImage image) {
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        long weight = 0L;
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) return null;

        int stepX = Math.max(1, width / 64);
        int stepY = Math.max(1, height / 64);
        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                int abgr = image.getPixelRGBA(x, y);
                int alpha = (abgr >>> 24) & 0xFF;
                if (alpha < 24) continue;
                int sampleRed = abgr & 0xFF;
                int sampleGreen = (abgr >>> 8) & 0xFF;
                int sampleBlue = (abgr >>> 16) & 0xFF;
                red += (long) sampleRed * alpha;
                green += (long) sampleGreen * alpha;
                blue += (long) sampleBlue * alpha;
                weight += alpha;
            }
        }
        if (weight == 0L) return null;
        int outRed = (int) (red / weight);
        int outGreen = (int) (green / weight);
        int outBlue = (int) (blue / weight);
        return 0xFF000000 | (outRed << 16) | (outGreen << 8) | outBlue;
    }

    record Sample(int argb, boolean usesTintIndex) {
    }

    private record ModelInfo(ResourceLocation texture, boolean usesTintIndex) {
    }
}
