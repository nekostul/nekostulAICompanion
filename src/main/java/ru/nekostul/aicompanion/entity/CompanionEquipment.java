package ru.nekostul.aicompanion.entity;

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

final class CompanionEquipment {
    private final CompanionEntity owner;
    private final CompanionInventory inventory;

    CompanionEquipment(CompanionEntity owner, CompanionInventory inventory) {
        this.owner = owner;
        this.inventory = inventory;
    }

    void equipToolForBlock(BlockState state) {
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

    void equipBestWeapon() {
        if (equipBestTool(ItemTags.SWORDS)) {
            return;
        }
        equipBestTool(ItemTags.AXES);
    }

    void equipBestArmor() {
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

    boolean equipBestTool(TagKey<Item> tag) {
        if (owner.getMainHandItem().is(tag)) {
            return true;
        }
        if (owner.getOffhandItem().is(tag)) {
            return moveOffhandToMainHand();
        }
        int slot = findBestToolSlot(tag);
        if (slot < 0) {
            return false;
        }
        return moveInventoryToMainHand(slot);
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
        if (!current.isEmpty()) {
            if (!inventory.add(current.copy())) {
                return false;
            }
            owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        owner.setItemInHand(InteractionHand.MAIN_HAND, candidate.copy());
        inventory.getItems().set(slotIndex, ItemStack.EMPTY);
        return true;
    }

    private boolean moveOffhandToMainHand() {
        ItemStack offhand = owner.getOffhandItem();
        if (offhand.isEmpty()) {
            return false;
        }
        ItemStack current = owner.getMainHandItem();
        if (!current.isEmpty()) {
            if (!inventory.add(current.copy())) {
                return false;
            }
            owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        owner.setItemInHand(InteractionHand.MAIN_HAND, offhand.copy());
        owner.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        return true;
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
