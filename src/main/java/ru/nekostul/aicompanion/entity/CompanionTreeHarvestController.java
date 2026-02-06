package ru.nekostul.aicompanion.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ru.nekostul.aicompanion.CompanionConfig;

final class CompanionTreeHarvestController {
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
    private static final int LOG_FROM_LEAVES_RADIUS = 2;
    private static final int LOG_FROM_LEAVES_MAX_DEPTH = 6;
    private static final int TREE_MAX_RADIUS = 7;
    private static final int TREE_MAX_BLOCKS = 512;

    private final CompanionEntity owner;
    private final CompanionInventory inventory;
    private final CompanionEquipment equipment;
    private final CompanionToolHandler toolHandler;
    private final CompanionMiningAnimator miningAnimator;
    private final CompanionMiningReach miningReach;

    private CompanionTreeRequestMode treeMode = CompanionTreeRequestMode.NONE;
    private int requestAmount;
    private int treesRemaining;
    private int logsRequired;
    private int logsCollected;
    private UUID requestPlayerId;
    private BlockPos targetBlock;
    private BlockPos pendingResourceBlock;
    private BlockPos pendingSightPos;
    private BlockPos treeBasePos;
    private long nextScanTick = -1L;
    private BlockPos cachedTarget;
    private long lastScanTick = -1L;
    private boolean lastScanFound = true;
    private BlockPos miningProgressPos;
    private float miningProgress;
    private int miningProgressStage = -1;
    private final Map<Item, Integer> collectedDrops = new HashMap<>();

    CompanionTreeHarvestController(CompanionEntity owner,
                                   CompanionInventory inventory,
                                   CompanionEquipment equipment,
                                   CompanionToolHandler toolHandler) {
        this.owner = owner;
        this.inventory = inventory;
        this.equipment = equipment;
        this.toolHandler = toolHandler;
        this.miningAnimator = new CompanionMiningAnimator(owner);
        this.miningReach = new CompanionMiningReach(owner);
    }

    Result tick(CompanionResourceRequest request, long gameTime) {
        if (request == null || !request.isTreeRequest()) {
            resetRequestState();
            return Result.IDLE;
        }
        if (treeMode != request.getTreeMode() || requestAmount != request.getAmount()) {
            resetRequestState();
            treeMode = request.getTreeMode();
            requestAmount = request.getAmount();
            requestPlayerId = request.getPlayerId();
            if (treeMode == CompanionTreeRequestMode.TREE_COUNT) {
                treesRemaining = requestAmount;
            } else {
                logsRequired = requestAmount;
            }
        }
        if (inventory.isFull()) {
            clearTargetState();
            return Result.NEED_CHEST;
        }
        if (treeMode == CompanionTreeRequestMode.TREE_COUNT && treesRemaining <= 0 && targetBlock == null) {
            return Result.DONE;
        }
        if (treeMode == CompanionTreeRequestMode.LOG_BLOCKS && logsCollected >= logsRequired && targetBlock == null) {
            return Result.DONE;
        }
        if (targetBlock == null || !isTargetValid()) {
            TargetSelection selection = findTreeTarget(gameTime);
            if (selection == null) {
                if (gameTime == lastScanTick && !lastScanFound) {
                    return Result.NOT_FOUND;
                }
                return Result.IN_PROGRESS;
            }
            applySelection(selection);
            resetMiningProgress();
        }
        return tickMining(gameTime);
    }

    List<ItemStack> takeCollectedDrops() {
        if (collectedDrops.isEmpty()) {
            return List.of();
        }
        List<ItemStack> result = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : collectedDrops.entrySet()) {
            int remaining = entry.getValue();
            if (remaining <= 0) {
                continue;
            }
            List<ItemStack> taken = inventory.takeMatching(stack -> stack.is(entry.getKey()), remaining);
            result.addAll(taken);
        }
        collectedDrops.clear();
        return result;
    }

    private Result tickMining(long gameTime) {
        if (targetBlock == null) {
            return Result.IN_PROGRESS;
        }
        BlockState state = owner.level().getBlockState(targetBlock);
        if (state.isAir()) {
            if (advancePendingTarget()) {
                return Result.IN_PROGRESS;
            }
            clearTargetState();
            return Result.IN_PROGRESS;
        }
        if (!isBreakable(state, targetBlock)) {
            clearTargetState();
            return Result.IN_PROGRESS;
        }
        Vec3 center = Vec3.atCenterOf(targetBlock);
        if (!miningReach.canMine(targetBlock)) {
            owner.getNavigation().moveTo(center.x, center.y, center.z, 1.0D);
            resetMiningProgress();
            return Result.IN_PROGRESS;
        }
        owner.getNavigation().stop();
        Player player = owner.getPlayerById(requestPlayerId);
        if (!toolHandler.prepareTool(state, targetBlock, player, gameTime)) {
            resetMiningProgress();
            return Result.IN_PROGRESS;
        }
        if (miningProgressPos == null || !miningProgressPos.equals(targetBlock)) {
            resetMiningProgress();
            miningProgressPos = targetBlock;
        }
        float progressPerTick = getBreakProgress(state, targetBlock);
        if (progressPerTick <= 0.0F) {
            clearTargetState();
            return Result.IN_PROGRESS;
        }
        miningAnimator.tick(targetBlock, progressPerTick, gameTime);
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
                clearTargetState();
                return Result.NEED_CHEST;
            }
            if (treeBasePos != null && targetBlock.equals(treeBasePos)) {
                if (CompanionConfig.isFullTreeChopEnabled()) {
                    boolean harvested = harvestTree(treeBasePos);
                    treeBasePos = null;
                    resetScanCache();
                    if (!harvested) {
                        clearTargetState();
                        return Result.NEED_CHEST;
                    }
                    if (treeMode == CompanionTreeRequestMode.TREE_COUNT) {
                        treesRemaining = Math.max(0, treesRemaining - 1);
                    }
                    clearTargetState();
                    return Result.IN_PROGRESS;
                }
                if (treeMode == CompanionTreeRequestMode.TREE_COUNT) {
                    treesRemaining = Math.max(0, treesRemaining - 1);
                    resetScanCache();
                }
                clearTargetState();
                return Result.IN_PROGRESS;
            }
            if (advancePendingTarget()) {
                return Result.IN_PROGRESS;
            }
            clearTargetState();
        }
        return Result.IN_PROGRESS;
    }

    private TargetSelection findTreeTarget(long gameTime) {
        if (gameTime < nextScanTick && cachedTarget != null) {
            BlockState cached = owner.level().getBlockState(cachedTarget);
            if (CompanionBlockRegistry.isLog(cached)) {
                BlockPos base = findTreeBase(cachedTarget);
                if (base != null) {
                    return new TargetSelection(base, base, cachedTarget);
                }
                return new TargetSelection(cachedTarget, cachedTarget, cachedTarget);
            }
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
                    TargetSelection selection = resolveTreeSelection(pos.immutable(), state);
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
                    double score = origin.distSqr(selection.treeBase);
                    if (score < bestDistance) {
                        bestDistance = score;
                        best = selection;
                    }
                }
            }
        }
        if (best != null) {
            lastScanFound = true;
        }
        nextScanTick = gameTime + RESOURCE_SCAN_COOLDOWN_TICKS;
        cachedTarget = best != null ? best.treeBase : null;
        return best;
    }

    private TargetSelection resolveTreeSelection(BlockPos pos, BlockState state) {
        if (CompanionBlockRegistry.isLog(state)) {
            BlockPos base = findTreeBase(pos);
            if (base != null) {
                return new TargetSelection(base, base, pos);
            }
            return new TargetSelection(pos, pos, pos);
        }
        if (CompanionBlockRegistry.isLeaves(state)) {
            BlockPos logPos = resolveLogFromLeaves(pos);
            if (logPos != null) {
                BlockPos base = findTreeBase(logPos);
                if (base != null) {
                    return new TargetSelection(base, base, pos);
                }
                return new TargetSelection(logPos, logPos, pos);
            }
        }
        return null;
    }

    private TargetSelection resolveObstruction(BlockPos resourcePos, BlockPos sightPos) {
        Vec3 eye = owner.getEyePosition();
        Vec3 targetCenter = Vec3.atCenterOf(resourcePos);
        BlockHitResult hit = owner.level().clip(new ClipContext(eye, targetCenter, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, owner));
        if (hit.getType() == HitResult.Type.MISS) {
            return new TargetSelection(resourcePos, resourcePos, sightPos);
        }
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos hitPos = hit.getBlockPos();
        if (hitPos.equals(resourcePos) || hitPos.equals(sightPos)) {
            return new TargetSelection(resourcePos, resourcePos, sightPos);
        }
        BlockState hitState = owner.level().getBlockState(hitPos);
        if (!isBreakable(hitState, hitPos)) {
            return null;
        }
        return new TargetSelection(hitPos, resourcePos, sightPos, resourcePos, sightPos);
    }

    private boolean advancePendingTarget() {
        if (pendingResourceBlock == null) {
            return false;
        }
        BlockPos sight = pendingSightPos != null ? pendingSightPos : pendingResourceBlock;
        TargetSelection selection = resolveObstruction(pendingResourceBlock, sight);
        if (selection == null) {
            pendingResourceBlock = null;
            pendingSightPos = null;
            return false;
        }
        targetBlock = selection.target;
        pendingResourceBlock = selection.pendingResource;
        pendingSightPos = selection.pendingSight;
        resetMiningProgress();
        return true;
    }

    private BlockPos resolveLogFromLeaves(BlockPos leafPos) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= LOG_FROM_LEAVES_MAX_DEPTH; dy++) {
            int y = leafPos.getY() - dy;
            for (int dx = -LOG_FROM_LEAVES_RADIUS; dx <= LOG_FROM_LEAVES_RADIUS; dx++) {
                for (int dz = -LOG_FROM_LEAVES_RADIUS; dz <= LOG_FROM_LEAVES_RADIUS; dz++) {
                    pos.set(leafPos.getX() + dx, y, leafPos.getZ() + dz);
                    if (CompanionBlockRegistry.isLog(owner.level().getBlockState(pos))) {
                        return pos.immutable();
                    }
                }
            }
        }
        return null;
    }

    private BlockPos findTreeBase(BlockPos start) {
        if (start == null) {
            return null;
        }
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        BlockPos bestWithAbove = null;
        BlockPos bestAny = null;
        int bestWithAboveY = Integer.MAX_VALUE;
        int bestAnyY = Integer.MAX_VALUE;
        double bestWithAboveDist = Double.MAX_VALUE;
        double bestAnyDist = Double.MAX_VALUE;
        int maxDistanceSqr = TREE_MAX_RADIUS * TREE_MAX_RADIUS;
        BlockPos origin = owner.blockPosition();

        while (!queue.isEmpty() && visited.size() < TREE_MAX_BLOCKS) {
            BlockPos pos = queue.poll();
            if (!visited.add(pos)) {
                continue;
            }
            if (start.distSqr(pos) > maxDistanceSqr) {
                continue;
            }
            BlockState state = owner.level().getBlockState(pos);
            if (!CompanionBlockRegistry.isLog(state)) {
                continue;
            }
            BlockState belowState = owner.level().getBlockState(pos.below());
            boolean belowIsLog = CompanionBlockRegistry.isLog(belowState);
            boolean belowIsLeaves = CompanionBlockRegistry.isLeaves(belowState);
            if (!belowIsLog && !belowIsLeaves) {
                boolean hasLogAbove = CompanionBlockRegistry.isLog(owner.level().getBlockState(pos.above()));
                int y = pos.getY();
                double dist = origin.distSqr(pos);
                if (hasLogAbove) {
                    if (y < bestWithAboveY || (y == bestWithAboveY && dist < bestWithAboveDist)) {
                        bestWithAboveY = y;
                        bestWithAboveDist = dist;
                        bestWithAbove = pos;
                    }
                } else if (y < bestAnyY || (y == bestAnyY && dist < bestAnyDist)) {
                    bestAnyY = y;
                    bestAnyDist = dist;
                    bestAny = pos;
                }
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = pos.offset(dx, dy, dz);
                        if (!visited.contains(next)) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return bestWithAbove != null ? bestWithAbove : bestAny;
    }

    private boolean harvestTree(BlockPos base) {
        if (!(owner.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(base);
        int maxDistanceSqr = TREE_MAX_RADIUS * TREE_MAX_RADIUS;

        while (!queue.isEmpty() && visited.size() < TREE_MAX_BLOCKS) {
            BlockPos pos = queue.poll();
            if (!visited.add(pos)) {
                continue;
            }
            BlockState state = owner.level().getBlockState(pos);
            boolean isTreeBlock = CompanionBlockRegistry.isLog(state) || CompanionBlockRegistry.isLeaves(state);
            if (isTreeBlock) {
                if (!breakTreeBlock(serverLevel, pos, state)) {
                    return false;
                }
            }
            if (!isTreeBlock && !pos.equals(base)) {
                continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = pos.offset(dx, dy, dz);
                        if (base.distSqr(next) > maxDistanceSqr) {
                            continue;
                        }
                        if (!visited.contains(next)) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return true;
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
        recordDrops(drops);
        return true;
    }

    private void recordDrops(List<ItemStack> drops) {
        for (ItemStack stack : drops) {
            if (stack.isEmpty()) {
                continue;
            }
            Item item = stack.getItem();
            collectedDrops.merge(item, stack.getCount(), Integer::sum);
            if (CompanionResourceType.LOG.matchesItem(stack)) {
                logsCollected += stack.getCount();
            }
        }
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
        recordDrops(drops);
        return true;
    }

    private List<ItemStack> filterLeafDrops(List<ItemStack> drops) {
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
        miningAnimator.reset();
    }

    private boolean isTargetValid() {
        if (targetBlock == null) {
            return false;
        }
        BlockState state = owner.level().getBlockState(targetBlock);
        if (pendingResourceBlock != null) {
            return isBreakable(state, targetBlock);
        }
        if (treeBasePos != null) {
            return CompanionBlockRegistry.isLog(state);
        }
        return isBreakable(state, targetBlock);
    }

    private void applySelection(TargetSelection selection) {
        targetBlock = selection.target;
        pendingResourceBlock = selection.pendingResource;
        pendingSightPos = selection.pendingSight;
        treeBasePos = selection.treeBase;
    }

    private void clearTargetState() {
        targetBlock = null;
        pendingResourceBlock = null;
        pendingSightPos = null;
        treeBasePos = null;
        resetMiningProgress();
    }

    private void resetRequestState() {
        clearTargetState();
        treeMode = CompanionTreeRequestMode.NONE;
        requestAmount = 0;
        treesRemaining = 0;
        logsRequired = 0;
        logsCollected = 0;
        requestPlayerId = null;
        collectedDrops.clear();
        resetScanCache();
    }

    private void resetScanCache() {
        nextScanTick = -1L;
        cachedTarget = null;
        lastScanTick = -1L;
        lastScanFound = true;
    }

    private static final class TargetSelection {
        private final BlockPos target;
        private final BlockPos treeBase;
        private final BlockPos sightPos;
        private final BlockPos pendingResource;
        private final BlockPos pendingSight;

        private TargetSelection(BlockPos target, BlockPos treeBase, BlockPos sightPos) {
            this(target, treeBase, sightPos, null, null);
        }

        private TargetSelection(BlockPos target, BlockPos treeBase, BlockPos sightPos,
                                BlockPos pendingResource, BlockPos pendingSight) {
            this.target = target;
            this.treeBase = treeBase;
            this.sightPos = sightPos;
            this.pendingResource = pendingResource;
            this.pendingSight = pendingSight;
        }
    }
}
