package ru.nekostul.aicompanion.entity.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
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
import java.util.UUID;

import ru.nekostul.aicompanion.entity.CompanionEntity;
import ru.nekostul.aicompanion.entity.inventory.CompanionEquipment;
import ru.nekostul.aicompanion.entity.inventory.CompanionInventory;
import ru.nekostul.aicompanion.entity.resource.CompanionBlockRegistry;
import ru.nekostul.aicompanion.entity.resource.CompanionResourceRequest;
import ru.nekostul.aicompanion.entity.resource.CompanionResourceType;
import ru.nekostul.aicompanion.entity.tool.CompanionOreToolGate;
import ru.nekostul.aicompanion.entity.tool.CompanionToolHandler;

public final class CompanionGatheringController {
    public enum Result {
        IDLE,
        IN_PROGRESS,
        DONE,
        NEED_CHEST,
        NOT_FOUND,
        TOOL_REQUIRED
    }

    private enum ScanPhase {
        VISIBLE,
        OCCLUDED,
        STONE_DIG
    }

    private static final int CHUNK_RADIUS = 6;
    private static final int RESOURCE_SCAN_RADIUS = CHUNK_RADIUS * 16;
    private static final int RESOURCE_SCAN_COOLDOWN_TICKS = 80;
    private static final float RESOURCE_FOV_DOT = -1.0F;
    private static final float VISIBLE_FOV_DOT = RESOURCE_FOV_DOT;
    private static final int MAX_SCAN_BLOCKS_PER_TICK = 256;
    private static final int MAX_STONE_SCAN_COLUMNS_PER_TICK = 64;
    private static final int LOCAL_RADIUS = 3;
    private static final int LOCAL_HEIGHT = 4;
    private static final int LOCAL_LOG_HEIGHT = 8;
    private static final int LOG_FROM_LEAVES_RADIUS = 2;
    private static final int LOG_FROM_LEAVES_MAX_DEPTH = 6;
    private static final int STONE_DIG_MAX_DEPTH = 8;

    private final CompanionEntity owner;
    private final CompanionInventory inventory;
    private final CompanionEquipment equipment;
    private final CompanionToolHandler toolHandler;
    private final CompanionMiningAnimator miningAnimator;
    private final CompanionMiningReach miningReach;
    private final CompanionOreToolGate oreToolGate;

    private CompanionResourceType activeType;
    private int activeAmount;
    private UUID requestPlayerId;
    private BlockPos targetBlock;
    private BlockPos pendingResourceBlock;
    private BlockPos pendingSightPos;
    private BlockPos digStonePos;
    private long nextScanTick = -1L;
    private BlockPos cachedTarget;
    private long lastScanTick = -1L;
    private boolean lastScanFound = true;
    private BlockPos miningProgressPos;
    private float miningProgress;
    private int miningProgressStage = -1;
    private BlockPos resourceAnchor;
    private BlockPos lastMinedBlock;
    private ScanPhase scanPhase;
    private ScanState scanState;
    private StoneDigScanState stoneDigScanState;

    public CompanionGatheringController(CompanionEntity owner,
                               CompanionInventory inventory,
                               CompanionEquipment equipment,
                               CompanionToolHandler toolHandler) {
        this.owner = owner;
        this.inventory = inventory;
        this.equipment = equipment;
        this.toolHandler = toolHandler;
        this.miningAnimator = new CompanionMiningAnimator(owner);
        this.miningReach = new CompanionMiningReach(owner);
        this.oreToolGate = new CompanionOreToolGate(owner, inventory);
    }

    public Result tick(CompanionResourceRequest request, long gameTime) {
        if (request == null) {
            resetRequestState();
            return Result.IDLE;
        }
        if (activeType != request.getResourceType() || activeAmount != request.getAmount()) {
            activeType = request.getResourceType();
            activeAmount = request.getAmount();
            requestPlayerId = request.getPlayerId();
            resetRequestState();
        }
        if (inventory.isFull()) {
            clearTargetState();
            return Result.NEED_CHEST;
        }
        if (inventory.countMatching(activeType::matchesItem) >= activeAmount) {
            resetRequestState();
            return Result.DONE;
        }
        Player requestPlayer = owner.getPlayerById(requestPlayerId);
        if (!toolHandler.ensurePickaxeForRequest(activeType, requestPlayer, gameTime)) {
            clearTargetState();
            return Result.IN_PROGRESS;
        }
        if (oreToolGate.isRequestBlocked(activeType, requestPlayer)) {
            resetRequestState();
            return Result.TOOL_REQUIRED;
        }
        if (targetBlock == null || !isTargetValid()) {
            TargetSelection selection = findTarget(activeType, gameTime);
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
        if (oreToolGate.isBlockBlocked(state)) {
            clearTargetState();
            return Result.IN_PROGRESS;
        }
        Player player = owner.getPlayerById(requestPlayerId);
        if (!toolHandler.ensurePickaxeForBlock(state, targetBlock, player, gameTime)) {
            resetMiningProgress();
            return Result.IN_PROGRESS;
        }
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
            lastMinedBlock = targetBlock;
            if (diggingForStone) {
                if (targetBlock.equals(digStonePos)) {
                    digStonePos = null;
                    clearTargetState();
                    return Result.IN_PROGRESS;
                }
                BlockPos next = targetBlock.below();
                if (next.getY() < digStonePos.getY()) {
                    digStonePos = null;
                    clearTargetState();
                    return Result.IN_PROGRESS;
                }
                targetBlock = next;
                return Result.IN_PROGRESS;
            }
            if (advancePendingTarget()) {
                return Result.IN_PROGRESS;
            }
            clearTargetState();
        }
        return Result.IN_PROGRESS;
    }

    private TargetSelection findTarget(CompanionResourceType type, long gameTime) {
        if (resourceAnchor != null) {
            if (owner.blockPosition().distSqr(resourceAnchor) <= (double) (RESOURCE_SCAN_RADIUS * RESOURCE_SCAN_RADIUS)) {
                TargetSelection local = findLocalTarget(type);
                if (local != null) {
                    return local;
                }
            }
            resourceAnchor = null;
            lastMinedBlock = null;
        }
        if (gameTime < nextScanTick && cachedTarget != null && isCachedTargetValid(type, cachedTarget)) {
            return new TargetSelection(cachedTarget, cachedTarget, cachedTarget, null);
        }
        if (gameTime < nextScanTick && scanPhase == null) {
            return null;
        }
        BlockPos origin = owner.blockPosition();
        if (!isScanCompatible(type, origin)) {
            startVisibleScan(type, origin);
        }
        if (scanPhase == ScanPhase.STONE_DIG) {
            TargetSelection selection = stoneDigScanState != null
                    ? stoneDigScanState.step(this, MAX_STONE_SCAN_COLUMNS_PER_TICK)
                    : null;
            if (selection != null) {
                finishScan(selection, gameTime, true);
                return selection;
            }
            if (stoneDigScanState != null && !stoneDigScanState.isFinished()) {
                return null;
            }
            finishScan(null, gameTime, false);
            return null;
        }
        TargetSelection selection = scanState != null
                ? scanState.step(this, MAX_SCAN_BLOCKS_PER_TICK)
                : null;
        if (selection != null) {
            finishScan(selection, gameTime, true);
            return selection;
        }
        if (scanState != null && !scanState.isFinished()) {
            return null;
        }
        if (scanPhase == ScanPhase.VISIBLE) {
            startOccludedScan(type, origin);
            return null;
        }
        if (scanPhase == ScanPhase.OCCLUDED && type == CompanionResourceType.STONE) {
            startStoneDigScan(origin);
            return null;
        }
        finishScan(null, gameTime, false);
        return null;
    }

    private TargetSelection findLocalTarget(CompanionResourceType type) {
        BlockPos anchor = lastMinedBlock != null ? lastMinedBlock : resourceAnchor;
        if (anchor == null) {
            return null;
        }
        int radius = LOCAL_RADIUS;
        int height = LOCAL_HEIGHT;
        if (type == CompanionResourceType.LOG) {
            height = LOCAL_LOG_HEIGHT;
        }
        if (type == CompanionResourceType.STONE) {
            return scanAreaVisible(type, anchor, radius, height, anchor, Math.max(radius, height) + 1, true);
        }
        if (isOreType(type)) {
            TargetSelection visible = scanAreaVisible(type, anchor, radius, height, anchor, Math.max(radius, height) + 1, true);
            if (visible != null) {
                return visible;
            }
            return scanArea(type, anchor, radius, height, anchor, Math.max(radius, height) + 1, true);
        }
        return scanArea(type, anchor, radius, height, anchor, Math.max(radius, height) + 1, true);
    }

    private boolean isOreType(CompanionResourceType type) {
        return switch (type) {
            case ORE,
                 COAL_ORE,
                 IRON_ORE,
                 COPPER_ORE,
                 GOLD_ORE,
                 REDSTONE_ORE,
                 LAPIS_ORE,
                 DIAMOND_ORE,
                 EMERALD_ORE -> true;
            default -> false;
        };
    }

    private TargetSelection scanArea(CompanionResourceType type,
                                     BlockPos origin,
                                     int radius,
                                     int height,
                                     BlockPos distanceOrigin,
                                     double maxDistance,
                                     boolean scoreByTarget) {
        Vec3 eye = owner.getEyePosition();
        Vec3 look = owner.getLookAngle().normalize();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        TargetSelection best = null;
        double bestDistance = Double.MAX_VALUE;
        double maxDistanceSqr = maxDistance * maxDistance;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -height; dy <= height; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState state = owner.level().getBlockState(pos);
                    TargetSelection selection = resolveTargetSelection(type, pos.immutable(), state);
                    if (selection == null) {
                        continue;
                    }
                    Vec3 sightCenter = Vec3.atCenterOf(selection.sightPos);
                    Vec3 toTarget = sightCenter.subtract(eye);
                    double distanceSqr = toTarget.lengthSqr();
                    if (distanceSqr > maxDistanceSqr) {
                        continue;
                    }
                    if (look.dot(toTarget.normalize()) < RESOURCE_FOV_DOT) {
                        continue;
                    }
                    TargetSelection raySelection = resolveObstruction(selection.resourcePos, selection.sightPos);
                    if (raySelection == null) {
                        continue;
                    }
                    BlockPos scorePos = scoreByTarget ? raySelection.target : raySelection.resourcePos;
                    double score = distanceOrigin.distSqr(scorePos);
                    if (score < bestDistance) {
                        bestDistance = score;
                        best = raySelection;
                    }
                }
            }
        }
        return best;
    }

    private TargetSelection scanAreaVisible(CompanionResourceType type,
                                            BlockPos origin,
                                            int radius,
                                            int height,
                                            BlockPos distanceOrigin,
                                            double maxDistance,
                                            boolean scoreByTarget) {
        Vec3 eye = owner.getEyePosition();
        Vec3 look = owner.getLookAngle().normalize();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        TargetSelection best = null;
        double bestDistance = Double.MAX_VALUE;
        double maxDistanceSqr = maxDistance * maxDistance;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -height; dy <= height; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState state = owner.level().getBlockState(pos);
                    TargetSelection selection = resolveTargetSelection(type, pos.immutable(), state);
                    if (selection == null) {
                        continue;
                    }
                    Vec3 sightCenter = Vec3.atCenterOf(selection.sightPos);
                    Vec3 toTarget = sightCenter.subtract(eye);
                    double distanceSqr = toTarget.lengthSqr();
                    if (distanceSqr > maxDistanceSqr) {
                        continue;
                    }
                    if (look.dot(toTarget.normalize()) < VISIBLE_FOV_DOT) {
                        continue;
                    }
                    TargetSelection raySelection = resolveObstruction(selection.resourcePos, selection.sightPos);
                    if (raySelection == null || raySelection.pendingResource != null) {
                        continue;
                    }
                    BlockPos scorePos = scoreByTarget ? raySelection.target : raySelection.resourcePos;
                    double score = distanceOrigin.distSqr(scorePos);
                    if (score < bestDistance) {
                        bestDistance = score;
                        best = raySelection;
                    }
                }
            }
        }
        return best;
    }

    private TargetSelection resolveTargetSelection(CompanionResourceType type, BlockPos pos, BlockState state) {
        if (type == CompanionResourceType.LOG) {
            if (CompanionBlockRegistry.isLog(state)) {
                return new TargetSelection(pos, pos, pos, null);
            }
            if (CompanionBlockRegistry.isLeaves(state)) {
                BlockPos logPos = resolveLogFromLeaves(pos);
                if (logPos != null) {
                    return new TargetSelection(logPos, logPos, pos, null);
                }
            }
            return null;
        }
        if (type.matchesBlock(state)) {
            if (oreToolGate.isBlockBlocked(state)) {
                return null;
            }
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

    private void startVisibleScan(CompanionResourceType type, BlockPos origin) {
        scanPhase = ScanPhase.VISIBLE;
        scanState = new ScanState(type, origin, RESOURCE_SCAN_RADIUS, RESOURCE_SCAN_RADIUS, origin,
                RESOURCE_SCAN_RADIUS, false, true, VISIBLE_FOV_DOT, true);
        stoneDigScanState = null;
    }

    private void startOccludedScan(CompanionResourceType type, BlockPos origin) {
        scanPhase = ScanPhase.OCCLUDED;
        scanState = new ScanState(type, origin, RESOURCE_SCAN_RADIUS, RESOURCE_SCAN_RADIUS, origin,
                RESOURCE_SCAN_RADIUS, false, false, RESOURCE_FOV_DOT, true);
        stoneDigScanState = null;
    }

    private void startStoneDigScan(BlockPos origin) {
        scanPhase = ScanPhase.STONE_DIG;
        scanState = null;
        stoneDigScanState = new StoneDigScanState(origin, RESOURCE_SCAN_RADIUS);
    }

    private boolean isScanCompatible(CompanionResourceType type, BlockPos origin) {
        if (scanPhase == null) {
            return false;
        }
        if (scanPhase == ScanPhase.STONE_DIG) {
            return type == CompanionResourceType.STONE
                    && stoneDigScanState != null
                    && stoneDigScanState.matches(origin);
        }
        return scanState != null && scanState.matches(type, origin);
    }

    private void finishScan(TargetSelection selection, long gameTime, boolean found) {
        lastScanTick = gameTime;
        lastScanFound = found;
        nextScanTick = gameTime + RESOURCE_SCAN_COOLDOWN_TICKS;
        cachedTarget = found && selection != null ? selection.target : null;
        resetScanState();
    }

    private BlockPos findStoneBelow(BlockPos start, BlockPos.MutableBlockPos pos) {
        int minY = Math.max(owner.level().getMinBuildHeight(), start.getY() - STONE_DIG_MAX_DEPTH);
        for (int y = start.getY() - 1; y >= minY; y--) {
            pos.set(start.getX(), y, start.getZ());
            BlockState state = owner.level().getBlockState(pos);
            if (CompanionBlockRegistry.isStoneBlock(state)) {
                return pos.immutable();
            }
            if (!CompanionBlockRegistry.isShovelMineable(state)
                    && !CompanionBlockRegistry.isPickaxeMineable(state)) {
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
                    if (CompanionBlockRegistry.isLog(owner.level().getBlockState(pos))) {
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
        while (CompanionBlockRegistry.isLog(owner.level().getBlockState(below))) {
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
            return CompanionBlockRegistry.isLog(state);
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
        if (!tool.isEmpty() && tool.isDamageableItem()) {
            tool.hurtAndBreak(1, owner, entity -> entity.broadcastBreakEvent(InteractionHand.MAIN_HAND));
        }
        return true;
    }

    private List<ItemStack> filterLeafDrops(List<ItemStack> drops) {
        if (drops.isEmpty()) {
            return drops;
        }
        List<ItemStack> filtered = new java.util.ArrayList<>();
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
        if (activeType == CompanionResourceType.LOG) {
            return CompanionBlockRegistry.isLog(state);
        }
        if (activeType == CompanionResourceType.STONE && digStonePos != null) {
            return CompanionBlockRegistry.isShovelMineable(state) || CompanionBlockRegistry.isPickaxeMineable(state);
        }
        if (oreToolGate.isBlockBlocked(state)) {
            return false;
        }
        return activeType.matchesBlock(state);
    }

    private void applySelection(TargetSelection selection) {
        targetBlock = selection.target;
        pendingResourceBlock = selection.pendingResource;
        pendingSightPos = selection.pendingResource != null ? selection.sightPos : null;
        digStonePos = selection.digStonePos;
        BlockPos anchorCandidate = selection.pendingResource != null ? selection.target : selection.resourcePos;
        if (resourceAnchor == null
                || owner.blockPosition().distSqr(resourceAnchor)
                > (double) (RESOURCE_SCAN_RADIUS * RESOURCE_SCAN_RADIUS)) {
            resourceAnchor = anchorCandidate;
        }
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
        pendingSightPos = selection.pendingResource != null ? selection.sightPos : null;
        resetMiningProgress();
        return true;
    }

    private void clearTargetState() {
        targetBlock = null;
        pendingResourceBlock = null;
        pendingSightPos = null;
        digStonePos = null;
        resetMiningProgress();
        resetScanState();
    }

    private void resetRequestState() {
        clearTargetState();
        resourceAnchor = null;
        lastMinedBlock = null;
        requestPlayerId = null;
    }

    private void resetScanState() {
        scanPhase = null;
        scanState = null;
        stoneDigScanState = null;
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
        private final CompanionResourceType type;
        private final BlockPos origin;
        private final BlockPos distanceOrigin;
        private final double maxDistanceSqr;
        private final boolean scoreByTarget;
        private final boolean requireLineOfSight;
        private final float minDot;
        private final boolean returnOnFirstMatch;
        private final int[] xOffsets;
        private final int[] yOffsets;
        private final int[] zOffsets;
        private int ix;
        private int iy;
        private int iz;
        private double bestScore = Double.MAX_VALUE;
        private TargetSelection best;
        private boolean finished;

        private ScanState(CompanionResourceType type,
                          BlockPos origin,
                          int radius,
                          int height,
                          BlockPos distanceOrigin,
                          double maxDistance,
                          boolean scoreByTarget,
                          boolean requireLineOfSight,
                          float minDot,
                          boolean returnOnFirstMatch) {
            this.type = type;
            this.origin = origin;
            this.distanceOrigin = distanceOrigin;
            this.maxDistanceSqr = maxDistance * maxDistance;
            this.scoreByTarget = scoreByTarget;
            this.requireLineOfSight = requireLineOfSight;
            this.minDot = minDot;
            this.returnOnFirstMatch = returnOnFirstMatch;
            this.xOffsets = buildOffsets(radius);
            this.yOffsets = buildOffsets(height);
            this.zOffsets = buildOffsets(radius);
        }

        private boolean matches(CompanionResourceType type, BlockPos origin) {
            return this.type == type && this.origin.equals(origin);
        }

        private boolean isFinished() {
            return finished;
        }

        private TargetSelection step(CompanionGatheringController controller, int budget) {
            if (finished) {
                return best;
            }
            Vec3 eye = controller.owner.getEyePosition();
            Vec3 look = controller.owner.getLookAngle().normalize();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int processed = 0;
            while (processed < budget && !finished) {
                int dx = xOffsets[ix];
                int dy = yOffsets[iy];
                int dz = zOffsets[iz];
                pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                processed++;
                BlockState state = controller.owner.level().getBlockState(pos);
                TargetSelection selection = controller.resolveTargetSelection(type, pos.immutable(), state);
                if (selection != null) {
                    Vec3 sightCenter = Vec3.atCenterOf(selection.sightPos);
                    Vec3 toTarget = sightCenter.subtract(eye);
                    double distanceSqr = toTarget.lengthSqr();
                    if (distanceSqr <= maxDistanceSqr) {
                        double dot = look.dot(toTarget.normalize());
                        if (dot >= minDot) {
                            TargetSelection raySelection = controller.resolveObstruction(selection.resourcePos, selection.sightPos);
                            if (raySelection != null && (!requireLineOfSight || raySelection.pendingResource == null)) {
                                if (returnOnFirstMatch) {
                                    best = raySelection;
                                    finished = true;
                                    return best;
                                }
                                BlockPos scorePos = scoreByTarget ? raySelection.target : raySelection.resourcePos;
                                double score = distanceOrigin.distSqr(scorePos);
                                if (score < bestScore) {
                                    bestScore = score;
                                    best = raySelection;
                                }
                            }
                        }
                    }
                }
                advanceIndices();
            }
            if (finished) {
                return best;
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
    }

    private static final class StoneDigScanState {
        private final BlockPos origin;
        private final int[] xOffsets;
        private final int[] zOffsets;
        private int ix;
        private int iz;
        private boolean finished;

        private StoneDigScanState(BlockPos origin, int radius) {
            this.origin = origin;
            this.xOffsets = buildOffsets(radius);
            this.zOffsets = buildOffsets(radius);
        }

        private boolean matches(BlockPos origin) {
            return this.origin.equals(origin);
        }

        private boolean isFinished() {
            return finished;
        }

        private TargetSelection step(CompanionGatheringController controller, int budget) {
            if (finished) {
                return null;
            }
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int processed = 0;
            while (processed < budget && !finished) {
                int dx = xOffsets[ix];
                int dz = zOffsets[iz];
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                BlockPos surface = controller.owner.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        new BlockPos(x, 0, z));
                if (controller.isPassable(surface.above())) {
                    BlockState surfaceState = controller.owner.level().getBlockState(surface);
                    if (CompanionBlockRegistry.isShovelMineable(surfaceState)) {
                        BlockPos stonePos = controller.findStoneBelow(surface, pos);
                        if (stonePos != null) {
                            finished = true;
                            return new TargetSelection(surface.immutable(), surface.immutable(), surface.immutable(), stonePos);
                        }
                    }
                }
                processed++;
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
            ix++;
            if (ix >= xOffsets.length) {
                finished = true;
            }
        }
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

