package ru.nekostul.aicompanion.entity;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

enum CompanionResourceType {
    LOG,
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

    boolean matchesBlock(BlockState state) {
        return CompanionBlockRegistry.matchesBlock(this, state);
    }

    boolean matchesItem(ItemStack stack) {
        return CompanionBlockRegistry.matchesItem(this, stack);
    }

    boolean isBucketResource() {
        return CompanionBlockRegistry.isBucketResource(this);
    }
}
