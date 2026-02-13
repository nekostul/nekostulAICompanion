package ru.nekostul.aicompanion.entity.inventory;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import ru.nekostul.aicompanion.entity.CompanionEntity;
import ru.nekostul.aicompanion.entity.tool.CompanionToolSlot;

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
    private static final String DROP_TOOLS_NOTICE_KEY = "entity.aicompanion.companion.inventory.drop.tools.notice";
    private static final String DROP_TOOLS_BUTTON_KEY = "entity.aicompanion.companion.inventory.drop.tools.button";
    private static final String DROP_TOOLS_IGNORE_KEY = "entity.aicompanion.companion.teleport.ignore.tools";
    private static final String RETURN_EMPTY_KEY = "entity.aicompanion.companion.inventory.return.empty";
    private static final String RETURN_EMPTY_INVENTORY_KEY = "entity.aicompanion.companion.inventory.return.empty.inventory";
    private static final String RETURN_TOOL_KEY = "entity.aicompanion.companion.inventory.return.tool";
    private static final String RETURN_FOOD_KEY = "entity.aicompanion.companion.inventory.return.food";
    private static final String CONFIRM_COMMAND_TEXT = "\u0434\u0430, \u0441\u043a\u0438\u0434\u044b\u0432\u0430\u0439";
    private static final String TOOL_DROP_COMMAND_TEXT = "\u0441\u043a\u0438\u043d\u0443\u0442\u044c";
    private static final int TOOL_DROP_WINDOW_TICKS = 100;

    private static Method ownerMethod;
    private static Method throwerMethod;
    private static boolean ownerResolved;
    private static boolean throwerResolved;

    private final CompanionEntity owner;
    private final CompanionInventory inventory;
    private final Deque<ItemStack> returnStack = new ArrayDeque<>();
    private UUID pendingDropPlayerId;
    private boolean pendingDropKeepToolsAndFood;
    private UUID pendingToolDropPlayerId;
    private long pendingToolDropUntilTick = -1L;
    private int pendingToolDropLastSeconds = -1;

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
        long gameTime = owner.level().getGameTime();
        expireToolDropIfNeeded(gameTime);
        if (isConfirmCommand(normalized)) {
            return handleConfirm(player, gameTime);
        }
        if (isToolDropCommand(normalized)) {
            return handleToolDrop(player, gameTime);
        }
        if (isDropAllCommand(normalized)) {
            handleDropAll(player, shouldKeepToolsAndFood(normalized), gameTime);
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

    private boolean handleConfirm(Player player, long gameTime) {
        if (pendingDropPlayerId == null || !pendingDropPlayerId.equals(player.getUUID())) {
            return false;
        }
        boolean keepToolsAndFood = pendingDropKeepToolsAndFood;
        pendingDropPlayerId = null;
        pendingDropKeepToolsAndFood = false;
        dropAll(player, keepToolsAndFood, gameTime);
        return true;
    }

    private void handleDropAll(Player player, boolean keepToolsAndFood, long gameTime) {
        clearToolDropRequest();
        if (isInventoryEmpty()) {
            owner.sendReply(player, Component.translatable(RETURN_EMPTY_INVENTORY_KEY));
            return;
        }
        int occupied = countOccupiedSlots();
        if (occupied >= CONFIRM_THRESHOLD) {
            if (pendingDropPlayerId == null) {
                pendingDropPlayerId = player.getUUID();
                pendingDropKeepToolsAndFood = keepToolsAndFood;
                sendConfirm(player);
            }
            return;
        }
        pendingDropPlayerId = null;
        pendingDropKeepToolsAndFood = false;
        dropAll(player, keepToolsAndFood, gameTime);
    }

    private void handleReturn(Player player) {
        if (returnStack.isEmpty() && isInventoryEmpty()) {
            owner.sendReply(player, Component.translatable(RETURN_EMPTY_INVENTORY_KEY));
            return;
        }
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
                ItemStack equipped = takeEquippedTool(requested);
                if (equipped.isEmpty()) {
                    continue;
                }
                dropStacksNearPlayer(player, List.of(equipped));
                sendReturnNotice(player, equipped);
                return;
            }
            dropStacksNearPlayer(player, removed);
            sendReturnNotice(player, requested);
            return;
        }
        owner.sendReply(player, Component.translatable(RETURN_EMPTY_KEY));
    }

    private void dropAll(Player player, boolean keepToolsAndFood, long gameTime) {
        List<ItemStack> drops = new ArrayList<>();
        boolean hasTools = false;
        boolean hasFood = false;
        for (int i = 0; i < inventory.getItems().size(); i++) {
            ItemStack stack = inventory.getItems().get(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (keepToolsAndFood && (isTool(stack) || isFood(stack))) {
                if (isTool(stack)) {
                    hasTools = true;
                }
                if (isFood(stack)) {
                    hasFood = true;
                }
                continue;
            }
            drops.add(stack.copy());
            inventory.getItems().set(i, ItemStack.EMPTY);
        }
        if (keepToolsAndFood) {
            if (isTool(owner.getMainHandItem()) || isTool(owner.getOffhandItem())) {
                hasTools = true;
            }
            if (isFood(owner.getMainHandItem()) || isFood(owner.getOffhandItem())) {
                hasFood = true;
            }
            for (CompanionToolSlot slot : CompanionToolSlot.values()) {
                if (!owner.getToolSlot(slot).isEmpty()) {
                    hasTools = true;
                    break;
                }
            }
        }
        if (!drops.isEmpty()) {
            owner.onInventoryUpdated();
            dropStacksNearPlayer(player, drops);
        }
        if (keepToolsAndFood && hasFood) {
            sendToolDropNotice(player, gameTime);
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

    private void dropTools(Player player) {
        List<ItemStack> drops = new ArrayList<>();
        for (int i = 0; i < inventory.getItems().size(); i++) {
            ItemStack stack = inventory.getItems().get(i);
            if (stack.isEmpty() || !isFood(stack)) {
                continue;
            }
            drops.add(stack.copy());
            inventory.getItems().set(i, ItemStack.EMPTY);
        }
        ItemStack mainHand = owner.getMainHandItem();
        if (isFood(mainHand)) {
            drops.add(mainHand.copy());
            owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        ItemStack offhand = owner.getOffhandItem();
        if (isFood(offhand)) {
            drops.add(offhand.copy());
            owner.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }
        if (!drops.isEmpty()) {
            owner.onInventoryUpdated();
            dropStacksNearPlayer(player, drops);
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

    private void sendToolDropNotice(Player player, long gameTime) {
        if (player == null) {
            return;
        }
        pendingToolDropPlayerId = player.getUUID();
        pendingToolDropUntilTick = gameTime + TOOL_DROP_WINDOW_TICKS;
        pendingToolDropLastSeconds = -1;
        int secondsLeft = secondsLeft(pendingToolDropUntilTick, gameTime);
        sendToolDropMessage(player, secondsLeft);
        pendingToolDropLastSeconds = secondsLeft;
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

    private boolean isInventoryEmpty() {
        for (ItemStack stack : inventory.getItems()) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean isDropAllCommand(String normalized) {
        if (!normalized.contains("\u0432\u0441\u0435")) {
            return false;
        }
        return normalized.contains("\u0441\u043a\u0438\u043d\u044c")
                || normalized.contains("\u0432\u044b\u0431\u0440\u043e\u0441\u044c")
                || normalized.contains("\u043e\u0442\u0434\u0430\u0439");
    }

    private boolean shouldKeepToolsAndFood(String normalized) {
        return normalized.contains("\u043e\u0442\u0434\u0430\u0439")
                || normalized.contains("\u0441\u043a\u0438\u043d\u044c")
                || normalized.contains("\u0432\u044b\u0431\u0440\u043e\u0441");
    }

    private boolean isToolDropCommand(String normalized) {
        return normalized.equals(TOOL_DROP_COMMAND_TEXT);
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

    private boolean handleToolDrop(Player player, long gameTime) {
        if (pendingToolDropPlayerId == null || !pendingToolDropPlayerId.equals(player.getUUID())) {
            return false;
        }
        if (gameTime >= pendingToolDropUntilTick) {
            clearToolDropRequest(true);
            return false;
        }
        clearToolDropRequest(true);
        dropTools(player);
        return true;
    }

    private void clearToolDropRequest() {
        clearToolDropRequest(false);
    }

    private void clearToolDropRequest(boolean notifyRemove) {
        if (pendingToolDropPlayerId != null && notifyRemove) {
            sendToolDropRemoval(pendingToolDropPlayerId);
        }
        pendingToolDropPlayerId = null;
        pendingToolDropUntilTick = -1L;
        pendingToolDropLastSeconds = -1;
    }

    private void expireToolDropIfNeeded(long gameTime) {
        if (pendingToolDropPlayerId == null) {
            return;
        }
        if (gameTime >= pendingToolDropUntilTick) {
            clearToolDropRequest(true);
        }
    }

    public void tickToolDropNotice(long gameTime) {
        if (pendingToolDropPlayerId == null) {
            return;
        }
        Player player = owner.getPlayerById(pendingToolDropPlayerId);
        if (player == null) {
            clearToolDropRequest(false);
            return;
        }
        if (gameTime >= pendingToolDropUntilTick) {
            clearToolDropRequest(true);
            return;
        }
        int secondsLeft = secondsLeft(pendingToolDropUntilTick, gameTime);
        if (secondsLeft != pendingToolDropLastSeconds) {
            pendingToolDropLastSeconds = secondsLeft;
            sendToolDropMessage(player, secondsLeft);
        }
    }

    private int secondsLeft(long untilTick, long gameTime) {
        long remaining = Math.max(0L, untilTick - gameTime);
        return (int) Math.ceil(remaining / 20.0D);
    }

    private void sendToolDropRemoval(UUID playerId) {
        Player player = owner.getPlayerById(playerId);
        if (player == null) {
            return;
        }
        owner.sendReply(player, Component.translatable(DROP_TOOLS_IGNORE_KEY));
    }

    private void sendToolDropMessage(Player player, int secondsLeft) {
        Component button = Component.translatable(DROP_TOOLS_BUTTON_KEY)
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/ainpc msg " + TOOL_DROP_COMMAND_TEXT)));
        Component message = Component.translatable(DROP_TOOLS_NOTICE_KEY, secondsLeft)
                .append(Component.literal(" "))
                .append(Component.literal("["))
                .append(button)
                .append(Component.literal("]"));
        owner.sendReply(player, message);
    }

    private void sendReturnNotice(Player player, ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) {
            return;
        }
        if (isTool(stack)) {
            owner.sendReply(player, Component.translatable(RETURN_TOOL_KEY));
            return;
        }
        if (stack.isEdible()) {
            owner.sendReply(player, Component.translatable(RETURN_FOOD_KEY));
        }
    }

    private ItemStack takeEquippedTool(ItemStack requested) {
        if (!isTool(requested)) {
            return ItemStack.EMPTY;
        }
        ItemStack mainHand = owner.getMainHandItem();
        if (matchesReturnItem(mainHand, requested)) {
            ItemStack result = mainHand.copy();
            owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            return result;
        }
        ItemStack offhand = owner.getOffhandItem();
        if (matchesReturnItem(offhand, requested)) {
            ItemStack result = offhand.copy();
            owner.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            return result;
        }
        return ItemStack.EMPTY;
    }

    private boolean matchesReturnItem(ItemStack candidate, ItemStack requested) {
        if (candidate == null || candidate.isEmpty() || requested == null || requested.isEmpty()) {
            return false;
        }
        return ItemStack.isSameItemSameTags(candidate, requested);
    }

    private boolean isFood(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.isEdible();
    }

    private boolean isTool(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.is(ItemTags.AXES)
                || stack.is(ItemTags.PICKAXES)
                || stack.is(ItemTags.SHOVELS)
                || stack.is(ItemTags.SWORDS)
                || stack.is(ItemTags.HOES)
                || stack.is(Items.SHEARS);
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
