package ru.nekostul.aicompanion.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

final class CompanionGatheringController {
    enum Result {
        IDLE,
        IN_PROGRESS,
        DONE,
        NEED_CHEST,
        NOT_FOUND
    }

    private static final int CHUNK_RADIUS = 6;
    private static final int RESOURCE_SCAN_RADIUS = CHUNK_RADIUS * 16;
    private static final int RESOURCE_SCAN_COOLDOWN_TICKS = 80;
    private static final float RESOURCE_FOV_DOT = -1.0F;
    private static final double MINING_REACH_SQR = 9.0D;
    private static final int MINING_SWING_COOLDOWN = 5;
    private static final int LOG_FROM_LEAVES_RADIUS = 2;
    private static final int LOG_FROM_LEAVES_MAX_DEPTH = 6;
    private static final int STONE_DIG_MAX_DEPTH = 8;

    private final CompanionEntity owner;
    private final CompanionInventory inventory;
    private final CompanionEquipment equipment;

    private CompanionResourceType activeType;
    private int activeAmount;
    private BlockPos targetBlock;
    private BlockPos pendingResourceBlock;
    private BlockPos digStonePos;
    private long nextScanTick = -1L;
    private BlockPos cachedTarget;
    private long lastScanTick = -1L;
    private boolean lastScanFound = true;
    private BlockPos miningProgressPos;
    private float miningProgress;
    private int miningProgressStage = -1;
    private long nextSwingTick = -1L;

    CompanionGatheringController(CompanionEntity owner, CompanionInventory inventory, CompanionEquipment equipment) {
        this.owner = owner;
        this.inventory = inventory;
        this.equipment = equipment;
    }

    Result tick(CompanionResourceRequest request, long gameTime) {
        if (request == null) {
            clearTarget();
            return Result.IDLE;
        }
        if (activeType != request.getResourceType() || activeAmount != request.getAmount()) {
            activeType = request.getResourceType();
            activeAmount = request.getAmount();
            clearTarget();
        }
        if (inventory.isFull()) {
            clearTarget();
            return Result.NEED_CHEST;
        }
        if (inventory.countMatching(activeType::matchesItem) >= activeAmount) {
            clearTarget();
            return Result.DONE;
        }
        if (targetBlock == null || !isTargetValid()) {
            TargetSelection selection = findTarget(activeType, gameTime);
            if (selection == null) {
                if (gameTime == lastScanTick && !lastScanFound) {
                    return Result.NOT_FOUND;
                }
                return Result.IN_PROGRESS;
            }
            this.targetBlock = selection.target;
            this.pendingResourceBlock = selection.pendingResource;
            this.digStonePos = selection.digStonePos;
            resetMiningProgress();
        }
        return tickMining(gameTime);
    }

    private Result tickMining(long gameTime) {
        if (targetBlock == null) {
            return Result.IN_PROGRESS;
        }
        BlockState state = owner.level().getBlockState(targetBlock);
        boolean diggingForStone = digStonePos != null;
        if (state.isAir()) {
            if (diggingForStone && targetBlock.getY() > digStonePos.getY()) {
                targetBlock = targetBlock.below();
                resetMiningProgress();
                return Result.IN_PROGRESS;
            }
            if (pendingResourceBlock != null && owner.level().getBlockState(pendingResourceBlock).isSolid()) {
                targetBlock = pendingResourceBlock;
                pendingResourceBlock = null;
                resetMiningProgress();
                return Result.IN_PROGRESS;
            }
            clearTarget();
            return Result.IN_PROGRESS;
        }
        if (!isBreakable(state, targetBlock)) {
            clearTarget();
            return Result.IN_PROGRESS;
        }
        equipment.equipToolForBlock(state);
        Vec3 center = Vec3.atCenterOf(targetBlock);
        double distanceSqr = owner.distanceToSqr(center);
        if (distanceSqr > MINING_REACH_SQR) {
            owner.getNavigation().moveTo(center.x, center.y, center.z, 1.0D);
            resetMiningProgress();
            return Result.IN_PROGRESS;
        }
        owner.getNavigation().stop();
        owner.getLookControl().setLookAt(center.x, center.y, center.z);
        if (miningProgressPos == null || !miningProgressPos.equals(targetBlock)) {
            resetMiningProgress();
            miningProgressPos = targetBlock;
        }
        float progressPerTick = getBreakProgress(state, targetBlock);
        if (progressPerTick <= 0.0F) {
            clearTarget();
            return Result.IN_PROGRESS;
        }
        if (gameTime >= nextSwingTick) {
            owner.swing(InteractionHand.MAIN_HAND, true);
            nextSwingTick = gameTime + MINING_SWING_COOLDOWN;
        }
        miningProgress += progressPerTick;
        int stage = Math.min(9, (int) (miningProgress * 10.0F));
        if (stage != miningProgressStage && owner.level() instanceof ServerLevel serverLevel) {
            miningProgressStage = stage;
            serverLevel.destroyBlockProgress(owner.getId(), miningProgressPos, stage);
        }
        if (miningProgress >= 1.0F) {
            boolean mined = mineBlock(targetBlock);
            resetMiningProgress();
            if (!mined) {
                clearTarget();
                return Result.NEED_CHEST;
            }
            if (diggingForStone) {
                if (targetBlock.equals(digStonePos)) {
                    clearTarget();
                    return Result.IN_PROGRESS;
                }
                BlockPos next = targetBlock.below();
                if (next.getY() < digStonePos.getY()) {
                    clearTarget();
                    return Result.IN_PROGRESS;
                }
                targetBlock = next;
                return Result.IN_PROGRESS;
            }
            if (pendingResourceBlock != null && owner.level().getBlockState(pendingResourceBlock).isSolid()) {
                targetBlock = pendingResourceBlock;
                pendingResourceBlock = null;
                return Result.IN_PROGRESS;
            }
            clearTarget();
        }
        return Result.IN_PROGRESS;
    }

    private TargetSelection findTarget(CompanionResourceType type, long gameTime) {
        if (gameTime < nextScanTick && cachedTarget != null && isCachedTargetValid(type, cachedTarget)) {
            return new TargetSelection(cachedTarget, cachedTarget, cachedTarget, null);
        }
        if (gameTime < nextScanTick) {
            return null;
        }
        lastScanTick = gameTime;
        lastScanFound = false;
        Vec3 eye = owner.getEyePosition();
        Vec3 look = owner.getLookAngle().normalize();
        BlockPos origin = owner.blockPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        TargetSelection best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int dx = -RESOURCE_SCAN_RADIUS; dx <= RESOURCE_SCAN_RADIUS; dx++) {
            for (int dy = -RESOURCE_SCAN_RADIUS; dy <= RESOURCE_SCAN_RADIUS; dy++) {
                for (int dz = -RESOURCE_SCAN_RADIUS; dz <= RESOURCE_SCAN_RADIUS; dz++) {
                    pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState state = owner.level().getBlockState(pos);
                    TargetSelection selection = resolveTargetSelection(type, pos.immutable(), state);
                    if (selection == null) {
                        continue;
                    }
                    Vec3 sightCenter = Vec3.atCenterOf(selection.sightPos);
                    Vec3 toTarget = sightCenter.subtract(eye);
                    double distance = toTarget.length();
                    if (distance > RESOURCE_SCAN_RADIUS) {
                        continue;
                    }
                    if (look.dot(toTarget.normalize()) < RESOURCE_FOV_DOT) {
                        continue;
                    }
                    TargetSelection raySelection = resolveObstruction(selection.resourcePos, selection.sightPos);
                    if (raySelection == null) {
                        continue;
                    }
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = raySelection;
                    }
                }
            }
        }
        if (best == null && type == CompanionResourceType.STONE) {
            TargetSelection digTarget = findStoneDigTarget(gameTime);
            if (digTarget != null) {
                best = digTarget;
            }
        }
        if (best != null) {
            lastScanFound = true;
        }
        nextScanTick = gameTime + RESOURCE_SCAN_COOLDOWN_TICKS;
        cachedTarget = best != null ? best.target : null;
        return best;
    }

    private TargetSelection resolveTargetSelection(CompanionResourceType type, BlockPos pos, BlockState state) {
        if (type == CompanionResourceType.LOG) {
            if (state.is(BlockTags.LOGS)) {
                return new TargetSelection(pos, pos, pos, null);
            }
            if (state.is(BlockTags.LEAVES)) {
                BlockPos logPos = resolveLogFromLeaves(pos);
                if (logPos != null) {
                    return new TargetSelection(logPos, logPos, pos, null);
                }
            }
            return null;
        }
        if (type.matchesBlock(state)) {
            return new TargetSelection(pos, pos, pos, null);
        }
        return null;
    }

    private TargetSelection resolveObstruction(BlockPos resourcePos, BlockPos sightPos) {
        Vec3 eye = owner.getEyePosition();
        Vec3 targetCenter = Vec3.atCenterOf(resourcePos);
        BlockHitResult hit = owner.level().clip(new ClipContext(eye, targetCenter, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, owner));
        if (hit.getType() == HitResult.Type.MISS) {
            return new TargetSelection(resourcePos, resourcePos, sightPos, null, null);
        }
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos hitPos = hit.getBlockPos();
        if (hitPos.equals(resourcePos) || hitPos.equals(sightPos)) {
            return new TargetSelection(resourcePos, resourcePos, sightPos, null, null);
        }
        BlockState hitState = owner.level().getBlockState(hitPos);
        if (!isBreakable(hitState, hitPos)) {
            return null;
        }
        return new TargetSelection(hitPos, resourcePos, sightPos, null, resourcePos);
    }

    private TargetSelection findStoneDigTarget(long gameTime) {
        BlockPos origin = owner.blockPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        TargetSelection best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -RESOURCE_SCAN_RADIUS; dx <= RESOURCE_SCAN_RADIUS; dx++) {
            for (int dz = -RESOURCE_SCAN_RADIUS; dz <= RESOURCE_SCAN_RADIUS; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                BlockPos surface = owner.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        new BlockPos(x, 0, z));
                if (!isPassable(surface.above())) {
                    continue;
                }
                BlockState surfaceState = owner.level().getBlockState(surface);
                if (!surfaceState.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
                    continue;
                }
                BlockPos stonePos = findStoneBelow(surface, pos);
                if (stonePos == null) {
                    continue;
                }
                double distance = origin.distSqr(surface);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new TargetSelection(surface.immutable(), surface.immutable(), surface.immutable(), stonePos);
                }
            }
        }
        return best;
    }

    private BlockPos findStoneBelow(BlockPos start, BlockPos.MutableBlockPos pos) {
        int minY = Math.max(owner.level().getMinBuildHeight(), start.getY() - STONE_DIG_MAX_DEPTH);
        for (int y = start.getY() - 1; y >= minY; y--) {
            pos.set(start.getX(), y, start.getZ());
            BlockState state = owner.level().getBlockState(pos);
            if (state.is(BlockTags.BASE_STONE_OVERWORLD)) {
                return pos.immutable();
            }
            if (!state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
                return null;
            }
        }
        return null;
    }

    private BlockPos resolveLogFromLeaves(BlockPos leafPos) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= LOG_FROM_LEAVES_MAX_DEPTH; dy++) {
            int y = leafPos.getY() - dy;
            for (int dx = -LOG_FROM_LEAVES_RADIUS; dx <= LOG_FROM_LEAVES_RADIUS; dx++) {
                for (int dz = -LOG_FROM_LEAVES_RADIUS; dz <= LOG_FROM_LEAVES_RADIUS; dz++) {
                    pos.set(leafPos.getX() + dx, y, leafPos.getZ() + dz);
                    if (owner.level().getBlockState(pos).is(BlockTags.LOGS)) {
                        return resolveLogTarget(pos.immutable());
                    }
                }
            }
        }
        return null;
    }

    private BlockPos resolveLogTarget(BlockPos pos) {
        BlockPos current = pos;
        BlockPos below = current.below();
        while (owner.level().getBlockState(below).is(BlockTags.LOGS)) {
            current = below;
            below = current.below();
        }
        return current;
    }

    private boolean isPassable(BlockPos pos) {
        BlockState state = owner.level().getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        return state.getCollisionShape(owner.level(), pos).isEmpty();
    }

    private boolean isCachedTargetValid(CompanionResourceType type, BlockPos pos) {
        BlockState state = owner.level().getBlockState(pos);
        if (type == CompanionResourceType.LOG) {
            return state.is(BlockTags.LOGS);
        }
        return type.matchesBlock(state);
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

    private boolean mineBlock(BlockPos pos) {
        if (!(owner.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        BlockState state = owner.level().getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        ItemStack tool = owner.getMainHandItem();
        List<ItemStack> drops = Block.getDrops(state, serverLevel, pos, owner.level().getBlockEntity(pos),
                owner, tool);
        if (drops.isEmpty()) {
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
        if (!tool.isEmpty() && tool.isDamageableItem()) {
            tool.hurtAndBreak(1, owner, entity -> entity.broadcastBreakEvent(InteractionHand.MAIN_HAND));
        }
        return true;
    }

    private float getBreakProgress(BlockState state, BlockPos pos) {
        float hardness = state.getDestroySpeed(owner.level(), pos);
        if (hardness <= 0.0F) {
            return 0.0F;
        }
        ItemStack tool = owner.getMainHandItem();
        float toolSpeed = 1.0F;
        if (!tool.isEmpty()) {
            toolSpeed = tool.getDestroySpeed(state);
            if (toolSpeed < 1.0F) {
                toolSpeed = 1.0F;
            }
        }
        boolean canHarvest = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        float divisor = canHarvest ? 30.0F : 100.0F;
        return toolSpeed / hardness / divisor;
    }

    private void resetMiningProgress() {
        if (miningProgressPos != null && owner.level() instanceof ServerLevel serverLevel) {
            serverLevel.destroyBlockProgress(owner.getId(), miningProgressPos, -1);
        }
        miningProgressPos = null;
        miningProgress = 0.0F;
        miningProgressStage = -1;
        nextSwingTick = -1L;
    }

    private boolean isTargetValid() {
        if (targetBlock == null) {
            return false;
        }
        BlockState state = owner.level().getBlockState(targetBlock);
        if (activeType == CompanionResourceType.LOG) {
            return state.is(BlockTags.LOGS);
        }
        if (activeType == CompanionResourceType.STONE && digStonePos != null) {
            return state.is(BlockTags.MINEABLE_WITH_SHOVEL) || state.is(BlockTags.BASE_STONE_OVERWORLD);
        }
        return activeType.matchesBlock(state);
    }

    private void clearTarget() {
        targetBlock = null;
        pendingResourceBlock = null;
        digStonePos = null;
        resetMiningProgress();
    }

    private static final class TargetSelection {
        private final BlockPos target;
        private final BlockPos resourcePos;
        private final BlockPos sightPos;
        private final BlockPos digStonePos;
        private final BlockPos pendingResource;

        private TargetSelection(BlockPos target, BlockPos resourcePos, BlockPos sightPos, BlockPos digStonePos) {
            this(target, resourcePos, sightPos, digStonePos, null);
        }

        private TargetSelection(BlockPos target, BlockPos resourcePos, BlockPos sightPos, BlockPos digStonePos,
                                BlockPos pendingResource) {
            this.target = target;
            this.resourcePos = resourcePos;
            this.sightPos = sightPos;
            this.digStonePos = digStonePos;
            this.pendingResource = pendingResource;
        }
    }
}
