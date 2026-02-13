package ru.nekostul.aicompanion.entity.inventory;

import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.InteractionHand;

import ru.nekostul.aicompanion.entity.CompanionEntity;
import ru.nekostul.aicompanion.entity.tool.CompanionToolSlot;
import ru.nekostul.aicompanion.entity.resource.CompanionBlockRegistry;

public final class CompanionEquipment {
    private static final int TOOL_HOLD_TICKS = 10 * 20;

    private final CompanionEntity owner;
    private final CompanionInventory inventory;
    private CompanionToolSlot activeToolSlot;
    private long toolHoldUntilTick = -1L;

    public CompanionEquipment(CompanionEntity owner, CompanionInventory inventory) {
        this.owner = owner;
        this.inventory = inventory;
    }

    public void equipToolForBlock(BlockState state) {
        if (CompanionBlockRegistry.isLog(state)) {
            if (equipBestTool(ItemTags.AXES)) {
                return;
            }
        } else if (CompanionBlockRegistry.isPickaxeMineable(state)) {
            if (equipBestTool(ItemTags.PICKAXES)) {
                return;
            }
        } else if (CompanionBlockRegistry.isShovelMineable(state)) {
            equipBestTool(ItemTags.SHOVELS);
        }
    }

    public void equipBestWeapon() {
        if (equipBestTool(ItemTags.SWORDS)) {
            return;
        }
        equipBestTool(ItemTags.AXES);
    }

    public void equipIdleHand() {
        long gameTime = owner.level().getGameTime();
        if (gameTime < toolHoldUntilTick
                && !owner.getMainHandItem().isEmpty()
                && !owner.getMainHandItem().is(ItemTags.SWORDS)) {
            return;
        }
        if (!storeActiveTool()) {
            return;
        }
        if (!storeMainHandIfUntracked()) {
            return;
        }
        if (equipFromToolSlot(CompanionToolSlot.SWORD)) {
            return;
        }
        if (equipBestTool(ItemTags.SWORDS)) {
            return;
        }
        if (owner.getMainHandItem().isEmpty()) {
            owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            activeToolSlot = null;
        }
    }

    public void equipBestArmor() {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack current = owner.getItemBySlot(slot);
            int bestScore = armorScore(current);
            int bestSlotIndex = -1;
            for (int i = 0; i < inventory.getItems().size(); i++) {
                ItemStack stack = inventory.getItems().get(i);
                if (!(stack.getItem() instanceof ArmorItem)) {
                    continue;
                }
                if (!(stack.getItem() instanceof Equipable equipable) || equipable.getEquipmentSlot() != slot) {
                    continue;
                }
                int score = armorScore(stack);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlotIndex = i;
                }
            }
            if (bestSlotIndex >= 0) {
                equipArmorFromInventory(slot, bestSlotIndex);
            }
        }
    }

    public boolean equipBestTool(TagKey<Item> tag) {
        if (owner.getMainHandItem().is(tag)) {
            toolHoldUntilTick = owner.level().getGameTime() + TOOL_HOLD_TICKS;
            return true;
        }
        if (owner.getOffhandItem().is(tag)) {
            activeToolSlot = null;
            if (moveOffhandToMainHand()) {
                toolHoldUntilTick = owner.level().getGameTime() + TOOL_HOLD_TICKS;
                return true;
            }
            return false;
        }
        CompanionToolSlot toolSlot = CompanionToolSlot.fromTag(tag);
        if (toolSlot != null && equipFromToolSlot(toolSlot)) {
            if (owner.getMainHandItem().is(tag)) {
                toolHoldUntilTick = owner.level().getGameTime() + TOOL_HOLD_TICKS;
                return true;
            }
            return false;
        }
        int slot = findBestToolSlot(tag);
        if (slot < 0) {
            return false;
        }
        activeToolSlot = null;
        if (moveInventoryToMainHand(slot)) {
            toolHoldUntilTick = owner.level().getGameTime() + TOOL_HOLD_TICKS;
            return true;
        }
        return false;
    }

    private int findBestToolSlot(TagKey<Item> tag) {
        int bestSlot = -1;
        int bestTier = -1;
        for (int i = 0; i < inventory.getItems().size(); i++) {
            ItemStack stack = inventory.getItems().get(i);
            if (stack.isEmpty() || !stack.is(tag)) {
                continue;
            }
            int tier = getToolTier(stack);
            if (tier > bestTier) {
                bestTier = tier;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private int getToolTier(ItemStack stack) {
        if (stack.getItem() instanceof TieredItem tiered) {
            return tiered.getTier().getLevel();
        }
        return 0;
    }

    private boolean moveInventoryToMainHand(int slotIndex) {
        ItemStack candidate = inventory.getItems().get(slotIndex);
        if (candidate.isEmpty()) {
            return false;
        }
        ItemStack current = owner.getMainHandItem();
        owner.setItemInHand(InteractionHand.MAIN_HAND, candidate.copy());
        if (current.isEmpty()) {
            inventory.getItems().set(slotIndex, ItemStack.EMPTY);
        } else {
            inventory.getItems().set(slotIndex, current.copy());
        }
        activeToolSlot = null;
        return true;
    }

    private boolean moveOffhandToMainHand() {
        ItemStack offhand = owner.getOffhandItem();
        if (offhand.isEmpty()) {
            return false;
        }
        ItemStack current = owner.getMainHandItem();
        owner.setItemInHand(InteractionHand.MAIN_HAND, offhand.copy());
        owner.setItemInHand(InteractionHand.OFF_HAND, current.isEmpty() ? ItemStack.EMPTY : current.copy());
        activeToolSlot = null;
        return true;
    }

    private boolean equipFromToolSlot(CompanionToolSlot slot) {
        if (slot == null) {
            return false;
        }
        if (activeToolSlot == slot && owner.getMainHandItem().is(slot.getTag())) {
            return true;
        }
        if (!storeActiveTool()) {
            return false;
        }
        if (!storeMainHandIfUntracked()) {
            return false;
        }
        ItemStack tool = owner.takeToolSlot(slot);
        if (tool.isEmpty()) {
            return false;
        }
        owner.setItemInHand(InteractionHand.MAIN_HAND, tool);
        activeToolSlot = slot;
        return true;
    }

    private boolean storeActiveTool() {
        if (activeToolSlot == null) {
            return true;
        }
        ItemStack mainHand = owner.getMainHandItem();
        if (mainHand.isEmpty()) {
            activeToolSlot = null;
            return true;
        }
        if (owner.getToolSlot(activeToolSlot).isEmpty()) {
            owner.setToolSlot(activeToolSlot, mainHand);
            owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            activeToolSlot = null;
            return true;
        }
        if (inventory.add(mainHand.copy())) {
            owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            activeToolSlot = null;
            return true;
        }
        return false;
    }

    private boolean storeMainHandIfUntracked() {
        if (activeToolSlot != null) {
            return true;
        }
        ItemStack mainHand = owner.getMainHandItem();
        if (mainHand.isEmpty()) {
            return true;
        }
        CompanionToolSlot slot = CompanionToolSlot.fromStack(mainHand);
        if (slot != null && owner.getToolSlot(slot).isEmpty()) {
            owner.setToolSlot(slot, mainHand);
            owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            return true;
        }
        if (inventory.add(mainHand.copy())) {
            owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            return true;
        }
        return false;
    }

    private int armorScore(ItemStack stack) {
        if (!(stack.getItem() instanceof ArmorItem armor)) {
            return 0;
        }
        return armor.getDefense() * 100 + Math.round(armor.getToughness() * 10.0F);
    }

    private void equipArmorFromInventory(EquipmentSlot slot, int inventorySlot) {
        ItemStack candidate = inventory.getItems().get(inventorySlot);
        if (candidate.isEmpty()) {
            return;
        }
        ItemStack current = owner.getItemBySlot(slot);
        if (!current.isEmpty()) {
            if (!inventory.add(current.copy())) {
                return;
            }
            owner.setItemSlot(slot, ItemStack.EMPTY);
        }
        owner.setItemSlot(slot, candidate.copy());
        inventory.getItems().set(inventorySlot, ItemStack.EMPTY);
    }
}

