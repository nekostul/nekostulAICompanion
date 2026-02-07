package ru.nekostul.aicompanion.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import ru.nekostul.aicompanion.entity.CompanionEntity;
import ru.nekostul.aicompanion.entity.inventory.CompanionInventory;
import ru.nekostul.aicompanion.entity.resource.CompanionResourceRequest;
import ru.nekostul.aicompanion.entity.resource.CompanionResourceType;

public final class CompanionTorchHandler {
    public enum Result {
        IDLE,
        READY,
        WAITING_RESOURCES,
        TIMED_OUT
    }

    private static final int TORCHES_PER_CRAFT = 4;
    private static final int REQUEST_COOLDOWN_TICKS = 1200;
    private static final int WAIT_TIMEOUT_TICKS = 100;
    private static final String NEED_BOTH_KEY = "entity.aicompanion.companion.torch.need.both";
    private static final String NEED_COAL_ONLY_KEY = "entity.aicompanion.companion.torch.need.coal.only";
    private static final String NEED_STICKS_ONLY_KEY = "entity.aicompanion.companion.torch.need.sticks.only";
    private static final String READY_KEY = "entity.aicompanion.companion.torch.ready";

    private final CompanionEntity owner;
    private final CompanionInventory inventory;
    private long nextRequestTick = -1L;
    private long waitingUntilTick = -1L;
    private boolean waitingActive;
    private java.util.UUID activePlayerId;
    private int activeAmount;
    private int waitingCrafts;

    public CompanionTorchHandler(CompanionEntity owner, CompanionInventory inventory) {
        this.owner = owner;
        this.inventory = inventory;
    }

    public Result tick(CompanionResourceRequest request, Player player, long gameTime) {
        if (request == null || request.getResourceType() != CompanionResourceType.TORCH) {
            resetState();
            return Result.IDLE;
        }
        if (waitingActive && !isSameRequest(request)) {
            if (gameTime >= waitingUntilTick) {
                resetState();
            } else {
                return Result.WAITING_RESOURCES;
            }
        }
        if (!isSameRequest(request)) {
            activePlayerId = request.getPlayerId();
            activeAmount = request.getAmount();
            resetWaiting();
        }
        int availableTorches = inventory.countItem(Items.TORCH);
        int neededTorches = Math.max(0, request.getAmount() - availableTorches);
        if (neededTorches <= 0) {
            if (!waitingActive) {
                sendReady(player);
            }
            resetWaiting();
            return Result.READY;
        }
        if (waitingActive) {
            if (gameTime >= waitingUntilTick) {
                resetState();
                return Result.TIMED_OUT;
            }
            if (hasResourcesForWaitingCraft()) {
                craftTorches(waitingCrafts, inventory.countItem(Items.CHARCOAL));
                sendReady(player);
                resetWaiting();
                return Result.READY;
            }
            return Result.WAITING_RESOURCES;
        }
        int craftsNeeded = (neededTorches + TORCHES_PER_CRAFT - 1) / TORCHES_PER_CRAFT;
        int sticks = inventory.countItem(Items.STICK);
        int charcoal = inventory.countItem(Items.CHARCOAL);
        int coal = inventory.countItem(Items.COAL);
        int fuel = charcoal + coal;
        int missingFuel = Math.max(0, craftsNeeded - fuel);
        int missingSticks = Math.max(0, craftsNeeded - sticks);
        if (missingFuel > 0 || missingSticks > 0) {
            requestResources(player, missingFuel, missingSticks, craftsNeeded, gameTime);
            return Result.WAITING_RESOURCES;
        }
        craftTorches(craftsNeeded, charcoal);
        if (!waitingActive) {
            sendReady(player);
        }
        resetWaiting();
        return Result.READY;
    }

    private void requestResources(Player player, int missingFuel, int missingSticks, int craftsNeeded, long gameTime) {
        if (player == null || player.isSpectator()) {
            return;
        }
        if (!waitingActive) {
            waitingActive = true;
            waitingUntilTick = gameTime + WAIT_TIMEOUT_TICKS;
            waitingCrafts = Math.max(0, craftsNeeded);
        }
        if (gameTime >= nextRequestTick) {
            nextRequestTick = gameTime + REQUEST_COOLDOWN_TICKS;
            Component message = buildMissingMessage(missingFuel, missingSticks);
            if (message != null) {
                owner.sendReply(player, message);
            }
        }
    }

    private Component buildMissingMessage(int missingFuel, int missingSticks) {
        if (missingFuel > 0 && missingSticks > 0) {
            return Component.translatable(NEED_BOTH_KEY,
                    formatFuelCount(missingFuel),
                    formatStickCount(missingSticks));
        }
        if (missingFuel > 0) {
            return Component.translatable(NEED_COAL_ONLY_KEY, formatFuelCount(missingFuel));
        }
        if (missingSticks > 0) {
            return Component.translatable(NEED_STICKS_ONLY_KEY, formatStickCount(missingSticks));
        }
        return null;
    }

    private void sendReady(Player player) {
        if (player == null || player.isSpectator()) {
            return;
        }
        owner.sendReply(player, Component.translatable(READY_KEY));
    }

    private boolean isSameRequest(CompanionResourceRequest request) {
        if (request == null) {
            return false;
        }
        if (activePlayerId == null) {
            return false;
        }
        return activePlayerId.equals(request.getPlayerId()) && activeAmount == request.getAmount();
    }

    private void resetWaiting() {
        waitingActive = false;
        waitingUntilTick = -1L;
        waitingCrafts = 0;
        nextRequestTick = -1L;
    }

    private void resetState() {
        resetWaiting();
        activePlayerId = null;
        activeAmount = 0;
    }

    private String formatFuelCount(int count) {
        String word = selectForm(count, "\u0443\u0433\u043e\u043b\u044c", "\u0443\u0433\u043b\u044f", "\u0443\u0433\u043b\u0435\u0439");
        return count + " " + word + " (\u0438\u043b\u0438 \u0434\u0440\u0435\u0432\u0435\u0441\u043d\u044b\u0439 \u0443\u0433\u043e\u043b\u044c)";
    }

    private String formatStickCount(int count) {
        String word = selectForm(count, "\u043f\u0430\u043b\u043a\u0430", "\u043f\u0430\u043b\u043a\u0438", "\u043f\u0430\u043b\u043e\u043a");
        return count + " " + word;
    }

    private String selectForm(int count, String one, String few, String many) {
        int mod100 = count % 100;
        if (mod100 >= 11 && mod100 <= 14) {
            return many;
        }
        int mod10 = count % 10;
        if (mod10 == 1) {
            return one;
        }
        if (mod10 >= 2 && mod10 <= 4) {
            return few;
        }
        return many;
    }

    private boolean hasResourcesForWaitingCraft() {
        if (waitingCrafts <= 0) {
            return false;
        }
        int sticks = inventory.countItem(Items.STICK);
        int fuel = inventory.countItem(Items.CHARCOAL) + inventory.countItem(Items.COAL);
        return sticks >= waitingCrafts && fuel >= waitingCrafts;
    }

    private void craftTorches(int craftsNeeded, int charcoalCount) {
        if (craftsNeeded <= 0) {
            return;
        }
        int useCharcoal = Math.min(charcoalCount, craftsNeeded);
        if (useCharcoal > 0) {
            inventory.consumeItem(Items.CHARCOAL, useCharcoal);
        }
        int useCoal = craftsNeeded - useCharcoal;
        if (useCoal > 0) {
            inventory.consumeItem(Items.COAL, useCoal);
        }
        inventory.consumeItem(Items.STICK, craftsNeeded);
        inventory.add(new ItemStack(Items.TORCH, craftsNeeded * TORCHES_PER_CRAFT));
    }
}


