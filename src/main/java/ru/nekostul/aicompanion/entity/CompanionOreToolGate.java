package ru.nekostul.aicompanion.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.block.state.BlockState;
import ru.nekostul.aicompanion.entity.resource.CompanionResourceType;

final class CompanionOreToolGate {
    private enum Requirement {
        NONE,
        STONE,
        IRON
    }

    private static final String REQUIRE_STONE_KEY = "entity.aicompanion.companion.ore.require.stone";
    private static final String REQUIRE_IRON_KEY = "entity.aicompanion.companion.ore.require.iron";

    private final CompanionEntity owner;
    private final CompanionInventory inventory;

    CompanionOreToolGate(CompanionEntity owner, CompanionInventory inventory) {
        this.owner = owner;
        this.inventory = inventory;
    }

    boolean isRequestBlocked(CompanionResourceType type, Player player) {
        Requirement requirement = requirementFor(type);
        if (requirement == Requirement.NONE) {
            return false;
        }
        if (hasRequiredTool(requirement)) {
            return false;
        }
        notifyPlayer(player, requirement);
        return true;
    }

    boolean isBlockBlocked(BlockState state) {
        CompanionResourceType type = findOreType(state);
        if (type == null) {
            return false;
        }
        Requirement requirement = requirementFor(type);
        if (requirement == Requirement.NONE) {
            return false;
        }
        return !hasRequiredTool(requirement);
    }

    private Requirement requirementFor(CompanionResourceType type) {
        if (type == null) {
            return Requirement.NONE;
        }
        return switch (type) {
            case IRON_ORE, COPPER_ORE, LAPIS_ORE -> Requirement.STONE;
            case GOLD_ORE, REDSTONE_ORE, DIAMOND_ORE, EMERALD_ORE -> Requirement.IRON;
            default -> Requirement.NONE;
        };
    }

    private CompanionResourceType findOreType(BlockState state) {
        for (CompanionResourceType type : new CompanionResourceType[]{
                CompanionResourceType.COAL_ORE,
                CompanionResourceType.IRON_ORE,
                CompanionResourceType.COPPER_ORE,
                CompanionResourceType.GOLD_ORE,
                CompanionResourceType.REDSTONE_ORE,
                CompanionResourceType.LAPIS_ORE,
                CompanionResourceType.DIAMOND_ORE,
                CompanionResourceType.EMERALD_ORE
        }) {
            if (type.matchesBlock(state)) {
                return type;
            }
        }
        return null;
    }

    private boolean hasRequiredTool(Requirement requirement) {
        int requiredTier = switch (requirement) {
            case STONE -> 1;
            case IRON -> 2;
            default -> 0;
        };
        return bestPickaxeTier() >= requiredTier;
    }

    private int bestPickaxeTier() {
        int best = -1;
        best = Math.max(best, tierFromStack(owner.getMainHandItem()));
        best = Math.max(best, tierFromStack(owner.getOffhandItem()));
        for (ItemStack stack : inventory.getItems()) {
            best = Math.max(best, tierFromStack(stack));
        }
        return best;
    }

    private int tierFromStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return -1;
        }
        if (!stack.is(ItemTags.PICKAXES)) {
            return -1;
        }
        if (stack.getItem() instanceof TieredItem tiered) {
            return tiered.getTier().getLevel();
        }
        return 0;
    }

    private void notifyPlayer(Player player, Requirement requirement) {
        Player resolved = player;
        if (resolved == null || resolved.isSpectator()) {
            resolved = owner.level().getNearestPlayer(owner, 24.0D);
        }
        if (resolved == null || resolved.isSpectator()) {
            return;
        }
        String key = requirement == Requirement.STONE ? REQUIRE_STONE_KEY : REQUIRE_IRON_KEY;
        owner.sendReply(resolved, Component.translatable(key));
    }
}
