package ru.nekostul.aicompanion.entity;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import ru.nekostul.aicompanion.CompanionConfig;
import ru.nekostul.aicompanion.entity.command.CompanionCommandParser;
import ru.nekostul.aicompanion.entity.inventory.CompanionDeliveryController;
import ru.nekostul.aicompanion.entity.inventory.CompanionEquipment;
import ru.nekostul.aicompanion.entity.inventory.CompanionInventory;
import ru.nekostul.aicompanion.entity.inventory.CompanionInventoryExchange;
import ru.nekostul.aicompanion.entity.mining.CompanionGatheringController;
import ru.nekostul.aicompanion.entity.resource.CompanionResourceRequest;
import ru.nekostul.aicompanion.entity.resource.CompanionResourceType;
import ru.nekostul.aicompanion.entity.tree.CompanionTreeHarvestController;
import ru.nekostul.aicompanion.entity.tree.CompanionTreeRequestMode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

final class CompanionTaskCoordinator {
    private enum TaskState {
        IDLE,
        WAITING_BUCKETS,
        WAITING_TORCH_RESOURCES,
        WAITING_CHEST,
        GATHERING,
        DELIVERING,
        DELIVERING_ALL
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int VILLAGE_POI_RADIUS = 32;
    private static final TagKey<PoiType> VILLAGE_POI_TAG = TagKey.create(
            Registries.POINT_OF_INTEREST_TYPE,
            ResourceLocation.fromNamespaceAndPath("minecraft", "village"));
    private static final String TREE_FAIL_KEY = "entity.aicompanion.companion.tree.harvest.failed";
    private static final String TREE_NOT_FOUND_KEY = "entity.aicompanion.companion.tree.harvest.not_found";
    private static final String TREE_RETRY_OFFER_KEY = "entity.aicompanion.companion.tree.retry.offer";
    private static final String TREE_RETRY_BUTTON_KEY = "entity.aicompanion.companion.tree.retry.button";
    private static final String TREE_RETRY_REMOVE_KEY = "entity.aicompanion.companion.tree.retry.remove";
    private static final String TREE_VILLAGE_BLOCK_KEY = "entity.aicompanion.companion.tree.harvest.village_block";
    private static final String GATHER_FAIL_KEY = "entity.aicompanion.companion.gather.failed";
    private static final String TREE_RETRY_CLICK_TOKEN = "__TREE_RETRY__";
    private static final int TREE_RETRY_TICKS = 5 * 20;
    private static final String BLOCK_LIMIT_KEY = "entity.aicompanion.companion.gather.limit.blocks";
    private static final String TREE_LIMIT_KEY = "entity.aicompanion.companion.gather.limit.trees";
    private static final String ORE_LIMIT_KEY = "entity.aicompanion.companion.gather.limit.ore";
    private static final String SEQUENCE_ACCEPTED_KEY = "entity.aicompanion.companion.sequence.accepted";
    private static final String SEQUENCE_DONE_KEY = "entity.aicompanion.companion.sequence.done";
    private static final String SEQUENCE_TASK_FAIL_KEY = "entity.aicompanion.companion.sequence.task.failed";
    private static final String SEQUENCE_PARSE_FAILED_KEY = "entity.aicompanion.companion.sequence.parse.failed";
    private static final String SEQUENCE_PARSE_REASON_ACTION_KEY =
            "entity.aicompanion.companion.sequence.parse.reason.action";
    private static final String SEQUENCE_PARSE_REASON_RESOURCE_KEY =
            "entity.aicompanion.companion.sequence.parse.reason.resource";
    private static final String SEQUENCE_PARSE_REASON_AMOUNT_KEY =
            "entity.aicompanion.companion.sequence.parse.reason.amount";
    private static final String SEQUENCE_PARSE_REASON_GENERIC_KEY =
            "entity.aicompanion.companion.sequence.parse.reason.generic";

    private final CompanionEntity owner;
    private final CompanionInventory inventory;
    private final CompanionEquipment equipment;
    private final CompanionGatheringController gathering;
    private final CompanionTreeHarvestController treeHarvest;
    private final CompanionDeliveryController delivery;
    private final CompanionBucketHandler bucketHandler;
    private final CompanionChestManager chestManager;
    private final CompanionCommandParser commandParser;
    private final CompanionTaskSequenceParser sequenceParser;
    private final CompanionHelpSystem helpSystem;
    private final CompanionInventoryExchange inventoryExchange;
    private final CompanionTorchHandler torchHandler;

    private CompanionResourceRequest activeRequest;
    private TaskState taskState = TaskState.IDLE;
    private final Deque<CompanionTaskSequenceParser.SequenceTask> queuedSequenceTasks = new ArrayDeque<>();
    private UUID sequencePlayerId;
    private CompanionTaskSequenceParser.SequenceTask currentSequenceTask;
    private int sequenceTotalTasks;
    private int sequenceCompletedTasks;
    private boolean sequenceDelivering;
    private final List<ItemStack> sequencePendingDrops = new ArrayList<>();
    private UUID pendingTreeRetryPlayerId;
    private long pendingTreeRetryUntilTick = -1L;
    private int pendingTreeRetryLastSeconds = -1;
    private CompanionResourceType pendingTreeRetryType;
    private int pendingTreeRetryAmount;
    private CompanionTreeRequestMode pendingTreeRetryTreeMode = CompanionTreeRequestMode.NONE;

    CompanionTaskCoordinator(CompanionEntity owner,
                             CompanionInventory inventory,
                             CompanionEquipment equipment,
                             CompanionGatheringController gathering,
                             CompanionTreeHarvestController treeHarvest,
                             CompanionDeliveryController delivery,
                             CompanionBucketHandler bucketHandler,
                             CompanionChestManager chestManager,
                             CompanionCommandParser commandParser,
                             CompanionHelpSystem helpSystem,
                             CompanionInventoryExchange inventoryExchange,
                             CompanionTorchHandler torchHandler) {
        this.owner = owner;
        this.inventory = inventory;
        this.equipment = equipment;
        this.gathering = gathering;
        this.treeHarvest = treeHarvest;
        this.delivery = delivery;
        this.bucketHandler = bucketHandler;
        this.chestManager = chestManager;
        this.commandParser = commandParser;
        this.sequenceParser = new CompanionTaskSequenceParser(commandParser);
        this.helpSystem = helpSystem;
        this.inventoryExchange = inventoryExchange;
        this.torchHandler = torchHandler;
    }

    boolean handlePlayerMessage(ServerPlayer player, String message) {
        if (helpSystem.handleHelp(player, message)) {
            return true;
        }
        if (chestManager.handleChestAssignment(player, message)) {
            return true;
        }
        if (inventoryExchange.handleMessage(player, message)) {
            return true;
        }
        if (handleTreeRetryClick(player, message)) {
            return true;
        }
        CompanionTaskSequenceParser.SequenceParseResult sequenceResult = sequenceParser.parse(message);
        if (sequenceResult.isSequenceCommand()) {
            if (!sequenceResult.isValid()) {
                Component reason = Component.translatable(parseReasonKey(sequenceResult.parseError()));
                String failedSegment = sequenceResult.failedSegment().isBlank()
                        ? CompanionTaskSequenceParser.normalizeTaskText(message)
                        : sequenceResult.failedSegment();
                owner.sendReply(player, Component.translatable(SEQUENCE_PARSE_FAILED_KEY, failedSegment, reason));
                LOGGER.debug("task-sequence parse failed: npc={} player={} segment='{}' error={}",
                        owner.getUUID(), player.getUUID(), failedSegment, sequenceResult.parseError());
                return true;
            }
            clearTreeRetryPrompt(player, true);
            startSequence(player, sequenceResult.tasks());
            return true;
        }
        CompanionCommandParser.CommandRequest parsed = commandParser.parse(message);
        if (parsed == null) {
            return false;
        }
        if (taskState == TaskState.WAITING_TORCH_RESOURCES
                && parsed.getResourceType() == CompanionResourceType.TORCH) {
            return true;
        }
        clearTreeRetryPrompt(player, true);
        clearTaskSequence();
        startRequest(player, parsed);
        return true;
    }

    void tick(CompanionEntity.CompanionMode mode, long gameTime) {
        tickTreeRetryPrompt(gameTime);
        if (activeRequest == null) {
            return;
        }
        Player player = owner.getPlayerById(activeRequest.getPlayerId());
        if (player == null) {
            return;
        }
        chestManager.tick(player);
        if (mode == CompanionEntity.CompanionMode.STOPPED) {
            owner.getNavigation().stop();
            return;
        }
        if (sequenceDelivering && taskState == TaskState.DELIVERING_ALL) {
            tickDeliveryAll(player, gameTime);
            return;
        }
        if (inventory.isFull()) {
            chestManager.requestChest(player, gameTime);
            if (chestManager.hasChest()) {
                chestManager.depositToChest();
            }
            taskState = TaskState.WAITING_CHEST;
            return;
        }
        if (taskState == TaskState.WAITING_CHEST && !inventory.isFull()) {
            taskState = TaskState.GATHERING;
        }
        if (taskState == TaskState.WAITING_BUCKETS) {
            if (bucketHandler.ensureBuckets(activeRequest, player, gameTime) == CompanionBucketHandler.BucketStatus.READY) {
                taskState = TaskState.GATHERING;
            } else {
                return;
            }
        }
        if (taskState == TaskState.WAITING_TORCH_RESOURCES) {
            CompanionTorchHandler.Result torchResult = torchHandler.tick(activeRequest, player, gameTime);
            if (torchResult == CompanionTorchHandler.Result.READY) {
                if (isSequenceGatherPhaseActive()) {
                    finishGatheringStep(player, List.of());
                } else {
                    taskState = TaskState.DELIVERING;
                    delivery.startDelivery();
                }
            } else if (torchResult == CompanionTorchHandler.Result.TIMED_OUT) {
                failActiveTask(player, "torch_timeout_waiting_resources");
            }
            return;
        }
        switch (taskState) {
            case GATHERING -> tickGathering(player, gameTime);
            case DELIVERING -> tickDelivery(player, gameTime);
            case DELIVERING_ALL -> tickDeliveryAll(player, gameTime);
            default -> {
            }
        }
    }

    boolean isBusy() {
        return activeRequest != null && taskState != TaskState.IDLE
                && taskState != TaskState.WAITING_BUCKETS
                && taskState != TaskState.WAITING_TORCH_RESOURCES;
    }

    void onInventoryUpdated() {
        equipment.equipBestArmor();
    }

    private void startRequest(Player player, CompanionCommandParser.CommandRequest parsed) {
        if (parsed.getTreeMode() != null
                && parsed.getTreeMode() != CompanionTreeRequestMode.NONE
                && isPlayerInVillage(player)) {
            owner.sendReply(player, Component.translatable(TREE_VILLAGE_BLOCK_KEY));
            resetActiveTask();
            return;
        }
        int amount = clampTaskAmount(parsed, player);
        activeRequest = new CompanionResourceRequest(player.getUUID(), parsed.getResourceType(), amount,
                parsed.getTreeMode());
        if (parsed.getResourceType().isBucketResource()) {
            if (bucketHandler.ensureBuckets(activeRequest, player, owner.level().getGameTime())
                    == CompanionBucketHandler.BucketStatus.NEED_BUCKETS) {
                taskState = TaskState.WAITING_BUCKETS;
                return;
            }
        }
        taskState = TaskState.GATHERING;
        if (!activeRequest.isTreeCountRequest() && !isSequenceGatherPhaseActive()) {
            delivery.startDelivery();
        }
    }

    private int clampTaskAmount(CompanionCommandParser.CommandRequest parsed, Player player) {
        int requested = parsed.getAmount();
        if (requested <= 0) {
            return requested;
        }
        if (parsed.getTreeMode() == CompanionTreeRequestMode.TREE_COUNT) {
            int maxTrees = CompanionConfig.getMaxTreesPerTask();
            if (requested > maxTrees) {
                if (player != null) {
                    owner.sendReply(player, limitMessage(TREE_LIMIT_KEY, maxTrees));
                }
                return maxTrees;
            }
            return requested;
        }
        if (isOreRequest(parsed.getResourceType())) {
            int maxOres = CompanionConfig.getMaxOresPerTask();
            if (requested > maxOres) {
                if (player != null) {
                    owner.sendReply(player, Component.translatable(ORE_LIMIT_KEY, maxOres));
                }
                return maxOres;
            }
            return requested;
        }
        if (isRegularBlockRequest(parsed.getResourceType())) {
            int maxBlocks = CompanionConfig.getMaxBlocksPerTask();
            if (requested > maxBlocks) {
                if (player != null) {
                    owner.sendReply(player, limitMessage(BLOCK_LIMIT_KEY, maxBlocks));
                }
                return maxBlocks;
            }
        }
        return requested;
    }

    private boolean isRegularBlockRequest(CompanionResourceType type) {
        if (type == null) {
            return false;
        }
        if (type.isBucketResource()) {
            return false;
        }
        if (isOreRequest(type)) {
            return false;
        }
        return type != CompanionResourceType.TORCH;
    }

    private boolean isOreRequest(CompanionResourceType type) {
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

    private void tickGathering(Player player, long gameTime) {
        if (activeRequest == null) {
            taskState = TaskState.IDLE;
            return;
        }
        if (activeRequest.getResourceType() == CompanionResourceType.TORCH) {
            CompanionTorchHandler.Result torchResult = torchHandler.tick(activeRequest, player, gameTime);
            if (torchResult == CompanionTorchHandler.Result.READY) {
                if (isSequenceGatherPhaseActive()) {
                    finishGatheringStep(player, List.of());
                } else {
                    taskState = TaskState.DELIVERING;
                    delivery.startDelivery();
                }
            } else if (torchResult == CompanionTorchHandler.Result.WAITING_RESOURCES) {
                taskState = TaskState.WAITING_TORCH_RESOURCES;
            } else if (torchResult == CompanionTorchHandler.Result.TIMED_OUT) {
                failActiveTask(player, "torch_timeout_gathering");
            }
            return;
        }
        if (activeRequest.getResourceType().isBucketResource()) {
            CompanionBucketHandler.FillResult result = bucketHandler.tickFillBuckets(activeRequest, player, gameTime);
            if (result == CompanionBucketHandler.FillResult.DONE) {
                if (isSequenceGatherPhaseActive()) {
                    finishGatheringStep(player, List.of());
                } else {
                    taskState = TaskState.DELIVERING;
                    delivery.startDelivery();
                }
                return;
            }
            if (result == CompanionBucketHandler.FillResult.NOT_FOUND) {
                owner.sendReply(player, Component.translatable(missingKey(activeRequest.getResourceType())));
                failActiveTask(player, "bucket_source_not_found");
                return;
            }
            return;
        }
        if (activeRequest.isTreeRequest()) {
            CompanionTreeHarvestController.Result treeResult = treeHarvest.tick(activeRequest, gameTime);
            if (treeResult == CompanionTreeHarvestController.Result.DONE) {
                if (activeRequest.isTreeCountRequest()) {
                    List<ItemStack> treeDrops = treeHarvest.takeCollectedDrops();
                    treeHarvest.resetAfterRequest();
                    if (isSequenceGatherPhaseActive()) {
                        finishGatheringStep(player, treeDrops);
                    } else {
                        delivery.startDelivery(treeDrops);
                        taskState = TaskState.DELIVERING_ALL;
                    }
                    return;
                }
                treeHarvest.resetAfterRequest();
                if (isSequenceGatherPhaseActive()) {
                    finishGatheringStep(player, List.of());
                } else {
                    taskState = TaskState.DELIVERING;
                    delivery.startDelivery();
                }
                return;
            }
            if (treeResult == CompanionTreeHarvestController.Result.FAILED) {
                CompanionResourceRequest failedTreeRequest = activeRequest;
                treeHarvest.resetAfterRequest();
                failActiveTask(player, "tree_failed");
                owner.setMode(CompanionEntity.CompanionMode.FOLLOW);
                boolean offeredRetry = false;
                if (player instanceof ServerPlayer serverPlayer && sequencePlayerId == null) {
                    offeredRetry = offerTreeRetry(serverPlayer, failedTreeRequest, gameTime);
                }
                if (!offeredRetry) {
                    owner.sendReply(player, Component.translatable(TREE_FAIL_KEY));
                }
                return;
            }
            if (treeResult == CompanionTreeHarvestController.Result.NOT_FOUND) {
                owner.sendReply(player, Component.translatable(TREE_NOT_FOUND_KEY));
                treeHarvest.resetAfterRequest();
                failActiveTask(player, "tree_not_found");
                return;
            }
            if (treeResult == CompanionTreeHarvestController.Result.NEED_CHEST) {
                chestManager.requestChest(player, gameTime);
                if (chestManager.hasChest()) {
                    chestManager.depositToChest();
                }
                taskState = TaskState.WAITING_CHEST;
            }
            return;
        }
        CompanionGatheringController.Result result = gathering.tick(activeRequest, gameTime);
        if (result == CompanionGatheringController.Result.DONE) {
            if (isSequenceGatherPhaseActive()) {
                finishGatheringStep(player, List.of());
            } else {
                taskState = TaskState.DELIVERING;
                delivery.startDelivery();
            }
            return;
        }
        if (result == CompanionGatheringController.Result.TOOL_REQUIRED) {
            failActiveTask(player, "tool_required");
            return;
        }
        if (result == CompanionGatheringController.Result.FAILED) {
            CompanionResourceRequest failedGatherRequest = activeRequest;
            failActiveTask(player, "gather_failed");
            boolean offeredRetry = false;
            if (player instanceof ServerPlayer serverPlayer && sequencePlayerId == null) {
                offeredRetry = offerTreeRetry(serverPlayer, failedGatherRequest, gameTime);
            }
            if (!offeredRetry) {
                owner.sendReply(player, Component.translatable(GATHER_FAIL_KEY));
            }
            return;
        }
        if (result == CompanionGatheringController.Result.NOT_FOUND) {
            owner.sendReply(player, Component.translatable(missingKey(activeRequest.getResourceType())));
            failActiveTask(player, "resource_not_found");
            return;
        }
        if (result == CompanionGatheringController.Result.NEED_CHEST) {
            chestManager.requestChest(player, gameTime);
            if (chestManager.hasChest()) {
                chestManager.depositToChest();
            }
            taskState = TaskState.WAITING_CHEST;
        }
    }

    private void tickDelivery(Player player, long gameTime) {
        if (activeRequest == null) {
            taskState = TaskState.IDLE;
            return;
        }
        if (delivery.tickDelivery(activeRequest, player, gameTime)) {
            finishActiveTaskSuccessfully(player);
        }
    }

    private void tickDeliveryAll(Player player, long gameTime) {
        if (activeRequest == null) {
            taskState = TaskState.IDLE;
            return;
        }
        if (delivery.tickDeliveryStacks(player, gameTime)) {
            finishActiveTaskSuccessfully(player);
        }
    }

    private void startSequence(ServerPlayer player, List<CompanionTaskSequenceParser.SequenceTask> tasks) {
        resetActiveTask();
        clearTaskSequence();
        sequencePlayerId = player.getUUID();
        queuedSequenceTasks.addAll(tasks);
        sequenceTotalTasks = tasks.size();
        sequenceCompletedTasks = 0;
        owner.sendReply(player, Component.translatable(SEQUENCE_ACCEPTED_KEY, tasks.size()));
        LOGGER.debug("task-sequence accepted: npc={} player={} tasks={}",
                owner.getUUID(), player.getUUID(), tasks.size());
        if (!startNextSequenceTask(player) && sequencePlayerId != null) {
            clearTaskSequence();
        }
    }

    private boolean startNextSequenceTask(Player player) {
        if (sequencePlayerId == null || player == null || !sequencePlayerId.equals(player.getUUID())) {
            clearTaskSequence();
            return false;
        }
        CompanionTaskSequenceParser.SequenceTask nextTask = queuedSequenceTasks.pollFirst();
        if (nextTask == null) {
            return false;
        }
        currentSequenceTask = nextTask;
        CompanionCommandParser.CommandRequest request = nextTask.request();
        LOGGER.debug("task-sequence step start: npc={} player={} step={}/{} action={} target={} amount={} text='{}'",
                owner.getUUID(),
                player.getUUID(),
                nextTask.order(),
                sequenceTotalTasks,
                request.getTaskAction(),
                request.getResourceType(),
                request.getAmount(),
                nextTask.originalText());
        startRequest(player, request);
        if (activeRequest == null || taskState == TaskState.IDLE) {
            failActiveTask(player, "request_rejected_on_start");
            return false;
        }
        return true;
    }

    private void finishActiveTaskSuccessfully(Player player) {
        CompanionTaskSequenceParser.SequenceTask completedTask = currentSequenceTask;
        resetActiveTask();
        if (sequencePlayerId == null) {
            return;
        }
        if (completedTask != null && player != null) {
            sequenceCompletedTasks = Math.min(sequenceTotalTasks, sequenceCompletedTasks + 1);
            LOGGER.debug("task-sequence step done: npc={} player={} step={}/{} text='{}'",
                    owner.getUUID(),
                    player.getUUID(),
                    sequenceCompletedTasks,
                    sequenceTotalTasks,
                    completedTask.originalText());
        }
        currentSequenceTask = null;
        if (sequenceDelivering) {
            if (sequencePlayerId != null && player != null) {
                owner.sendReply(player, Component.translatable(SEQUENCE_DONE_KEY));
                LOGGER.debug("task-sequence completed: npc={} player={} tasks={} completedSteps={}",
                        owner.getUUID(), player.getUUID(), sequenceTotalTasks, sequenceCompletedTasks);
            }
            clearTaskSequence();
            return;
        }
        if (startNextSequenceTask(player)) {
            return;
        }
        if (sequencePlayerId != null && player != null) {
            startSequenceFinalDelivery(player);
        }
    }

    private void failActiveTask(Player player, String debugReason) {
        CompanionTaskSequenceParser.SequenceTask failedTask = currentSequenceTask;
        resetActiveTask();
        owner.getNavigation().stop();
        if (sequencePlayerId != null && failedTask != null && player != null) {
            owner.sendReply(player, Component.translatable(SEQUENCE_TASK_FAIL_KEY, failedTask.originalText()));
            LOGGER.debug("task-sequence step failed: npc={} player={} step={}/{} text='{}' reason={}",
                    owner.getUUID(),
                    player.getUUID(),
                    failedTask.order(),
                    sequenceTotalTasks,
                    failedTask.originalText(),
                    debugReason);
            clearTaskSequence();
            return;
        }
        clearTaskSequence();
    }

    private void clearTaskSequence() {
        queuedSequenceTasks.clear();
        sequencePlayerId = null;
        currentSequenceTask = null;
        sequenceTotalTasks = 0;
        sequenceCompletedTasks = 0;
        sequenceDelivering = false;
        sequencePendingDrops.clear();
    }

    private void resetActiveTask() {
        activeRequest = null;
        taskState = TaskState.IDLE;
    }

    private boolean handleTreeRetryClick(ServerPlayer player, String message) {
        if (player == null || message == null || !TREE_RETRY_CLICK_TOKEN.equalsIgnoreCase(message.trim())) {
            return false;
        }
        if (pendingTreeRetryPlayerId == null || !pendingTreeRetryPlayerId.equals(player.getUUID())) {
            return true;
        }
        long gameTime = owner.level().getGameTime();
        if (gameTime >= pendingTreeRetryUntilTick || !hasPendingTreeRetryRequest()) {
            clearTreeRetryPrompt(player, true);
            return true;
        }
        if (activeRequest != null) {
            clearTreeRetryPrompt(player, true);
            return true;
        }
        if (pendingTreeRetryTreeMode != CompanionTreeRequestMode.NONE && isPlayerInVillage(player)) {
            clearTreeRetryPrompt(player, true);
            owner.sendReply(player, Component.translatable(TREE_VILLAGE_BLOCK_KEY));
            return true;
        }
        CompanionResourceType retryType = pendingTreeRetryType;
        int retryAmount = pendingTreeRetryAmount;
        CompanionTreeRequestMode retryMode = pendingTreeRetryTreeMode;
        if (retryType == null || retryAmount <= 0 || retryMode == null) {
            clearTreeRetryPrompt(player, true);
            return true;
        }
        clearTreeRetryPrompt(player, true);
        clearTaskSequence();
        activeRequest = new CompanionResourceRequest(
                player.getUUID(),
                retryType,
                retryAmount,
                retryMode
        );
        taskState = TaskState.GATHERING;
        if (!activeRequest.isTreeCountRequest()) {
            delivery.startDelivery();
        }
        return true;
    }

    private boolean offerTreeRetry(ServerPlayer player, CompanionResourceRequest failedRequest, long gameTime) {
        if (player == null || failedRequest == null) {
            return false;
        }
        pendingTreeRetryPlayerId = player.getUUID();
        pendingTreeRetryType = failedRequest.getResourceType();
        pendingTreeRetryAmount = failedRequest.getAmount();
        pendingTreeRetryTreeMode = failedRequest.getTreeMode();
        pendingTreeRetryUntilTick = gameTime + TREE_RETRY_TICKS;
        pendingTreeRetryLastSeconds = -1;
        int secondsLeft = secondsLeft(pendingTreeRetryUntilTick, gameTime);
        sendTreeRetryMessage(player, secondsLeft);
        pendingTreeRetryLastSeconds = secondsLeft;
        return true;
    }

    private void tickTreeRetryPrompt(long gameTime) {
        if (pendingTreeRetryPlayerId == null) {
            return;
        }
        Player player = owner.getPlayerById(pendingTreeRetryPlayerId);
        if (!(player instanceof ServerPlayer serverPlayer)) {
            clearTreeRetryState();
            return;
        }
        if (gameTime >= pendingTreeRetryUntilTick || !hasPendingTreeRetryRequest()) {
            clearTreeRetryPrompt(serverPlayer, true);
            return;
        }
        int secondsLeft = secondsLeft(pendingTreeRetryUntilTick, gameTime);
        if (secondsLeft != pendingTreeRetryLastSeconds) {
            pendingTreeRetryLastSeconds = secondsLeft;
            sendTreeRetryMessage(serverPlayer, secondsLeft);
        }
    }

    private boolean hasPendingTreeRetryRequest() {
        return pendingTreeRetryType != null
                && pendingTreeRetryAmount > 0
                && pendingTreeRetryTreeMode != null;
    }

    private void clearTreeRetryPrompt(ServerPlayer player, boolean notifyRemove) {
        if (notifyRemove && player != null) {
            owner.sendReply(player, Component.translatable(TREE_RETRY_REMOVE_KEY));
        }
        clearTreeRetryState();
    }

    private void clearTreeRetryState() {
        pendingTreeRetryPlayerId = null;
        pendingTreeRetryUntilTick = -1L;
        pendingTreeRetryLastSeconds = -1;
        pendingTreeRetryType = null;
        pendingTreeRetryAmount = 0;
        pendingTreeRetryTreeMode = CompanionTreeRequestMode.NONE;
    }

    private void sendTreeRetryMessage(ServerPlayer player, int secondsLeft) {
        if (player == null) {
            return;
        }
        owner.sendReply(player, Component.translatable(TREE_RETRY_REMOVE_KEY));
        MutableComponent base = Component.translatable(TREE_RETRY_OFFER_KEY, secondsLeft);
        Component button = Component.translatable(TREE_RETRY_BUTTON_KEY)
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/ainpc msg " + TREE_RETRY_CLICK_TOKEN)));
        owner.sendReply(player, base.append(Component.literal(" ")).append(button));
    }

    private int secondsLeft(long untilTick, long gameTime) {
        long diff = Math.max(0L, untilTick - gameTime);
        return (int) ((diff + 19L) / 20L);
    }

    private void finishGatheringStep(Player player, List<ItemStack> extraDrops) {
        if (!isSequenceGatherPhaseActive()) {
            taskState = TaskState.DELIVERING;
            delivery.startDelivery();
            return;
        }
        stageCurrentTaskDrops(extraDrops);
        finishActiveTaskSuccessfully(player);
    }

    private void stageCurrentTaskDrops(List<ItemStack> extraDrops) {
        if (activeRequest == null) {
            return;
        }
        if (activeRequest.isTreeCountRequest()) {
            appendPendingDrops(extraDrops);
            return;
        }
        List<ItemStack> staged = inventory.takeMatching(
                activeRequest.getResourceType()::matchesItem,
                activeRequest.getAmount());
        appendPendingDrops(staged);
    }

    private void appendPendingDrops(List<ItemStack> drops) {
        if (drops == null || drops.isEmpty()) {
            return;
        }
        for (ItemStack stack : drops) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            sequencePendingDrops.add(stack.copy());
        }
    }

    private boolean isSequenceGatherPhaseActive() {
        return sequencePlayerId != null && !sequenceDelivering;
    }

    private void startSequenceFinalDelivery(Player player) {
        if (player == null || sequencePlayerId == null || !sequencePlayerId.equals(player.getUUID())) {
            clearTaskSequence();
            return;
        }
        if (sequencePendingDrops.isEmpty()) {
            owner.sendReply(player, Component.translatable(SEQUENCE_DONE_KEY));
            LOGGER.debug("task-sequence completed without drops: npc={} player={} tasks={}",
                    owner.getUUID(), player.getUUID(), sequenceTotalTasks);
            clearTaskSequence();
            return;
        }
        List<ItemStack> toDeliver = new ArrayList<>(sequencePendingDrops.size());
        for (ItemStack stack : sequencePendingDrops) {
            if (stack != null && !stack.isEmpty()) {
                toDeliver.add(stack.copy());
            }
        }
        sequencePendingDrops.clear();
        sequenceDelivering = true;
        activeRequest = new CompanionResourceRequest(player.getUUID(), CompanionResourceType.LOG, 1,
                CompanionTreeRequestMode.NONE);
        taskState = TaskState.DELIVERING_ALL;
        delivery.startDelivery(toDeliver);
        LOGGER.debug("task-sequence delivery start: npc={} player={} stacks={} items={}",
                owner.getUUID(), player.getUUID(), toDeliver.size(), countItems(toDeliver));
    }

    private int countItems(List<ItemStack> stacks) {
        int total = 0;
        if (stacks == null) {
            return 0;
        }
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            total += stack.getCount();
        }
        return total;
    }

    private String parseReasonKey(CompanionCommandParser.ParseError error) {
        return switch (error) {
            case MISSING_ACTION -> SEQUENCE_PARSE_REASON_ACTION_KEY;
            case MISSING_RESOURCE -> SEQUENCE_PARSE_REASON_RESOURCE_KEY;
            case INVALID_AMOUNT -> SEQUENCE_PARSE_REASON_AMOUNT_KEY;
            case NONE, EMPTY_MESSAGE -> SEQUENCE_PARSE_REASON_GENERIC_KEY;
        };
    }

    private String missingKey(CompanionResourceType type) {
        if (type == CompanionResourceType.WATER) {
            return "entity.aicompanion.companion.bucket.missing.water";
        }
        if (type == CompanionResourceType.LAVA) {
            return "entity.aicompanion.companion.bucket.missing.lava";
        }
        return "entity.aicompanion.companion.gather.missing";
    }

    private boolean isPlayerInVillage(Player player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        PoiManager poiManager = serverLevel.getPoiManager();
        return poiManager.getInRange(holder -> holder.is(VILLAGE_POI_TAG),
                player.blockPosition(),
                VILLAGE_POI_RADIUS,
                PoiManager.Occupancy.ANY)
                .findAny()
                .isPresent();
    }

    private Component limitMessage(String baseKey, int amount) {
        return Component.translatable(baseKey + "." + pluralCategory(amount), amount);
    }

    private String pluralCategory(int amount) {
        int value = Math.abs(amount);
        int mod10 = value % 10;
        int mod100 = value % 100;
        if (mod10 == 1 && mod100 != 11) {
            return "one";
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return "few";
        }
        return "many";
    }
}
