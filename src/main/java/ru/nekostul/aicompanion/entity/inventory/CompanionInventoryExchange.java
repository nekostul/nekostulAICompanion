package ru.nekostul.aicompanion.entity.inventory;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import ru.nekostul.aicompanion.entity.CompanionEntity;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class CompanionInventoryExchange {
    private static final int CONFIRM_THRESHOLD = 9;
    private static final String DROP_CONFIRM_KEY = "entity.aicompanion.companion.inventory.drop.confirm";
    private static final String DROP_CONFIRM_BUTTON_KEY = "entity.aicompanion.companion.inventory.drop.confirm.button";
    private static final String RETURN_EMPTY_KEY = "entity.aicompanion.companion.inventory.return.empty";
    private static final String CONFIRM_COMMAND_TEXT = "\u0434\u0430, \u0441\u043a\u0438\u0434\u044b\u0432\u0430\u0439";

    private static Method ownerMethod;
    private static Method throwerMethod;
    private static boolean ownerResolved;
    private static boolean throwerResolved;

    private final CompanionEntity owner;
    private final CompanionInventory inventory;
    private final Deque<ItemStack> returnStack = new ArrayDeque<>();
    private UUID pendingDropPlayerId;

    public CompanionInventoryExchange(CompanionEntity owner, CompanionInventory inventory) {
        this.owner = owner;
        this.inventory = inventory;
    }

    public boolean handleMessage(Player player, String message) {
        if (player == null || message == null) {
            return false;
        }
        String normalized = normalize(message);
        if (normalized.isEmpty()) {
            return false;
        }
        if (isConfirmCommand(normalized)) {
            return handleConfirm(player);
        }
        if (isDropAllCommand(normalized)) {
            handleDropAll(player);
            return true;
        }
        if (isReturnCommand(normalized)) {
            handleReturn(player);
            return true;
        }
        return false;
    }

    public void recordPickup(ItemEntity itemEntity, ItemStack pickedStack) {
        if (itemEntity == null || pickedStack == null || pickedStack.isEmpty()) {
            return;
        }
        Player dropper = resolveDropper(itemEntity);
        if (dropper == null) {
            return;
        }
        returnStack.push(pickedStack.copy());
    }

    private boolean handleConfirm(Player player) {
        if (pendingDropPlayerId == null || !pendingDropPlayerId.equals(player.getUUID())) {
            return false;
        }
        pendingDropPlayerId = null;
        dropAll(player);
        return true;
    }

    private void handleDropAll(Player player) {
        int occupied = countOccupiedSlots();
        if (occupied >= CONFIRM_THRESHOLD) {
            if (pendingDropPlayerId == null) {
                pendingDropPlayerId = player.getUUID();
                sendConfirm(player);
            }
            return;
        }
        pendingDropPlayerId = null;
        dropAll(player);
    }

    private void handleReturn(Player player) {
        if (returnStack.isEmpty()) {
            owner.sendReply(player, Component.translatable(RETURN_EMPTY_KEY));
            return;
        }
        while (!returnStack.isEmpty()) {
            ItemStack requested = returnStack.pop();
            List<ItemStack> removed = inventory.takeMatching(
                    stack -> ItemStack.isSameItemSameTags(stack, requested),
                    requested.getCount());
            if (removed.isEmpty()) {
                continue;
            }
            dropStacksNearPlayer(player, removed);
            return;
        }
        owner.sendReply(player, Component.translatable(RETURN_EMPTY_KEY));
    }

    private void dropAll(Player player) {
        List<ItemStack> drops = new ArrayList<>();
        for (int i = 0; i < inventory.getItems().size(); i++) {
            ItemStack stack = inventory.getItems().get(i);
            if (stack.isEmpty()) {
                continue;
            }
            drops.add(stack.copy());
            inventory.getItems().set(i, ItemStack.EMPTY);
        }
        if (!drops.isEmpty()) {
            owner.onInventoryUpdated();
            dropStacksNearPlayer(player, drops);
        }
    }

    private void dropStacksNearPlayer(Player player, List<ItemStack> stacks) {
        if (player == null) {
            return;
        }
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            double offsetX = (owner.getRandom().nextDouble() - 0.5D) * 0.4D;
            double offsetZ = (owner.getRandom().nextDouble() - 0.5D) * 0.4D;
            ItemEntity entity = new ItemEntity(player.level(),
                    player.getX() + offsetX,
                    player.getY() + 0.5D,
                    player.getZ() + offsetZ,
                    stack);
            entity.setDefaultPickUpDelay();
            CompanionDropTracker.markDropped(entity, owner.getUUID());
            player.level().addFreshEntity(entity);
        }
    }

    private void sendConfirm(Player player) {
        Component button = Component.translatable(DROP_CONFIRM_BUTTON_KEY)
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/ainpc msg " + CONFIRM_COMMAND_TEXT)));
        Component message = Component.translatable(DROP_CONFIRM_KEY)
                .append(Component.literal(" "))
                .append(Component.literal("["))
                .append(button)
                .append(Component.literal("]"));
        owner.sendReply(player, message);
    }

    private int countOccupiedSlots() {
        int occupied = 0;
        for (ItemStack stack : inventory.getItems()) {
            if (!stack.isEmpty()) {
                occupied++;
            }
        }
        return occupied;
    }

    private boolean isDropAllCommand(String normalized) {
        if (!normalized.contains("\u0432\u0441\u0435")) {
            return false;
        }
        return normalized.contains("\u0441\u043a\u0438\u043d\u044c")
                || normalized.contains("\u0432\u044b\u0431\u0440\u043e\u0441\u044c")
                || normalized.contains("\u043e\u0442\u0434\u0430\u0439");
    }

    private boolean isReturnCommand(String normalized) {
        if (normalized.contains("\u0432\u0441\u0435")) {
            return false;
        }
        return normalized.contains("\u043e\u0442\u0434\u0430\u0439")
                || normalized.contains("\u0432\u0435\u0440\u043d\u0438");
    }

    private boolean isConfirmCommand(String normalized) {
        return normalized.startsWith("\u0434\u0430") && normalized.contains("\u0441\u043a\u0438\u0434\u044b\u0432\u0430\u0439");
    }

    private String normalize(String message) {
        return message.trim()
                .toLowerCase(Locale.ROOT)
                .replace('\u0451', '\u0435');
    }

    private Player resolveDropper(ItemEntity itemEntity) {
        UUID ownerId = CompanionDropTracker.getPlayerDropper(itemEntity);
        if (ownerId == null) {
            ownerId = resolveOwnerId(itemEntity);
        }
        if (ownerId == null) {
            return null;
        }
        Player player = owner.level().getPlayerByUUID(ownerId);
        if (player == null || player.isSpectator()) {
            return null;
        }
        return player;
    }

    private UUID resolveOwnerId(ItemEntity itemEntity) {
        UUID ownerId = invokeUuid(itemEntity, ownerMethod, "getOwner");
        if (ownerId != null) {
            return ownerId;
        }
        return invokeUuid(itemEntity, throwerMethod, "getThrower");
    }

    private UUID invokeUuid(ItemEntity entity, Method cached, String methodName) {
        Method method = cached;
        if (method == null) {
            if ("getOwner".equals(methodName)) {
                if (ownerResolved) {
                    return null;
                }
                method = findMethod(entity, methodName);
                ownerMethod = method;
                ownerResolved = true;
            } else {
                if (throwerResolved) {
                    return null;
                }
                method = findMethod(entity, methodName);
                throwerMethod = method;
                throwerResolved = true;
            }
        }
        if (method == null) {
            return null;
        }
        try {
            Object result = method.invoke(entity);
            if (result instanceof UUID uuid) {
                return uuid;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private Method findMethod(ItemEntity entity, String name) {
        try {
            return entity.getClass().getMethod(name);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
