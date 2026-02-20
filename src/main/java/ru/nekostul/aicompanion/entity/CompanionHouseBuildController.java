package ru.nekostul.aicompanion.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import ru.nekostul.aicompanion.CompanionConfig;
import ru.nekostul.aicompanion.aiproviders.yandexgpt.YandexGptClient;
import ru.nekostul.aicompanion.entity.inventory.CompanionInventory;
import ru.nekostul.aicompanion.entity.movement.CompanionMovementSpeed;
import ru.nekostul.aicompanion.entity.resource.CompanionResourceType;
import ru.nekostul.aicompanion.entity.tree.CompanionTreeRequestMode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class CompanionHouseBuildController {
    static final class GatherTask {
        final UUID playerId;
        final CompanionResourceType type;
        final int amount;
        final CompanionTreeRequestMode treeMode;
        final Item sourceItem;

        GatherTask(UUID playerId, CompanionResourceType type, int amount, CompanionTreeRequestMode treeMode, Item sourceItem) {
            this.playerId = playerId;
            this.type = type;
            this.amount = amount;
            this.treeMode = treeMode;
            this.sourceItem = sourceItem;
        }
    }

    private record Placement(BlockPos rel, Block block, Item item) {
    }

    private record BuildBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
    }

    private enum State {
        IDLE,
        WAITING_LOCATION,
        WAITING_PLAN,
        WAITING_RESOURCE_DECISION,
        WAITING_PLAYER_RESOURCES,
        WAITING_GATHER_START,
        GATHERING_RESOURCES,
        BUILDING
    }

    private static final int MAX_BLOCKS = 1500;
    private static final int MAX_REL = 64;
    private static final int BLOCKS_PER_TICK = 1;
    private static final int PLACE_INTERVAL_TICKS = 4;
    private static final int MAX_PLACEMENT_RETRIES = 16;
    private static final double BUILD_SPEED = 0.35D;
    private static final double PLACE_RANGE_SQR = 20.25D;
    private static final double BUILD_START_ANCHOR_REACH_SQR = 2.25D;
    private static final int REPATH_TICKS = 3;
    private static final int BUILD_OUTSIDE_MARGIN = 1;
    private static final int STALL_SKIP_TICKS = 30;
    private static final int OUTSIDE_ESCAPE_MAX_TICKS = 60;
    private static final int MAX_TEMP_SUPPORT_BLOCKS = 16;
    private static final int TEMP_SUPPORT_STALL_TICKS = 1;
    private static final int TEMP_SUPPORT_COOLDOWN_TICKS = 1;
    private static final Block TEMP_SUPPORT_BLOCK = Blocks.COBBLESTONE;
    private static final String MATERIAL_TOKEN_SPLIT_REGEX = "[^\\p{L}0-9:_]+";

    private static final String TOKEN_RES_PLAYER = "__BUILD_RES_PLAYER__";
    private static final String TOKEN_RES_GATHER = "__BUILD_RES_GATHER__";

    private static final String K_BUILD_IN_PROGRESS = "entity.aicompanion.companion.build.in_progress";
    private static final String K_POINT_ASK = "entity.aicompanion.companion.build.point.ask";
    private static final String K_POINT_REMOVE = "entity.aicompanion.companion.build.point.remove";
    private static final String K_POINT_INVALID = "entity.aicompanion.companion.build.point.invalid";
    private static final String K_PLAN_WAIT = "entity.aicompanion.companion.build.plan.wait";
    private static final String K_PLAN_FAILED = "entity.aicompanion.companion.build.plan.failed";
    private static final String K_PLAN_READY = "entity.aicompanion.companion.build.plan.ready";
    private static final String K_BUILD_START = "entity.aicompanion.companion.build.start";
    private static final String K_BUILD_DONE = "entity.aicompanion.companion.build.done";
    private static final String K_BUILD_DONE_BLOCKED = "entity.aicompanion.companion.build.done.blocked";
    private static final String K_RES_DETAILS = "entity.aicompanion.companion.build.resources.details";
    private static final String K_RES_REMOVE = "entity.aicompanion.companion.build.resources.remove";
    private static final String K_RES_WAIT_PLAYER = "entity.aicompanion.companion.build.resources.wait_player";
    private static final String K_RES_UNAVAILABLE = "entity.aicompanion.companion.build.resources.unavailable";
    private static final String K_GATHER_START = "entity.aicompanion.companion.build.gather.start";
    private static final String K_GATHER_FAILED = "entity.aicompanion.companion.build.gather.failed";

    private static final String K_AI_DISABLED = "entity.aicompanion.companion.ai.disabled";
    private static final String K_AI_NOT_CONFIGURED = "entity.aicompanion.companion.ai.not_configured";
    private static final String K_AI_DAILY_LIMIT = "entity.aicompanion.companion.ai.daily_limit";

    private static final CompanionResourceType[] RESOURCE_PRIORITY = {
            CompanionResourceType.LOG,
            CompanionResourceType.DIRT,
            CompanionResourceType.STONE,
            CompanionResourceType.SAND,
            CompanionResourceType.GRAVEL,
            CompanionResourceType.CLAY,
            CompanionResourceType.ANDESITE,
            CompanionResourceType.DIORITE,
            CompanionResourceType.GRANITE,
            CompanionResourceType.BASALT,
            CompanionResourceType.COAL_ORE,
            CompanionResourceType.IRON_ORE,
            CompanionResourceType.COPPER_ORE,
            CompanionResourceType.GOLD_ORE,
            CompanionResourceType.REDSTONE_ORE,
            CompanionResourceType.LAPIS_ORE,
            CompanionResourceType.DIAMOND_ORE,
            CompanionResourceType.EMERALD_ORE,
            CompanionResourceType.ORE
    };

    private final CompanionEntity owner;
    private final CompanionInventory inventory;

    private State state = State.IDLE;
    private UUID activePlayerId;
    private String buildRequestText = "";
    private BlockPos buildOrigin;
    private BlockPos buildStartAnchor;
    private final Deque<Placement> placementQueue = new ArrayDeque<>();
    private final Map<Item, Integer> remainingRequired = new LinkedHashMap<>();
    private final Deque<GatherTask> gatherQueue = new ArrayDeque<>();
    private GatherTask pendingGatherTask;
    private int blockedPlacements;
    private BlockPos lastMoveTarget;
    private long lastMoveTick = -1L;
    private long nextPlaceTick = -1L;
    private BuildBounds buildBounds;
    private BlockPos stalledTarget;
    private int stalledTargetTicks;
    private BlockPos outsideEscapeTarget;
    private int outsideEscapeTicks;
    private final Deque<BlockPos> tempSupportBlocks = new ArrayDeque<>();
    private long nextTempSupportTick = -1L;
    private final Set<BlockPos> plannedWorldBlocks = new HashSet<>();
    private final Map<BlockPos, Integer> placementRetryCounts = new HashMap<>();
    private int planVariantSeed;

    CompanionHouseBuildController(CompanionEntity owner, CompanionInventory inventory) {
        this.owner = owner;
        this.inventory = inventory;
    }

    boolean isSessionActive() {
        return state != State.IDLE;
    }

    boolean isBuildingActive() {
        return state == State.BUILDING;
    }

    boolean handleMessage(ServerPlayer player, String message, long gameTime) {
        if (player == null || message == null) {
            return false;
        }
        String normalized = normalize(message);
        if (normalized.isEmpty()) {
            return false;
        }
        if (state == State.IDLE) {
            if (!isBuildCommand(normalized)) {
                return false;
            }
            startSession(player, message);
            return true;
        }
        if (!player.getUUID().equals(activePlayerId)) {
            return false;
        }
        if (isBuildCommand(normalized)) {
            owner.sendReply(player, Component.translatable(K_BUILD_IN_PROGRESS));
            return true;
        }
        String raw = message.trim();
        if (state == State.WAITING_LOCATION) {
            if (isHereAnswer(normalized)) {
                setBuildPoint(player, gameTime);
            }
            return true;
        }
        if (state == State.WAITING_RESOURCE_DECISION) {
            if (TOKEN_RES_PLAYER.equalsIgnoreCase(raw) || isPlayerGiveAnswer(normalized)) {
                owner.sendReply(player, Component.translatable(K_RES_REMOVE));
                owner.sendReply(player, Component.translatable(K_RES_WAIT_PLAYER));
                state = State.WAITING_PLAYER_RESOURCES;
                checkPlayerResources(player);
                return true;
            }
            if (TOKEN_RES_GATHER.equalsIgnoreCase(raw) || isGatherAnswer(normalized)) {
                owner.sendReply(player, Component.translatable(K_RES_REMOVE));
                tryNpcGather(player, true);
                return true;
            }
            return true;
        }
        if (state == State.WAITING_PLAYER_RESOURCES) {
            if (TOKEN_RES_GATHER.equalsIgnoreCase(raw) || isGatherAnswer(normalized)) {
                tryNpcGather(player, true);
                return true;
            }
            checkPlayerResources(player);
            return true;
        }
        return true;
    }

    void tick(long gameTime) {
        if (state == State.IDLE) {
            return;
        }
        ServerPlayer player = getActivePlayer();
        if (player == null) {
            cancel();
            return;
        }
        if (state == State.WAITING_PLAYER_RESOURCES) {
            ensureNearBuildStartAnchor(gameTime);
            checkPlayerResources(player);
            return;
        }
        if (state == State.WAITING_GATHER_START) {
            ensureNearBuildStartAnchor(gameTime);
        }
        if (state == State.BUILDING) {
            tickBuilding(player, gameTime);
        }
    }

    GatherTask takePendingGatherTask() {
        if (state != State.WAITING_GATHER_START || pendingGatherTask == null) {
            return null;
        }
        GatherTask task = pendingGatherTask;
        pendingGatherTask = null;
        state = State.GATHERING_RESOURCES;
        return task;
    }

    void onGatherFinished(boolean success) {
        if (state != State.GATHERING_RESOURCES) {
            return;
        }
        ServerPlayer player = getActivePlayer();
        if (player == null) {
            cancel();
            return;
        }
        if (!success) {
            owner.sendReply(player, Component.translatable(K_GATHER_FAILED));
            owner.sendReply(player, Component.translatable(K_RES_WAIT_PLAYER));
            state = State.WAITING_PLAYER_RESOURCES;
            return;
        }
        Map<Item, Integer> missing = computeMissing();
        if (missing.isEmpty()) {
            startBuilding(player);
            return;
        }
        if (!gatherQueue.isEmpty()) {
            scheduleNextGather(player);
            return;
        }
        if (!scheduleGatherFromMissing(player, missing, false)) {
            owner.sendReply(player, Component.translatable(K_RES_WAIT_PLAYER));
            state = State.WAITING_PLAYER_RESOURCES;
        }
    }

    void cancel() {
        cleanupTempSupports();
        state = State.IDLE;
        activePlayerId = null;
        buildRequestText = "";
        buildOrigin = null;
        buildStartAnchor = null;
        placementQueue.clear();
        remainingRequired.clear();
        plannedWorldBlocks.clear();
        gatherQueue.clear();
        pendingGatherTask = null;
        blockedPlacements = 0;
        placementRetryCounts.clear();
        lastMoveTarget = null;
        lastMoveTick = -1L;
        nextPlaceTick = -1L;
        nextTempSupportTick = -1L;
        buildBounds = null;
        planVariantSeed = 0;
        resetStallState();
        resetOutsideEscapeState();
    }

    private void startSession(ServerPlayer player, String request) {
        cancel();
        activePlayerId = player.getUUID();
        buildRequestText = request == null ? "" : request.trim();
        state = State.WAITING_LOCATION;
        owner.sendReply(player, Component.translatable(K_POINT_ASK));
    }

    boolean handleBuildPointClick(ServerPlayer player, BlockPos clickedPos, long gameTime) {
        if (player == null || clickedPos == null || state != State.WAITING_LOCATION || activePlayerId == null) {
            return false;
        }
        if (!activePlayerId.equals(player.getUUID())) {
            return false;
        }
        setBuildPoint(player, clickedPos, gameTime);
        return true;
    }

    private void setBuildPoint(ServerPlayer player, long gameTime) {
        BlockHitResult hit = findLookedBlock(player, 8.0D);
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            owner.sendReply(player, Component.translatable(K_POINT_INVALID));
            return;
        }
        setBuildPoint(player, hit.getBlockPos(), gameTime);
    }

    private void setBuildPoint(ServerPlayer player, BlockPos point, long gameTime) {
        owner.sendReply(player, Component.translatable(K_POINT_REMOVE));
        buildOrigin = point.immutable();
        buildStartAnchor = null;
        int requestHash = buildRequestText == null ? 0 : buildRequestText.hashCode();
        planVariantSeed = (int) (gameTime ^ buildOrigin.asLong() ^ player.getUUID().hashCode() ^ requestHash);
        state = State.WAITING_PLAN;
        owner.sendReply(player, Component.translatable(K_PLAN_WAIT));

        UUID playerId = player.getUUID();
        String request = buildRequestText;
        String pointContext = "origin=" + buildOrigin.getX() + "," + buildOrigin.getY() + "," + buildOrigin.getZ()
                + ";game_time=" + gameTime
                + ";biome=" + resolveBuildBiomeContext();

        CompletableFuture
                .supplyAsync(() -> YandexGptClient.generateHomeBuildPlan(player, request, pointContext))
                .thenAccept(result -> onPlanReady(playerId, result))
                .exceptionally(error -> {
                    onPlanReady(playerId, null);
                    return null;
                });
    }

    private void onPlanReady(UUID playerId, YandexGptClient.Result result) {
        if (owner.getServer() == null) {
            return;
        }
        owner.getServer().execute(() -> {
            if (state != State.WAITING_PLAN || activePlayerId == null || !activePlayerId.equals(playerId)) {
                return;
            }
            ServerPlayer player = getActivePlayer();
            if (player == null) {
                cancel();
                return;
            }
            if (result == null) {
                owner.sendReply(player, Component.translatable(K_PLAN_FAILED));
                cancel();
                return;
            }
            if (result.status() != YandexGptClient.Status.SUCCESS) {
                sendAiStatus(player, result);
                cancel();
                return;
            }

            List<Placement> placements = parsePlacements(result.text());
            if (placements.isEmpty()) {
                placements = fallbackPlacements(buildRequestText);
            }
            placements = normalizeToGroundLevel(placements);
            placements = applyRequestedMaterialPreference(placements, buildRequestText);
            placements = ensureHabitableInterior(placements);
            placements = ensureFloorAndCeiling(placements, buildRequestText);
            placements = ensureWallShell(placements, buildRequestText);
            placements = replaceGlassPanesWithBlocks(placements);
            placements = ensureLightSources(placements, buildRequestText);
            if (placements.isEmpty()) {
                owner.sendReply(player, Component.translatable(K_PLAN_FAILED));
                cancel();
                return;
            }

            placementQueue.clear();
            remainingRequired.clear();
            gatherQueue.clear();
            pendingGatherTask = null;
            blockedPlacements = 0;
            placementRetryCounts.clear();
            plannedWorldBlocks.clear();
            buildBounds = computeBounds(placements);
            buildStartAnchor = null;
            nextPlaceTick = -1L;
            int toBuildCount = 0;
            for (Placement placement : placements) {
                if (placement == null || placement.rel() == null || placement.block() == null || placement.item() == null) {
                    continue;
                }
                BlockPos target = buildOrigin == null ? null : buildOrigin.offset(placement.rel());
                if (target != null) {
                    BlockState existing = owner.level().getBlockState(target);
                    if (existing.is(placement.block()) || isFoundationSatisfied(placement, existing)) {
                        continue;
                    }
                    plannedWorldBlocks.add(target.immutable());
                }
                placementQueue.addLast(placement);
                remainingRequired.merge(placement.item(), 1, Integer::sum);
                toBuildCount++;
            }
            // Recalculate anchor after planned targets are known to avoid standing on future placements.
            buildStartAnchor = null;
            resolveBuildStartAnchor();

            owner.sendReply(player, Component.translatable(K_PLAN_READY, toBuildCount));
            Map<Item, Integer> missing = computeMissing();
            if (missing.isEmpty()) {
                startBuilding(player);
            } else {
                promptResources(player, missing);
            }
        });
    }

    private void sendAiStatus(ServerPlayer player, YandexGptClient.Result result) {
        if (result == null || player == null) {
            owner.sendReply(player, Component.translatable(K_PLAN_FAILED));
            return;
        }
        switch (result.status()) {
            case DISABLED -> owner.sendReply(player, Component.translatable(K_AI_DISABLED));
            case NOT_CONFIGURED -> owner.sendReply(player, Component.translatable(K_AI_NOT_CONFIGURED));
            case DAILY_LIMIT -> owner.sendReply(player,
                    Component.translatable(K_AI_DAILY_LIMIT, result.remainingLimit()));
            case ERROR -> owner.sendReply(player, Component.translatable(K_PLAN_FAILED));
            case SUCCESS -> {
            }
        }
    }

    private void startBuilding(ServerPlayer player) {
        state = State.BUILDING;
        resolveBuildStartAnchor();
        owner.getNavigation().stop();
        owner.sendReply(player, Component.translatable(K_RES_REMOVE));
        owner.sendReply(player, Component.translatable(K_BUILD_START));
        lastMoveTarget = null;
        lastMoveTick = -1L;
        nextPlaceTick = -1L;
        nextTempSupportTick = -1L;
        resetStallState();
        resetOutsideEscapeState();
    }

    private void checkPlayerResources(ServerPlayer player) {
        if (state != State.WAITING_PLAYER_RESOURCES) {
            return;
        }
        if (computeMissing().isEmpty()) {
            startBuilding(player);
        }
    }

    private void tickBuilding(ServerPlayer player, long gameTime) {
        if (placementQueue.isEmpty()) {
            finishBuild(player);
            return;
        }
        if (buildOrigin == null) {
            cancel();
            return;
        }

        Map<Item, Integer> missing = computeMissing();
        if (!missing.isEmpty()) {
            promptResources(player, missing);
            return;
        }
        if (ensureNearBuildStartAnchor(gameTime)) {
            return;
        }
        if (nextPlaceTick >= 0L && gameTime < nextPlaceTick) {
            return;
        }
        if (ensureBuilderOutsideStructure(gameTime)) {
            return;
        }
        if (isInsideBuildFootprint(owner.blockPosition())) {
            return;
        }
        owner.getNavigation().stop();

        int actions = 0;
        while (actions < BLOCKS_PER_TICK && !placementQueue.isEmpty()) {
            prioritizeNearestPlacement(48);
            Placement placement = placementQueue.peekFirst();
            if (placement == null) {
                break;
            }

            BlockPos target = buildOrigin.offset(placement.rel());
            BlockState existing = owner.level().getBlockState(target);
            if (existing.is(placement.block()) || isFoundationSatisfied(placement, existing)) {
                clearPlacementRetry(target);
                consumePlannedBlock(placement);
                actions++;
                nextPlaceTick = gameTime + PLACE_INTERVAL_TICKS;
                continue;
            }

            if (!canPlaceAt(target, placement.block(), existing)) {
                if (handlePlacementFailure(target, placement, false)) {
                    actions++;
                    nextPlaceTick = gameTime + PLACE_INTERVAL_TICKS;
                    continue;
                }
                return;
            }

            if (!inventory.consumeItem(placement.item(), 1)) {
                promptResources(player, computeMissing());
                return;
            }

            if (!placePlannedBlock(target, placement.block())) {
                refundPlacementItem(placement);
                if (handlePlacementFailure(target, placement, true)) {
                    actions++;
                    nextPlaceTick = gameTime + PLACE_INTERVAL_TICKS;
                    continue;
                }
                return;
            }
            playPlacementSound(target, placement.block());
            owner.swing(InteractionHand.MAIN_HAND, true);
            clearPlacementRetry(target);
            consumePlannedBlock(placement);
            actions++;
            nextPlaceTick = gameTime + PLACE_INTERVAL_TICKS;
        }

        if (placementQueue.isEmpty()) {
            finishBuild(player);
        }
    }

    private void refundPlacementItem(Placement placement) {
        if (placement == null || placement.item() == null || placement.item() == Items.AIR) {
            return;
        }
        ItemStack refund = new ItemStack(placement.item(), 1);
        if (inventory.add(refund)) {
            return;
        }
        owner.spawnAtLocation(refund);
    }

    private boolean canPlaceAt(BlockPos target, Block block, BlockState existing) {
        if (target == null || block == null || existing == null) {
            return false;
        }
        if (existing.is(block)) {
            return true;
        }
        if (!existing.canBeReplaced() && !canOverwriteOccupiedBlock(target, existing)) {
            return false;
        }
        if (!(block instanceof DoorBlock)) {
            return true;
        }
        BlockPos upperPos = target.above();
        BlockState upper = owner.level().getBlockState(upperPos);
        return upper.is(block) || upper.canBeReplaced() || canOverwriteOccupiedBlock(upperPos, upper);
    }

    private boolean canOverwriteOccupiedBlock(BlockPos target, BlockState state) {
        if (target == null || state == null) {
            return false;
        }
        if (state.isAir() || state.canBeReplaced()) {
            return true;
        }
        if (state.hasBlockEntity()) {
            return false;
        }
        if (state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER) || state.is(Blocks.END_PORTAL_FRAME)) {
            return false;
        }
        return state.getDestroySpeed(owner.level(), target) >= 0.0F;
    }

    private boolean isFoundationSatisfied(Placement placement, BlockState existing) {
        if (placement == null || placement.block() == null || placement.rel() == null || existing == null) {
            return false;
        }
        if (placement.block() instanceof DoorBlock || buildBounds == null) {
            return false;
        }
        if (placement.rel().getY() != buildBounds.minY()) {
            return false;
        }
        if (existing.isAir() || existing.canBeReplaced()) {
            return false;
        }
        return existing.blocksMotion() && existing.getFluidState().isEmpty();
    }

    private boolean placePlannedBlock(BlockPos target, Block block) {
        if (target == null || block == null) {
            return false;
        }
        if (!(block instanceof DoorBlock doorBlock)) {
            return owner.level().setBlock(target, block.defaultBlockState(), 3);
        }
        BlockPos upperPos = target.above();
        BlockState upper = owner.level().getBlockState(upperPos);
        if (!(upper.is(block) || upper.canBeReplaced())) {
            return false;
        }
        BlockState lowerState = resolveDoorLowerStateForPlacement(doorBlock, target);
        BlockState upperState = lowerState.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        owner.level().setBlock(target, lowerState, 3);
        owner.level().setBlock(upperPos, upperState, 3);
        return true;
    }

    private boolean handlePlacementFailure(BlockPos target, Placement placement, boolean retryable) {
        if (placement == null) {
            return true;
        }
        if (target == null) {
            blockedPlacements++;
            consumePlannedBlock(placement);
            return true;
        }
        if (!retryable) {
            blockedPlacements++;
            consumePlannedBlock(placement);
            return true;
        }
        int retries = placementRetryCounts.merge(target.immutable(), 1, Integer::sum);
        if (retries >= MAX_PLACEMENT_RETRIES) {
            placementRetryCounts.remove(target);
            blockedPlacements++;
            consumePlannedBlock(placement);
            return true;
        }
        // Do not reposition while placing: keep NPC stationary and retry this block later.
        deferCurrentPlacement();
        return true;
    }

    private void clearPlacementRetry(BlockPos target) {
        if (target == null) {
            return;
        }
        placementRetryCounts.remove(target);
    }

    private BlockState resolveDoorLowerStateForPlacement(DoorBlock doorBlock, BlockPos target) {
        BlockState lower = doorBlock.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.OPEN, false)
                .setValue(DoorBlock.POWERED, false);
        Direction facing = lower.getValue(DoorBlock.FACING);
        DoorHingeSide hinge = lower.getValue(DoorBlock.HINGE);

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos adjacentPos = target.relative(direction);
            BlockState adjacent = owner.level().getBlockState(adjacentPos);
            if (!(adjacent.getBlock() instanceof DoorBlock) || !adjacent.is(doorBlock)) {
                continue;
            }
            if (!adjacent.hasProperty(DoorBlock.HALF)
                    || !adjacent.hasProperty(DoorBlock.FACING)
                    || !adjacent.hasProperty(DoorBlock.HINGE)) {
                continue;
            }
            if (adjacent.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                continue;
            }
            facing = adjacent.getValue(DoorBlock.FACING);
            hinge = adjacent.getValue(DoorBlock.HINGE) == DoorHingeSide.LEFT
                    ? DoorHingeSide.RIGHT
                    : DoorHingeSide.LEFT;
            break;
        }

        return lower
                .setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.HINGE, hinge);
    }

    private boolean tryPlaceTempSupportIfNeeded(BlockPos target, long gameTime) {
        if (target == null || buildOrigin == null) {
            return false;
        }
        if (nextTempSupportTick >= 0L && gameTime < nextTempSupportTick) {
            return false;
        }
        BlockPos ownerPos = owner.blockPosition();
        if (ownerPos == null) {
            return false;
        }
        if (stalledTarget == null || !stalledTarget.equals(target) || stalledTargetTicks < TEMP_SUPPORT_STALL_TICKS) {
            return false;
        }
        int verticalGap = target.getY() - ownerPos.getY();
        if (verticalGap <= 1) {
            return false;
        }
        int dx = Math.abs(target.getX() - ownerPos.getX());
        int dz = Math.abs(target.getZ() - ownerPos.getZ());
        if (dx > 3 || dz > 3) {
            return false;
        }
        trimTempSupportsIfNeeded();
        if (tempSupportBlocks.size() >= MAX_TEMP_SUPPORT_BLOCKS) {
            return false;
        }

        List<BlockPos> candidates = new ArrayList<>(8);
        int sx = Integer.compare(target.getX(), ownerPos.getX());
        int sz = Integer.compare(target.getZ(), ownerPos.getZ());
        if (sx == 0 && sz == 0) {
            Direction facing = owner.getDirection();
            if (facing == null || facing.getAxis().isVertical()) {
                facing = Direction.NORTH;
            }
            sx = facing.getStepX();
            sz = facing.getStepZ();
            if (sx == 0 && sz == 0) {
                sx = 1;
            }
        }
        // Prefer same-Y "step" supports first: they help climb target height without huge support spam.
        addTempSupportCandidate(candidates, ownerPos.offset(sx, 0, sz));
        addTempSupportCandidate(candidates, ownerPos.offset(sx, 0, 0));
        addTempSupportCandidate(candidates, ownerPos.offset(0, 0, sz));
        addTempSupportCandidate(candidates, ownerPos.offset(-sx, 0, 0));
        addTempSupportCandidate(candidates, ownerPos.offset(0, 0, -sz));
        // Lower supports are fallback when there is no stable step at current level.
        addTempSupportCandidate(candidates, ownerPos.offset(sx, -1, sz));
        addTempSupportCandidate(candidates, ownerPos.offset(sx, -1, 0));
        addTempSupportCandidate(candidates, ownerPos.offset(0, -1, sz));
        addTempSupportCandidate(candidates, ownerPos.below());
        for (BlockPos candidate : candidates) {
            if (!canUseTempSupportAt(candidate)) {
                continue;
            }
            owner.level().setBlock(candidate, TEMP_SUPPORT_BLOCK.defaultBlockState(), 3);
            if (!tempSupportBlocks.contains(candidate)) {
                tempSupportBlocks.addLast(candidate.immutable());
            }
            removePreviousTempSupport(candidate);
            playPlacementSound(candidate, TEMP_SUPPORT_BLOCK);
            owner.swing(InteractionHand.MAIN_HAND, true);
            nextTempSupportTick = gameTime + TEMP_SUPPORT_COOLDOWN_TICKS;
            BlockPos stepTarget = candidate.above();
            if (stepTarget.equals(ownerPos)) {
                stepTarget = computeOutsideApproachTarget(target);
            }
            moveTo(stepTarget, gameTime);
            return true;
        }
        return false;
    }

    private void addTempSupportCandidate(List<BlockPos> list, BlockPos candidate) {
        if (list == null || candidate == null) {
            return;
        }
        if (!list.contains(candidate)) {
            list.add(candidate);
        }
    }

    private boolean canUseTempSupportAt(BlockPos pos) {
        BlockPos ownerPos = owner.blockPosition();
        boolean underOwner = ownerPos != null && pos != null && pos.equals(ownerPos.below());
        if (pos == null) {
            return false;
        }
        if (!underOwner && (intersectsOwner(pos) || intersectsOwner(pos.above()))) {
            return false;
        }
        if (plannedWorldBlocks.contains(pos)) {
            return false;
        }
        BlockState existing = owner.level().getBlockState(pos);
        if (!(existing.canBeReplaced() || existing.isAir())) {
            return false;
        }
        BlockState head = owner.level().getBlockState(pos.above());
        if (!(head.canBeReplaced() || head.isAir())) {
            return false;
        }
        BlockState below = owner.level().getBlockState(pos.below());
        if (below.blocksMotion() || below.is(TEMP_SUPPORT_BLOCK)) {
            return true;
        }
        // Allow side-attached support blocks so NPC can continue climbing instead of spinning in place.
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockState side = owner.level().getBlockState(pos.relative(direction));
            if (side.blocksMotion() || side.is(TEMP_SUPPORT_BLOCK)) {
                return true;
            }
        }
        return false;
    }

    private void trimTempSupportsIfNeeded() {
        if (tempSupportBlocks.size() < MAX_TEMP_SUPPORT_BLOCKS || owner.level() == null) {
            return;
        }
        int attempts = tempSupportBlocks.size();
        while (tempSupportBlocks.size() >= MAX_TEMP_SUPPORT_BLOCKS && attempts-- > 0) {
            BlockPos oldest = tempSupportBlocks.pollFirst();
            if (oldest == null) {
                return;
            }
            if (plannedWorldBlocks.contains(oldest)
                    || intersectsOwner(oldest)
                    || intersectsOwner(oldest.above())) {
                tempSupportBlocks.addLast(oldest);
                continue;
            }
            BlockState state = owner.level().getBlockState(oldest);
            if (state.is(TEMP_SUPPORT_BLOCK)) {
                owner.level().setBlock(oldest, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private void removePreviousTempSupport(BlockPos newest) {
        if (newest == null || tempSupportBlocks.size() < 2 || owner.level() == null) {
            return;
        }
        int attempts = tempSupportBlocks.size();
        while (attempts-- > 0) {
            BlockPos oldest = tempSupportBlocks.peekFirst();
            if (oldest == null) {
                return;
            }
            if (oldest.equals(newest)) {
                tempSupportBlocks.pollFirst();
                tempSupportBlocks.addLast(oldest);
                continue;
            }
            if (plannedWorldBlocks.contains(oldest)
                    || intersectsOwner(oldest)
                    || intersectsOwner(oldest.above())) {
                tempSupportBlocks.pollFirst();
                tempSupportBlocks.addLast(oldest);
                continue;
            }
            tempSupportBlocks.pollFirst();
            BlockState state = owner.level().getBlockState(oldest);
            if (state.is(TEMP_SUPPORT_BLOCK)) {
                owner.level().setBlock(oldest, Blocks.AIR.defaultBlockState(), 3);
            }
            return;
        }
    }

    private void cleanupTempSupports() {
        if (tempSupportBlocks.isEmpty() || owner.level() == null) {
            tempSupportBlocks.clear();
            return;
        }
        while (!tempSupportBlocks.isEmpty()) {
            BlockPos pos = tempSupportBlocks.pollLast();
            if (pos == null || plannedWorldBlocks.contains(pos)) {
                continue;
            }
            BlockState state = owner.level().getBlockState(pos);
            if (state.is(TEMP_SUPPORT_BLOCK)) {
                owner.level().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private boolean wouldTrapBuilderAt(BlockPos target) {
        if (target == null) {
            return false;
        }
        BlockPos ownerPos = owner.blockPosition();
        if (ownerPos == null || !isInsideBuildFootprint(ownerPos)) {
            return false;
        }
        if (!isStandableSim(ownerPos, target)) {
            return true;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos next = ownerPos.relative(direction);
            if (isStandableSim(next, target)) {
                return false;
            }
        }
        return true;
    }

    private boolean isStandableSim(BlockPos pos, BlockPos placedTarget) {
        if (pos == null) {
            return false;
        }
        if (!isBodyFreeSim(pos, placedTarget)) {
            return false;
        }
        if (!isBodyFreeSim(pos.above(), placedTarget)) {
            return false;
        }
        return isSupportSolidSim(pos.below(), placedTarget);
    }

    private boolean isBodyFreeSim(BlockPos pos, BlockPos placedTarget) {
        if (pos == null) {
            return false;
        }
        if (pos.equals(placedTarget)) {
            return false;
        }
        BlockState state = owner.level().getBlockState(pos);
        if (state.canBeReplaced() || state.isAir()) {
            return true;
        }
        return !state.blocksMotion() && state.getFluidState().isEmpty();
    }

    private boolean isSupportSolidSim(BlockPos pos, BlockPos placedTarget) {
        if (pos == null) {
            return false;
        }
        if (pos.equals(placedTarget)) {
            return true;
        }
        return owner.level().getBlockState(pos).blocksMotion();
    }

    private void playPlacementSound(BlockPos target, Block block) {
        if (target == null || block == null || owner.level().isClientSide()) {
            return;
        }
        BlockState placedState = owner.level().getBlockState(target);
        if (!placedState.is(block)) {
            placedState = block.defaultBlockState();
        }
        SoundType soundType = placedState.getSoundType(owner.level(), target, owner);
        owner.level().playSound(
                null,
                target,
                soundType.getPlaceSound(),
                SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) * 0.5F,
                soundType.getPitch() * 0.8F
        );
    }

    private boolean isOnPlacementBlock(BlockPos target) {
        if (target == null) {
            return false;
        }
        return intersectsOwner(target) || intersectsOwner(target.above());
    }

    private boolean shouldDeferForEntityCollision(BlockPos target, Placement placement) {
        if (target == null || placement == null) {
            return false;
        }
        if (intersectsOwner(target)) {
            return true;
        }
        return placement.block() instanceof DoorBlock && intersectsOwner(target.above());
    }

    private boolean intersectsOwner(BlockPos target) {
        if (target == null) {
            return false;
        }
        return owner.getBoundingBox().inflate(0.05D).intersects(
                target.getX(),
                target.getY(),
                target.getZ(),
                target.getX() + 1.0D,
                target.getY() + 1.0D,
                target.getZ() + 1.0D
        );
    }

    private boolean shouldSkipStalledTarget(BlockPos target) {
        if (target == null) {
            return false;
        }
        if (stalledTarget == null || !stalledTarget.equals(target)) {
            stalledTarget = target.immutable();
            stalledTargetTicks = 1;
            return false;
        }
        stalledTargetTicks++;
        return stalledTargetTicks >= STALL_SKIP_TICKS;
    }

    private void resetStallState() {
        stalledTarget = null;
        stalledTargetTicks = 0;
    }

    private void resetOutsideEscapeState() {
        outsideEscapeTarget = null;
        outsideEscapeTicks = 0;
    }

    private BlockPos resolveBuildStartAnchor() {
        if (buildStartAnchor != null && isValidBuildStartAnchor(buildStartAnchor)) {
            return buildStartAnchor;
        }
        buildStartAnchor = null;
        if (buildOrigin == null) {
            return null;
        }
        BlockPos adjacent = resolveAdjacentBuildStartAnchor();
        if (adjacent != null) {
            buildStartAnchor = adjacent.immutable();
            return buildStartAnchor;
        }
        if (buildBounds != null) {
            BlockPos outside = computeOutsideAnchor(buildOrigin);
            if (outside != null) {
                buildStartAnchor = outside.immutable();
                return buildStartAnchor;
            }
        }
        buildStartAnchor = buildOrigin.immutable();
        return buildStartAnchor;
    }

    private BlockPos resolveAdjacentBuildStartAnchor() {
        if (buildOrigin == null) {
            return null;
        }
        BlockPos ownerPos = owner.blockPosition();
        List<BlockPos> candidates = new ArrayList<>(12);
        int[][] horizontal = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };
        int[] yOffsets = {0, 1, -1, 2, -2};
        for (int[] side : horizontal) {
            int dx = side[0];
            int dz = side[1];
            for (int yOffset : yOffsets) {
                BlockPos candidate = buildOrigin.offset(dx, yOffset, dz);
                if (!isWalkableStandPos(candidate)) {
                    continue;
                }
                if (intersectsPlannedBuildColumn(candidate)) {
                    continue;
                }
                if (buildBounds != null && isInsideBuildFootprint(candidate)) {
                    continue;
                }
                if (!candidates.contains(candidate)) {
                    candidates.add(candidate.immutable());
                }
            }
            int heightY = owner.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    buildOrigin.getX() + dx, buildOrigin.getZ() + dz);
            BlockPos byHeight = new BlockPos(buildOrigin.getX() + dx, heightY, buildOrigin.getZ() + dz);
            if (isWalkableStandPos(byHeight)
                    && !intersectsPlannedBuildColumn(byHeight)
                    && (buildBounds == null || !isInsideBuildFootprint(byHeight))
                    && !candidates.contains(byHeight)) {
                candidates.add(byHeight.immutable());
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (ownerPos != null) {
            candidates.sort(Comparator.comparingDouble(pos ->
                    owner.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D)));
        }
        return candidates.get(0);
    }

    private boolean ensureNearBuildStartAnchor(long gameTime) {
        BlockPos anchor = resolveBuildStartAnchor();
        if (anchor == null) {
            return false;
        }
        double distanceSqr = owner.distanceToSqr(anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D);
        if (distanceSqr <= BUILD_START_ANCHOR_REACH_SQR) {
            owner.getNavigation().stop();
            return false;
        }
        if (!canNavigateTo(anchor)) {
            // Fallback: do not freeze building when anchor path is temporarily unavailable.
            buildStartAnchor = null;
            BlockPos recalculated = resolveBuildStartAnchor();
            if (recalculated == null) {
                return false;
            }
            anchor = recalculated;
            distanceSqr = owner.distanceToSqr(anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D);
            if (distanceSqr <= BUILD_START_ANCHOR_REACH_SQR || !canNavigateTo(anchor)) {
                return false;
            }
        }
        moveTo(anchor, gameTime);
        return true;
    }

    private boolean canNavigateTo(BlockPos target) {
        if (target == null || owner.getNavigation() == null) {
            return false;
        }
        Path path = owner.getNavigation().createPath(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 0);
        return path != null;
    }

    private boolean isValidBuildStartAnchor(BlockPos anchor) {
        if (anchor == null || !isWalkableStandPos(anchor)) {
            return false;
        }
        if (buildBounds != null && isInsideBuildFootprint(anchor)) {
            return false;
        }
        return !intersectsPlannedBuildColumn(anchor);
    }

    private boolean intersectsPlannedBuildColumn(BlockPos pos) {
        if (pos == null || plannedWorldBlocks.isEmpty()) {
            return false;
        }
        if (plannedWorldBlocks.contains(pos) || plannedWorldBlocks.contains(pos.above())) {
            return true;
        }
        return false;
    }

    private void prioritizeNearestPlacement(int scanLimit) {
        if (placementQueue.isEmpty() || buildOrigin == null || scanLimit <= 1) {
            return;
        }
        int limit = Math.min(scanLimit, placementQueue.size());
        int bestIndex = -1;
        double bestScore = Double.MAX_VALUE;
        int index = 0;
        for (Placement placement : placementQueue) {
            if (placement == null) {
                break;
            }
            BlockPos target = buildOrigin.offset(placement.rel());
            double score = owner.distanceToSqr(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D);
            if (score < bestScore) {
                bestScore = score;
                bestIndex = index;
            }
            index++;
            if (index >= limit) {
                break;
            }
        }
        if (bestIndex <= 0) {
            return;
        }
        for (int i = 0; i < bestIndex; i++) {
            Placement first = placementQueue.pollFirst();
            if (first == null) {
                break;
            }
            placementQueue.addLast(first);
        }
    }

    private boolean ensureBuilderOutsideStructure(long gameTime) {
        if (buildOrigin == null || buildBounds == null) {
            return false;
        }
        BlockPos ownerPos = owner.blockPosition();
        if (!isInsideBuildFootprint(ownerPos)) {
            resetOutsideEscapeState();
            return false;
        }
        BlockPos outside = computeOutsideAnchor(ownerPos);
        if (outside == null) {
            resetOutsideEscapeState();
            return false;
        }
        if (outsideEscapeTarget == null || !outsideEscapeTarget.equals(outside)) {
            outsideEscapeTarget = outside.immutable();
            outsideEscapeTicks = 0;
        }
        outsideEscapeTicks++;
        moveTo(outside, gameTime);
        return true;
    }

    private boolean isInsideBuildFootprint(BlockPos pos) {
        if (pos == null || buildOrigin == null || buildBounds == null) {
            return false;
        }
        int relX = pos.getX() - buildOrigin.getX();
        int relY = pos.getY() - buildOrigin.getY();
        int relZ = pos.getZ() - buildOrigin.getZ();
        if (relX < buildBounds.minX() || relX > buildBounds.maxX()) {
            return false;
        }
        if (relZ < buildBounds.minZ() || relZ > buildBounds.maxZ()) {
            return false;
        }
        return relY >= buildBounds.minY() - 1 && relY <= buildBounds.maxY() + 2;
    }

    private BlockPos computeOutsideApproachTarget(BlockPos target) {
        if (target == null) {
            return target;
        }
        List<BlockPos> candidates = new ArrayList<>(48);
        int[][] horizontal = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };
        int[] yOffsets = {0, 1, -1, 2, -2, 3, -3};
        for (int yOffset : yOffsets) {
            for (int[] side : horizontal) {
                BlockPos candidate = target.offset(side[0], yOffset, side[1]);
                if (!candidates.contains(candidate)) {
                    candidates.add(candidate);
                }
            }
        }
        BlockPos ownerPos = owner.blockPosition();
        if (ownerPos != null) {
            candidates.sort(Comparator
                    .comparingInt((BlockPos pos) -> Math.abs(pos.getY() - ownerPos.getY()))
                    .thenComparingDouble(pos ->
                            owner.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D)));
        } else {
            candidates.sort(Comparator.comparingDouble(pos ->
                    owner.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D)));
        }
        for (BlockPos candidate : candidates) {
            if (candidate.equals(ownerPos)) {
                continue;
            }
            if (isWalkableStandPos(candidate)) {
                return candidate;
            }
        }
        for (BlockPos candidate : candidates) {
            if (!candidate.equals(ownerPos)) {
                return candidate;
            }
        }
        return target;
    }

    private boolean isWalkableStandPos(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        BlockState feet = owner.level().getBlockState(pos);
        BlockState head = owner.level().getBlockState(pos.above());
        BlockState below = owner.level().getBlockState(pos.below());
        if (!feet.canBeReplaced() && !feet.isAir()) {
            return false;
        }
        if (!head.canBeReplaced() && !head.isAir()) {
            return false;
        }
        return below.blocksMotion();
    }

    private BlockPos computeOutsideAnchor(BlockPos around) {
        if (buildOrigin == null || buildBounds == null) {
            return around;
        }
        int relX = around.getX() - buildOrigin.getX();
        int relZ = around.getZ() - buildOrigin.getZ();

        int distMinX = Math.abs(relX - buildBounds.minX());
        int distMaxX = Math.abs(buildBounds.maxX() - relX);
        int distMinZ = Math.abs(relZ - buildBounds.minZ());
        int distMaxZ = Math.abs(buildBounds.maxZ() - relZ);

        int min = Math.min(Math.min(distMinX, distMaxX), Math.min(distMinZ, distMaxZ));
        if (min == distMinX) {
            return toWalkableOutsidePos(
                    buildBounds.minX() - BUILD_OUTSIDE_MARGIN,
                    clamp(relZ, buildBounds.minZ(), buildBounds.maxZ())
            );
        }
        if (min == distMaxX) {
            return toWalkableOutsidePos(
                    buildBounds.maxX() + BUILD_OUTSIDE_MARGIN,
                    clamp(relZ, buildBounds.minZ(), buildBounds.maxZ())
            );
        }
        if (min == distMinZ) {
            return toWalkableOutsidePos(
                    clamp(relX, buildBounds.minX(), buildBounds.maxX()),
                    buildBounds.minZ() - BUILD_OUTSIDE_MARGIN
            );
        }
        return toWalkableOutsidePos(
                clamp(relX, buildBounds.minX(), buildBounds.maxX()),
                buildBounds.maxZ() + BUILD_OUTSIDE_MARGIN
        );
    }

    private BlockPos toWalkableOutsidePos(int relX, int relZ) {
        if (buildOrigin == null || buildBounds == null) {
            return null;
        }
        int absX = buildOrigin.getX() + relX;
        int absZ = buildOrigin.getZ() + relZ;
        int groundY = owner.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, absX, absZ);
        int minBuildY = buildOrigin.getY() + buildBounds.minY();
        int y = Math.max(minBuildY, groundY);
        return new BlockPos(absX, y, absZ);
    }

    private int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private void moveTo(BlockPos target, long gameTime) {
        if (target == null) {
            return;
        }
        boolean needNewPath = lastMoveTarget == null || !lastMoveTarget.equals(target);
        if (!needNewPath && lastMoveTick >= 0L && gameTime - lastMoveTick < REPATH_TICKS) {
            return;
        }
        // Re-issue path periodically even when navigation is "active":
        // this avoids long idle stalls on stale path state during building.
        owner.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D,
                CompanionMovementSpeed.strictByAttribute(owner, BUILD_SPEED));
        lastMoveTarget = target.immutable();
        lastMoveTick = gameTime;
    }

    private void deferCurrentPlacement() {
        Placement current = placementQueue.pollFirst();
        if (current == null) {
            return;
        }
        placementQueue.addLast(current);
        resetStallState();
    }

    private void consumePlannedBlock(Placement placement) {
        placementQueue.pollFirst();
        resetStallState();
        if (placement == null || placement.item() == null) {
            return;
        }
        Integer need = remainingRequired.get(placement.item());
        if (need == null) {
            return;
        }
        if (need <= 1) {
            remainingRequired.remove(placement.item());
        } else {
            remainingRequired.put(placement.item(), need - 1);
        }
    }

    private void finishBuild(ServerPlayer player) {
        owner.getNavigation().stop();
        cleanupTempSupports();
        if (blockedPlacements > 0) {
            owner.sendReply(player, Component.translatable(K_BUILD_DONE_BLOCKED, blockedPlacements));
        } else {
            owner.sendReply(player, Component.translatable(K_BUILD_DONE));
        }
        cancel();
    }

    private List<Placement> parsePlacements(String rawPlan) {
        String json = extractJson(rawPlan);
        if (json.isBlank()) {
            return List.of();
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (Exception ignored) {
            return List.of();
        }

        Map<BlockPos, Placement> unique = new LinkedHashMap<>();
        Map<String, Placement> doorColumns = new LinkedHashMap<>();
        if (root.isJsonObject()) {
            JsonObject rootObject = root.getAsJsonObject();
            parsePlanObject(rootObject, unique, doorColumns);
            if (unique.isEmpty() && rootObject.has("plan") && rootObject.get("plan").isJsonObject()) {
                parsePlanObject(rootObject.getAsJsonObject("plan"), unique, doorColumns);
            }
        } else if (root.isJsonArray()) {
            parseBlocksArray(root.getAsJsonArray(), unique, doorColumns);
        }

        if (unique.isEmpty()) {
            return List.of();
        }
        List<Placement> placements = new ArrayList<>(unique.values());
        placements.sort(Comparator
                .comparingInt((Placement p) -> p.rel().getY())
                .thenComparingInt(p -> p.rel().getX())
                .thenComparingInt(p -> p.rel().getZ()));
        return placements;
    }

    private void parsePlanObject(JsonObject planObject,
                                 Map<BlockPos, Placement> unique,
                                 Map<String, Placement> doorColumns) {
        if (planObject == null || unique == null || doorColumns == null) {
            return;
        }
        parseFillArray(readArray(planObject, "fill"), unique, doorColumns);
        parseLineArray(readArray(planObject, "line"), unique, doorColumns);
        JsonArray blocks = readArray(planObject, "blocks");
        if (blocks == null || blocks.isEmpty()) {
            blocks = readArray(planObject, "placements");
        }
        parseBlocksArray(blocks, unique, doorColumns);
    }

    private void parseFillArray(JsonArray fillArray,
                                Map<BlockPos, Placement> unique,
                                Map<String, Placement> doorColumns) {
        if (fillArray == null || unique == null || doorColumns == null) {
            return;
        }
        for (JsonElement element : fillArray) {
            if (unique.size() >= MAX_BLOCKS || element == null || !element.isJsonObject()) {
                break;
            }
            JsonObject obj = element.getAsJsonObject();
            int[] from = readIntTriple(obj, "from");
            int[] to = readIntTriple(obj, "to");
            String blockId = firstNonBlank(
                    readString(obj, "block"),
                    readString(obj, "id"),
                    readString(obj, "block_id"),
                    readString(obj, "blockId"),
                    readString(obj, "name")
            );
            if (from == null || to == null || blockId == null) {
                continue;
            }
            int minX = Math.min(from[0], to[0]);
            int maxX = Math.max(from[0], to[0]);
            int minY = Math.min(from[1], to[1]);
            int maxY = Math.max(from[1], to[1]);
            int minZ = Math.min(from[2], to[2]);
            int maxZ = Math.max(from[2], to[2]);
            boolean forceHollow = readBooleanLike(obj, "hollow");
            boolean autoHollow = (maxX - minX) >= 2 && (maxY - minY) >= 2 && (maxZ - minZ) >= 2;
            boolean hollow = forceHollow || autoHollow;
            for (int x = minX; x <= maxX && unique.size() < MAX_BLOCKS; x++) {
                for (int y = minY; y <= maxY && unique.size() < MAX_BLOCKS; y++) {
                    for (int z = minZ; z <= maxZ && unique.size() < MAX_BLOCKS; z++) {
                        if (hollow
                                && x > minX && x < maxX
                                && y > minY && y < maxY
                                && z > minZ && z < maxZ) {
                            continue;
                        }
                        addPlacementFromValues(unique, doorColumns, x, y, z, blockId);
                    }
                }
            }
        }
    }

    private void parseLineArray(JsonArray lineArray,
                                Map<BlockPos, Placement> unique,
                                Map<String, Placement> doorColumns) {
        if (lineArray == null || unique == null || doorColumns == null) {
            return;
        }
        for (JsonElement element : lineArray) {
            if (unique.size() >= MAX_BLOCKS || element == null || !element.isJsonObject()) {
                break;
            }
            JsonObject obj = element.getAsJsonObject();
            int[] start = readIntTriple(obj, "start");
            int[] end = readIntTriple(obj, "end");
            String blockId = firstNonBlank(
                    readString(obj, "block"),
                    readString(obj, "id"),
                    readString(obj, "block_id"),
                    readString(obj, "blockId"),
                    readString(obj, "name")
            );
            if (start == null || end == null || blockId == null) {
                continue;
            }
            int dx = end[0] - start[0];
            int dy = end[1] - start[1];
            int dz = end[2] - start[2];
            int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
            if (steps <= 0) {
                addPlacementFromValues(unique, doorColumns, start[0], start[1], start[2], blockId);
                continue;
            }
            for (int i = 0; i <= steps && unique.size() < MAX_BLOCKS; i++) {
                int x = (int) Math.round(start[0] + (dx * (double) i / steps));
                int y = (int) Math.round(start[1] + (dy * (double) i / steps));
                int z = (int) Math.round(start[2] + (dz * (double) i / steps));
                addPlacementFromValues(unique, doorColumns, x, y, z, blockId);
            }
        }
    }

    private void parseBlocksArray(JsonArray blocksArray,
                                  Map<BlockPos, Placement> unique,
                                  Map<String, Placement> doorColumns) {
        if (blocksArray == null || unique == null || doorColumns == null) {
            return;
        }
        for (JsonElement element : blocksArray) {
            if (unique.size() >= MAX_BLOCKS || element == null || !element.isJsonObject()) {
                break;
            }
            JsonObject obj = element.getAsJsonObject();
            Integer x = readIntLike(obj, "x");
            Integer y = readIntLike(obj, "y");
            Integer z = readIntLike(obj, "z");
            int[] pos = readIntTriple(obj, "pos");
            if (x == null && pos != null) {
                x = pos[0];
            }
            if (y == null && pos != null) {
                y = pos[1];
            }
            if (z == null && pos != null) {
                z = pos[2];
            }
            String blockId = firstNonBlank(
                    readString(obj, "block"),
                    readString(obj, "id"),
                    readString(obj, "block_id"),
                    readString(obj, "blockId"),
                    readString(obj, "name")
            );
            if (x == null || y == null || z == null || blockId == null) {
                continue;
            }
            addPlacementFromValues(unique, doorColumns, x, y, z, blockId);
        }
    }

    private void addPlacementFromValues(Map<BlockPos, Placement> unique,
                                        Map<String, Placement> doorColumns,
                                        int x,
                                        int y,
                                        int z,
                                        String blockId) {
        if (unique == null || doorColumns == null || blockId == null || unique.size() >= MAX_BLOCKS) {
            return;
        }
        if (Math.abs(x) > MAX_REL || Math.abs(y) > MAX_REL || Math.abs(z) > MAX_REL) {
            return;
        }
        ResourceLocation key = ResourceLocation.tryParse(normalizeBlockId(blockId));
        if (key == null) {
            return;
        }
        Block block = ForgeRegistries.BLOCKS.getValue(key);
        if (block == null || block == Blocks.AIR) {
            return;
        }
        Item item = block.asItem();
        if (item == null || item == Items.AIR) {
            return;
        }
        BlockPos rel = new BlockPos(x, y, z);
        Placement placement = new Placement(rel, block, item);
        if (block instanceof DoorBlock) {
            ResourceLocation blockKey = ForgeRegistries.BLOCKS.getKey(block);
            String doorKey = (blockKey == null ? "minecraft:oak_door" : blockKey.toString())
                    + "|" + rel.getX() + "|" + rel.getZ();
            Placement existingDoor = doorColumns.get(doorKey);
            if (existingDoor == null || rel.getY() < existingDoor.rel().getY()) {
                if (existingDoor != null) {
                    unique.remove(existingDoor.rel());
                }
                unique.remove(rel.above());
                unique.put(rel, placement);
                doorColumns.put(doorKey, placement);
            }
            return;
        }
        Placement below = unique.get(rel.below());
        if (below != null && below.block() instanceof DoorBlock) {
            return;
        }
        Placement atPos = unique.get(rel);
        if (atPos != null && atPos.block() instanceof DoorBlock) {
            return;
        }
        unique.put(rel, placement);
    }

    private BuildBounds computeBounds(List<Placement> placements) {
        if (placements == null || placements.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Placement placement : placements) {
            if (placement == null || placement.rel() == null) {
                continue;
            }
            BlockPos rel = placement.rel();
            minX = Math.min(minX, rel.getX());
            maxX = Math.max(maxX, rel.getX());
            minY = Math.min(minY, rel.getY());
            maxY = Math.max(maxY, rel.getY());
            minZ = Math.min(minZ, rel.getZ());
            maxZ = Math.max(maxZ, rel.getZ());
            if (placement.block() instanceof DoorBlock) {
                maxY = Math.max(maxY, rel.getY() + 1);
            }
        }
        if (minX == Integer.MAX_VALUE) {
            return null;
        }
        return new BuildBounds(minX, maxX, minY, maxY, minZ, maxZ);
    }

    private List<Placement> normalizeToGroundLevel(List<Placement> placements) {
        if (placements == null || placements.isEmpty()) {
            return placements;
        }
        int minY = Integer.MAX_VALUE;
        for (Placement placement : placements) {
            if (placement == null || placement.rel() == null) {
                continue;
            }
            minY = Math.min(minY, placement.rel().getY());
        }
        if (minY == Integer.MAX_VALUE || minY == 0) {
            return placements;
        }
        List<Placement> adjusted = new ArrayList<>(placements.size());
        for (Placement placement : placements) {
            if (placement == null || placement.rel() == null) {
                continue;
            }
            BlockPos rel = placement.rel();
            BlockPos shifted = new BlockPos(rel.getX(), rel.getY() - minY, rel.getZ());
            adjusted.add(new Placement(shifted, placement.block(), placement.item()));
        }
        adjusted.sort(Comparator
                .comparingInt((Placement p) -> p.rel().getY())
                .thenComparingInt(p -> p.rel().getX())
                .thenComparingInt(p -> p.rel().getZ()));
        return adjusted;
    }

    private List<Placement> ensureHabitableInterior(List<Placement> placements) {
        if (placements == null || placements.isEmpty()) {
            return placements;
        }
        BuildBounds bounds = computeBounds(placements);
        if (bounds == null) {
            return placements;
        }
        int innerX = bounds.maxX() - bounds.minX() - 1;
        int innerY = bounds.maxY() - bounds.minY() - 1;
        int innerZ = bounds.maxZ() - bounds.minZ() - 1;
        if (innerX <= 0 || innerY <= 0 || innerZ <= 0) {
            return placements;
        }
        int interiorVolume = innerX * innerY * innerZ;
        if (interiorVolume < 8) {
            return placements;
        }
        int interiorBlocks = 0;
        for (Placement placement : placements) {
            if (placement == null || placement.rel() == null) {
                continue;
            }
            if (isInterior(placement.rel(), bounds)) {
                interiorBlocks++;
            }
        }
        if (interiorBlocks * 2 < interiorVolume) {
            return placements;
        }

        List<Placement> adjusted = new ArrayList<>(placements.size());
        boolean changed = false;
        for (Placement placement : placements) {
            if (placement == null || placement.rel() == null) {
                continue;
            }
            if (isInterior(placement.rel(), bounds) && !(placement.block() instanceof DoorBlock)) {
                changed = true;
                continue;
            }
            adjusted.add(placement);
        }
        return changed ? adjusted : placements;
    }

    private List<Placement> ensureFloorAndCeiling(List<Placement> placements, String requestText) {
        if (placements == null || placements.isEmpty()) {
            return placements;
        }
        BuildBounds bounds = computeBounds(placements);
        if (bounds == null) {
            return placements;
        }
        Block shellBlock = chooseShellBlockForStructure(placements, normalize(requestText));
        if (shellBlock == null || shellBlock == Blocks.AIR) {
            return placements;
        }
        Item shellItem = shellBlock.asItem();
        if (shellItem == null || shellItem == Items.AIR) {
            return placements;
        }

        int minY = bounds.minY();
        int maxY = bounds.maxY();
        Map<BlockPos, Placement> unique = new LinkedHashMap<>();
        for (Placement placement : placements) {
            if (placement == null || placement.rel() == null) {
                continue;
            }
            unique.put(placement.rel(), placement);
        }

        boolean changed = false;
        outer:
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                if (unique.size() >= MAX_BLOCKS) {
                    break outer;
                }
                BlockPos floor = new BlockPos(x, minY, z);
                if (!unique.containsKey(floor)) {
                    unique.put(floor, new Placement(floor, shellBlock, shellItem));
                    changed = true;
                }
                if (unique.size() >= MAX_BLOCKS) {
                    break outer;
                }
                BlockPos ceiling = new BlockPos(x, maxY, z);
                if (!unique.containsKey(ceiling)) {
                    unique.put(ceiling, new Placement(ceiling, shellBlock, shellItem));
                    changed = true;
                }
            }
        }
        if (!changed) {
            return placements;
        }

        List<Placement> adjusted = new ArrayList<>(unique.values());
        adjusted.sort(Comparator
                .comparingInt((Placement p) -> p.rel().getY())
                .thenComparingInt(p -> p.rel().getX())
                .thenComparingInt(p -> p.rel().getZ()));
        return adjusted;
    }

    private List<Placement> ensureWallShell(List<Placement> placements, String requestText) {
        if (placements == null || placements.isEmpty()) {
            return placements;
        }
        BuildBounds bounds = computeBounds(placements);
        if (bounds == null) {
            return placements;
        }
        if (bounds.maxX() - bounds.minX() < 2 || bounds.maxZ() - bounds.minZ() < 2) {
            return placements;
        }

        Block wallBlock = chooseShellBlockForStructure(placements, normalize(requestText));
        if (wallBlock == null || wallBlock == Blocks.AIR) {
            return placements;
        }
        Item wallItem = wallBlock.asItem();
        if (wallItem == null || wallItem == Items.AIR) {
            return placements;
        }

        Map<BlockPos, Placement> unique = new LinkedHashMap<>();
        for (Placement placement : placements) {
            if (placement == null || placement.rel() == null) {
                continue;
            }
            unique.put(placement.rel(), placement);
        }

        int minX = bounds.minX();
        int maxX = bounds.maxX();
        int minY = bounds.minY();
        int maxY = Math.max(bounds.maxY(), minY + 3);
        int minZ = bounds.minZ();
        int maxZ = bounds.maxZ();

        int wallStartY = minY + 1;
        int wallEndY = Math.max(wallStartY, maxY - 1);
        boolean changed = false;

        for (int x = minX; x <= maxX && unique.size() < MAX_BLOCKS; x++) {
            for (int z = minZ; z <= maxZ && unique.size() < MAX_BLOCKS; z++) {
                BlockPos floor = new BlockPos(x, minY, z);
                if (!unique.containsKey(floor)) {
                    unique.put(floor, new Placement(floor, wallBlock, wallItem));
                    changed = true;
                }
                BlockPos ceiling = new BlockPos(x, maxY, z);
                if (!unique.containsKey(ceiling)) {
                    unique.put(ceiling, new Placement(ceiling, wallBlock, wallItem));
                    changed = true;
                }
            }
        }

        int sideSpan = Math.max(1, (maxX - minX) - 1);
        int doorOffset = sideSpan <= 1 ? 0 : Math.floorMod(planVariantSeed, sideSpan) - (sideSpan / 2);
        int doorX = clamp((minX + maxX) / 2 + doorOffset, minX + 1, maxX - 1);
        int doorZ = minZ;
        int doorBottomY = wallStartY;
        int doorTopY = Math.min(wallEndY, doorBottomY + 1);

        for (int y = wallStartY; y <= wallEndY && unique.size() < MAX_BLOCKS; y++) {
            for (int x = minX; x <= maxX && unique.size() < MAX_BLOCKS; x++) {
                for (int z = minZ; z <= maxZ && unique.size() < MAX_BLOCKS; z++) {
                    if (x != minX && x != maxX && z != minZ && z != maxZ) {
                        continue;
                    }
                    if (x == doorX && z == doorZ && (y == doorBottomY || y == doorTopY)) {
                        continue;
                    }
                    BlockPos rel = new BlockPos(x, y, z);
                    if (!unique.containsKey(rel)) {
                        unique.put(rel, new Placement(rel, wallBlock, wallItem));
                        changed = true;
                    }
                }
            }
        }

        Block doorBlock = resolveRequestedDoorBlock(wallBlock);
        Item doorItem = doorBlock == null ? Items.AIR : doorBlock.asItem();
        if (doorBlock instanceof DoorBlock && doorItem != null && doorItem != Items.AIR
                && doorBottomY <= wallEndY && unique.size() < MAX_BLOCKS) {
            BlockPos doorRel = new BlockPos(doorX, doorBottomY, doorZ);
            BlockPos doorTopRel = doorRel.above();
            if (unique.remove(doorTopRel) != null) {
                changed = true;
            }
            unique.put(doorRel, new Placement(doorRel, doorBlock, doorItem));
            changed = true;
        }

        Block windowBlock = chooseWindowBlockForStructure(placements);
        Item windowItem = windowBlock == null ? Items.AIR : windowBlock.asItem();
        if (windowBlock != null && windowBlock != Blocks.AIR && windowItem != null && windowItem != Items.AIR) {
            int windowY = clamp(minY + 2, wallStartY, wallEndY);
            int xOffset = Math.max(1, (maxX - minX) / 4);
            int zOffset = Math.max(1, (maxZ - minZ) / 4);
            int xShift = Math.floorMod(planVariantSeed >> 3, 3) - 1;
            int zShift = Math.floorMod(planVariantSeed >> 5, 3) - 1;
            int wx = clamp((minX + maxX) / 2 + xShift * xOffset, minX + 1, maxX - 1);
            int wz = clamp((minZ + maxZ) / 2 + zShift * zOffset, minZ + 1, maxZ - 1);

            List<BlockPos> windowCandidates = new ArrayList<>(6);
            windowCandidates.add(new BlockPos(wx, windowY, maxZ));
            windowCandidates.add(new BlockPos(wx, windowY, minZ));
            windowCandidates.add(new BlockPos(minX, windowY, wz));
            windowCandidates.add(new BlockPos(maxX, windowY, wz));
            windowCandidates.add(new BlockPos(minX, windowY, clamp(wz + 1, minZ + 1, maxZ - 1)));
            windowCandidates.add(new BlockPos(maxX, windowY, clamp(wz - 1, minZ + 1, maxZ - 1)));

            int placedWindows = 0;
            for (BlockPos candidate : windowCandidates) {
                if (candidate == null || unique.size() >= MAX_BLOCKS || placedWindows >= 2) {
                    break;
                }
                if (candidate.getX() == doorX && candidate.getZ() == doorZ) {
                    continue;
                }
                Placement existing = unique.get(candidate);
                if (existing != null && existing.block() instanceof DoorBlock) {
                    continue;
                }
                if (existing != null && existing.block() != wallBlock && existing.block() != windowBlock) {
                    continue;
                }
                unique.put(candidate, new Placement(candidate, windowBlock, windowItem));
                placedWindows++;
                changed = true;
            }
        }

        if (!changed) {
            return placements;
        }
        List<Placement> adjusted = new ArrayList<>(unique.values());
        adjusted.sort(Comparator
                .comparingInt((Placement p) -> p.rel().getY())
                .thenComparingInt(p -> p.rel().getX())
                .thenComparingInt(p -> p.rel().getZ()));
        return adjusted;
    }

    private Block chooseWindowBlockForStructure(List<Placement> placements) {
        if (placements == null || placements.isEmpty()) {
            return Blocks.GLASS;
        }
        for (Placement placement : placements) {
            if (placement == null || placement.block() == null) {
                continue;
            }
            Block block = placement.block();
            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
            if (key == null || !"minecraft".equals(key.getNamespace())) {
                continue;
            }
            String path = key.getPath();
            if (path.contains("glass") && !path.contains("pane")) {
                return block;
            }
        }
        for (Placement placement : placements) {
            if (placement == null || placement.block() == null) {
                continue;
            }
            Block block = placement.block();
            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
            if (key == null || !"minecraft".equals(key.getNamespace())) {
                continue;
            }
            if (key.getPath().contains("glass") && !key.getPath().contains("pane")) {
                return block;
            }
        }
        return Blocks.GLASS;
    }

    private List<Placement> replaceGlassPanesWithBlocks(List<Placement> placements) {
        if (placements == null || placements.isEmpty()) {
            return placements;
        }
        boolean changed = false;
        List<Placement> adjusted = new ArrayList<>(placements.size());
        for (Placement placement : placements) {
            if (placement == null || placement.block() == null) {
                continue;
            }
            Block replacement = replacementForGlassPane(placement.block());
            if (replacement != placement.block()) {
                changed = true;
            }
            Item item = replacement.asItem();
            if (item == null || item == Items.AIR) {
                item = placement.item();
            }
            adjusted.add(new Placement(placement.rel(), replacement, item));
        }
        return changed ? adjusted : placements;
    }

    private Block replacementForGlassPane(Block block) {
        if (block == null || block == Blocks.AIR) {
            return block;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        if (key == null || !"minecraft".equals(key.getNamespace())) {
            return block;
        }
        String path = key.getPath();
        if (path == null || !path.endsWith("glass_pane")) {
            return block;
        }
        String fullPath;
        if ("glass_pane".equals(path)) {
            fullPath = "glass";
        } else if (path.endsWith("_stained_glass_pane")) {
            fullPath = path.substring(0, path.length() - "_pane".length());
        } else {
            fullPath = "glass";
        }
        ResourceLocation fullKey = ResourceLocation.tryParse("minecraft:" + fullPath);
        if (fullKey == null) {
            return Blocks.GLASS;
        }
        Block full = ForgeRegistries.BLOCKS.getValue(fullKey);
        if (full == null || full == Blocks.AIR) {
            return Blocks.GLASS;
        }
        return full;
    }

    private Block chooseShellBlockForStructure(List<Placement> placements, String normalizedRequest) {
        Block requestedMain = resolveRequestedMainBlock(normalizedRequest);
        if (requestedMain != null) {
            return requestedMain;
        }
        if (placements != null && !placements.isEmpty()) {
            Map<Block, Integer> counts = new LinkedHashMap<>();
            for (Placement placement : placements) {
                if (placement == null || placement.block() == null) {
                    continue;
                }
                Block block = placement.block();
                if (!isSuitableMainBuildBlock(block) || isProtectedDetailBlock(block)) {
                    continue;
                }
                counts.merge(block, 1, Integer::sum);
            }
            Block best = null;
            int bestCount = 0;
            for (Map.Entry<Block, Integer> entry : counts.entrySet()) {
                Integer count = entry.getValue();
                if (count != null && count > bestCount) {
                    best = entry.getKey();
                    bestCount = count;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return chooseFallbackMainBlock(normalizedRequest);
    }

    private boolean isInterior(BlockPos rel, BuildBounds bounds) {
        if (rel == null || bounds == null) {
            return false;
        }
        return rel.getX() > bounds.minX() && rel.getX() < bounds.maxX()
                && rel.getY() > bounds.minY() && rel.getY() < bounds.maxY()
                && rel.getZ() > bounds.minZ() && rel.getZ() < bounds.maxZ();
    }

    private List<Placement> ensureLightSources(List<Placement> placements, String requestText) {
        if (placements == null || placements.isEmpty()) {
            return placements;
        }
        boolean hasLight = false;
        for (Placement placement : placements) {
            if (placement == null || placement.block() == null) {
                continue;
            }
            if (placement.block().defaultBlockState().getLightEmission() > 0) {
                hasLight = true;
                break;
            }
        }
        if (hasLight) {
            return placements;
        }

        BuildBounds bounds = computeBounds(placements);
        if (bounds == null) {
            return placements;
        }
        if (bounds.maxX() - bounds.minX() < 2 || bounds.maxZ() - bounds.minZ() < 2) {
            return placements;
        }
        Block requestedLight = chooseRequestedLightBlock(normalize(requestText));
        List<Block> lightOptions = new ArrayList<>(6);
        addUniqueLightBlock(lightOptions, requestedLight);
        addUniqueLightBlock(lightOptions, Blocks.TORCH);
        addUniqueLightBlock(lightOptions, Blocks.LANTERN);
        addUniqueLightBlock(lightOptions, Blocks.GLOWSTONE);
        addUniqueLightBlock(lightOptions, Blocks.SEA_LANTERN);
        addUniqueLightBlock(lightOptions, Blocks.SHROOMLIGHT);
        if (lightOptions.isEmpty()) {
            return placements;
        }

        Map<BlockPos, Placement> unique = new LinkedHashMap<>();
        for (Placement placement : placements) {
            if (placement == null || placement.rel() == null) {
                continue;
            }
            unique.put(placement.rel(), placement);
        }

        int midX = (bounds.minX() + bounds.maxX()) / 2;
        int midZ = (bounds.minZ() + bounds.maxZ()) / 2;
        int y = Math.max(bounds.minY() + 1, Math.min(bounds.maxY() - 1, bounds.minY() + 2));
        List<BlockPos> candidates = new ArrayList<>(4);
        candidates.add(new BlockPos(midX, y, midZ));
        candidates.add(new BlockPos(midX - 1, y, midZ));
        candidates.add(new BlockPos(midX + 1, y, midZ));
        candidates.add(new BlockPos(midX, y, midZ - 1));
        candidates.add(new BlockPos(midX, y, midZ + 1));

        for (BlockPos rel : candidates) {
            if (!isInterior(rel, bounds)) {
                continue;
            }
            for (Block lightBlock : lightOptions) {
                if (!canPlaceLightAt(unique, rel, lightBlock)) {
                    continue;
                }
                Item lightItem = lightBlock.asItem();
                if (lightItem == null || lightItem == Items.AIR) {
                    continue;
                }
                unique.put(rel, new Placement(rel, lightBlock, lightItem));
                List<Placement> adjusted = new ArrayList<>(unique.values());
                adjusted.sort(Comparator
                        .comparingInt((Placement p) -> p.rel().getY())
                        .thenComparingInt(p -> p.rel().getX())
                        .thenComparingInt(p -> p.rel().getZ()));
                return adjusted;
            }
        }
        return placements;
    }

    private void addUniqueLightBlock(List<Block> target, Block block) {
        if (target == null || block == null || !isLightBlockCandidate(block) || target.contains(block)) {
            return;
        }
        target.add(block);
    }

    private boolean canPlaceLightAt(Map<BlockPos, Placement> unique, BlockPos rel, Block lightBlock) {
        if (unique == null || rel == null || lightBlock == null) {
            return false;
        }
        Placement existing = unique.get(rel);
        if (existing != null && existing.block() != null
                && !existing.block().defaultBlockState().canBeReplaced()
                && !existing.block().equals(lightBlock)) {
            return false;
        }
        if (lightBlock == Blocks.TORCH || lightBlock == Blocks.SOUL_TORCH
                || lightBlock == Blocks.LANTERN || lightBlock == Blocks.SOUL_LANTERN) {
            Placement below = unique.get(rel.below());
            return below != null && below.block() != null && below.block().defaultBlockState().blocksMotion();
        }
        return true;
    }

    private Block chooseRequestedLightBlock(String normalizedRequest) {
        if (normalizedRequest == null || normalizedRequest.isBlank()) {
            return Blocks.TORCH;
        }
        Block byId = tryFindRequestedLightBlockById(normalizedRequest);
        if (byId != null) {
            return byId;
        }
        if (containsAny(normalizedRequest, "\u0441\u0432\u0435\u0442\u043e\u043a\u0430\u043c", "glowstone")) {
            return Blocks.GLOWSTONE;
        }
        if (containsAny(normalizedRequest, "\u043c\u043e\u0440\u0441\u043a", "sea_lantern", "sea lantern")) {
            return Blocks.SEA_LANTERN;
        }
        if (containsAny(normalizedRequest, "\u0444\u043e\u043d\u0430\u0440", "lantern")) {
            return Blocks.LANTERN;
        }
        if (containsAny(normalizedRequest, "\u0448\u0440\u0443\u043c", "shroomlight")) {
            return Blocks.SHROOMLIGHT;
        }
        if (containsAny(normalizedRequest, "\u043b\u044f\u0433\u0443\u0448", "froglight")) {
            return Blocks.OCHRE_FROGLIGHT;
        }
        if (containsAny(normalizedRequest, "\u0444\u0430\u043a\u0435\u043b", "torch")) {
            return Blocks.TORCH;
        }
        return Blocks.TORCH;
    }

    private Block tryFindRequestedLightBlockById(String normalizedRequest) {
        if (normalizedRequest == null || normalizedRequest.isBlank()) {
            return null;
        }
        String[] tokens = normalizedRequest.split(MATERIAL_TOKEN_SPLIT_REGEX);
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            ResourceLocation key = ResourceLocation.tryParse(normalizeBlockId(token));
            if (key == null || !"minecraft".equals(key.getNamespace())) {
                continue;
            }
            Block block = ForgeRegistries.BLOCKS.getValue(key);
            if (isLightBlockCandidate(block)) {
                return block;
            }
        }
        return null;
    }

    private boolean isLightBlockCandidate(Block block) {
        if (block == null || block == Blocks.AIR) {
            return false;
        }
        Item item = block.asItem();
        if (item == null || item == Items.AIR) {
            return false;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        return key != null && "minecraft".equals(key.getNamespace())
                && block.defaultBlockState().getLightEmission() > 0;
    }

    private List<Placement> applyRequestedMaterialPreference(List<Placement> placements, String requestText) {
        if (placements == null || placements.isEmpty()) {
            return placements;
        }
        String normalizedRequest = normalize(requestText);
        boolean explicitMaterial = hasExplicitMaterialHint(normalizedRequest);
        Block preferredMain = explicitMaterial
                ? resolveRequestedMainBlock(normalizedRequest)
                : chooseBiomeMainBlock();
        if (preferredMain == null) {
            return placements;
        }
        if (!explicitMaterial && preferredMain == Blocks.OAK_PLANKS) {
            return placements;
        }
        Block preferredDoor = resolveRequestedDoorBlock(preferredMain);
        String preferredWoodPrefix = woodPrefixFromBlock(preferredMain);

        boolean changed = false;
        List<Placement> adjusted = new ArrayList<>(placements.size());
        for (Placement placement : placements) {
            if (placement == null || placement.block() == null) {
                continue;
            }
            Block current = placement.block();
            Block replacement = current;

            if (current instanceof DoorBlock) {
                replacement = preferredDoor;
            } else if (preferredWoodPrefix != null) {
                replacement = remapWoodFamilyBlock(current, preferredWoodPrefix);
                if (replacement == current && shouldReplaceWithPreferredMain(current, preferredMain)) {
                    replacement = preferredMain;
                }
            } else if (shouldReplaceWithPreferredMain(current, preferredMain)) {
                replacement = preferredMain;
            }

            if (replacement != current) {
                changed = true;
            }
            Item item = replacement.asItem();
            if (item == null || item == Items.AIR) {
                item = placement.item();
            }
            adjusted.add(new Placement(placement.rel(), replacement, item));
        }
        return changed ? adjusted : placements;
    }

    private boolean shouldReplaceWithPreferredMain(Block current, Block preferredMain) {
        if (current == null || preferredMain == null || current == preferredMain) {
            return false;
        }
        if (isProtectedDetailBlock(current)) {
            return false;
        }
        return isSuitableMainBuildBlock(current);
    }

    private boolean isProtectedDetailBlock(Block block) {
        if (block == null || block == Blocks.AIR) {
            return true;
        }
        if (block instanceof DoorBlock) {
            return true;
        }
        BlockState state = block.defaultBlockState();
        if (state.getLightEmission() > 0) {
            return true;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        if (key == null || !"minecraft".equals(key.getNamespace())) {
            return true;
        }
        String path = key.getPath();
        if (path == null) {
            return true;
        }
        return path.contains("glass")
                || path.endsWith("_pane")
                || path.endsWith("_torch")
                || path.endsWith("_lantern")
                || path.endsWith("_candle")
                || path.endsWith("_wall_torch")
                || path.endsWith("_sign")
                || path.endsWith("_hanging_sign")
                || path.endsWith("_banner");
    }

    private List<Placement> fallbackPlacements(String requestText) {
        String normalized = normalize(requestText);
        Block main = resolveRequestedMainBlock(normalized);
        if (main == null) {
            main = chooseFallbackMainBlock(normalized);
        }
        Block floor = main;
        Block roof = main;
        Block window = Blocks.GLASS;
        Block door = resolveRequestedDoorBlock(main);

        boolean compact = normalized.contains("\u043c\u0430\u043b\u0435\u043d\u044c\u043a") || normalized.contains("small");
        int baseSize = compact ? 5 : 7;
        int sizeJitter = Math.floorMod(planVariantSeed, compact ? 2 : 3);
        int size = baseSize + sizeJitter;
        int wallHeight = 4 + Math.floorMod(planVariantSeed >> 2, 2);
        int half = size / 2;
        int sideSpan = Math.max(1, size - 2);
        int doorX = clamp(Math.floorMod(planVariantSeed >> 4, sideSpan) - (sideSpan / 2), -half + 1, half - 1);
        int doorZ = -half;
        int windowY = clamp(2 + Math.floorMod(planVariantSeed >> 6, 2), 2, wallHeight - 1);

        Map<BlockPos, Placement> unique = new LinkedHashMap<>();

        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                addPlacement(unique, x, 0, z, floor);
                addPlacement(unique, x, wallHeight, z, roof);
            }
        }

        for (int y = 1; y < wallHeight; y++) {
            for (int x = -half; x <= half; x++) {
                if (!(x == doorX && (y == 1 || y == 2))) {
                    addPlacement(unique, x, y, -half, main);
                }
                addPlacement(unique, x, y, half, main);
            }
            for (int z = -half + 1; z <= half - 1; z++) {
                addPlacement(unique, -half, y, z, main);
                addPlacement(unique, half, y, z, main);
            }
        }

        addPlacement(unique, -half, windowY, 0, window);
        addPlacement(unique, half, windowY, 0, window);
        addPlacement(unique, 0, windowY, half, window);
        addPlacement(unique, doorX, 1, doorZ, door);

        List<Placement> placements = new ArrayList<>(unique.values());
        placements.sort(Comparator
                .comparingInt((Placement p) -> p.rel().getY())
                .thenComparingInt(p -> p.rel().getX())
                .thenComparingInt(p -> p.rel().getZ()));
        return placements;
    }

    private Block chooseFallbackMainBlock(String normalizedRequest) {
        Block biomePreferred = chooseBiomeMainBlock();
        if (normalizedRequest == null || normalizedRequest.isBlank()) {
            return biomePreferred;
        }
        if (containsAny(normalizedRequest, "\u0431\u0440\u0435\u0432", "\u043b\u043e\u0433", "log")) {
            return Blocks.OAK_LOG;
        }
        if (containsAny(normalizedRequest, "\u0441\u0430\u043a\u0443\u0440", "\u0432\u0438\u0448\u043d\u0435\u0432", "\u0440\u043e\u0437\u043e\u0432", "sakura", "cherry", "pink")) {
            return Blocks.CHERRY_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u0431\u0430\u043c\u0431\u0443\u043a", "bamboo")) {
            return Blocks.BAMBOO_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u043c\u0430\u043d\u0433\u0440", "mangrove")) {
            return Blocks.MANGROVE_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u0431\u0430\u0433\u0440\u043e\u0432", "crimson")) {
            return Blocks.CRIMSON_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u0438\u0441\u043a\u0430\u0436", "warped")) {
            return Blocks.WARPED_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u0430\u043a\u0430\u0446", "acacia")) {
            return Blocks.ACACIA_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u0434\u0436\u0443\u043d\u0433", "jungle")) {
            return Blocks.JUNGLE_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u0431\u0435\u0440\u0435\u0437", "birch")) {
            return Blocks.BIRCH_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u0435\u043b\u043e\u0432", "spruce")) {
            return Blocks.SPRUCE_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u0442\u0435\u043c\u043d", "dark oak", "dark_oak")) {
            return Blocks.DARK_OAK_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u043a\u0432\u0430\u0440\u0446", "quartz")) {
            return Blocks.QUARTZ_BLOCK;
        }
        if (containsAny(normalizedRequest, "\u043f\u0435\u0441\u0447\u0430\u043d\u0438\u043a", "sandstone")) {
            return Blocks.SANDSTONE;
        }
        if (containsAny(normalizedRequest, "\u043a\u0438\u0440\u043f\u0438\u0447", "brick")) {
            return Blocks.BRICKS;
        }
        if (containsAny(normalizedRequest, "\u043a\u0430\u043c\u0435\u043d", "\u0431\u0443\u043b\u044b\u0436", "stone", "cobble")) {
            return Blocks.COBBLESTONE;
        }
        return biomePreferred;
    }

    private Block chooseBiomeMainBlock() {
        if (owner.level() == null) {
            return Blocks.OAK_PLANKS;
        }
        BlockPos origin = buildOrigin != null ? buildOrigin : owner.blockPosition();
        if (origin == null) {
            return Blocks.OAK_PLANKS;
        }
        String biomePath = owner.level().getBiome(origin)
                .unwrapKey()
                .map(key -> key.location().getPath())
                .orElse("");
        if (biomePath.isBlank()) {
            return Blocks.OAK_PLANKS;
        }
        if (biomePath.contains("cherry")) {
            return Blocks.CHERRY_PLANKS;
        }
        if (biomePath.contains("mangrove")) {
            return Blocks.MANGROVE_PLANKS;
        }
        if (biomePath.contains("bamboo")) {
            return Blocks.BAMBOO_PLANKS;
        }
        if (biomePath.contains("jungle")) {
            return Blocks.JUNGLE_PLANKS;
        }
        if (biomePath.contains("savanna")) {
            return Blocks.ACACIA_PLANKS;
        }
        if (biomePath.contains("taiga") || biomePath.contains("snow") || biomePath.contains("frozen")
                || biomePath.contains("ice") || biomePath.contains("grove")) {
            return Blocks.SPRUCE_PLANKS;
        }
        if (biomePath.contains("birch")) {
            return Blocks.BIRCH_PLANKS;
        }
        if (biomePath.contains("dark_forest")) {
            return Blocks.DARK_OAK_PLANKS;
        }
        if (biomePath.contains("desert") || biomePath.contains("beach")) {
            return Blocks.SANDSTONE;
        }
        if (biomePath.contains("badlands")) {
            return Blocks.RED_SANDSTONE;
        }
        if (biomePath.contains("stony")
                || biomePath.contains("mountain")
                || biomePath.contains("peak")
                || biomePath.contains("windswept")) {
            return Blocks.COBBLESTONE;
        }
        if (biomePath.contains("swamp")) {
            return Blocks.MOSSY_COBBLESTONE;
        }
        return Blocks.OAK_PLANKS;
    }

    private String resolveBuildBiomeContext() {
        if (owner.level() == null || buildOrigin == null) {
            return "unknown";
        }
        return owner.level().getBiome(buildOrigin)
                .unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unknown");
    }

    private Block chooseFallbackDoorBlock(Block main) {
        if (main == Blocks.BIRCH_PLANKS) {
            return Blocks.BIRCH_DOOR;
        }
        if (main == Blocks.SPRUCE_PLANKS) {
            return Blocks.SPRUCE_DOOR;
        }
        if (main == Blocks.DARK_OAK_PLANKS) {
            return Blocks.DARK_OAK_DOOR;
        }
        return Blocks.OAK_DOOR;
    }

    private Block resolveRequestedMainBlock(String normalizedRequest) {
        if (normalizedRequest == null || normalizedRequest.isBlank()) {
            return null;
        }
        if (normalizedRequest.contains("minecraft:")) {
            Block byId = tryFindRequestedMainBlockById(normalizedRequest);
            if (byId != null) {
                return byId;
            }
        }
        Block byWoodForm = resolveWoodRequestedMainBlock(normalizedRequest);
        if (byWoodForm != null) {
            return byWoodForm;
        }
        Block byId = tryFindRequestedMainBlockById(normalizedRequest);
        if (byId != null) {
            return byId;
        }
        Block byRegistryName = tryFindRequestedMainBlockByRegistryName(normalizedRequest);
        if (byRegistryName != null) {
            return byRegistryName;
        }
        Block byAlias = tryFindRequestedMainBlockByAliases(normalizedRequest);
        if (byAlias != null) {
            return byAlias;
        }
        return null;
    }

    private Block resolveWoodRequestedMainBlock(String normalizedRequest) {
        String woodPrefix = detectWoodPrefixHint(normalizedRequest);
        if (woodPrefix == null) {
            return null;
        }
        if (containsAny(normalizedRequest,
                "\u0431\u0440\u0435\u0432", "\u043b\u043e\u0433", "log")) {
            String path = switch (woodPrefix) {
                case "crimson", "warped" -> woodPrefix + "_stem";
                case "bamboo" -> "bamboo_block";
                default -> woodPrefix + "_log";
            };
            return findVanillaBlockByPath(path);
        }
        if (containsAny(normalizedRequest,
                "\u0434\u043e\u0441", "plank", "wood")) {
            return findVanillaBlockByPath(woodPrefix + "_planks");
        }
        return null;
    }

    private String detectWoodPrefixHint(String normalizedRequest) {
        if (normalizedRequest == null || normalizedRequest.isBlank()) {
            return null;
        }
        if (containsAny(normalizedRequest, "\u0441\u0430\u043a\u0443\u0440", "\u0432\u0438\u0448\u043d\u0435\u0432",
                "\u0440\u043e\u0437\u043e\u0432", "sakura", "cherry", "pink")) {
            return "cherry";
        }
        if (containsAny(normalizedRequest, "\u0431\u0430\u043c\u0431\u0443\u043a", "bamboo")) {
            return "bamboo";
        }
        if (containsAny(normalizedRequest, "\u043c\u0430\u043d\u0433\u0440", "mangrove")) {
            return "mangrove";
        }
        if (containsAny(normalizedRequest, "\u0431\u0430\u0433\u0440\u043e\u0432", "crimson")) {
            return "crimson";
        }
        if (containsAny(normalizedRequest, "\u0438\u0441\u043a\u0430\u0436", "warped")) {
            return "warped";
        }
        if (containsAny(normalizedRequest, "\u0430\u043a\u0430\u0446", "acacia")) {
            return "acacia";
        }
        if (containsAny(normalizedRequest, "\u0434\u0436\u0443\u043d\u0433", "jungle")) {
            return "jungle";
        }
        if (containsAny(normalizedRequest, "\u0431\u0435\u0440\u0435\u0437", "birch")) {
            return "birch";
        }
        if (containsAny(normalizedRequest, "\u0435\u043b\u043e\u0432", "\u0435\u043b\u044c", "spruce")) {
            return "spruce";
        }
        if (containsAny(normalizedRequest, "\u0442\u0435\u043c\u043d", "dark oak", "dark_oak")) {
            return "dark_oak";
        }
        if (containsAny(normalizedRequest, "\u0434\u0443\u0431\u043e\u0432", "\u0434\u0443\u0431", "oak")) {
            return "oak";
        }
        return null;
    }

    private Block findVanillaBlockByPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        ResourceLocation key = ResourceLocation.tryParse("minecraft:" + path);
        if (key == null) {
            return null;
        }
        Block block = ForgeRegistries.BLOCKS.getValue(key);
        return isSuitableMainBuildBlock(block) ? block : null;
    }

    private Block resolveRequestedDoorBlock(Block main) {
        String woodPrefix = woodPrefixFromBlock(main);
        if (woodPrefix != null) {
            ResourceLocation doorKey = ResourceLocation.tryParse("minecraft:" + woodPrefix + "_door");
            if (doorKey != null) {
                Block door = ForgeRegistries.BLOCKS.getValue(doorKey);
                if (door instanceof DoorBlock) {
                    return door;
                }
            }
        }
        return chooseFallbackDoorBlock(main);
    }

    private boolean hasExplicitMaterialHint(String normalizedRequest) {
        if (normalizedRequest == null || normalizedRequest.isBlank()) {
            return false;
        }
        return normalizedRequest.contains("minecraft:")
                || tryFindRequestedMainBlockById(normalizedRequest) != null
                || tryFindRequestedMainBlockByRegistryName(normalizedRequest) != null
                || resolveWoodRequestedMainBlock(normalizedRequest) != null
                || tryFindRequestedMainBlockByAliases(normalizedRequest) != null
                || containsAny(normalizedRequest,
                "\u043c\u0430\u0442\u0435\u0440\u0438\u0430\u043b", "\u0431\u0440\u0435\u0432", "\u0431\u0443\u043b\u044b\u0436",
                "plank", "wood", "log", "stone", "brick", "cobble", "glass", "quartz", "concrete", "terracotta");
    }

    private boolean containsAny(String source, String... needles) {
        if (source == null || source.isBlank() || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && source.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private Block tryFindRequestedMainBlockById(String normalizedRequest) {
        if (normalizedRequest == null || normalizedRequest.isBlank()) {
            return null;
        }
        String[] tokens = normalizedRequest.split(MATERIAL_TOKEN_SPLIT_REGEX);
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String normalizedId = normalizeBlockId(token);
            ResourceLocation key = ResourceLocation.tryParse(normalizedId);
            if (key == null) {
                continue;
            }
            Block block = ForgeRegistries.BLOCKS.getValue(key);
            if (isSuitableMainBuildBlock(block)) {
                return block;
            }
        }
        return null;
    }

    private Block tryFindRequestedMainBlockByRegistryName(String normalizedRequest) {
        if (normalizedRequest == null || normalizedRequest.isBlank()) {
            return null;
        }
        Block best = null;
        int bestScore = 0;
        for (ResourceLocation key : ForgeRegistries.BLOCKS.getKeys()) {
            if (key == null || !"minecraft".equals(key.getNamespace())) {
                continue;
            }
            String path = key.getPath();
            if (path == null || path.isBlank()) {
                continue;
            }
            String spaced = path.replace('_', ' ');
            if (!normalizedRequest.contains(path) && !normalizedRequest.contains(spaced)) {
                continue;
            }
            Block block = ForgeRegistries.BLOCKS.getValue(key);
            if (!isSuitableMainBuildBlock(block)) {
                continue;
            }
            int score = Math.max(path.length(), spaced.length());
            if (score > bestScore) {
                bestScore = score;
                best = block;
            }
        }
        return best;
    }

    private Block tryFindRequestedMainBlockByAliases(String normalizedRequest) {
        if (normalizedRequest == null || normalizedRequest.isBlank()) {
            return null;
        }
        if (containsAny(normalizedRequest, "\u0441\u0430\u043a\u0443\u0440", "\u0432\u0438\u0448\u043d\u0435\u0432",
                "\u0440\u043e\u0437\u043e\u0432", "sakura", "cherry", "pink")) {
            return Blocks.CHERRY_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u0431\u0430\u043c\u0431\u0443\u043a", "bamboo")) {
            return Blocks.BAMBOO_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u043c\u0430\u043d\u0433\u0440", "mangrove")) {
            return Blocks.MANGROVE_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u0431\u0430\u0433\u0440\u043e\u0432", "crimson")) {
            return Blocks.CRIMSON_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u0438\u0441\u043a\u0430\u0436", "warped")) {
            return Blocks.WARPED_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u0434\u0443\u0431\u043e\u0432", "\u0434\u0443\u0431", "oak")) {
            return Blocks.OAK_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u0435\u043b\u043e\u0432", "\u0435\u043b\u044c", "spruce")) {
            return Blocks.SPRUCE_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u0431\u0435\u0440\u0435\u0437", "birch")) {
            return Blocks.BIRCH_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u0434\u0436\u0443\u043d\u0433", "jungle")) {
            return Blocks.JUNGLE_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u0430\u043a\u0430\u0446", "acacia")) {
            return Blocks.ACACIA_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u0442\u0435\u043c\u043d", "dark oak", "dark_oak")) {
            return Blocks.DARK_OAK_PLANKS;
        }
        if (containsAny(normalizedRequest, "\u043a\u0432\u0430\u0440\u0446", "quartz")) {
            return Blocks.QUARTZ_BLOCK;
        }
        if (containsAny(normalizedRequest, "\u043f\u0435\u0441\u0447\u0430\u043d\u0438\u043a", "sandstone")) {
            return Blocks.SANDSTONE;
        }
        if (containsAny(normalizedRequest, "\u043a\u0438\u0440\u043f\u0438\u0447", "brick")) {
            return Blocks.BRICKS;
        }
        if (containsAny(normalizedRequest, "\u043a\u0430\u043c\u0435\u043d", "\u0431\u0443\u043b\u044b\u0436",
                "stone", "cobble")) {
            return Blocks.COBBLESTONE;
        }
        return null;
    }

    private boolean isSuitableMainBuildBlock(Block block) {
        if (block == null || block == Blocks.AIR
                || block == Blocks.WATER || block == Blocks.LAVA
                || block instanceof DoorBlock) {
            return false;
        }
        Item item = block.asItem();
        if (item == null || item == Items.AIR) {
            return false;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        if (key == null || !"minecraft".equals(key.getNamespace())) {
            return false;
        }
        String path = key.getPath();
        if (path == null || path.isBlank()) {
            return false;
        }
        return true;
    }

    private String woodPrefixFromBlock(Block main) {
        if (main == null || main == Blocks.AIR) {
            return null;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(main);
        if (key == null || !"minecraft".equals(key.getNamespace())) {
            return null;
        }
        String path = key.getPath();
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] prefixes = {
                "oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
                "mangrove", "cherry", "bamboo", "crimson", "warped"
        };
        for (String prefix : prefixes) {
            if ((prefix + "_planks").equals(path)
                    || (prefix + "_log").equals(path)
                    || (prefix + "_wood").equals(path)
                    || (prefix + "_stem").equals(path)
                    || (prefix + "_hyphae").equals(path)
                    || path.startsWith(prefix + "_")) {
                return prefix;
            }
        }
        return null;
    }

    private Block remapWoodFamilyBlock(Block current, String preferredWoodPrefix) {
        if (current == null || preferredWoodPrefix == null || preferredWoodPrefix.isBlank()) {
            return current;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(current);
        if (key == null || !"minecraft".equals(key.getNamespace())) {
            return current;
        }
        String path = key.getPath();
        if (path == null || path.isBlank()) {
            return current;
        }
        String[] prefixes = {
                "oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
                "mangrove", "cherry", "bamboo", "crimson", "warped"
        };
        String matchedPrefix = null;
        for (String prefix : prefixes) {
            if ((prefix + "_planks").equals(path) || path.startsWith(prefix + "_")) {
                matchedPrefix = prefix;
                break;
            }
        }
        if (matchedPrefix == null || matchedPrefix.equals(preferredWoodPrefix)) {
            return current;
        }
        String suffix;
        if ((matchedPrefix + "_planks").equals(path)) {
            suffix = "planks";
        } else {
            suffix = path.substring(matchedPrefix.length() + 1);
        }
        ResourceLocation mappedKey = ResourceLocation.tryParse("minecraft:" + preferredWoodPrefix + "_" + suffix);
        if (mappedKey == null) {
            return current;
        }
        Block mapped = ForgeRegistries.BLOCKS.getValue(mappedKey);
        if (mapped == null || mapped == Blocks.AIR) {
            return current;
        }
        Item item = mapped.asItem();
        if (item == null || item == Items.AIR) {
            return current;
        }
        return mapped;
    }

    private void addPlacement(Map<BlockPos, Placement> target, int x, int y, int z, Block block) {
        if (target == null || block == null || block == Blocks.AIR) {
            return;
        }
        if (target.size() >= MAX_BLOCKS) {
            return;
        }
        Item item = block.asItem();
        if (item == null || item == Items.AIR) {
            return;
        }
        BlockPos rel = new BlockPos(x, y, z);
        target.put(rel, new Placement(rel, block, item));
    }

    private String extractJson(String rawPlan) {
        if (rawPlan == null) {
            return "";
        }
        String cleaned = rawPlan.trim();
        if (cleaned.startsWith("```")) {
            int lineBreak = cleaned.indexOf('\n');
            if (lineBreak >= 0 && lineBreak + 1 < cleaned.length()) {
                cleaned = cleaned.substring(lineBreak + 1);
            }
            int close = cleaned.lastIndexOf("```");
            if (close >= 0) {
                cleaned = cleaned.substring(0, close);
            }
            cleaned = cleaned.trim();
        }
        int startObj = cleaned.indexOf('{');
        int endObj = cleaned.lastIndexOf('}');
        if (startObj >= 0 && endObj > startObj) {
            return cleaned.substring(startObj, endObj + 1).trim();
        }
        int startArr = cleaned.indexOf('[');
        int endArr = cleaned.lastIndexOf(']');
        if (startArr >= 0 && endArr > startArr) {
            return cleaned.substring(startArr, endArr + 1).trim();
        }
        return "";
    }

    private JsonArray readArray(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) {
            return null;
        }
        try {
            JsonElement value = obj.get(key);
            return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer readIntLike(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) {
            return null;
        }
        try {
            return (int) Math.round(obj.get(key).getAsDouble());
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean readBooleanLike(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) {
            return false;
        }
        try {
            JsonElement value = obj.get(key);
            if (value == null || value.isJsonNull()) {
                return false;
            }
            if (value.isJsonPrimitive()) {
                if (value.getAsJsonPrimitive().isBoolean()) {
                    return value.getAsBoolean();
                }
                if (value.getAsJsonPrimitive().isNumber()) {
                    return value.getAsInt() != 0;
                }
                String text = value.getAsString().trim().toLowerCase(Locale.ROOT);
                return "true".equals(text) || "yes".equals(text) || "1".equals(text);
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private int[] readIntTriple(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) {
            return null;
        }
        try {
            JsonElement value = obj.get(key);
            if (value == null || !value.isJsonArray()) {
                return null;
            }
            JsonArray triple = value.getAsJsonArray();
            if (triple.size() < 3) {
                return null;
            }
            return new int[]{
                    (int) Math.round(triple.get(0).getAsDouble()),
                    (int) Math.round(triple.get(1).getAsDouble()),
                    (int) Math.round(triple.get(2).getAsDouble())
            };
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) {
            return null;
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String normalizeBlockId(String blockId) {
        if (blockId == null) {
            return "";
        }
        String id = blockId.trim()
                .toLowerCase(Locale.ROOT)
                .replace('\u0451', '\u0435')
                .replace(' ', '_')
                .replace('-', '_')
                .replace("\"", "")
                .replace("'", "");
        if (id.startsWith("minecraft:")) {
            id = id.substring("minecraft:".length());
        }
        id = switch (id) {
            case "pink_planks",
                 "pink_plank",
                 "pink_wood_planks",
                 "rose_planks",
                 "sakura_planks",
                 "sakura",
                 "cherry_plank",
                 "cherry_wood_planks",
                 "\u0441\u0430\u043a\u0443\u0440\u0430",
                 "\u0441\u0430\u043a\u0443\u0440\u0430_\u0434\u043e\u0441\u043a\u0438",
                 "\u0440\u043e\u0437\u043e\u0432\u044b\u0435_\u0434\u043e\u0441\u043a\u0438",
                 "\u0432\u0438\u0448\u043d\u0435\u0432\u044b\u0435_\u0434\u043e\u0441\u043a\u0438",
                 "\u0432\u0438\u0448\u043d\u0435\u0432\u0430\u044f_\u0434\u043e\u0441\u043a\u0430" -> "cherry_planks";
            case "pink_door",
                 "cherry_wood_door",
                 "sakura_door",
                 "\u0440\u043e\u0437\u043e\u0432\u0430\u044f_\u0434\u0432\u0435\u0440\u044c",
                 "\u0432\u0438\u0448\u043d\u0435\u0432\u0430\u044f_\u0434\u0432\u0435\u0440\u044c" -> "cherry_door";
            case "\u0431\u0443\u043b\u044b\u0436\u043d\u0438\u043a",
                 "\u0431\u0443\u043b\u044b\u0436\u043d\u0438\u043a\u0430",
                 "\u0431\u0443\u043b\u044b\u0436\u043d\u0438\u043a\u043e\u043c" -> "cobblestone";
            case "\u0434\u0443\u0431\u043e\u0432\u043e\u0435_\u0431\u0440\u0435\u0432\u043d\u043e",
                 "\u0434\u0443\u0431\u043e\u0432\u044b\u0435_\u0431\u0440\u0435\u0432\u043d\u0430" -> "oak_log";
            case "\u0435\u043b\u043e\u0432\u043e\u0435_\u0431\u0440\u0435\u0432\u043d\u043e",
                 "\u0435\u043b\u043e\u0432\u044b\u0435_\u0431\u0440\u0435\u0432\u043d\u0430" -> "spruce_log";
            case "\u0431\u0435\u0440\u0435\u0437\u043e\u0432\u043e\u0435_\u0431\u0440\u0435\u0432\u043d\u043e",
                 "\u0431\u0435\u0440\u0435\u0437\u043e\u0432\u044b\u0435_\u0431\u0440\u0435\u0432\u043d\u0430" -> "birch_log";
            case "\u0434\u0436\u0443\u043d\u0433\u043b\u0435\u0432\u043e\u0435_\u0431\u0440\u0435\u0432\u043d\u043e",
                 "\u0434\u0436\u0443\u043d\u0433\u043b\u0435\u0432\u044b\u0435_\u0431\u0440\u0435\u0432\u043d\u0430" -> "jungle_log";
            case "\u0430\u043a\u0430\u0446\u0438\u0435\u0432\u043e\u0435_\u0431\u0440\u0435\u0432\u043d\u043e",
                 "\u0430\u043a\u0430\u0446\u0438\u0435\u0432\u044b\u0435_\u0431\u0440\u0435\u0432\u043d\u0430" -> "acacia_log";
            case "\u0442\u0435\u043c\u043d\u043e\u0434\u0443\u0431\u043e\u0432\u043e\u0435_\u0431\u0440\u0435\u0432\u043d\u043e",
                 "\u0442\u0435\u043c\u043d\u043e\u0434\u0443\u0431\u043e\u0432\u044b\u0435_\u0431\u0440\u0435\u0432\u043d\u0430" -> "dark_oak_log";
            case "\u043c\u0430\u043d\u0433\u0440\u043e\u0432\u043e\u0435_\u0431\u0440\u0435\u0432\u043d\u043e",
                 "\u043c\u0430\u043d\u0433\u0440\u043e\u0432\u044b\u0435_\u0431\u0440\u0435\u0432\u043d\u0430" -> "mangrove_log";
            case "\u0432\u0438\u0448\u043d\u0435\u0432\u043e\u0435_\u0431\u0440\u0435\u0432\u043d\u043e",
                 "\u0432\u0438\u0448\u043d\u0435\u0432\u044b\u0435_\u0431\u0440\u0435\u0432\u043d\u0430",
                 "\u0441\u0430\u043a\u0443\u0440\u043e\u0432\u043e\u0435_\u0431\u0440\u0435\u0432\u043d\u043e",
                 "\u0441\u0430\u043a\u0443\u0440\u043e\u0432\u044b\u0435_\u0431\u0440\u0435\u0432\u043d\u0430" -> "cherry_log";
            case "\u0431\u0430\u0433\u0440\u043e\u0432\u043e\u0435_\u0431\u0440\u0435\u0432\u043d\u043e",
                 "\u0431\u0430\u0433\u0440\u043e\u0432\u044b\u0435_\u0431\u0440\u0435\u0432\u043d\u0430" -> "crimson_stem";
            case "\u0438\u0441\u043a\u0430\u0436\u0435\u043d\u043d\u043e\u0435_\u0431\u0440\u0435\u0432\u043d\u043e",
                 "\u0438\u0441\u043a\u0430\u0436\u0435\u043d\u043d\u044b\u0435_\u0431\u0440\u0435\u0432\u043d\u0430" -> "warped_stem";
            case "glow_stone",
                 "\u0441\u0432\u0435\u0442\u043e\u043a\u0430\u043c\u0435\u043d\u044c",
                 "\u0441\u0432\u0435\u0442\u043e\u043a\u0430\u043c\u0435\u043d" -> "glowstone";
            case "\u0444\u0430\u043a\u0435\u043b",
                 "\u0444\u0430\u043a\u0435\u043b\u044b" -> "torch";
            case "\u0444\u043e\u043d\u0430\u0440\u044c",
                 "\u0444\u043e\u043d\u0430\u0440\u0438" -> "lantern";
            default -> id;
        };
        return "minecraft:" + id;
    }

    private void promptResources(ServerPlayer player, Map<Item, Integer> missing) {
        if (missing == null || missing.isEmpty()) {
            startBuilding(player);
            return;
        }
        state = State.WAITING_PLAYER_RESOURCES;
        owner.sendReply(player, Component.translatable(K_RES_REMOVE));
        owner.sendReply(player, Component.translatable(K_RES_DETAILS, formatMissing(missing, 6)));
        owner.sendReply(player, Component.translatable(K_RES_WAIT_PLAYER));
        checkPlayerResources(player);
    }

    private void tryNpcGather(ServerPlayer player, boolean announce) {
        Map<Item, Integer> missing = computeMissing();
        if (missing.isEmpty()) {
            startBuilding(player);
            return;
        }
        if (!scheduleGatherFromMissing(player, missing, announce)) {
            owner.sendReply(player, Component.translatable(K_RES_WAIT_PLAYER));
            state = State.WAITING_PLAYER_RESOURCES;
        }
    }

    private boolean scheduleGatherFromMissing(ServerPlayer player, Map<Item, Integer> missing, boolean announce) {
        gatherQueue.clear();
        pendingGatherTask = null;
        List<String> unavailable = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
            Item item = entry.getKey();
            int amount = entry.getValue() == null ? 0 : entry.getValue();
            if (item == null || amount <= 0) {
                continue;
            }
            CompanionResourceType type = resolveType(item);
            if (type == null) {
                unavailable.add(itemName(item));
                continue;
            }
            CompanionTreeRequestMode treeMode = type == CompanionResourceType.LOG
                    ? CompanionTreeRequestMode.LOG_BLOCKS
                    : CompanionTreeRequestMode.NONE;
            int maxPerTask = Math.max(1, maxGatherAmount(type, treeMode));
            int rest = amount;
            while (rest > 0) {
                int chunk = Math.min(rest, maxPerTask);
                gatherQueue.addLast(new GatherTask(activePlayerId, type, chunk, treeMode, item));
                rest -= chunk;
            }
        }

        if (!unavailable.isEmpty()) {
            owner.sendReply(player, Component.translatable(K_RES_UNAVAILABLE, String.join(", ", unavailable)));
        }
        if (gatherQueue.isEmpty()) {
            return false;
        }
        scheduleNextGather(player, announce);
        return true;
    }

    private void scheduleNextGather(ServerPlayer player) {
        scheduleNextGather(player, true);
    }

    private void scheduleNextGather(ServerPlayer player, boolean announce) {
        if (gatherQueue.isEmpty()) {
            return;
        }
        pendingGatherTask = gatherQueue.pollFirst();
        state = State.WAITING_GATHER_START;
        if (announce && pendingGatherTask != null) {
            owner.sendReply(player, Component.translatable(K_GATHER_START,
                    itemName(pendingGatherTask.sourceItem), pendingGatherTask.amount));
        }
    }

    private Map<Item, Integer> computeMissing() {
        Map<Item, Integer> missing = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : remainingRequired.entrySet()) {
            Item item = entry.getKey();
            int need = entry.getValue() == null ? 0 : Math.max(0, entry.getValue());
            if (item == null || need <= 0) {
                continue;
            }
            int have = inventory.countItem(item);
            if (have < need) {
                missing.put(item, need - have);
            }
        }
        return missing;
    }

    private CompanionResourceType resolveType(Item item) {
        if (item == null || item == Items.AIR) {
            return null;
        }
        for (CompanionResourceType type : RESOURCE_PRIORITY) {
            if (type == null || type.isBucketResource()) {
                continue;
            }
            if (type.matchesItem(item.getDefaultInstance())) {
                return type;
            }
        }
        return null;
    }

    private int maxGatherAmount(CompanionResourceType type, CompanionTreeRequestMode treeMode) {
        if (treeMode == CompanionTreeRequestMode.TREE_COUNT) {
            return CompanionConfig.getMaxTreesPerTask();
        }
        if (isOre(type)) {
            return CompanionConfig.getMaxOresPerTask();
        }
        return CompanionConfig.getMaxBlocksPerTask();
    }

    private boolean isOre(CompanionResourceType type) {
        if (type == null) {
            return false;
        }
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

    private String formatMissing(Map<Item, Integer> missing, int limit) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        int max = Math.max(1, limit);
        for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            if (i > 0) {
                result.append(", ");
            }
            result.append(itemName(entry.getKey())).append(" x").append(entry.getValue());
            i++;
            if (i >= max) {
                break;
            }
        }
        if (missing.size() > max) {
            result.append(", ...");
        }
        return result.toString();
    }

    private String itemName(Item item) {
        if (item == null || item == Items.AIR) {
            return "Unknown";
        }
        String translated = item.getDescription().getString();
        if (translated != null && !translated.isBlank() && !translated.equals(item.getDescriptionId())) {
            return translated;
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        return key == null ? "Unknown" : key.getPath();
    }

    private BlockHitResult findLookedBlock(ServerPlayer player, double range) {
        if (player == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(range));
        BlockHitResult hit = player.level().clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return hit;
    }

    private ServerPlayer getActivePlayer() {
        if (activePlayerId == null) {
            return null;
        }
        Player player = owner.getPlayerById(activePlayerId);
        if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.isSpectator()) {
            return null;
        }
        return serverPlayer;
    }

    private boolean isBuildCommand(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        // Chat button tokens are service commands and must not be interpreted as "build house".
        if (normalized.startsWith("__") && normalized.endsWith("__")) {
            return false;
        }
        return containsAny(normalized,
                "\u043f\u043e\u0441\u0442\u0440\u043e\u0439",
                "\u043f\u043e\u0441\u0442\u0440\u043e\u0438",
                "\u0441\u0442\u0440\u043e\u0439 \u0434\u043e\u043c",
                "\u0434\u043e\u043c \u043f\u043e\u0441\u0442\u0440\u043e\u0439",
                "construct")
                || normalized.equals("build")
                || normalized.startsWith("build ")
                || normalized.contains(" build ");
    }

    private boolean isHereAnswer(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        return containsAny(normalized,
                "\u0441\u0442\u0440\u043e\u0438\u0442\u044c \u0437\u0434\u0435\u0441\u044c",
                "\u0441\u0442\u0440\u043e\u0439 \u0437\u0434\u0435\u0441\u044c",
                "build here")
                || normalized.equals("\u0437\u0434\u0435\u0441\u044c");
    }

    private boolean isPlayerGiveAnswer(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        return containsAny(normalized,
                "\u044f \u0434\u0430\u043c",
                "\u0434\u0430\u043c \u0440\u0435\u0441\u0443\u0440\u0441\u044b",
                "\u043f\u043e\u0434\u043e\u0436\u0434\u0438",
                "i give");
    }

    private boolean isGatherAnswer(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        return containsAny(normalized,
                "\u0434\u043e\u0431\u0443\u0434\u044c",
                "\u0434\u043e\u0431\u044b\u0432\u0430\u0439",
                "\u0441\u0430\u043c \u0434\u043e\u0431\u0443\u0434\u044c",
                "gather",
                "mine it");
    }

    private String normalize(String message) {
        return message == null
                ? ""
                : message.trim().toLowerCase(Locale.ROOT).replace('\u0451', '\u0435');
    }
}


