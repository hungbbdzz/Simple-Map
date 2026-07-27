package com.velorise.simplemap.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Generic visual resolver for block entities whose rendered material differs from
 * their registry block state (camouflage, framed/copycat and multipart wrappers).
 *
 * <p>The core map pipeline remains mod-independent. Known semantic method/field
 * names are discovered once per block-entity class, while offline Anvil data is
 * inspected for nested serialized BlockState compounds. Failure is conservative:
 * the original state is always retained.</p>
 */
public final class MapBlockEntityVisualResolver {
    private static final MapBlockEntityVisualResolver INSTANCE =
            new MapBlockEntityVisualResolver();
    private static final int MAX_NBT_DEPTH = 4;
    private static final String[] SEMANTIC_TOKENS = {
            "camo", "camouflage", "material", "appearance", "copycat",
            "framed", "cover", "mimic", "modelstate", "blockstate"
    };

    private final Map<Class<?>, AccessPlan> accessPlans = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Adapter> adapters = new CopyOnWriteArrayList<>();

    private MapBlockEntityVisualResolver() {
    }

    public static MapBlockEntityVisualResolver getInstance() {
        return INSTANCE;
    }

    public BlockState resolveLive(Level level, BlockPos pos, BlockState fallback) {
        if (level == null || pos == null || fallback == null || !fallback.hasBlockEntity()) return fallback;
        try {
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity == null) return fallback;
            for (Adapter adapter : adapters) {
                BlockState resolved = adapter.resolveLive(level, pos, fallback, entity);
                if (usable(resolved)) return resolved;
            }
            AccessPlan plan = accessPlans.computeIfAbsent(entity.getClass(), this::inspectClass);
            BlockState resolved = plan.resolve(entity);
            return usable(resolved) ? resolved : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public BlockState resolveOffline(BlockState fallback, CompoundTag blockEntityTag) {
        if (fallback == null || blockEntityTag == null || blockEntityTag.isEmpty()) return fallback;
        try {
            for (Adapter adapter : adapters) {
                BlockState resolved = adapter.resolveOffline(fallback, blockEntityTag);
                if (usable(resolved)) return resolved;
            }
            BlockState resolved = findSerializedState(blockEntityTag, 0, "");
            return usable(resolved) ? resolved : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public void clear() {
        accessPlans.clear();
    }

    /** Registers an optional mod adapter without introducing a hard dependency. */
    public void register(Adapter adapter) {
        if (adapter != null) adapters.addIfAbsent(adapter);
    }

    public void unregister(Adapter adapter) {
        adapters.remove(adapter);
    }

    private AccessPlan inspectClass(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        List<Field> fields = new ArrayList<>();
        for (Class<?> cursor = type; cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() != 0
                        || !supportedType(method.getReturnType())
                        || !semanticName(method.getName())) continue;
                try {
                    method.setAccessible(true);
                    methods.add(method);
                } catch (RuntimeException ignored) {
                }
            }
            for (Field field : cursor.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !supportedType(field.getType())
                        || !semanticName(field.getName())) continue;
                try {
                    field.setAccessible(true);
                    fields.add(field);
                } catch (RuntimeException ignored) {
                }
            }
        }
        return new AccessPlan(methods.toArray(Method[]::new), fields.toArray(Field[]::new));
    }


    private static boolean supportedType(Class<?> type) {
        return BlockState.class.isAssignableFrom(type)
                || Block.class.isAssignableFrom(type)
                || ResourceLocation.class.isAssignableFrom(type)
                || String.class.isAssignableFrom(type)
                || Optional.class.isAssignableFrom(type)
                || CompoundTag.class.isAssignableFrom(type);
    }

    private static BlockState candidateState(Object value) {
        if (value instanceof BlockState state) return state;
        if (value instanceof Block block) return block.defaultBlockState();
        if (value instanceof ResourceLocation id) return decodeBlockId(id.toString());
        if (value instanceof String id) return decodeBlockId(id);
        if (value instanceof Optional<?> optional) return candidateState(optional.orElse(null));
        if (value instanceof CompoundTag tag) return decodeStateCompound(tag);
        return null;
    }

    private static boolean semanticName(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.ROOT).replace("_", "");
        for (String token : SEMANTIC_TOKENS) {
            if (lower.contains(token.replace("_", ""))) return true;
        }
        return false;
    }

    private static boolean usable(BlockState state) {
        return state != null && !state.isAir() && !state.is(Blocks.STRUCTURE_VOID);
    }

    private BlockState findSerializedState(CompoundTag tag, int depth, String semanticPath) {
        if (depth > MAX_NBT_DEPTH) return null;

        BlockState direct = decodeStateCompound(tag);
        if (direct != null && (depth > 0 || semanticName(semanticPath))) return direct;

        for (String key : tag.getAllKeys()) {
            String path = semanticPath.isEmpty() ? key : semanticPath + '.' + key;
            boolean semantic = semanticName(path);
            if (tag.contains(key, Tag.TAG_COMPOUND)) {
                CompoundTag child = tag.getCompound(key);
                BlockState childDirect = decodeStateCompound(child);
                if (childDirect != null && semantic) return childDirect;
                BlockState nested = findSerializedState(child, depth + 1, path);
                if (nested != null && (semantic || depth < 2)) return nested;
            } else if (semantic && tag.contains(key, Tag.TAG_STRING)) {
                BlockState stringState = decodeBlockId(tag.getString(key));
                if (stringState != null) return stringState;
            }
        }
        return null;
    }

    private static BlockState decodeStateCompound(CompoundTag stateTag) {
        if (stateTag == null || stateTag.isEmpty()) return null;
        String id = null;
        if (stateTag.contains("Name", Tag.TAG_STRING)) id = stateTag.getString("Name");
        else if (stateTag.contains("name", Tag.TAG_STRING)) id = stateTag.getString("name");
        else if (stateTag.contains("block", Tag.TAG_STRING)) id = stateTag.getString("block");
        BlockState state = decodeBlockId(id);
        if (state == null) return null;
        CompoundTag properties = stateTag.contains("Properties", Tag.TAG_COMPOUND)
                ? stateTag.getCompound("Properties")
                : stateTag.getCompound("properties");
        Block block = state.getBlock();
        for (String propertyName : properties.getAllKeys()) {
            Property<?> property = block.getStateDefinition().getProperty(propertyName);
            if (property == null) continue;
            state = applyProperty(state, property, properties.getString(propertyName));
        }
        return state;
    }

    private static BlockState decodeBlockId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            ResourceLocation id = ResourceLocation.parse(raw);
            Block block = BuiltInRegistries.BLOCK.get(id);
            return block == null || block == Blocks.AIR ? null : block.defaultBlockState();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static <T extends Comparable<T>> BlockState applyProperty(
            BlockState state, Property<T> property, String serialized) {
        Optional<T> value = property.getValue(serialized);
        return value.map(candidate -> state.setValue(property, candidate)).orElse(state);
    }


    /** Optional compatibility bridge implemented by integrations loaded at runtime. */
    public interface Adapter {
        default BlockState resolveLive(Level level, BlockPos pos, BlockState fallback,
                BlockEntity entity) {
            return null;
        }

        default BlockState resolveOffline(BlockState fallback, CompoundTag blockEntityTag) {
            return null;
        }
    }

    private record AccessPlan(Method[] methods, Field[] fields) {
        BlockState resolve(BlockEntity entity) {
            for (Method method : methods) {
                try {
                    BlockState state = candidateState(method.invoke(entity));
                    if (usable(state)) return state;
                } catch (Throwable ignored) {
                }
            }
            for (Field field : fields) {
                try {
                    BlockState state = candidateState(field.get(entity));
                    if (usable(state)) return state;
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }
}
