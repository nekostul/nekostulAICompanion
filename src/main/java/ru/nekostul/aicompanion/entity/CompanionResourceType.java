package ru.nekostul.aicompanion.entity;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.function.Predicate;

enum CompanionResourceType {
    LOG(
            state -> state.is(BlockTags.LOGS),
            stack -> stack.is(ItemTags.LOGS),
            false
    ),
    DIRT(
            state -> state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)
                    || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT),
            stack -> stack.is(Items.DIRT) || stack.is(Items.GRASS_BLOCK)
                    || stack.is(Items.COARSE_DIRT) || stack.is(Items.ROOTED_DIRT),
            false
    ),
    STONE(
            state -> state.is(BlockTags.BASE_STONE_OVERWORLD),
            stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.STONE),
            false
    ),
    SAND(
            state -> state.is(Blocks.SAND) || state.is(Blocks.RED_SAND),
            stack -> stack.is(Items.SAND) || stack.is(Items.RED_SAND),
            false
    ),
    GRAVEL(
            state -> state.is(Blocks.GRAVEL),
            stack -> stack.is(Items.GRAVEL),
            false
    ),
    WATER(
            state -> state.getFluidState().isSource() && state.getFluidState().is(Fluids.WATER),
            stack -> stack.is(Items.WATER_BUCKET),
            true
    ),
    LAVA(
            state -> state.getFluidState().isSource() && state.getFluidState().is(Fluids.LAVA),
            stack -> stack.is(Items.LAVA_BUCKET),
            true
    );

    private final Predicate<BlockState> blockPredicate;
    private final Predicate<ItemStack> itemPredicate;
    private final boolean bucketResource;

    CompanionResourceType(Predicate<BlockState> blockPredicate, Predicate<ItemStack> itemPredicate, boolean bucketResource) {
        this.blockPredicate = blockPredicate;
        this.itemPredicate = itemPredicate;
        this.bucketResource = bucketResource;
    }

    boolean matchesBlock(BlockState state) {
        return blockPredicate.test(state);
    }

    boolean matchesItem(ItemStack stack) {
        return itemPredicate.test(stack);
    }

    boolean isBucketResource() {
        return bucketResource;
    }
}
