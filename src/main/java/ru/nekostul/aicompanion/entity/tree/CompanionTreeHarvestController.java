package ru.nekostul.aicompanion.entity.tree;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TieredItem;
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
import ru.nekostul.aicompanion.entity.resource.CompanionBlockRegistry;
import ru.nekostul.aicompanion.entity.CompanionEntity;
import ru.nekostul.aicompanion.entity.inventory.CompanionEquipment;
import ru.nekostul.aicompanion.entity.inventory.CompanionInventory;
import ru.nekostul.aicompanion.entity.mining.CompanionMiningAnimator;
import ru.nekostul.aicompanion.entity.mining.CompanionMiningReach;
import ru.nekostul.aicompanion.entity.resource.CompanionResourceRequest;
import ru.nekostul.aicompanion.entity.resource.CompanionResourceType;
import ru.nekostul.aicompanion.entity.tool.CompanionToolHandler;
import ru.nekostul.aicompanion.entity.tool.CompanionToolWear;

public final class CompanionTreeHarvestController {
    public enum Result {
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
    private static final int MAX_SCAN_BLOCKS_PER_TICK = 256;
    private static final int MAX_PATH_CANDIDATES = 24;
    private static final int MAX_PATH_CHECKS_PER_TICK = 2;
    private static final double PATH_DETOUR_MULTIPLIER = 2.0D;
    private static final int TREE_SEARCH_TIMEOUT_TICKS = 100;
    private static final int NEAR_SCAN_RADIUS = 8;
    private static final int LOG_FROM_LEAVES_RADIUS = 2;
    private static final int LOG_FROM_LEAVES_MAX_DEPTH = 6;
    private static final int TREE_MAX_RADIUS = 9;
    private static final int TREE_MAX_BLOCKS = 1024;

    private final CompanionEntity owner;
    private final CompanionInventory inventory;
    private final CompanionEquipment equipment;
    private final CompanionToolHandler toolHandler;
    private final CompanionMiningAnimator miningAnimator;
    private final CompanionMiningReach miningReach;
    private final CompanionTreeChopper treeChopper;

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
    private BlockPos trunkLogPos;
    private long nextScanTick = -1L;
    private BlockPos cachedTarget;
    private long lastScanTick = -1L;
    private boolean lastScanFound = true;
    private long searchStartTick = -1L;
    private BlockPos miningProgressPos;
    private float miningProgress;
    private int miningProgressStage = -1;
    private final Map<Item, Integer> collectedDrops = new HashMap<>();
    private ScanState scanState;
    private PathCheckState pathCheckState;
    private ScanState nearScanState;
    private PathCheckState nearPathCheckState;

    public CompanionTreeHarvestController(CompanionEntity owner,
                                          CompanionInventory inventory,
                                          CompanionEquipment equipment,
                                          CompanionToolHandler toolHandler) {
        this.owner = owner;
        this.inventory = inventory;
        this.equipment = equipment;
        this.toolHandler = toolHandler;
        this.miningAnimator = new CompanionMiningAnimator(owner);
        this.miningReach = new CompanionMiningReach(owner);
        this.treeChopper = new CompanionTreeChopper(owner, inventory, this::recordDrops,
                TREE_MAX_RADIUS, TREE_MAX_BLOCKS, LOG_FROM_LEAVES_RADIUS);
    }

    public Result tick(CompanionResourceRequest request, long gameTime) {
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

    public List<ItemStack> takeCollectedDrops() {
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

    public void resetAfterRequest() {
        resetRequestState();
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
            if (shouldPlaceStepBlock() && tryPlaceStepBlock()) {
                resetMiningProgress();
                return Result.IN_PROGRESS;
            }
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
            resetMiningProgress();
            if (treeBasePos != null && targetBlock.equals(treeBasePos)
                    && CompanionConfig.isFullTreeChopEnabled()) {
                boolean harvested = treeChopper.harvestTree(treeBasePos);
                treeBasePos = null;
                trunkLogPos = null;
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
            boolean mined = mineBlock(targetBlock);
            if (!mined) {
                clearTargetState();
                return Result.NEED_CHEST;
            }
            if (treeBasePos != null && !CompanionConfig.isFullTreeChopEnabled()) {
                if (treeMode == CompanionTreeRequestMode.LOG_BLOCKS && logsCollected >= logsRequired) {
                    clearTargetState();
                    return Result.IN_PROGRESS;
                }
                BlockPos trunkPos = trunkLogPos != null ? trunkLogPos : treeBasePos;
                if (targetBlock.equals(trunkPos)) {
                    BlockPos nextTrunk = findNextTrunkLog(treeBasePos, trunkPos);
                    if (nextTrunk != null) {
                        trunkLogPos = nextTrunk;
                        TargetSelection nextSelection = resolveTrunkTarget(treeBasePos, nextTrunk);
                        if (nextSelection != null) {
                            applySelection(nextSelection);
                            resetMiningProgress();
                            return Result.IN_PROGRESS;
                        }
                    }
                    if (treeMode == CompanionTreeRequestMode.TREE_COUNT) {
                        treesRemaining = Math.max(0, treesRemaining - 1);
                        resetScanCache();
                    }
                    clearTargetState();
                    return Result.IN_PROGRESS;
                }
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
                TargetSelection candidate = base != null
                        ? new TargetSelection(base, base, cachedTarget)
                        : new TargetSelection(cachedTarget, cachedTarget, cachedTarget);
                TargetSelection resolved = resolveTreeObstruction(candidate);
                if (resolved != null && (resolved.pendingResource == null || isTreeObstacle(resolved))
                        && isPathAcceptable(resolved, owner.blockPosition().distSqr(resolved.treeBase))) {
                    return resolved;
                }
            }
            resetScanCache();
        }
        if (gameTime < nextScanTick && scanState == null && pathCheckState == null
                && nearScanState == null && nearPathCheckState == null) {
            return null;
        }
        if (searchStartTick < 0L) {
            searchStartTick = gameTime;
        }
        BlockPos origin = owner.blockPosition();
        TargetSelection nearSelection = stepNearScan(origin);
        if (nearSelection != null) {
            finishScan(nearSelection, gameTime, true);
            return nearSelection;
        }
        if (nearScanState != null || nearPathCheckState != null) {
            if (searchStartTick >= 0L && gameTime - searchStartTick >= TREE_SEARCH_TIMEOUT_TICKS) {
                finishScan(null, gameTime, false);
            }
            return null;
        }
        if (pathCheckState == null) {
            if (scanState == null || !scanState.matches(origin)) {
                scanState = new ScanState(origin, owner.getEyePosition(), owner.getLookAngle().normalize(),
                        RESOURCE_SCAN_RADIUS, false);
            }
            scanState.step(this, MAX_SCAN_BLOCKS_PER_TICK);
            if (!scanState.getCandidates().isEmpty()) {
                pathCheckState = new PathCheckState(new ArrayList<>(scanState.getCandidates()));
            }
        }
        if (pathCheckState != null) {
            TargetSelection selection = pathCheckState.step(this, MAX_PATH_CHECKS_PER_TICK);
            if (selection != null) {
                finishScan(selection, gameTime, true);
                return selection;
            }
            if (pathCheckState.isFinished()) {
                if (scanState != null && !scanState.isFinished()) {
                    scanState.clearCandidates();
                    pathCheckState = null;
                } else {
                    finishScan(null, gameTime, false);
                    return null;
                }
            }
        }
        if (searchStartTick >= 0L && gameTime - searchStartTick >= TREE_SEARCH_TIMEOUT_TICKS) {
            finishScan(null, gameTime, false);
            return null;
        }
        if (scanState != null && scanState.isFinished() && pathCheckState == null) {
            finishScan(null, gameTime, false);
            return null;
        }
        return null;
    }

    private TargetSelection stepNearScan(BlockPos origin) {
        if (origin == null) {
            return null;
        }
        if (nearPathCheckState != null) {
            TargetSelection selection = nearPathCheckState.step(this, MAX_PATH_CHECKS_PER_TICK);
            if (selection != null) {
                return selection;
            }
            if (nearPathCheckState.isFinished()) {
                if (nearScanState != null && !nearScanState.isFinished()) {
                    nearScanState.clearCandidates();
                    nearPathCheckState = null;
                } else {
                    nearPathCheckState = null;
                    nearScanState = null;
                }
            }
            return null;
        }
        if (nearScanState == null || !nearScanState.matches(origin)) {
            nearScanState = new ScanState(origin, owner.getEyePosition(), owner.getLookAngle().normalize(),
                    NEAR_SCAN_RADIUS, true);
        }
        nearScanState.step(this, MAX_SCAN_BLOCKS_PER_TICK);
        if (nearPathCheckState == null && !nearScanState.getCandidates().isEmpty()) {
            nearPathCheckState = new PathCheckState(new ArrayList<>(nearScanState.getCandidates()));
        }
        if (nearScanState.isFinished() && nearPathCheckState == null) {
            nearScanState = null;
        }
        return null;
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
        if (hitPos.equals(resourcePos)) {
            return new TargetSelection(resourcePos, resourcePos, sightPos);
        }
        if (hitPos.equals(sightPos)) {
            BlockState hitState = owner.level().getBlockState(hitPos);
            if (!CompanionBlockRegistry.isLeaves(hitState)) {
                return new TargetSelection(resourcePos, resourcePos, sightPos);
            }
        }
        BlockState hitState = owner.level().getBlockState(hitPos);
        if (!isBreakable(hitState, hitPos)) {
            return null;
        }
        return new TargetSelection(hitPos, resourcePos, sightPos, resourcePos, sightPos);
    }

    private TargetSelection resolveTreeObstruction(TargetSelection selection) {
        if (selection == null || selection.treeBase == null) {
            return selection;
        }
        TargetSelection resolved = resolveObstruction(selection.treeBase, selection.sightPos);
        if (resolved == null) {
            return null;
        }
        if (resolved.pendingResource != null && isUpperTrunkBlock(resolved.target, selection.treeBase)) {
            return new TargetSelection(selection.treeBase, selection.treeBase, selection.sightPos);
        }
        return resolved;
    }

    private boolean isUpperTrunkBlock(BlockPos target, BlockPos treeBase) {
        if (target == null || treeBase == null) {
            return false;
        }
        if (target.getX() != treeBase.getX() || target.getZ() != treeBase.getZ()) {
            return false;
        }
        if (target.getY() <= treeBase.getY()) {
            return false;
        }
        return CompanionBlockRegistry.isLog(owner.level().getBlockState(target));
    }

    private TargetSelection resolveTrunkTarget(BlockPos base, BlockPos logPos) {
        TargetSelection resolved = resolveObstruction(logPos, logPos);
        if (resolved == null) {
            return null;
        }
        if (!CompanionConfig.isFullTreeChopEnabled()
                && resolved.pendingResource != null
                && CompanionBlockRegistry.isLeaves(owner.level().getBlockState(resolved.target))) {
            return new TargetSelection(logPos, base, logPos);
        }
        return new TargetSelection(resolved.target, base, resolved.sightPos, resolved.pendingResource, resolved.pendingSight);
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

    private BlockPos findNextTrunkLog(BlockPos base, BlockPos current) {
        if (base == null) {
            return null;
        }
        int startY = current != null ? current.getY() + 1 : base.getY() + 1;
        BlockPos next = new BlockPos(base.getX(), startY, base.getZ());
        if (CompanionBlockRegistry.isLog(owner.level().getBlockState(next))) {
            return next;
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
            drops = CompanionTreeChopper.filterLeafDrops(drops);
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
        CompanionToolWear.applyToolWear(owner, tool, InteractionHand.MAIN_HAND);
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
            toolSpeed = resolveToolSpeed(state, tool);
        }
        boolean canHarvest = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        float divisor = canHarvest ? 30.0F : 100.0F;
        return toolSpeed / hardness / divisor;
    }

    private float resolveToolSpeed(BlockState state, ItemStack tool) {
        float speed = tool.getDestroySpeed(state);
        if (speed < 1.0F && tool.getItem() instanceof TieredItem tiered) {
            if (isToolEffectiveForBlock(state, tool)) {
                speed = tiered.getTier().getSpeed();
            }
        }
        return Math.max(1.0F, speed);
    }

    private boolean isToolEffectiveForBlock(BlockState state, ItemStack tool) {
        return (state.is(BlockTags.MINEABLE_WITH_AXE) && tool.is(ItemTags.AXES))
                || (state.is(BlockTags.MINEABLE_WITH_PICKAXE) && tool.is(ItemTags.PICKAXES))
                || (state.is(BlockTags.MINEABLE_WITH_SHOVEL) && tool.is(ItemTags.SHOVELS))
                || (state.is(BlockTags.MINEABLE_WITH_HOE) && tool.is(ItemTags.HOES));
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
        BlockPos previousBase = treeBasePos;
        targetBlock = selection.target;
        pendingResourceBlock = selection.pendingResource;
        pendingSightPos = selection.pendingSight;
        treeBasePos = selection.treeBase;
        if (treeBasePos != null && (previousBase == null || !treeBasePos.equals(previousBase))) {
            trunkLogPos = treeBasePos;
        } else if (treeBasePos == null) {
            trunkLogPos = null;
        }
    }

    private void clearTargetState() {
        targetBlock = null;
        pendingResourceBlock = null;
        pendingSightPos = null;
        treeBasePos = null;
        trunkLogPos = null;
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
        searchStartTick = -1L;
        scanState = null;
        pathCheckState = null;
        nearScanState = null;
        nearPathCheckState = null;
    }

    private void finishScan(TargetSelection selection, long gameTime, boolean found) {
        lastScanTick = gameTime;
        lastScanFound = found;
        nextScanTick = gameTime + RESOURCE_SCAN_COOLDOWN_TICKS;
        cachedTarget = found && selection != null ? selection.treeBase : null;
        searchStartTick = -1L;
        scanState = null;
        pathCheckState = null;
        nearScanState = null;
        nearPathCheckState = null;
    }

    private boolean isPathAcceptable(TargetSelection selection, double distanceSqr) {
        if (selection == null || selection.treeBase == null) {
            return false;
        }
        if (distanceSqr <= 4.0D) {
            return true;
        }
        if (owner.getNavigation() == null) {
            return false;
        }
        int nodes = findBestPathNodes(selection.treeBase);
        if (nodes < 0) {
            return false;
        }
        if (nodes <= 0) {
            return true;
        }
        double distance = Math.sqrt(distanceSqr);
        return nodes <= distance * PATH_DETOUR_MULTIPLIER + 2.0D;
    }

    private int findBestPathNodes(BlockPos base) {
        int bestNodes = Integer.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 1; dy++) {
            int y = base.getY() + dy;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) != 1) {
                        continue;
                    }
                    pos.set(base.getX() + dx, y, base.getZ() + dz);
                    if (!isPassable(pos)) {
                        continue;
                    }
                    net.minecraft.world.level.pathfinder.Path path = owner.getNavigation().createPath(pos, 1);
                    if (path == null || !path.canReach()) {
                        continue;
                    }
                    int nodes = path.getNodeCount();
                    if (nodes < bestNodes) {
                        bestNodes = nodes;
                    }
                }
            }
        }
        return bestNodes == Integer.MAX_VALUE ? -1 : bestNodes;
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

    private boolean isTreeObstacle(TargetSelection selection) {
        if (selection == null || selection.pendingResource == null) {
            return false;
        }
        BlockState state = owner.level().getBlockState(selection.target);
        if (CompanionConfig.isFullTreeChopEnabled()) {
            return CompanionBlockRegistry.isLeaves(state) || CompanionBlockRegistry.isLog(state);
        }
        return CompanionBlockRegistry.isLog(state);
    }

    private boolean shouldPlaceStepBlock() {
        if (CompanionConfig.isFullTreeChopEnabled() || treeBasePos == null) {
            return false;
        }
        if (treeMode == CompanionTreeRequestMode.LOG_BLOCKS && logsCollected >= logsRequired) {
            return false;
        }
        if (treeMode == CompanionTreeRequestMode.TREE_COUNT && treesRemaining <= 0) {
            return false;
        }
        BlockPos desired = pendingResourceBlock != null ? pendingResourceBlock : targetBlock;
        if (desired == null) {
            return false;
        }
        if (!CompanionBlockRegistry.isLog(owner.level().getBlockState(desired))) {
            return false;
        }
        if (desired.getX() != treeBasePos.getX() || desired.getZ() != treeBasePos.getZ()) {
            return false;
        }
        BlockPos foot = owner.blockPosition();
        if (Math.abs(desired.getX() - foot.getX()) > 1 || Math.abs(desired.getZ() - foot.getZ()) > 1) {
            return false;
        }
        return desired.getY() > foot.getY() + 1;
    }

    private boolean tryPlaceStepBlock() {
        if (!(owner.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        BlockPos placePos = owner.blockPosition();
        BlockState placeState = selectStepBlockState();
        if (placeState == null) {
            return false;
        }
        if (!canPlaceStepAt(placePos, placeState)) {
            return false;
        }
        if (!serverLevel.setBlock(placePos, placeState, 3)) {
            return false;
        }
        if (!consumeStepBlock(placeState.getBlock())) {
            serverLevel.setBlock(placePos, Blocks.AIR.defaultBlockState(), 3);
            return false;
        }
        if (canStandAt(placePos.above()) && canStandAt(placePos.above(2))) {
            owner.setPos(owner.getX(), owner.getY() + 1.0D, owner.getZ());
        }
        return true;
    }

    private BlockState selectStepBlockState() {
        int slot = findStepBlockSlot();
        if (slot < 0) {
            return null;
        }
        ItemStack stack = inventory.getItems().get(slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        if (state.isAir() || state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER)) {
            return null;
        }
        return state;
    }

    private int findStepBlockSlot() {
        int fallbackLogSlot = -1;
        for (int i = 0; i < inventory.getItems().size(); i++) {
            ItemStack stack = inventory.getItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            BlockState state = blockItem.getBlock().defaultBlockState();
            if (state.isAir() || state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER)) {
                continue;
            }
            boolean isLog = CompanionBlockRegistry.isLog(state);
            boolean isLeaves = CompanionBlockRegistry.isLeaves(state);
            if (!isLog && !isLeaves) {
                return i;
            }
            if (fallbackLogSlot < 0 && treeMode == CompanionTreeRequestMode.TREE_COUNT) {
                fallbackLogSlot = i;
            }
        }
        return fallbackLogSlot;
    }

    private boolean consumeStepBlock(Block block) {
        for (int i = 0; i < inventory.getItems().size(); i++) {
            ItemStack stack = inventory.getItems().get(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock() == block) {
                stack.shrink(1);
                if (stack.isEmpty()) {
                    inventory.getItems().set(i, ItemStack.EMPTY);
                }
                owner.onInventoryUpdated();
                return true;
            }
        }
        return false;
    }

    private boolean canPlaceStepAt(BlockPos pos, BlockState placeState) {
        BlockState current = owner.level().getBlockState(pos);
        if (!current.canBeReplaced()) {
            return false;
        }
        if (!placeState.canSurvive(owner.level(), pos)) {
            return false;
        }
        return canStandAt(pos.above()) && canStandAt(pos.above(2));
    }

    private boolean canStandAt(BlockPos pos) {
        BlockState state = owner.level().getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        return state.getCollisionShape(owner.level(), pos).isEmpty();
    }

    private static int[] buildOffsets(int radius) {
        int size = radius * 2 + 1;
        int[] offsets = new int[size];
        offsets[0] = 0;
        int idx = 1;
        for (int i = 1; i <= radius; i++) {
            offsets[idx++] = i;
            offsets[idx++] = -i;
        }
        return offsets;
    }

    private static final class ScanState {
        private final BlockPos origin;
        private final Vec3 eye;
        private final Vec3 look;
        private final int radius;
        private final boolean requireVisible;
        private final int[] xOffsets;
        private final int[] yOffsets;
        private final int[] zOffsets;
        private int ix;
        private int iy;
        private int iz;
        private final List<Candidate> candidates = new ArrayList<>();
        private boolean foundVisible;
        private boolean finished;

        private ScanState(BlockPos origin, Vec3 eye, Vec3 look, int radius, boolean requireVisible) {
            this.origin = origin;
            this.eye = eye;
            this.look = look;
            this.radius = radius;
            this.requireVisible = requireVisible;
            this.xOffsets = buildOffsets(radius);
            this.yOffsets = buildOffsets(radius);
            this.zOffsets = buildOffsets(radius);
        }

        private boolean matches(BlockPos origin) {
            return this.origin.equals(origin);
        }

        private boolean isFinished() {
            return finished;
        }

        private List<Candidate> getCandidates() {
            return candidates;
        }

        private void clearCandidates() {
            candidates.clear();
        }

        private TargetSelection step(CompanionTreeHarvestController controller, int budget) {
            if (finished) {
                return null;
            }
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int processed = 0;
            while (processed < budget && !finished) {
                int dx = xOffsets[ix];
                int dy = yOffsets[iy];
                int dz = zOffsets[iz];
                pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                processed++;
                BlockState state = controller.owner.level().getBlockState(pos);
                TargetSelection selection = controller.resolveTreeSelection(pos.immutable(), state);
                if (selection != null) {
                    TargetSelection resolved = controller.resolveTreeObstruction(selection);
                    if (resolved != null && (resolved.pendingResource == null || controller.isTreeObstacle(resolved))) {
                        if (requireVisible && resolved.pendingResource != null) {
                            advanceIndices();
                            continue;
                        }
                        Vec3 sightCenter = Vec3.atCenterOf(selection.sightPos);
                        Vec3 toTarget = sightCenter.subtract(eye);
                        double distance = toTarget.length();
                        if (distance <= radius) {
                            if (look.dot(toTarget.normalize()) >= RESOURCE_FOV_DOT) {
                                double score = origin.distSqr(resolved.treeBase);
                                offerCandidate(resolved, score, resolved.pendingResource == null);
                            }
                        }
                    }
                }
                advanceIndices();
            }
            return null;
        }

        private void advanceIndices() {
            iz++;
            if (iz < zOffsets.length) {
                return;
            }
            iz = 0;
            iy++;
            if (iy < yOffsets.length) {
                return;
            }
            iy = 0;
            ix++;
            if (ix >= xOffsets.length) {
                finished = true;
            }
        }

        private void offerCandidate(TargetSelection selection, double distanceSqr, boolean visible) {
            if (requireVisible && !visible) {
                return;
            }
            if (!requireVisible && visible && !foundVisible) {
                candidates.clear();
                foundVisible = true;
            }
            if (!requireVisible && foundVisible && !visible) {
                return;
            }
            int index = 0;
            while (index < candidates.size() && distanceSqr >= candidates.get(index).distanceSqr) {
                index++;
            }
            if (index >= MAX_PATH_CANDIDATES) {
                return;
            }
            candidates.add(index, new Candidate(selection, distanceSqr));
            if (candidates.size() > MAX_PATH_CANDIDATES) {
                candidates.remove(MAX_PATH_CANDIDATES);
            }
        }
    }

    private static final class Candidate {
        private final TargetSelection selection;
        private final double distanceSqr;

        private Candidate(TargetSelection selection, double distanceSqr) {
            this.selection = selection;
            this.distanceSqr = distanceSqr;
        }
    }

    private static final class PathCheckState {
        private final List<Candidate> candidates;
        private int index;
        private boolean finished;

        private PathCheckState(List<Candidate> candidates) {
            this.candidates = candidates == null ? List.of() : candidates;
        }

        private boolean isFinished() {
            return finished;
        }

        private TargetSelection step(CompanionTreeHarvestController controller, int budget) {
            int processed = 0;
            while (index < candidates.size() && processed < budget) {
                Candidate candidate = candidates.get(index++);
                processed++;
                if (controller.isPathAcceptable(candidate.selection, candidate.distanceSqr)) {
                    finished = true;
                    return candidate.selection;
                }
            }
            if (index >= candidates.size()) {
                finished = true;
            }
            return null;
        }
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
