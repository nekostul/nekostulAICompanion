package ru.nekostul.aicompanion.entity.inventory;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import ru.nekostul.aicompanion.entity.CompanionEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class CompanionInventory {
    private final CompanionEntity owner;
    private final NonNullList<ItemStack> items;

    public CompanionInventory(CompanionEntity owner, int size) {
        this.owner = owner;
        this.items = NonNullList.withSize(size, ItemStack.EMPTY);
    }

    public NonNullList<ItemStack> getItems() {
        return items;
    }

    public void saveToTag(CompoundTag tag) {
        ContainerHelper.saveAllItems(tag, items);
    }

    public void loadFromTag(CompoundTag tag) {
        ContainerHelper.loadAllItems(tag, items);
    }

    public boolean isFull() {
        for (ItemStack stack : items) {
            if (stack.isEmpty()) {
                return false;
            }
            if (stack.getCount() < stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    public boolean add(ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        boolean added = addTo(items, stack);
        if (added) {
            owner.onInventoryUpdated();
        }
        return added;
    }

    public boolean addAll(List<ItemStack> stacks) {
        boolean changed = false;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack copy = stack.copy();
            if (addTo(items, copy)) {
                changed = true;
            }
        }
        if (changed) {
            owner.onInventoryUpdated();
        }
        return changed;
    }

    public boolean canStoreAll(List<ItemStack> drops) {
        NonNullList<ItemStack> copy = NonNullList.withSize(items.size(), ItemStack.EMPTY);
        for (int i = 0; i < items.size(); i++) {
            copy.set(i, items.get(i).copy());
        }
        for (ItemStack drop : drops) {
            if (!addTo(copy, drop.copy())) {
                return false;
            }
        }
        return true;
    }

    public int countItem(Item item) {
        int total = 0;
        for (ItemStack stack : items) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public int countTag(TagKey<Item> tag) {
        int total = 0;
        for (ItemStack stack : items) {
            if (stack.is(tag)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public int countMatching(Predicate<ItemStack> predicate) {
        int total = 0;
        for (ItemStack stack : items) {
            if (predicate.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public boolean hasItem(Item item) {
        for (ItemStack stack : items) {
            if (stack.is(item)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasItemTag(TagKey<Item> tag) {
        for (ItemStack stack : items) {
            if (stack.is(tag)) {
                return true;
            }
        }
        return false;
    }

    public boolean consumeItem(Item item, int count) {
        int remaining = count;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.is(item)) {
                continue;
            }
            int toRemove = Math.min(remaining, stack.getCount());
            stack.shrink(toRemove);
            remaining -= toRemove;
            if (stack.isEmpty()) {
                items.set(i, ItemStack.EMPTY);
            }
            if (remaining <= 0) {
                owner.onInventoryUpdated();
                return true;
            }
        }
        if (remaining != count) {
            owner.onInventoryUpdated();
        }
        return false;
    }

    public boolean consumeTag(TagKey<Item> tag, int count) {
        int remaining = count;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.is(tag)) {
                continue;
            }
            int toRemove = Math.min(remaining, stack.getCount());
            stack.shrink(toRemove);
            remaining -= toRemove;
            if (stack.isEmpty()) {
                items.set(i, ItemStack.EMPTY);
            }
            if (remaining <= 0) {
                owner.onInventoryUpdated();
                return true;
            }
        }
        if (remaining != count) {
            owner.onInventoryUpdated();
        }
        return false;
    }

    public int transferToPlayer(Player player, Predicate<ItemStack> predicate, int maxCount) {
        int remaining = maxCount;
        boolean changed = false;
        for (int i = 0; i < items.size(); i++) {
            if (remaining <= 0) {
                break;
            }
            ItemStack stack = items.get(i);
            if (stack.isEmpty() || !predicate.test(stack)) {
                continue;
            }
            int toMove = Math.min(remaining, stack.getCount());
            ItemStack moved = stack.copy();
            moved.setCount(toMove);
            if (!player.addItem(moved)) {
                continue;
            }
            stack.shrink(toMove);
            remaining -= toMove;
            changed = true;
            if (stack.isEmpty()) {
                items.set(i, ItemStack.EMPTY);
            }
        }
        if (changed) {
            owner.onInventoryUpdated();
        }
        return maxCount - remaining;
    }

    public List<ItemStack> takeMatching(Predicate<ItemStack> predicate, int maxCount) {
        int remaining = maxCount;
        boolean changed = false;
        List<ItemStack> removed = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (remaining <= 0) {
                break;
            }
            ItemStack stack = items.get(i);
            if (stack.isEmpty() || !predicate.test(stack)) {
                continue;
            }
            int toMove = Math.min(remaining, stack.getCount());
            ItemStack moved = stack.copy();
            moved.setCount(toMove);
            removed.add(moved);
            stack.shrink(toMove);
            remaining -= toMove;
            changed = true;
            if (stack.isEmpty()) {
                items.set(i, ItemStack.EMPTY);
            }
        }
        if (changed) {
            owner.onInventoryUpdated();
        }
        return removed;
    }

    public int transferToContainer(Container container) {
        int moved = 0;
        boolean changed = false;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack toMove = stack.copy();
            int before = toMove.getCount();
            ItemStack remainder = addToContainer(container, toMove);
            int movedNow = before - remainder.getCount();
            if (movedNow > 0) {
                stack.shrink(movedNow);
                moved += movedNow;
                changed = true;
                if (stack.isEmpty()) {
                    items.set(i, ItemStack.EMPTY);
                }
            }
        }
        if (changed) {
            owner.onInventoryUpdated();
        }
        return moved;
    }

    private static boolean addTo(NonNullList<ItemStack> target, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        for (int i = 0; i < target.size(); i++) {
            ItemStack existing = target.get(i);
            if (existing.isEmpty()) {
                continue;
            }
            if (!ItemStack.isSameItemSameTags(existing, stack)) {
                continue;
            }
            int space = existing.getMaxStackSize() - existing.getCount();
            if (space <= 0) {
                continue;
            }
            int toMove = Math.min(space, stack.getCount());
            existing.grow(toMove);
            stack.shrink(toMove);
            if (stack.isEmpty()) {
                return true;
            }
        }
        for (int i = 0; i < target.size(); i++) {
            ItemStack existing = target.get(i);
            if (!existing.isEmpty()) {
                continue;
            }
            target.set(i, stack.copy());
            stack.setCount(0);
            return true;
        }
        return stack.isEmpty();
    }

    private static ItemStack addToContainer(Container container, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack existing = container.getItem(i);
            if (existing.isEmpty()) {
                continue;
            }
            if (!ItemStack.isSameItemSameTags(existing, stack)) {
                continue;
            }
            int space = existing.getMaxStackSize() - existing.getCount();
            if (space <= 0) {
                continue;
            }
            int toMove = Math.min(space, stack.getCount());
            existing.grow(toMove);
            stack.shrink(toMove);
            container.setChanged();
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack existing = container.getItem(i);
            if (!existing.isEmpty()) {
                continue;
            }
            container.setItem(i, stack.copy());
            container.setChanged();
            return ItemStack.EMPTY;
        }
        return stack;
    }
}

