package ru.nekostul.aicompanion.entity.tree;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ru.nekostul.aicompanion.entity.CompanionEntity;
import ru.nekostul.aicompanion.entity.inventory.CompanionInventory;
import ru.nekostul.aicompanion.entity.resource.CompanionBlockRegistry;

final class CompanionTreeChopper {
    interface DropHandler {
        void recordDrops(List<ItemStack> drops);
    }

    private final CompanionEntity owner;
    private final CompanionInventory inventory;
    private final DropHandler dropHandler;
    private final int maxDistanceSqr;
    private final int maxBlocks;
    private final int leavesRadius;

    CompanionTreeChopper(CompanionEntity owner,
                         CompanionInventory inventory,
                         DropHandler dropHandler,
                         int treeMaxRadius,
                         int treeMaxBlocks,
                         int logFromLeavesRadius) {
        this.owner = owner;
        this.inventory = inventory;
        this.dropHandler = dropHandler;
        this.maxDistanceSqr = treeMaxRadius * treeMaxRadius;
        this.maxBlocks = treeMaxBlocks;
        this.leavesRadius = logFromLeavesRadius + 1;
    }

    boolean harvestTree(BlockPos base) {
        if (!(owner.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        Set<BlockPos> logs = collectLogComponent(base);
        if (logs.isEmpty()) {
            return true;
        }
        Set<BlockPos> toBreak = new HashSet<>(logs);
        for (BlockPos logPos : logs) {
            collectNearbyLeaves(logPos, toBreak);
        }
        for (BlockPos pos : toBreak) {
            BlockState state = owner.level().getBlockState(pos);
            if (!CompanionBlockRegistry.isLog(state) && !CompanionBlockRegistry.isLeaves(state)) {
                continue;
            }
            if (!breakTreeBlock(serverLevel, pos, state)) {
                return false;
            }
        }
        return true;
    }

    static List<ItemStack> filterLeafDrops(List<ItemStack> drops) {
        if (drops.isEmpty()) {
            return drops;
        }
        List<ItemStack> filtered = new ArrayList<>();
        for (ItemStack stack : drops) {
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(ItemTags.LEAVES)) {
                continue;
            }
            filtered.add(stack);
        }
        return filtered;
    }

    private boolean breakTreeBlock(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (!isBreakable(state, pos)) {
            return true;
        }
        ItemStack tool = owner.getMainHandItem();
        List<ItemStack> drops = Block.getDrops(state, serverLevel, pos, owner.level().getBlockEntity(pos),
                owner, tool);
        if (CompanionBlockRegistry.isLeaves(state)) {
            drops = filterLeafDrops(drops);
        } else if (drops.isEmpty()) {
            Item item = state.getBlock().asItem();
            if (item != Items.AIR) {
                drops = List.of(new ItemStack(item));
            }
        }
        if (!inventory.canStoreAll(drops)) {
            return false;
        }
        owner.level().destroyBlock(pos, false);
        inventory.addAll(drops);
        if (dropHandler != null) {
            dropHandler.recordDrops(drops);
        }
        return true;
    }

    private boolean isBreakable(BlockState state, BlockPos pos) {
        if (state.isAir()) {
            return false;
        }
        if (state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER)) {
            return false;
        }
        return state.getDestroySpeed(owner.level(), pos) >= 0.0F;
    }

    private Set<BlockPos> collectLogComponent(BlockPos base) {
        Set<BlockPos> logs = new HashSet<>();
        if (base == null) {
            return logs;
        }
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(base);
        while (!queue.isEmpty() && logs.size() < maxBlocks) {
            BlockPos pos = queue.poll();
            if (logs.contains(pos)) {
                continue;
            }
            if (base.distSqr(pos) > maxDistanceSqr) {
                continue;
            }
            BlockState state = owner.level().getBlockState(pos);
            if (!CompanionBlockRegistry.isLog(state)) {
                continue;
            }
            logs.add(pos);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = pos.offset(dx, dy, dz);
                        if (!logs.contains(next)) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return logs;
    }

    private void collectNearbyLeaves(BlockPos logPos, Set<BlockPos> out) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -leavesRadius; dx <= leavesRadius; dx++) {
            for (int dy = -leavesRadius; dy <= leavesRadius; dy++) {
                for (int dz = -leavesRadius; dz <= leavesRadius; dz++) {
                    pos.set(logPos.getX() + dx, logPos.getY() + dy, logPos.getZ() + dz);
                    if (CompanionBlockRegistry.isLeaves(owner.level().getBlockState(pos))) {
                        out.add(pos.immutable());
                    }
                }
            }
        }
    }
}
