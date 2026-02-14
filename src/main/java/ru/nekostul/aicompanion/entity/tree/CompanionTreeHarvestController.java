package ru.nekostul.aicompanion.entity.tree;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fml.ModList;

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
        NOT_FOUND,
        FAILED
    }

    private static final int CHUNK_RADIUS = 6;
    private static final int RESOURCE_SCAN_RADIUS = CHUNK_RADIUS * 16;
    private static final int RESOURCE_SCAN_COOLDOWN_TICKS = 80;
    private static final float RESOURCE_FOV_DOT = -1.0F;
    private static final int MAX_SCAN_BLOCKS_PER_TICK = 200000;
    private static final int MAX_PATH_CANDIDATES = 24;
    private static final int MAX_PATH_CHECKS_PER_TICK = 2;
    private static final double PATH_DETOUR_MULTIPLIER = 2.0D;
    private static final int TREE_SEARCH_TIMEOUT_TICKS = 500;
    private static final int NEAR_SCAN_RADIUS = 24;
    private static final int LOG_FROM_LEAVES_RADIUS = 3;
    private static final int LOG_FROM_LEAVES_MAX_DEPTH = 6;
    private static final int TREE_MAX_RADIUS = 9;
    private static final int TREE_MAX_BLOCKS = 1024;
    private static final int MIN_TRUNK_HEIGHT = 3;
    private static final int TREE_SEARCH_RETRY_LIMIT = 10;
    private static final int LEAF_SEARCH_RADIUS = 4;
    private static final int LEAF_SEARCH_EXTRA_HEIGHT = 4;
    private static final int STUCK_RETRY_INTERVAL_TICKS = 40;
    private static final int STUCK_RETRY_LIMIT = 10;
    private static final double STUCK_MOVE_EPSILON_SQR = 0.0004D;
    private static final int STEP_CLEANUP_INTERVAL_TICKS = 10;
    private static final int STEP_PLACE_INTERVAL_TICKS = 8;
    private static final int STEP_TRIM_INTERVAL_TICKS = 10;
    private static final double TREE_MOVE_SPEED_BLOCKS_PER_TICK = 0.35D;
    private static final GameProfile MINING_PROFILE = new GameProfile(
            UUID.fromString("d44fdfd6-11c2-4766-9fd2-9a7f702cc563"), "CompanionLumber");

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
    private int logsBaseline;
    private UUID requestPlayerId;
    private BlockPos targetBlock;
    private BlockPos pendingResourceBlock;
    private BlockPos pendingSightPos;
    private BlockPos treeBasePos;
    private BlockPos trunkLogPos;
    private BlockPos treeChopWaitPos;
    private BlockPos treeChopLockedBase;
    private final Set<BlockPos> treeChopTrackedLogs = new HashSet<>();
    private boolean forceTrunkChop;
    private final Deque<PlacedStep> placedSteps = new ArrayDeque<>();
    private boolean stepCleanupActive;
    private long nextStepCleanupTick = -1L;
    private long nextStepPlaceTick = -1L;
    private long nextStepTrimTick = -1L;
    private boolean scanSawCandidate;
    private boolean lastScanHadCandidates;
    private long nextScanTick = -1L;
    private BlockPos cachedTarget;
    private long lastScanTick = -1L;
    private boolean lastScanFound = true;
    private long searchStartTick = -1L;
    private BlockPos miningProgressPos;
    private float miningProgress;
    private int miningProgressStage = -1;
    private int failedSearchAttempts;
    private long lastProgressTick = -1L;
    private long lastRestartTick = -1L;
    private int stuckRetryCount;
    private int lastProgressHash;
    private float lastProgressMining;
    private Vec3 lastProgressPos;
    private BlockPos lastMoveTarget;
    private long lastMoveAttemptTick = -1L;
    private final Map<Item, Integer> collectedDrops = new HashMap<>();
    private final Map<Item, Integer> treeChopBaseline = new HashMap<>();
    private final Set<BlockPos> manualTreeLogs = new HashSet<>();
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
    }

    public Result tick(CompanionResourceRequest request, long gameTime) {
        if (request == null || !request.isTreeRequest()) {
            resetRequestState();
            return finalizeResult(Result.IDLE, gameTime);
        }
        if (treeMode != request.getTreeMode() || requestAmount != request.getAmount()) {
            resetRequestState();
            treeMode = request.getTreeMode();
            requestAmount = request.getAmount();
            requestPlayerId = request.getPlayerId();
            if (treeMode == CompanionTreeRequestMode.TREE_COUNT) {
                if (isTreeChopActive()) {
                    treesRemaining = requestAmount;
                    captureTreeChopBaseline();
                } else {
                    logsRequired = requestAmount;
                    logsCollected = 0;
                    treesRemaining = 1;
                }
            } else {
                logsRequired = requestAmount;
                if (isTreeChopActive()) {
                    logsBaseline = countLogsInInventory();
                    logsCollected = 0;
                }
            }
        }
        if (inventory.isFull()) {
            clearTargetState();
            return finalizeResult(Result.NEED_CHEST, gameTime);
        }
        if (stepCleanupActive && tickStepCleanup(gameTime)) {
            return finalizeResult(Result.IN_PROGRESS, gameTime);
        }
        if (isTreeChopActive() && treeChopWaitPos != null) {
            if (!isTreeChopComplete(treeChopWaitPos)) {
                return finalizeResult(Result.IN_PROGRESS, gameTime);
            }
            collectTreeChopDrops(treeChopWaitPos);
            startStepCleanup(gameTime);
            treeChopWaitPos = null;
            treeChopLockedBase = null;
            treeChopTrackedLogs.clear();
            forceTrunkChop = false;
            if (treeMode == CompanionTreeRequestMode.TREE_COUNT) {
                treesRemaining = Math.max(0, treesRemaining - 1);
                failedSearchAttempts = 0;
                resetScanCache();
            }
        }
        if (treeMode == CompanionTreeRequestMode.LOG_BLOCKS && isTreeChopActive()) {
            logsCollected = Math.max(0, countLogsInInventory() - logsBaseline);
        }
        if (treeMode == CompanionTreeRequestMode.TREE_COUNT && isTreeCountCompleted() && targetBlock == null) {
            if (isTreeChopActive()) {
                captureTreeChopDrops();
            }
            return finalizeResult(Result.DONE, gameTime);
        }
        if (treeMode == CompanionTreeRequestMode.LOG_BLOCKS && logsCollected >= logsRequired && targetBlock == null) {
            if (isTreeChopInProgress()) {
                return finalizeResult(Result.IN_PROGRESS, gameTime);
            }
            return finalizeResult(Result.DONE, gameTime);
        }
        if (targetBlock != null && !isTargetValid()) {
            clearTargetState();
            lastRestartTick = gameTime;
        }
        if (targetBlock == null) {
            if (isRestartCooldownActive(gameTime)) {
                if (!owner.getNavigation().isDone()) {
                    owner.getNavigation().stop();
                }
                return finalizeResult(Result.IN_PROGRESS, gameTime);
            }
            TargetSelection selection = findTreeTarget(gameTime);
            if (selection == null) {
                if (isTreeChopInProgress()) {
                    return finalizeResult(Result.IN_PROGRESS, gameTime);
                }
                if (gameTime == lastScanTick && !lastScanFound) {
                    if (!lastScanHadCandidates) {
                        clearTargetState();
                        return finalizeResult(Result.NOT_FOUND, gameTime);
                    }
                    failedSearchAttempts++;
                    if (failedSearchAttempts >= TREE_SEARCH_RETRY_LIMIT) {
                        clearTargetState();
                        return finalizeResult(Result.FAILED, gameTime);
                    }
                    return finalizeResult(Result.IN_PROGRESS, gameTime);
                }
                return finalizeResult(Result.IN_PROGRESS, gameTime);
            }
            applySelection(selection);
            resetMiningProgress();
        }
        return finalizeResult(tickMining(gameTime), gameTime);
    }

    public boolean isTreeChopInProgress() {
        return isTreeChopActive() && treeChopLockedBase != null;
    }

    public List<ItemStack> takeCollectedDrops() {
        if (collectedDrops.isEmpty()) {
            return List.of();
        }
        if (!isTreeChopActive() && treeMode == CompanionTreeRequestMode.TREE_COUNT && requestAmount > 1) {
            List<ItemStack> limitedLogs = takeCollectedLogs(Math.max(0, requestAmount));
            collectedDrops.clear();
            return limitedLogs;
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

    private Result finalizeResult(Result result, long gameTime) {
        if (result != Result.IN_PROGRESS) {
            resetStuckTracking();
            return result;
        }
        updateProgress(gameTime);
        Result stuckResult = handleStuckRestart(gameTime);
        return stuckResult != null ? stuckResult : result;
    }

    private void noteScanCandidate() {
        scanSawCandidate = true;
    }

    private void updateProgress(long gameTime) {
        if (stepCleanupActive) {
            lastProgressTick = gameTime;
            lastProgressHash = computeProgressHash();
            lastProgressMining = miningProgress;
            lastProgressPos = owner.position();
            stuckRetryCount = 0;
            return;
        }
        if (isScanInProgress()) {
            lastProgressTick = gameTime;
            lastProgressHash = computeProgressHash();
            lastProgressMining = miningProgress;
            lastProgressPos = owner.position();
            stuckRetryCount = 0;
            return;
        }
        Vec3 currentPos = owner.position();
        if (lastProgressPos == null) {
            lastProgressPos = currentPos;
            lastProgressTick = gameTime;
            lastProgressHash = computeProgressHash();
            lastProgressMining = miningProgress;
            return;
        }
        boolean moved = currentPos.distanceToSqr(lastProgressPos) > STUCK_MOVE_EPSILON_SQR;
        boolean movementProgress = hasMovementGoal() && moved;
        boolean miningAdvanced = miningProgress > lastProgressMining + 1.0E-4F;
        int progressHash = computeProgressHash();
        boolean stateChanged = progressHash != lastProgressHash;
        if (stateChanged || movementProgress || miningAdvanced) {
            lastProgressTick = gameTime;
            lastProgressHash = progressHash;
            if (stateChanged || movementProgress) {
                lastProgressPos = currentPos;
            }
            if (stateChanged || movementProgress || miningAdvanced) {
                stuckRetryCount = 0;
            }
        }
        lastProgressMining = miningProgress;
    }

    private boolean isScanInProgress() {
        return scanState != null || pathCheckState != null || nearScanState != null || nearPathCheckState != null;
    }

    private int computeProgressHash() {
        int hash = 1;
        hash = 31 * hash + (treeMode != null ? treeMode.ordinal() : 0);
        hash = 31 * hash + requestAmount;
        hash = 31 * hash + treesRemaining;
        hash = 31 * hash + logsRequired;
        hash = 31 * hash + logsCollected;
        hash = 31 * hash + (targetBlock != null ? targetBlock.hashCode() : 0);
        hash = 31 * hash + (pendingResourceBlock != null ? pendingResourceBlock.hashCode() : 0);
        hash = 31 * hash + (pendingSightPos != null ? pendingSightPos.hashCode() : 0);
        hash = 31 * hash + (treeBasePos != null ? treeBasePos.hashCode() : 0);
        hash = 31 * hash + (trunkLogPos != null ? trunkLogPos.hashCode() : 0);
        hash = 31 * hash + (treeChopWaitPos != null ? treeChopWaitPos.hashCode() : 0);
        hash = 31 * hash + (treeChopLockedBase != null ? treeChopLockedBase.hashCode() : 0);
        hash = 31 * hash + (miningProgressPos != null ? miningProgressPos.hashCode() : 0);
        hash = 31 * hash + (forceTrunkChop ? 1 : 0);
        if (treeChopWaitPos != null) {
            hash = 31 * hash + countRemainingTreeChopLogs();
        }
        return hash;
    }

    private int countRemainingTreeChopLogs() {
        BlockPos base = treeChopWaitPos != null ? treeChopWaitPos : treeChopLockedBase;
        if (base == null) {
            return 0;
        }
        if (treeChopTrackedLogs.isEmpty()) {
            captureTreeChopLogPositions(base);
        }
        int remaining = 0;
        for (BlockPos pos : treeChopTrackedLogs) {
            if (CompanionBlockRegistry.isLog(owner.level().getBlockState(pos))) {
                remaining++;
            }
        }
        return remaining;
    }

    private Result handleStuckRestart(long gameTime) {
        if (!shouldCheckStuck()) {
            return null;
        }
        if (lastProgressTick < 0L) {
            lastProgressTick = gameTime;
            lastProgressHash = computeProgressHash();
            lastProgressMining = miningProgress;
            lastProgressPos = owner.position();
            return null;
        }
        if (gameTime - lastProgressTick < STUCK_RETRY_INTERVAL_TICKS) {
            return null;
        }
        if (lastRestartTick >= 0L && gameTime - lastRestartTick < STUCK_RETRY_INTERVAL_TICKS) {
            return null;
        }
        if (miningProgress > 0.0F) {
            return null;
        }
        if (stuckRetryCount >= STUCK_RETRY_LIMIT) {
            clearTargetState();
            treeChopWaitPos = null;
            treeChopLockedBase = null;
            treeChopTrackedLogs.clear();
            forceTrunkChop = false;
            resetScanCache();
            return Result.FAILED;
        }
        stuckRetryCount++;
        if (forceTrunkChop && treeChopLockedBase != null) {
            restartManualTrunk(gameTime);
            return Result.IN_PROGRESS;
        }
        if (tryStartManualTrunkChop(gameTime)) {
            return Result.IN_PROGRESS;
        }
        restartStuckSearch(gameTime);
        return Result.IN_PROGRESS;
    }

    private boolean shouldCheckStuck() {
        return treeMode != CompanionTreeRequestMode.NONE;
    }

    private boolean hasMovementGoal() {
        return targetBlock != null || pendingResourceBlock != null || treeBasePos != null;
    }

    private boolean isRestartCooldownActive(long gameTime) {
        return lastRestartTick >= 0L && gameTime - lastRestartTick < STUCK_RETRY_INTERVAL_TICKS;
    }

    private boolean shouldIssueMoveTo(BlockPos moveTarget, long gameTime) {
        if (moveTarget == null) {
            return false;
        }
        if (lastMoveTarget == null || !lastMoveTarget.equals(moveTarget)) {
            return true;
        }
        if (!owner.getNavigation().isDone()) {
            return false;
        }
        return lastMoveAttemptTick < 0L || gameTime - lastMoveAttemptTick >= STUCK_RETRY_INTERVAL_TICKS;
    }

    private void rememberMoveTo(BlockPos moveTarget, long gameTime) {
        lastMoveTarget = moveTarget;
        lastMoveAttemptTick = gameTime;
    }

    private void resetMoveTracking() {
        lastMoveTarget = null;
        lastMoveAttemptTick = -1L;
    }

    private boolean tryStartManualTrunkChop(long gameTime) {
        if (!isTreeChopActive() || forceTrunkChop) {
            return false;
        }
        if (treeChopWaitPos == null || treeChopLockedBase == null) {
            return false;
        }
        if (isTreeChopComplete(treeChopWaitPos)) {
            return false;
        }
        BlockPos base = treeChopLockedBase;
        BlockPos nextTrunk = findNextTrunkLog(base, base);
        if (nextTrunk == null) {
            return false;
        }
        forceTrunkChop = true;
        treeChopWaitPos = null;
        treeBasePos = base;
        trunkLogPos = nextTrunk;
        TargetSelection selection = resolveTrunkTarget(base, nextTrunk);
        if (selection != null) {
            applySelection(selection);
            resetMiningProgress();
        }
        lastRestartTick = gameTime;
        lastProgressTick = gameTime;
        lastProgressHash = computeProgressHash();
        lastProgressMining = miningProgress;
        lastProgressPos = owner.position();
        return true;
    }

    private void restartManualTrunk(long gameTime) {
        owner.getNavigation().stop();
        clearTargetState();
        BlockPos base = treeChopLockedBase;
        if (base == null) {
            restartStuckSearch(gameTime);
            return;
        }
        treeBasePos = base;
        trunkLogPos = resolveTreeChopLogStart(base);
        if (trunkLogPos != null) {
            TargetSelection selection = resolveTrunkTarget(base, trunkLogPos);
            if (selection != null) {
                applySelection(selection);
                resetMiningProgress();
            }
        }
        lastRestartTick = gameTime;
        lastProgressTick = gameTime;
        lastProgressHash = computeProgressHash();
        lastProgressMining = miningProgress;
        lastProgressPos = owner.position();
    }

    private void restartStuckSearch(long gameTime) {
        owner.getNavigation().stop();
        clearTargetState();
        treeChopWaitPos = null;
        treeChopLockedBase = null;
        treeChopTrackedLogs.clear();
        forceTrunkChop = false;
        failedSearchAttempts = 0;
        resetScanCache();
        lastRestartTick = gameTime;
        lastProgressTick = gameTime;
        lastProgressHash = computeProgressHash();
        lastProgressMining = miningProgress;
        lastProgressPos = owner.position();
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
        if (!miningReach.canMine(targetBlock)) {
            if (tryTrimUnusedStep(gameTime)) {
                resetMoveTracking();
                resetMiningProgress();
                return Result.IN_PROGRESS;
            }
            if (shouldPlaceStepBlock() && tryPlaceStepBlock(gameTime)) {
                resetMoveTracking();
                resetMiningProgress();
                return Result.IN_PROGRESS;
            }
            BlockPos moveTarget = resolveMiningMoveTarget();
            if (moveTarget == null) {
                moveTarget = targetBlock;
            }
            Vec3 center = Vec3.atCenterOf(moveTarget);
            if (shouldIssueMoveTo(moveTarget, gameTime)) {
                owner.getNavigation().moveTo(center.x, center.y, center.z, speedModifierFor(TREE_MOVE_SPEED_BLOCKS_PER_TICK));
                rememberMoveTo(moveTarget, gameTime);
            }
            resetMiningProgress();
            return Result.IN_PROGRESS;
        }
        owner.getNavigation().stop();
        resetMoveTracking();
        Player player = owner.getPlayerById(requestPlayerId);
        boolean suppressToolNotice = treeBasePos != null && pendingResourceBlock != null;
        if (suppressToolNotice) {
            toolHandler.prepareToolSilent(state, targetBlock);
        } else if (!toolHandler.prepareTool(state, targetBlock, player, gameTime)) {
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
            boolean mined = mineBlock(targetBlock);
            if (!mined) {
                clearTargetState();
                if (isTreeChopActive()) {
                    return Result.IN_PROGRESS;
                }
                return Result.NEED_CHEST;
            }
            if (treeBasePos != null && isTreeChopActive() && !forceTrunkChop && targetBlock.equals(treeBasePos)) {
                treeChopWaitPos = treeBasePos;
                if (treeChopLockedBase == null) {
                    treeChopLockedBase = treeBasePos;
                    captureTreeChopLogPositions(treeBasePos);
                }
                clearTargetState();
                return Result.IN_PROGRESS;
            }
            if (treeBasePos != null && (!isTreeChopActive() || forceTrunkChop)) {
                if (treeMode == CompanionTreeRequestMode.LOG_BLOCKS && logsCollected >= logsRequired) {
                    startStepCleanup(gameTime);
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
                    if (forceTrunkChop) {
                        collectTreeChopDrops(treeChopLockedBase);
                        treeChopLockedBase = null;
                        treeChopTrackedLogs.clear();
                        treeChopWaitPos = null;
                        forceTrunkChop = false;
                    }
                    if (treeMode == CompanionTreeRequestMode.TREE_COUNT) {
                        if (isTreeChopActive()) {
                            treesRemaining = Math.max(0, treesRemaining - 1);
                        } else {
                            treesRemaining = logsCollected >= logsRequired ? 0 : 1;
                        }
                        failedSearchAttempts = 0;
                        resetScanCache();
                    }
                    startStepCleanup(gameTime);
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
        if (forceTrunkChop && treeChopLockedBase != null) {
            TargetSelection lockedTrunk = resolveLockedTrunkTarget();
            if (lockedTrunk != null) {
                return lockedTrunk;
            }
        }
        if (isTreeChopInProgress()) {
            TargetSelection locked = resolveLockedTreeTarget();
            if (locked != null) {
                return locked;
            }
        }
        if (gameTime < nextScanTick && scanState == null && pathCheckState == null
                && nearScanState == null && nearPathCheckState == null) {
            return null;
        }
        if (searchStartTick < 0L) {
            searchStartTick = gameTime;
            scanSawCandidate = false;
        }
        BlockPos origin = owner.blockPosition();
        TargetSelection nearSelection = stepNearScan(origin);
        if (nearSelection != null) {
            finishScan(nearSelection, gameTime, true);
            return nearSelection;
        }
        if (gameTime < nextScanTick && cachedTarget != null) {
            BlockState cached = owner.level().getBlockState(cachedTarget);
            if (CompanionBlockRegistry.isLog(cached)) {
                BlockPos base = findTreeBase(cachedTarget);
                if (base != null && !isValidTreeBase(base)) {
                    resetScanCache();
                    return null;
                }
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
                    NEAR_SCAN_RADIUS, false);
        }
        nearScanState.step(this, MAX_SCAN_BLOCKS_PER_TICK);
        if (nearScanState.isFinished()) {
            if (nearPathCheckState == null && !nearScanState.getCandidates().isEmpty()) {
                nearPathCheckState = new PathCheckState(new ArrayList<>(nearScanState.getCandidates()));
            }
            if (nearPathCheckState == null) {
                nearScanState = null;
            }
        }
        return null;
    }

    private TargetSelection resolveTreeSelection(BlockPos pos, BlockState state) {
        if (CompanionBlockRegistry.isLog(state)) {
            BlockPos base = findTreeBase(pos);
            if (base != null) {
                if (!isValidTreeBase(base)) {
                    return null;
                }
                BlockPos sight = isTreeChopActive() ? base : pos;
                return new TargetSelection(base, base, sight);
            }
            return null;
        }
        if (CompanionBlockRegistry.isLeaves(state)) {
            BlockPos logPos = resolveLogFromLeaves(pos);
            if (logPos != null) {
                BlockPos base = findTreeBase(logPos);
                if (base != null) {
                    if (!isValidTreeBase(base)) {
                        return null;
                    }
                    BlockPos sight = isTreeChopActive() ? base : pos;
                    return new TargetSelection(base, base, sight);
                }
                return null;
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
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= LOG_FROM_LEAVES_MAX_DEPTH; dy++) {
            int y = leafPos.getY() - dy;
            for (int dx = -LOG_FROM_LEAVES_RADIUS; dx <= LOG_FROM_LEAVES_RADIUS; dx++) {
                for (int dz = -LOG_FROM_LEAVES_RADIUS; dz <= LOG_FROM_LEAVES_RADIUS; dz++) {
                    pos.set(leafPos.getX() + dx, y, leafPos.getZ() + dz);
                    if (!CompanionBlockRegistry.isLog(owner.level().getBlockState(pos))) {
                        continue;
                    }
                    BlockPos candidate = pos.immutable();
                    if (isPlacedStepPos(candidate)) {
                        continue;
                    }
                    double score = leafPos.distSqr(candidate);
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        return best;
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
        if (!isTreeChopActive()) {
            return findNextManualTreeLog(base, current);
        }
        return null;
    }

    private BlockPos findNextManualTreeLog(BlockPos base, BlockPos current) {
        if (base == null) {
            return null;
        }
        if (manualTreeLogs.isEmpty()) {
            captureManualTreeLogPositions(base);
        }
        if (manualTreeLogs.isEmpty()) {
            return null;
        }
        manualTreeLogs.removeIf(pos -> !CompanionBlockRegistry.isLog(owner.level().getBlockState(pos)));
        BlockPos reference = current != null ? current : owner.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos pos : manualTreeLogs) {
            if (current != null && pos.equals(current)) {
                continue;
            }
            if (pos.getY() < base.getY()) {
                continue;
            }
            double score = reference.distSqr(pos);
            if (score < bestScore) {
                bestScore = score;
                best = pos;
            }
        }
        return best != null ? best.immutable() : null;
    }

    private void captureManualTreeLogPositions(BlockPos base) {
        manualTreeLogs.clear();
        if (base == null) {
            return;
        }
        BlockPos seed = CompanionBlockRegistry.isLog(owner.level().getBlockState(base)) ? base : resolveTreeChopLogStart(base);
        if (seed == null) {
            return;
        }
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        int maxDistanceSqr = TREE_MAX_RADIUS * TREE_MAX_RADIUS;
        int minY = Math.max(owner.level().getMinBuildHeight(), base.getY() - 1);
        int maxY = Math.min(owner.level().getMaxBuildHeight() - 1,
                base.getY() + TREE_MAX_RADIUS * 2 + LOG_FROM_LEAVES_MAX_DEPTH);
        while (!queue.isEmpty() && visited.size() < TREE_MAX_BLOCKS) {
            BlockPos pos = queue.poll();
            if (!visited.add(pos)) {
                continue;
            }
            int dx = pos.getX() - base.getX();
            int dz = pos.getZ() - base.getZ();
            if (dx * dx + dz * dz > maxDistanceSqr || pos.getY() < minY || pos.getY() > maxY) {
                continue;
            }
            if (!CompanionBlockRegistry.isLog(owner.level().getBlockState(pos))) {
                continue;
            }
            manualTreeLogs.add(pos.immutable());
            for (int ox = -1; ox <= 1; ox++) {
                for (int oy = -1; oy <= 1; oy++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        if (ox == 0 && oy == 0 && oz == 0) {
                            continue;
                        }
                        BlockPos nextPos = pos.offset(ox, oy, oz);
                        if (!visited.contains(nextPos)) {
                            queue.add(nextPos);
                        }
                    }
                }
            }
        }
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
            int dxBase = pos.getX() - start.getX();
            int dzBase = pos.getZ() - start.getZ();
            if (dxBase * dxBase + dzBase * dzBase > maxDistanceSqr) {
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
                if (!isSolidBaseSupport(pos.below(), belowState)) {
                    continue;
                }
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

    private boolean isSolidBaseSupport(BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        return state.isFaceSturdy(owner.level(), pos, Direction.UP);
    }

    private boolean isValidTreeBase(BlockPos base) {
        if (base == null) {
            return false;
        }
        int trunkHeight = countTrunkHeight(base);
        if (trunkHeight < MIN_TRUNK_HEIGHT) {
            return false;
        }
        return hasTreeLeaves(base, trunkHeight);
    }

    private int countTrunkHeight(BlockPos base) {
        if (base == null) {
            return 0;
        }
        int maxY = Math.min(owner.level().getMaxBuildHeight() - 1,
                base.getY() + TREE_MAX_RADIUS * 2 + LOG_FROM_LEAVES_MAX_DEPTH);
        int height = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = base.getY(); y <= maxY; y++) {
            pos.set(base.getX(), y, base.getZ());
            if (!CompanionBlockRegistry.isLog(owner.level().getBlockState(pos))) {
                break;
            }
            height++;
        }
        return height;
    }

    private boolean hasTreeLeaves(BlockPos base, int trunkHeight) {
        if (base == null || trunkHeight <= 0) {
            return false;
        }
        int startY = Math.max(owner.level().getMinBuildHeight(), base.getY() + 1);
        int endY = Math.min(owner.level().getMaxBuildHeight() - 1,
                base.getY() + trunkHeight + LEAF_SEARCH_EXTRA_HEIGHT);
        int leafStartY = Math.max(startY, base.getY() + Math.max(1, trunkHeight - LEAF_SEARCH_EXTRA_HEIGHT));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -LEAF_SEARCH_RADIUS; dx <= LEAF_SEARCH_RADIUS; dx++) {
            for (int dz = -LEAF_SEARCH_RADIUS; dz <= LEAF_SEARCH_RADIUS; dz++) {
                for (int y = leafStartY; y <= endY; y++) {
                    pos.set(base.getX() + dx, y, base.getZ() + dz);
                    if (CompanionBlockRegistry.isLeaves(owner.level().getBlockState(pos))) {
                        return true;
                    }
                }
            }
        }
        return false;
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

    private boolean isTreeChopActive() {
        return CompanionConfig.isFullTreeChopEnabled() && ModList.get().isLoaded("treechop");
    }

    private boolean isTreeCountCompleted() {
        if (treeMode != CompanionTreeRequestMode.TREE_COUNT) {
            return false;
        }
        if (isTreeChopActive()) {
            return treesRemaining <= 0;
        }
        return treesRemaining <= 0 && logsCollected >= logsRequired;
    }

    private List<ItemStack> takeCollectedLogs(int maxLogs) {
        if (maxLogs <= 0) {
            return List.of();
        }
        int remaining = maxLogs;
        List<ItemStack> result = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : collectedDrops.entrySet()) {
            if (remaining <= 0) {
                break;
            }
            Item item = entry.getKey();
            int available = Math.max(0, entry.getValue());
            if (available <= 0 || !CompanionResourceType.LOG.matchesItem(new ItemStack(item))) {
                continue;
            }
            int toTake = Math.min(available, remaining);
            List<ItemStack> taken = inventory.takeMatching(stack -> stack.is(item), toTake);
            for (ItemStack stack : taken) {
                if (stack.isEmpty()) {
                    continue;
                }
                result.add(stack);
                remaining -= stack.getCount();
                if (remaining <= 0) {
                    break;
                }
            }
        }
        if (remaining > 0) {
            result.addAll(inventory.takeMatching(CompanionResourceType.LOG::matchesItem, remaining));
        }
        return result;
    }

    private void captureTreeChopBaseline() {
        treeChopBaseline.clear();
        for (ItemStack stack : inventory.getItems()) {
            if (stack.isEmpty()) {
                continue;
            }
            treeChopBaseline.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
    }

    private void captureTreeChopDrops() {
        if (treeChopBaseline.isEmpty()) {
            return;
        }
        Map<Item, Integer> currentCounts = new HashMap<>();
        for (ItemStack stack : inventory.getItems()) {
            if (stack.isEmpty()) {
                continue;
            }
            currentCounts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        collectedDrops.clear();
        for (Map.Entry<Item, Integer> entry : currentCounts.entrySet()) {
            int baseline = treeChopBaseline.getOrDefault(entry.getKey(), 0);
            int delta = entry.getValue() - baseline;
            if (delta > 0) {
                collectedDrops.put(entry.getKey(), delta);
            }
        }
    }

    private void captureTreeChopLogPositions(BlockPos base) {
        treeChopTrackedLogs.clear();
        BlockPos start = resolveTreeChopLogStart(base);
        if (start == null) {
            return;
        }
        int maxY = Math.min(owner.level().getMaxBuildHeight() - 1,
                start.getY() + TREE_MAX_RADIUS * 2 + LOG_FROM_LEAVES_MAX_DEPTH);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = start.getY(); y <= maxY && treeChopTrackedLogs.size() < TREE_MAX_BLOCKS; y++) {
            pos.set(start.getX(), y, start.getZ());
            if (!CompanionBlockRegistry.isLog(owner.level().getBlockState(pos))) {
                break;
            }
            treeChopTrackedLogs.add(pos.immutable());
        }
    }

    private BlockPos resolveTreeChopLogStart(BlockPos base) {
        if (base == null) {
            return null;
        }
        if (CompanionBlockRegistry.isLog(owner.level().getBlockState(base))) {
            return base;
        }
        int maxY = Math.min(owner.level().getMaxBuildHeight() - 1,
                base.getY() + TREE_MAX_RADIUS * 2 + LOG_FROM_LEAVES_MAX_DEPTH);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = base.getY() + 1; y <= maxY; y++) {
            pos.set(base.getX(), y, base.getZ());
            if (CompanionBlockRegistry.isLog(owner.level().getBlockState(pos))) {
                return pos.immutable();
            }
        }
        return null;
    }

    private boolean isTreeChopComplete(BlockPos base) {
        if (base == null) {
            return true;
        }
        if (treeChopTrackedLogs.isEmpty()) {
            captureTreeChopLogPositions(base);
        }
        if (!treeChopTrackedLogs.isEmpty()) {
            for (BlockPos pos : treeChopTrackedLogs) {
                if (CompanionBlockRegistry.isLog(owner.level().getBlockState(pos))) {
                    return false;
                }
            }
            return true;
        }
        return !hasNearbyTreeChopLogs(base);
    }

    private boolean hasNearbyTreeChopLogs(BlockPos base) {
        int minY = Math.max(owner.level().getMinBuildHeight(), base.getY());
        int maxY = Math.min(owner.level().getMaxBuildHeight() - 1,
                base.getY() + TREE_MAX_RADIUS * 2 + LOG_FROM_LEAVES_MAX_DEPTH);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            pos.set(base.getX(), y, base.getZ());
            if (CompanionBlockRegistry.isLog(owner.level().getBlockState(pos))) {
                return true;
            }
        }
        return false;
    }

    private TargetSelection resolveLockedTreeTarget() {
        BlockPos base = treeChopLockedBase;
        if (base == null) {
            return null;
        }
        if (!CompanionBlockRegistry.isLog(owner.level().getBlockState(base))) {
            if (treeChopWaitPos == null) {
                treeChopWaitPos = base;
            }
            return null;
        }
        TargetSelection candidate = new TargetSelection(base, base, base);
        TargetSelection resolved = resolveTreeObstruction(candidate);
        if (resolved == null) {
            return null;
        }
        if (resolved.pendingResource == null || isTreeObstacle(resolved)) {
            return resolved;
        }
        return resolved;
    }

    private TargetSelection resolveLockedTrunkTarget() {
        BlockPos base = treeChopLockedBase;
        if (base == null) {
            return null;
        }
        BlockPos logPos = resolveTreeChopLogStart(base);
        if (logPos == null) {
            treeChopWaitPos = null;
            treeChopLockedBase = null;
            treeChopTrackedLogs.clear();
            forceTrunkChop = false;
            return null;
        }
        treeBasePos = base;
        trunkLogPos = logPos;
        return resolveTrunkTarget(base, logPos);
    }

    private void collectTreeChopDrops(BlockPos base) {
        if (base == null || inventory.isFull()) {
            return;
        }
        int vertical = TREE_MAX_RADIUS * 2 + LOG_FROM_LEAVES_MAX_DEPTH;
        AABB range = new AABB(base).inflate(TREE_MAX_RADIUS, vertical, TREE_MAX_RADIUS);
        List<ItemEntity> drops = owner.level().getEntitiesOfClass(ItemEntity.class, range);
        for (ItemEntity itemEntity : drops) {
            if (!itemEntity.isAlive()) {
                continue;
            }
            ItemStack stack = itemEntity.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            int before = stack.getCount();
            inventory.add(stack);
            int picked = before - stack.getCount();
            if (picked <= 0) {
                continue;
            }
            if (stack.isEmpty()) {
                itemEntity.discard();
            } else {
                itemEntity.setItem(stack);
            }
            if (inventory.isFull()) {
                break;
            }
        }
    }

    private int countLogsInInventory() {
        int total = 0;
        for (ItemStack stack : inventory.getItems()) {
            if (stack.isEmpty()) {
                continue;
            }
            if (CompanionResourceType.LOG.matchesItem(stack)) {
                total += stack.getCount();
            }
        }
        return total;
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
        if (isTreeChopActive()) {
            return breakBlockWithPlayer(serverLevel, pos);
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
        CompanionToolWear.applyToolWear(owner, tool, InteractionHand.MAIN_HAND);
        return true;
    }

    private boolean breakBlockWithPlayer(ServerLevel serverLevel, BlockPos pos) {
        FakePlayer fakePlayer = FakePlayerFactory.get(serverLevel, MINING_PROFILE);
        fakePlayer.setPos(owner.getX(), owner.getY(), owner.getZ());
        fakePlayer.setPose(owner.getPose());
        fakePlayer.setOnGround(owner.onGround());
        fakePlayer.setXRot(owner.getXRot());
        fakePlayer.setYRot(owner.getYRot());
        fakePlayer.setGameMode(GameType.SURVIVAL);
        fakePlayer.getInventory().selected = 0;
        fakePlayer.setItemSlot(EquipmentSlot.MAINHAND, owner.getMainHandItem().copy());
        fakePlayer.setItemSlot(EquipmentSlot.OFFHAND, owner.getOffhandItem().copy());
        fakePlayer.setItemSlot(EquipmentSlot.HEAD, owner.getItemBySlot(EquipmentSlot.HEAD).copy());
        fakePlayer.setItemSlot(EquipmentSlot.CHEST, owner.getItemBySlot(EquipmentSlot.CHEST).copy());
        fakePlayer.setItemSlot(EquipmentSlot.LEGS, owner.getItemBySlot(EquipmentSlot.LEGS).copy());
        fakePlayer.setItemSlot(EquipmentSlot.FEET, owner.getItemBySlot(EquipmentSlot.FEET).copy());
        fakePlayer.removeAllEffects();
        for (MobEffectInstance effect : owner.getActiveEffects()) {
            fakePlayer.addEffect(new MobEffectInstance(effect));
        }
        boolean broken = fakePlayer.gameMode.destroyBlock(pos);
        if (broken) {
            CompanionToolWear.applyToolWear(owner, owner.getMainHandItem(), InteractionHand.MAIN_HAND);
        }
        return broken;
    }

    private static List<ItemStack> filterLeafDrops(List<ItemStack> drops) {
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
        if (!(owner.level() instanceof ServerLevel serverLevel)) {
            return 0.0F;
        }
        FakePlayer fakePlayer = FakePlayerFactory.get(serverLevel, MINING_PROFILE);
        fakePlayer.setPos(owner.getX(), owner.getY(), owner.getZ());
        fakePlayer.setPose(owner.getPose());
        fakePlayer.setOnGround(owner.onGround());
        fakePlayer.setXRot(owner.getXRot());
        fakePlayer.setYRot(owner.getYRot());
        fakePlayer.getInventory().selected = 0;
        fakePlayer.setItemSlot(EquipmentSlot.MAINHAND, owner.getMainHandItem().copy());
        fakePlayer.setItemSlot(EquipmentSlot.OFFHAND, owner.getOffhandItem().copy());
        fakePlayer.setItemSlot(EquipmentSlot.HEAD, owner.getItemBySlot(EquipmentSlot.HEAD).copy());
        fakePlayer.setItemSlot(EquipmentSlot.CHEST, owner.getItemBySlot(EquipmentSlot.CHEST).copy());
        fakePlayer.setItemSlot(EquipmentSlot.LEGS, owner.getItemBySlot(EquipmentSlot.LEGS).copy());
        fakePlayer.setItemSlot(EquipmentSlot.FEET, owner.getItemBySlot(EquipmentSlot.FEET).copy());
        fakePlayer.removeAllEffects();
        for (MobEffectInstance effect : owner.getActiveEffects()) {
            fakePlayer.addEffect(new MobEffectInstance(effect));
        }
        fakePlayer.updateFluidHeightAndDoFluidPushing(fluidState -> true);
        return state.getDestroyProgress(fakePlayer, serverLevel, pos);
    }

    private double speedModifierFor(double desiredSpeed) {
        double base = owner.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (base <= 0.0D) {
            return 0.0D;
        }
        return desiredSpeed / base;
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
        if (isTreeChopActive() && treeChopLockedBase == null && treeBasePos != null) {
            treeChopLockedBase = treeBasePos;
            captureTreeChopLogPositions(treeBasePos);
        }
        if (treeBasePos != null && (previousBase == null || !treeBasePos.equals(previousBase))) {
            trunkLogPos = treeBasePos;
            if (!isTreeChopActive()) {
                captureManualTreeLogPositions(treeBasePos);
            }
        } else if (treeBasePos == null) {
            trunkLogPos = null;
            manualTreeLogs.clear();
        }
    }

    private void clearTargetState() {
        targetBlock = null;
        pendingResourceBlock = null;
        pendingSightPos = null;
        treeBasePos = null;
        trunkLogPos = null;
        manualTreeLogs.clear();
        resetMiningProgress();
        resetMoveTracking();
    }

    private void resetRequestState() {
        clearTargetState();
        treeMode = CompanionTreeRequestMode.NONE;
        requestAmount = 0;
        treesRemaining = 0;
        logsRequired = 0;
        logsCollected = 0;
        logsBaseline = 0;
        requestPlayerId = null;
        treeChopWaitPos = null;
        treeChopLockedBase = null;
        treeChopTrackedLogs.clear();
        forceTrunkChop = false;
        failedSearchAttempts = 0;
        collectedDrops.clear();
        treeChopBaseline.clear();
        resetScanCache();
        resetStuckTracking();
        clearPlacedStepBlock();
    }

    private void resetScanCache() {
        nextScanTick = -1L;
        cachedTarget = null;
        lastScanTick = -1L;
        lastScanFound = true;
        scanSawCandidate = false;
        lastScanHadCandidates = false;
        searchStartTick = -1L;
        scanState = null;
        pathCheckState = null;
        nearScanState = null;
        nearPathCheckState = null;
    }

    private void resetStuckTracking() {
        lastProgressTick = -1L;
        lastRestartTick = -1L;
        stuckRetryCount = 0;
        lastProgressHash = 0;
        lastProgressMining = 0.0F;
        lastProgressPos = null;
        resetMoveTracking();
    }

    private void finishScan(TargetSelection selection, long gameTime, boolean found) {
        lastScanTick = gameTime;
        lastScanFound = found;
        lastScanHadCandidates = scanSawCandidate || found;
        nextScanTick = gameTime + RESOURCE_SCAN_COOLDOWN_TICKS;
        cachedTarget = found && selection != null ? selection.treeBase : null;
        scanSawCandidate = false;
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
            return true;
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
        return isBreakable(state, selection.target);
    }

    private boolean shouldPlaceStepBlock() {
        if ((isTreeChopActive() && !forceTrunkChop) || treeBasePos == null) {
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
        BlockPos foot = owner.blockPosition();
        int dx = foot.getX() - desired.getX();
        int dz = foot.getZ() - desired.getZ();
        if (dx * dx + dz * dz > 2) {
            return false;
        }
        return desired.getY() > foot.getY() + 1;
    }

    private boolean tryPlaceStepBlock(long gameTime) {
        if (!(owner.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (nextStepPlaceTick >= 0L && gameTime < nextStepPlaceTick) {
            return false;
        }
        BlockPos foot = owner.blockPosition();
        BlockPos desired = pendingResourceBlock != null ? pendingResourceBlock : targetBlock;
        if (desired != null && desired.getY() <= foot.getY() + 1) {
            return false;
        }
        boolean placed = tryPlaceStepBlockAt(serverLevel, foot, true);
        if (placed) {
            nextStepPlaceTick = gameTime + STEP_PLACE_INTERVAL_TICKS;
        }
        return placed;
    }

    private boolean tryPlaceStepBlockAt(ServerLevel serverLevel, BlockPos placePos, boolean liftOwner) {
        if (placePos == null) {
            return false;
        }
        BlockState placeState = selectStepBlockState(placePos);
        if (placeState == null) {
            return false;
        }
        if (!prepareStepPlacement(serverLevel, placePos, placeState)) {
            return false;
        }
        if (!serverLevel.setBlock(placePos, placeState, 3)) {
            return false;
        }
        if (!consumeStepBlock(placeState.getBlock())) {
            serverLevel.setBlock(placePos, Blocks.AIR.defaultBlockState(), 3);
            return false;
        }
        markPlacedStepBlock(placePos, placeState);
        if (liftOwner && placePos.equals(owner.blockPosition())
                && canStandAt(placePos.above()) && canStandAt(placePos.above(2))) {
            owner.setPos(owner.getX(), owner.getY() + 1.0D, owner.getZ());
        }
        return true;
    }

    private boolean prepareStepPlacement(ServerLevel serverLevel, BlockPos placePos, BlockState placeState) {
        if (!clearStepPlacementPos(serverLevel, placePos)) {
            return false;
        }
        if (!clearStepStandSpace(serverLevel, placePos.above())) {
            return false;
        }
        if (!clearStepStandSpace(serverLevel, placePos.above(2))) {
            return false;
        }
        return canPlaceStepAt(placePos, placeState);
    }

    private boolean clearStepPlacementPos(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = owner.level().getBlockState(pos);
        if (state.canBeReplaced()) {
            return true;
        }
        return breakStepObstacle(serverLevel, pos, state) && owner.level().getBlockState(pos).canBeReplaced();
    }

    private boolean clearStepStandSpace(ServerLevel serverLevel, BlockPos pos) {
        if (canStandAt(pos)) {
            return true;
        }
        BlockState state = owner.level().getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        return breakStepObstacle(serverLevel, pos, state) && canStandAt(pos);
    }

    private boolean breakStepObstacle(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (!isBreakable(state, pos)) {
            return false;
        }
        if (CompanionBlockRegistry.isLog(state)) {
            return false;
        }
        return serverLevel.destroyBlock(pos, true, owner);
    }

    private BlockPos findSideStepPlacePos(BlockPos desired, BlockPos foot) {
        if (desired == null || foot == null) {
            return null;
        }
        if (desired.getX() == foot.getX() && desired.getZ() == foot.getZ()) {
            return null;
        }
        int startY = Math.min(desired.getY() - 1, foot.getY());
        int minY = owner.level().getMinBuildHeight();
        if (treeBasePos != null) {
            minY = Math.max(minY, treeBasePos.getY() - 1);
        }
        if (startY < minY) {
            return null;
        }
        BlockPos.MutableBlockPos candidate = new BlockPos.MutableBlockPos(desired.getX(), startY, desired.getZ());
        for (int y = startY; y >= minY; y--) {
            candidate.setY(y);
            if (isPotentialSideStepPlacement(candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private boolean isPotentialSideStepPlacement(BlockPos pos) {
        BlockState current = owner.level().getBlockState(pos);
        if (!current.canBeReplaced()) {
            return false;
        }
        BlockPos below = pos.below();
        if (!isSolidBaseSupport(below, owner.level().getBlockState(below))) {
            return false;
        }
        return canStandAt(pos.above()) && canStandAt(pos.above(2));
    }

    private BlockState selectStepBlockState(BlockPos placePos) {
        BlockState fallbackLog = null;
        for (ItemStack stack : inventory.getItems()) {
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            BlockState state = blockItem.getBlock().defaultBlockState();
            if (!isUsableStepBlock(state, placePos)) {
                continue;
            }
            boolean isLog = CompanionBlockRegistry.isLog(state);
            boolean isLeaves = CompanionBlockRegistry.isLeaves(state);
            if (!isLog && !isLeaves) {
                return state;
            }
            if (fallbackLog == null && isLog) {
                fallbackLog = state;
            }
        }
        return fallbackLog;
    }

    private boolean isUsableStepBlock(BlockState state, BlockPos pos) {
        if (state == null || state.isAir() || state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER)) {
            return false;
        }
        if (state.is(BlockTags.SAPLINGS)) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (pos != null) {
            return state.canOcclude()
                    && state.isFaceSturdy(owner.level(), pos, Direction.UP)
                    && state.isCollisionShapeFullBlock(owner.level(), pos);
        }
        return state.canOcclude();
    }

    private void markPlacedStepBlock(BlockPos pos, BlockState state) {
        placedSteps.addLast(new PlacedStep(pos, state.getBlock(), CompanionBlockRegistry.isLog(state)));
    }

    private void clearPlacedStepBlock() {
        placedSteps.clear();
        stepCleanupActive = false;
        nextStepCleanupTick = -1L;
        nextStepPlaceTick = -1L;
        nextStepTrimTick = -1L;
    }

    private void startStepCleanup(long gameTime) {
        if (placedSteps.isEmpty()) {
            return;
        }
        stepCleanupActive = true;
        if (nextStepCleanupTick < 0L) {
            nextStepCleanupTick = gameTime;
        }
    }

    private boolean tickStepCleanup(long gameTime) {
        if (!stepCleanupActive) {
            return false;
        }
        if (placedSteps.isEmpty()) {
            stepCleanupActive = false;
            nextStepCleanupTick = -1L;
            return false;
        }
        if (gameTime < nextStepCleanupTick) {
            return true;
        }
        if (!(owner.level() instanceof ServerLevel serverLevel)) {
            clearPlacedStepBlock();
            return false;
        }
        PlacedStep step = placedSteps.peekLast();
        if (step == null) {
            clearPlacedStepBlock();
            return false;
        }
        if (!tryBreakPlacedStep(serverLevel, step)) {
            clearPlacedStepBlock();
            return false;
        }
        placedSteps.removeLast();
        nextStepCleanupTick = gameTime + STEP_CLEANUP_INTERVAL_TICKS;
        if (placedSteps.isEmpty()) {
            stepCleanupActive = false;
            nextStepCleanupTick = -1L;
        }
        return stepCleanupActive;
    }

    private boolean tryTrimUnusedStep(long gameTime) {
        if (stepCleanupActive || placedSteps.isEmpty() || !owner.onGround()) {
            return false;
        }
        if (nextStepTrimTick >= 0L && gameTime < nextStepTrimTick) {
            return false;
        }
        if (!(owner.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        BlockPos foot = owner.blockPosition();
        BlockPos support = foot.below();
        PlacedStep candidate = null;
        for (PlacedStep step : placedSteps) {
            if (step == null) {
                continue;
            }
            if (step.pos.equals(support) || step.pos.equals(foot)) {
                continue;
            }
            candidate = step;
            break;
        }
        if (candidate == null) {
            return false;
        }
        if (!tryBreakPlacedStep(serverLevel, candidate)) {
            nextStepTrimTick = gameTime + STEP_TRIM_INTERVAL_TICKS;
            return false;
        }
        placedSteps.remove(candidate);
        nextStepTrimTick = gameTime + STEP_TRIM_INTERVAL_TICKS;
        return true;
    }

    private boolean tryBreakPlacedStep(ServerLevel serverLevel, PlacedStep step) {
        BlockState state = owner.level().getBlockState(step.pos);
        if (state.isAir()) {
            return true;
        }
        if (step.block != null && state.getBlock() != step.block) {
            return true;
        }
        ItemStack tool = owner.getMainHandItem();
        List<ItemStack> drops = Block.getDrops(state, serverLevel, step.pos,
                owner.level().getBlockEntity(step.pos), owner, tool);
        if (drops.isEmpty()) {
            Item item = state.getBlock().asItem();
            if (item != Items.AIR) {
                drops = List.of(new ItemStack(item));
            }
        }
        if (!inventory.canStoreAll(drops)) {
            return false;
        }
        owner.level().destroyBlock(step.pos, false);
        inventory.addAll(drops);
        if (step.isLog) {
            recordDrops(drops);
        }
        CompanionToolWear.applyToolWear(owner, tool, InteractionHand.MAIN_HAND);
        return true;
    }

    private boolean isPlacedStepPos(BlockPos pos) {
        if (pos == null || placedSteps.isEmpty()) {
            return false;
        }
        for (PlacedStep step : placedSteps) {
            if (step != null && step.pos.equals(pos)) {
                return true;
            }
        }
        return false;
    }

    private static final class PlacedStep {
        private final BlockPos pos;
        private final Block block;
        private final boolean isLog;

        private PlacedStep(BlockPos pos, Block block, boolean isLog) {
            this.pos = pos;
            this.block = block;
            this.isLog = isLog;
        }
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
        if (!isUsableStepBlock(placeState, pos)) {
            return false;
        }
        return canStandAt(pos.above()) && canStandAt(pos.above(2));
    }

    private BlockPos resolveMiningMoveTarget() {
        BlockPos desired = pendingResourceBlock != null ? pendingResourceBlock : targetBlock;
        if (desired == null) {
            return null;
        }
        if (!CompanionBlockRegistry.isLog(owner.level().getBlockState(desired))) {
            return targetBlock;
        }
        BlockPos foot = owner.blockPosition();
        if (foot.getX() == desired.getX() && foot.getZ() == desired.getZ()) {
            return desired;
        }
        BlockPos standPos = findStandPosUnderColumn(desired);
        return standPos != null ? standPos : desired;
    }

    private BlockPos findStandPosUnderColumn(BlockPos desired) {
        if (desired == null) {
            return null;
        }
        int maxY = Math.min(desired.getY(), owner.level().getMaxBuildHeight() - 2);
        int minY = owner.level().getMinBuildHeight() + 1;
        if (treeBasePos != null) {
            minY = Math.max(minY, treeBasePos.getY() - 1);
        }
        BlockPos.MutableBlockPos standPos = new BlockPos.MutableBlockPos(desired.getX(), minY, desired.getZ());
        for (int y = minY; y <= maxY; y++) {
            standPos.setY(y);
            if (!canStandAt(standPos) || !canStandAt(standPos.above())) {
                continue;
            }
            BlockPos below = standPos.below();
            if (isSolidBaseSupport(below, owner.level().getBlockState(below))) {
                return standPos.immutable();
            }
        }
        return null;
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
                    controller.noteScanCandidate();
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
