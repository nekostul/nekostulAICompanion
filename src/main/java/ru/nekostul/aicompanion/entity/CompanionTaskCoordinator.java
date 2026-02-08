package ru.nekostul.aicompanion.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import ru.nekostul.aicompanion.entity.command.CompanionCommandParser;
import ru.nekostul.aicompanion.entity.inventory.CompanionDeliveryController;
import ru.nekostul.aicompanion.entity.inventory.CompanionEquipment;
import ru.nekostul.aicompanion.entity.inventory.CompanionInventory;
import ru.nekostul.aicompanion.entity.inventory.CompanionInventoryExchange;
import ru.nekostul.aicompanion.entity.mining.CompanionGatheringController;
import ru.nekostul.aicompanion.entity.resource.CompanionResourceRequest;
import ru.nekostul.aicompanion.entity.resource.CompanionResourceType;
import ru.nekostul.aicompanion.entity.tree.CompanionTreeHarvestController;

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

    private final CompanionEntity owner;
    private final CompanionInventory inventory;
    private final CompanionEquipment equipment;
    private final CompanionGatheringController gathering;
    private final CompanionTreeHarvestController treeHarvest;
    private final CompanionDeliveryController delivery;
    private final CompanionBucketHandler bucketHandler;
    private final CompanionChestManager chestManager;
    private final CompanionCommandParser commandParser;
    private final CompanionHelpSystem helpSystem;
    private final CompanionInventoryExchange inventoryExchange;
    private final CompanionTorchHandler torchHandler;

    private CompanionResourceRequest activeRequest;
    private TaskState taskState = TaskState.IDLE;

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
        CompanionCommandParser.CommandRequest parsed = commandParser.parse(message);
        if (parsed == null) {
            return false;
        }
        if (taskState == TaskState.WAITING_TORCH_RESOURCES
                && parsed.getResourceType() == CompanionResourceType.TORCH) {
            return true;
        }
        startRequest(player, parsed);
        return true;
    }

    void tick(CompanionEntity.CompanionMode mode, long gameTime) {
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
                taskState = TaskState.DELIVERING;
                delivery.startDelivery();
            } else if (torchResult == CompanionTorchHandler.Result.TIMED_OUT) {
                activeRequest = null;
                taskState = TaskState.IDLE;
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
        activeRequest = new CompanionResourceRequest(player.getUUID(), parsed.getResourceType(), parsed.getAmount(),
                parsed.getTreeMode());
        if (parsed.getResourceType().isBucketResource()) {
            if (bucketHandler.ensureBuckets(activeRequest, player, owner.level().getGameTime())
                    == CompanionBucketHandler.BucketStatus.NEED_BUCKETS) {
                taskState = TaskState.WAITING_BUCKETS;
                return;
            }
        }
        taskState = TaskState.GATHERING;
        if (!activeRequest.isTreeCountRequest()) {
            delivery.startDelivery();
        }
    }

    private void tickGathering(Player player, long gameTime) {
        if (activeRequest == null) {
            taskState = TaskState.IDLE;
            return;
        }
        if (activeRequest.getResourceType() == CompanionResourceType.TORCH) {
            CompanionTorchHandler.Result torchResult = torchHandler.tick(activeRequest, player, gameTime);
            if (torchResult == CompanionTorchHandler.Result.READY) {
                taskState = TaskState.DELIVERING;
                delivery.startDelivery();
            } else if (torchResult == CompanionTorchHandler.Result.WAITING_RESOURCES) {
                taskState = TaskState.WAITING_TORCH_RESOURCES;
            } else if (torchResult == CompanionTorchHandler.Result.TIMED_OUT) {
                activeRequest = null;
                taskState = TaskState.IDLE;
            }
            return;
        }
        if (activeRequest.getResourceType().isBucketResource()) {
            CompanionBucketHandler.FillResult result = bucketHandler.tickFillBuckets(activeRequest, player, gameTime);
            if (result == CompanionBucketHandler.FillResult.DONE) {
                taskState = TaskState.DELIVERING;
                delivery.startDelivery();
                return;
            }
            if (result == CompanionBucketHandler.FillResult.NOT_FOUND) {
                owner.sendReply(player, Component.translatable(missingKey(activeRequest.getResourceType())));
                activeRequest = null;
                taskState = TaskState.IDLE;
                return;
            }
            return;
        }
        if (activeRequest.isTreeRequest()) {
            CompanionTreeHarvestController.Result treeResult = treeHarvest.tick(activeRequest, gameTime);
            if (treeResult == CompanionTreeHarvestController.Result.DONE) {
                if (activeRequest.isTreeCountRequest()) {
                    delivery.startDelivery(treeHarvest.takeCollectedDrops());
                    treeHarvest.resetAfterRequest();
                    taskState = TaskState.DELIVERING_ALL;
                    return;
                }
                treeHarvest.resetAfterRequest();
                taskState = TaskState.DELIVERING;
                delivery.startDelivery();
                return;
            }
            if (treeResult == CompanionTreeHarvestController.Result.NOT_FOUND) {
                owner.sendReply(player, Component.translatable(missingKey(activeRequest.getResourceType())));
                treeHarvest.resetAfterRequest();
                activeRequest = null;
                taskState = TaskState.IDLE;
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
            taskState = TaskState.DELIVERING;
            delivery.startDelivery();
            return;
        }
        if (result == CompanionGatheringController.Result.TOOL_REQUIRED) {
            activeRequest = null;
            taskState = TaskState.IDLE;
            return;
        }
        if (result == CompanionGatheringController.Result.NOT_FOUND) {
            owner.sendReply(player, Component.translatable(missingKey(activeRequest.getResourceType())));
            activeRequest = null;
            taskState = TaskState.IDLE;
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
            activeRequest = null;
            taskState = TaskState.IDLE;
        }
    }

    private void tickDeliveryAll(Player player, long gameTime) {
        if (activeRequest == null) {
            taskState = TaskState.IDLE;
            return;
        }
        if (delivery.tickDeliveryStacks(player, gameTime)) {
            activeRequest = null;
            taskState = TaskState.IDLE;
        }
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
}
