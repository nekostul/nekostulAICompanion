package ru.nekostul.aicompanion.entity.inventory;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

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
    private static final boolean DEBUG_DROP_FLOW = false;
    private static final int CONFIRM_THRESHOLD = 9;
    private static final String DROP_CONFIRM_KEY = "entity.aicompanion.companion.inventory.drop.confirm";
    private static final String DROP_CONFIRM_REMOVE_KEY = "entity.aicompanion.companion.inventory.drop.confirm.remove";
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
    private static final double OWNER_DROP_COLLECT_RADIUS = 48.0D;
    private static final int OWNER_DROP_COLLECT_MAX_AGE_TICKS = 1200;
    private static final double OWNER_DROP_COLLECT_FALLBACK_NEAR_PLAYER_RADIUS_SQR = 400.0D;
    private static final double OWNER_DROP_MATCHING_SWEEP_RADIUS = 128.0D;
    private static final int OWNER_DROP_MATCHING_SWEEP_MAX_AGE_TICKS = 6000;
    private static final int DROP_ALL_PICKUP_PAUSE_TICKS = 120;
    private static final int DROP_ALL_CATCHUP_TICKS = 120;
    private static final int DROP_ALL_CONFIRM_DELAY_TICKS = 60;
    private static final int PLAYER_SWEEP_RECENT_PICKUP_TICKS = 200;

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
    private UUID pendingDropAllPlayerId;
    private long pendingDropAllExecuteTick = -1L;
    private boolean pendingDropAllKeepToolsAndFood;
    private UUID pendingDropAllCatchupPlayerId;
    private long pendingDropAllCatchupUntilTick = -1L;
    private boolean pendingDropAllCatchupKeepToolsAndFood;
    private long lastRecordedPickupTick = -10000L;

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
        returnStack.push(pickedStack.copy());
        lastRecordedPickupTick = owner.level().getGameTime();
    }

    private boolean handleConfirm(Player player, long gameTime) {
        if (pendingDropPlayerId == null || !pendingDropPlayerId.equals(player.getUUID())) {
            return false;
        }
        boolean keepToolsAndFood = pendingDropKeepToolsAndFood;
        pendingDropPlayerId = null;
        pendingDropKeepToolsAndFood = false;
        owner.sendReply(player, Component.translatable(DROP_CONFIRM_REMOVE_KEY));
        pendingDropAllPlayerId = player.getUUID();
        pendingDropAllExecuteTick = gameTime + DROP_ALL_CONFIRM_DELAY_TICKS;
        pendingDropAllKeepToolsAndFood = keepToolsAndFood;
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
        List<ItemStack> toReturn = new ArrayList<>();
        boolean changedOutsideInventory = false;
        boolean returnedTool = false;
        boolean returnedFood = false;
        while (!returnStack.isEmpty()) {
            ItemStack requested = returnStack.pop();
            if (requested == null || requested.isEmpty()) {
                continue;
            }
            List<ItemStack> removed = inventory.takeMatching(
                    stack -> ItemStack.isSameItemSameTags(stack, requested),
                    requested.getCount());
            if (!removed.isEmpty()) {
                for (ItemStack returned : removed) {
                    if (returned == null || returned.isEmpty()) {
                        continue;
                    }
                    toReturn.add(returned);
                    if (isTool(returned)) {
                        returnedTool = true;
                    }
                    if (returned.isEdible()) {
                        returnedFood = true;
                    }
                }
                continue;
            }
            ItemStack equipped = takeHeldOrEquippedItem(requested);
            if (equipped.isEmpty()) {
                continue;
            }
            toReturn.add(equipped);
            changedOutsideInventory = true;
            if (isTool(equipped)) {
                returnedTool = true;
            }
            if (equipped.isEdible()) {
                returnedFood = true;
            }
        }
        if (changedOutsideInventory) {
            owner.onInventoryUpdated();
        }
        if (toReturn.isEmpty()) {
            owner.sendReply(player, Component.translatable(RETURN_EMPTY_KEY));
            return;
        }
        dropStacksNearPlayer(player, toReturn);
        if (returnedTool) {
            owner.sendReply(player, Component.translatable(RETURN_TOOL_KEY));
        }
        if (returnedFood) {
            owner.sendReply(player, Component.translatable(RETURN_FOOD_KEY));
        }
    }

    private void dropAll(Player player, boolean keepToolsAndFood, long gameTime) {
        int npcBeforeStacks = countNpcStoredStacks();
        int npcBeforeItems = countNpcStoredItems();
        List<ItemStack> drops = new ArrayList<>();
        boolean hasTools = false;
        boolean hasFood = false;
        boolean changed = false;
        DropCollectStats collectStats = collectNearbyOwnerDrops(player, keepToolsAndFood, drops);
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
            changed = true;
        }
        ItemStack mainHand = owner.getMainHandItem();
        if (!mainHand.isEmpty()) {
            if (keepToolsAndFood && (isTool(mainHand) || isFood(mainHand))) {
                if (isTool(mainHand)) {
                    hasTools = true;
                }
                if (isFood(mainHand)) {
                    hasFood = true;
                }
            } else {
                drops.add(mainHand.copy());
                owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                changed = true;
            }
        }
        ItemStack offhand = owner.getOffhandItem();
        if (!offhand.isEmpty()) {
            if (keepToolsAndFood && (isTool(offhand) || isFood(offhand))) {
                if (isTool(offhand)) {
                    hasTools = true;
                }
                if (isFood(offhand)) {
                    hasFood = true;
                }
            } else {
                drops.add(offhand.copy());
                owner.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                changed = true;
            }
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack armor = owner.getItemBySlot(slot);
            if (armor.isEmpty()) {
                continue;
            }
            if (keepToolsAndFood && (isTool(armor) || isFood(armor))) {
                if (isTool(armor)) {
                    hasTools = true;
                }
                if (isFood(armor)) {
                    hasFood = true;
                }
                continue;
            }
            drops.add(armor.copy());
            owner.setItemSlot(slot, ItemStack.EMPTY);
            changed = true;
        }
        for (CompanionToolSlot slot : CompanionToolSlot.values()) {
            ItemStack toolStack = owner.getToolSlot(slot);
            if (toolStack.isEmpty()) {
                continue;
            }
            if (keepToolsAndFood && (isTool(toolStack) || isFood(toolStack))) {
                if (isTool(toolStack)) {
                    hasTools = true;
                }
                if (isFood(toolStack)) {
                    hasFood = true;
                }
                continue;
            }
            drops.add(toolStack.copy());
            owner.setToolSlot(slot, ItemStack.EMPTY);
            changed = true;
        }
        ItemStack foodSlot = owner.getFoodSlot();
        if (!foodSlot.isEmpty()) {
            if (keepToolsAndFood) {
                hasFood = true;
            } else {
                drops.add(foodSlot.copy());
                owner.setFoodSlot(ItemStack.EMPTY);
                changed = true;
            }
        }
        if (changed) {
            owner.onInventoryUpdated();
        }
        DropCollectStats matchingSweepStats = collectMatchingNearbyOwnerDrops(player, keepToolsAndFood, drops);
        PlayerSweepStats playerSweepStats = PlayerSweepStats.empty();
        if (player != null && gameTime - lastRecordedPickupTick <= PLAYER_SWEEP_RECENT_PICKUP_TICKS) {
            playerSweepStats = sweepMatchingPlayerStacks(player, drops, keepToolsAndFood);
        }
        int preparedStacks = countNonEmptyStacks(drops);
        int preparedItems = countItems(drops);
        DropDeliveryStats deliveryStats = DropDeliveryStats.empty();
        if (!drops.isEmpty()) {
            deliveryStats = dropStacksNearPlayer(player, drops);
        }
        owner.pauseItemPickup(DROP_ALL_PICKUP_PAUSE_TICKS);
        if (player != null) {
            pendingDropAllCatchupPlayerId = player.getUUID();
            pendingDropAllCatchupUntilTick = gameTime + DROP_ALL_CATCHUP_TICKS;
            pendingDropAllCatchupKeepToolsAndFood = keepToolsAndFood;
        } else {
            pendingDropAllCatchupPlayerId = null;
            pendingDropAllCatchupUntilTick = -1L;
            pendingDropAllCatchupKeepToolsAndFood = false;
        }
        if (DEBUG_DROP_FLOW && player != null) {
            int npcAfterStacks = countNpcStoredStacks();
            int npcAfterItems = countNpcStoredItems();
            owner.sendReply(player, Component.literal("[DEBUG dropAll] npc before: "
                    + npcBeforeStacks + " st / " + npcBeforeItems + " it"
                    + ", ground collected: " + collectStats.stacks + " st / " + collectStats.items + " it"
                    + ", matching sweep: " + matchingSweepStats.stacks + " st / " + matchingSweepStats.items + " it"
                    + ", player sweep: " + playerSweepStats.stacks + " st / " + playerSweepStats.items + " it"
                    + ", prepared: " + preparedStacks + " st / " + preparedItems + " it"));
            owner.sendReply(player, Component.literal("[DEBUG dropAll] delivered: "
                    + deliveryStats.deliveredStacks + " st / " + deliveryStats.deliveredItems + " it"
                    + ", leftover: " + deliveryStats.leftoverStacks + " st / " + deliveryStats.leftoverItems + " it"
                    + ", npc after: " + npcAfterStacks + " st / " + npcAfterItems + " it"));
            owner.sendReply(player, Component.literal("[DEBUG dropAll] kept items: " + describeNpcStoredStacks()));
        }
        if (keepToolsAndFood && hasFood) {
            sendToolDropNotice(player, gameTime);
        }
    }

    private DropCollectStats collectNearbyOwnerDrops(Player player, boolean keepToolsAndFood, List<ItemStack> drops) {
        DropCollectStats stats = new DropCollectStats();
        if (player == null || drops == null) {
            return stats;
        }
        AABB playerArea = player.getBoundingBox().inflate(OWNER_DROP_COLLECT_RADIUS);
        AABB ownerArea = owner.getBoundingBox().inflate(OWNER_DROP_COLLECT_RADIUS);
        AABB area = playerArea.minmax(ownerArea);
        List<ItemEntity> nearby = owner.level().getEntitiesOfClass(ItemEntity.class, area);
        for (ItemEntity entity : nearby) {
            if (entity == null || !entity.isAlive()) {
                continue;
            }
            if (entity.tickCount > OWNER_DROP_COLLECT_MAX_AGE_TICKS) {
                continue;
            }
            ItemStack stack = entity.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (CompanionDropTracker.isMobDrop(entity)) {
                continue;
            }
            Player dropper = resolveDropper(entity);
            if (dropper != null) {
                if (!player.getUUID().equals(dropper.getUUID())) {
                    continue;
                }
            } else if (entity.distanceToSqr(player) > OWNER_DROP_COLLECT_FALLBACK_NEAR_PLAYER_RADIUS_SQR) {
                continue;
            }
            if (CompanionDropTracker.isPlayerBlockDrop(entity)) {
                continue;
            }
            if (keepToolsAndFood && (isTool(stack) || isFood(stack))) {
                continue;
            }
            drops.add(stack.copy());
            stats.stacks++;
            stats.items += stack.getCount();
            entity.discard();
        }
        return stats;
    }

    private DropCollectStats collectMatchingNearbyOwnerDrops(Player player,
                                                             boolean keepToolsAndFood,
                                                             List<ItemStack> drops) {
        DropCollectStats stats = new DropCollectStats();
        if (player == null || drops == null || drops.isEmpty()) {
            return stats;
        }
        List<ItemStack> signatures = new ArrayList<>();
        for (ItemStack candidate : drops) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            if (containsSignature(signatures, candidate)) {
                continue;
            }
            signatures.add(candidate.copy());
        }
        if (signatures.isEmpty()) {
            return stats;
        }
        AABB playerArea = player.getBoundingBox().inflate(OWNER_DROP_MATCHING_SWEEP_RADIUS);
        AABB ownerArea = owner.getBoundingBox().inflate(OWNER_DROP_MATCHING_SWEEP_RADIUS);
        AABB area = playerArea.minmax(ownerArea);
        List<ItemEntity> nearby = owner.level().getEntitiesOfClass(ItemEntity.class, area);
        for (ItemEntity entity : nearby) {
            if (entity == null || !entity.isAlive()) {
                continue;
            }
            if (entity.tickCount > OWNER_DROP_MATCHING_SWEEP_MAX_AGE_TICKS) {
                continue;
            }
            ItemStack stack = entity.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (CompanionDropTracker.isMobDrop(entity)) {
                continue;
            }
            if (!matchesAnySignature(signatures, stack)) {
                continue;
            }
            Player dropper = resolveDropper(entity);
            if (dropper != null && !player.getUUID().equals(dropper.getUUID())) {
                continue;
            }
            if (keepToolsAndFood && (isTool(stack) || isFood(stack))) {
                continue;
            }
            drops.add(stack.copy());
            stats.stacks++;
            stats.items += stack.getCount();
            entity.discard();
        }
        return stats;
    }

    private DropDeliveryStats dropStacksNearPlayer(Player player, List<ItemStack> stacks) {
        DropDeliveryStats stats = DropDeliveryStats.empty();
        if (player == null) {
            return stats;
        }
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            stats.requestedStacks++;
            stats.requestedItems += stack.getCount();
            player.getInventory().placeItemBackInInventory(stack.copy());
            stats.deliveredStacks++;
            stats.deliveredItems += stack.getCount();
        }
        return stats;
    }

    private int countNpcStoredItems() {
        int total = 0;
        for (ItemStack stack : inventory.getItems()) {
            if (!stack.isEmpty()) {
                total += stack.getCount();
            }
        }
        ItemStack mainHand = owner.getMainHandItem();
        if (!mainHand.isEmpty()) {
            total += mainHand.getCount();
        }
        ItemStack offhand = owner.getOffhandItem();
        if (!offhand.isEmpty()) {
            total += offhand.getCount();
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack armor = owner.getItemBySlot(slot);
            if (!armor.isEmpty()) {
                total += armor.getCount();
            }
        }
        for (CompanionToolSlot slot : CompanionToolSlot.values()) {
            ItemStack tool = owner.getToolSlot(slot);
            if (!tool.isEmpty()) {
                total += tool.getCount();
            }
        }
        ItemStack food = owner.getFoodSlot();
        if (!food.isEmpty()) {
            total += food.getCount();
        }
        return total;
    }

    private int countNpcStoredStacks() {
        int total = countNonEmptyStacks(inventory.getItems());
        if (!owner.getMainHandItem().isEmpty()) {
            total++;
        }
        if (!owner.getOffhandItem().isEmpty()) {
            total++;
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (!owner.getItemBySlot(slot).isEmpty()) {
                total++;
            }
        }
        for (CompanionToolSlot slot : CompanionToolSlot.values()) {
            if (!owner.getToolSlot(slot).isEmpty()) {
                total++;
            }
        }
        if (!owner.getFoodSlot().isEmpty()) {
            total++;
        }
        return total;
    }

    private int countItems(List<ItemStack> stacks) {
        int total = 0;
        if (stacks == null) {
            return 0;
        }
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countNonEmptyStacks(List<ItemStack> stacks) {
        int total = 0;
        if (stacks == null) {
            return 0;
        }
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                total++;
            }
        }
        return total;
    }

    private static final class DropCollectStats {
        private int stacks;
        private int items;
    }

    private static final class DropDeliveryStats {
        private int requestedStacks;
        private int requestedItems;
        private int deliveredStacks;
        private int deliveredItems;
        private int leftoverStacks;
        private int leftoverItems;

        private static DropDeliveryStats empty() {
            return new DropDeliveryStats();
        }
    }

    private static final class PlayerSweepStats {
        private int stacks;
        private int items;

        private static PlayerSweepStats empty() {
            return new PlayerSweepStats();
        }
    }

    private PlayerSweepStats sweepMatchingPlayerStacks(Player player, List<ItemStack> drops, boolean keepToolsAndFood) {
        PlayerSweepStats stats = PlayerSweepStats.empty();
        if (player == null || drops == null || drops.isEmpty()) {
            return stats;
        }
        List<ItemStack> signatures = new ArrayList<>();
        for (ItemStack candidate : drops) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            if (containsSignature(signatures, candidate)) {
                continue;
            }
            signatures.add(candidate.copy());
        }
        if (signatures.isEmpty()) {
            return stats;
        }
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            ItemStack playerStack = player.getInventory().items.get(i);
            if (playerStack == null || playerStack.isEmpty()) {
                continue;
            }
            if (keepToolsAndFood && (isTool(playerStack) || isFood(playerStack))) {
                continue;
            }
            if (!matchesAnySignature(signatures, playerStack)) {
                continue;
            }
            drops.add(playerStack.copy());
            stats.stacks++;
            stats.items += playerStack.getCount();
            player.getInventory().items.set(i, ItemStack.EMPTY);
        }
        return stats;
    }

    private boolean containsSignature(List<ItemStack> signatures, ItemStack candidate) {
        if (signatures == null || candidate == null || candidate.isEmpty()) {
            return false;
        }
        for (ItemStack signature : signatures) {
            if (ItemStack.isSameItemSameTags(signature, candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnySignature(List<ItemStack> signatures, ItemStack stack) {
        if (signatures == null || signatures.isEmpty() || stack == null || stack.isEmpty()) {
            return false;
        }
        for (ItemStack signature : signatures) {
            if (ItemStack.isSameItemSameTags(signature, stack)) {
                return true;
            }
        }
        return false;
    }

    private String describeNpcStoredStacks() {
        List<String> entries = new ArrayList<>();
        for (ItemStack stack : inventory.getItems()) {
            appendDebugStack(entries, "inv", stack);
        }
        appendDebugStack(entries, "main", owner.getMainHandItem());
        appendDebugStack(entries, "off", owner.getOffhandItem());
        appendDebugStack(entries, "head", owner.getItemBySlot(EquipmentSlot.HEAD));
        appendDebugStack(entries, "chest", owner.getItemBySlot(EquipmentSlot.CHEST));
        appendDebugStack(entries, "legs", owner.getItemBySlot(EquipmentSlot.LEGS));
        appendDebugStack(entries, "feet", owner.getItemBySlot(EquipmentSlot.FEET));
        for (CompanionToolSlot slot : CompanionToolSlot.values()) {
            appendDebugStack(entries, "tool:" + slot.name().toLowerCase(Locale.ROOT), owner.getToolSlot(slot));
        }
        appendDebugStack(entries, "foodSlot", owner.getFoodSlot());
        if (entries.isEmpty()) {
            return "empty";
        }
        int limit = Math.min(entries.size(), 8);
        String joined = String.join(", ", entries.subList(0, limit));
        if (entries.size() > limit) {
            joined += ", ... +" + (entries.size() - limit);
        }
        return joined;
    }

    private void appendDebugStack(List<String> entries, String source, ItemStack stack) {
        if (entries == null || stack == null || stack.isEmpty()) {
            return;
        }
        String name = stack.getHoverName().getString();
        entries.add(source + "=" + name + " x" + stack.getCount());
    }

    private void dropTools(Player player) {
        List<ItemStack> drops = new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < inventory.getItems().size(); i++) {
            ItemStack stack = inventory.getItems().get(i);
            if (stack.isEmpty() || !isFood(stack)) {
                continue;
            }
            drops.add(stack.copy());
            inventory.getItems().set(i, ItemStack.EMPTY);
            changed = true;
        }
        ItemStack mainHand = owner.getMainHandItem();
        if (isFood(mainHand)) {
            drops.add(mainHand.copy());
            owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            changed = true;
        }
        ItemStack offhand = owner.getOffhandItem();
        if (isFood(offhand)) {
            drops.add(offhand.copy());
            owner.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            changed = true;
        }
        ItemStack foodSlot = owner.getFoodSlot();
        if (!foodSlot.isEmpty()) {
            drops.add(foodSlot.copy());
            owner.setFoodSlot(ItemStack.EMPTY);
            changed = true;
        }
        if (changed) {
            owner.onInventoryUpdated();
        }
        if (!drops.isEmpty()) {
            dropStacksNearPlayer(player, drops);
        }
    }

    private void sendConfirm(Player player) {
        owner.sendReply(player, Component.translatable(DROP_CONFIRM_REMOVE_KEY));
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
        if (!owner.getMainHandItem().isEmpty() || !owner.getOffhandItem().isEmpty()) {
            return false;
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (!owner.getItemBySlot(slot).isEmpty()) {
                return false;
            }
        }
        for (CompanionToolSlot slot : CompanionToolSlot.values()) {
            if (!owner.getToolSlot(slot).isEmpty()) {
                return false;
            }
        }
        if (!owner.getFoodSlot().isEmpty()) {
            return false;
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
        return true;
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

    public void tickPendingDropAll(long gameTime) {
        if (pendingDropAllPlayerId == null) {
            return;
        }
        if (gameTime < pendingDropAllExecuteTick) {
            return;
        }
        Player player = owner.getPlayerById(pendingDropAllPlayerId);
        boolean keepToolsAndFood = pendingDropAllKeepToolsAndFood;
        pendingDropAllPlayerId = null;
        pendingDropAllExecuteTick = -1L;
        pendingDropAllKeepToolsAndFood = false;
        if (player == null || player.isSpectator()) {
            return;
        }
        dropAll(player, keepToolsAndFood, gameTime);
    }

    public void tickDropAllCatchup(long gameTime) {
        if (pendingDropAllCatchupPlayerId == null) {
            return;
        }
        if (gameTime >= pendingDropAllCatchupUntilTick) {
            clearDropAllCatchup();
            return;
        }
        Player player = owner.getPlayerById(pendingDropAllCatchupPlayerId);
        if (player == null || player.isSpectator()) {
            clearDropAllCatchup();
            return;
        }
        List<ItemStack> collected = new ArrayList<>();
        collectNearbyOwnerDrops(player, pendingDropAllCatchupKeepToolsAndFood, collected);
        if (!collected.isEmpty()) {
            dropStacksNearPlayer(player, collected);
        }
    }

    public boolean isDropAllCatchupActiveFor(Player player, long gameTime) {
        if (player == null || pendingDropAllCatchupPlayerId == null) {
            return false;
        }
        if (gameTime >= pendingDropAllCatchupUntilTick) {
            clearDropAllCatchup();
            return false;
        }
        return pendingDropAllCatchupPlayerId.equals(player.getUUID());
    }

    private void clearDropAllCatchup() {
        pendingDropAllCatchupPlayerId = null;
        pendingDropAllCatchupUntilTick = -1L;
        pendingDropAllCatchupKeepToolsAndFood = false;
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

    private ItemStack takeHeldOrEquippedItem(ItemStack requested) {
        if (requested == null || requested.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack result = takeFromHand(InteractionHand.MAIN_HAND, requested);
        if (!result.isEmpty()) {
            return result;
        }
        result = takeFromHand(InteractionHand.OFF_HAND, requested);
        if (!result.isEmpty()) {
            return result;
        }
        for (CompanionToolSlot slot : CompanionToolSlot.values()) {
            ItemStack toolStack = owner.getToolSlot(slot);
            if (!matchesReturnItem(toolStack, requested)) {
                continue;
            }
            int toMove = Math.min(requested.getCount(), toolStack.getCount());
            ItemStack moved = toolStack.copy();
            moved.setCount(toMove);
            if (toMove >= toolStack.getCount()) {
                owner.setToolSlot(slot, ItemStack.EMPTY);
            } else {
                ItemStack left = toolStack.copy();
                left.shrink(toMove);
                owner.setToolSlot(slot, left);
            }
            return moved;
        }
        ItemStack foodSlot = owner.getFoodSlot();
        if (matchesReturnItem(foodSlot, requested)) {
            int toMove = Math.min(requested.getCount(), foodSlot.getCount());
            ItemStack moved = foodSlot.copy();
            moved.setCount(toMove);
            if (toMove >= foodSlot.getCount()) {
                owner.setFoodSlot(ItemStack.EMPTY);
            } else {
                ItemStack left = foodSlot.copy();
                left.shrink(toMove);
                owner.setFoodSlot(left);
            }
            return moved;
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack armor = owner.getItemBySlot(slot);
            if (!matchesReturnItem(armor, requested)) {
                continue;
            }
            int toMove = Math.min(requested.getCount(), armor.getCount());
            ItemStack moved = armor.copy();
            moved.setCount(toMove);
            if (toMove >= armor.getCount()) {
                owner.setItemSlot(slot, ItemStack.EMPTY);
            } else {
                ItemStack left = armor.copy();
                left.shrink(toMove);
                owner.setItemSlot(slot, left);
            }
            return moved;
        }
        return ItemStack.EMPTY;
    }

    private ItemStack takeFromHand(InteractionHand hand, ItemStack requested) {
        ItemStack inHand = hand == InteractionHand.MAIN_HAND ? owner.getMainHandItem() : owner.getOffhandItem();
        if (!matchesReturnItem(inHand, requested)) {
            return ItemStack.EMPTY;
        }
        int toMove = Math.min(requested.getCount(), inHand.getCount());
        ItemStack moved = inHand.copy();
        moved.setCount(toMove);
        ItemStack left = inHand.copy();
        left.shrink(toMove);
        owner.setItemInHand(hand, left.isEmpty() ? ItemStack.EMPTY : left);
        return moved;
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
