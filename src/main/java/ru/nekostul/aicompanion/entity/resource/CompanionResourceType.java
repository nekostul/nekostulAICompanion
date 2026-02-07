package ru.nekostul.aicompanion.entity.resource;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import ru.nekostul.aicompanion.entity.resource.CompanionBlockRegistry;

public enum CompanionResourceType {
    LOG,
    TORCH,
    DIRT,
    STONE,
    SAND,
    GRAVEL,
    CLAY,
    ANDESITE,
    DIORITE,
    GRANITE,
    BASALT,
    ORE,
    COAL_ORE,
    IRON_ORE,
    COPPER_ORE,
    GOLD_ORE,
    REDSTONE_ORE,
    LAPIS_ORE,
    DIAMOND_ORE,
    EMERALD_ORE,
    WATER,
    LAVA;

    public boolean matchesBlock(BlockState state) {
        return CompanionBlockRegistry.matchesBlock(this, state);
    }

    public boolean matchesItem(ItemStack stack) {
        return CompanionBlockRegistry.matchesItem(this, stack);
    }

    public boolean isBucketResource() {
        return CompanionBlockRegistry.isBucketResource(this);
    }
}
