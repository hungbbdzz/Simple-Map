package com.velorise.simplemap;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * Common-side bridge for optional client Map Book interactions.
 *
 * <p>The default hooks are no-ops. The physical-client mod entrypoint installs
 * the real implementations, so item classes loaded on a dedicated server never
 * reference classes from {@code net.minecraft.client} or Simple Map's client
 * package.</p>
 */
public final class MapBookHooks {
    @FunctionalInterface
    public interface EmptyBookAction {
        void use(Player player, InteractionHand hand, Item cooldownItem);
    }

    @FunctionalInterface
    public interface WrittenBookAction {
        void use(Player player, InteractionHand hand, ItemStack stack, Item cooldownItem);
    }

    private static final EmptyBookAction NO_EMPTY_ACTION = (player, hand, item) -> {
    };
    private static final WrittenBookAction NO_WRITTEN_ACTION = (player, hand, stack, item) -> {
    };

    private static volatile EmptyBookAction emptyBookAction = NO_EMPTY_ACTION;
    private static volatile WrittenBookAction writtenBookAction = NO_WRITTEN_ACTION;

    private MapBookHooks() {
    }

    public static void install(EmptyBookAction emptyAction, WrittenBookAction writtenAction) {
        emptyBookAction = Objects.requireNonNull(emptyAction, "emptyAction");
        writtenBookAction = Objects.requireNonNull(writtenAction, "writtenAction");
    }

    public static void useEmptyBook(Player player, InteractionHand hand, Item cooldownItem) {
        emptyBookAction.use(player, hand, cooldownItem);
    }

    public static void useWrittenBook(Player player, InteractionHand hand, ItemStack stack, Item cooldownItem) {
        writtenBookAction.use(player, hand, stack, cooldownItem);
    }
}
