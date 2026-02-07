package ru.nekostul.aicompanion.entity.tool;

import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public enum CompanionToolSlot {
    PICKAXE(ItemTags.PICKAXES),
    AXE(ItemTags.AXES),
    SHOVEL(ItemTags.SHOVELS),
    SWORD(ItemTags.SWORDS);

    private final TagKey<Item> tag;

    CompanionToolSlot(TagKey<Item> tag) {
        this.tag = tag;
    }

    public TagKey<Item> getTag() {
        return tag;
    }

    public boolean matches(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(tag);
    }

    public static CompanionToolSlot fromTag(TagKey<Item> tag) {
        if (tag == null) {
            return null;
        }
        for (CompanionToolSlot slot : values()) {
            if (slot.tag == tag) {
                return slot;
            }
        }
        return null;
    }

    public static CompanionToolSlot fromStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        for (CompanionToolSlot slot : values()) {
            if (stack.is(slot.tag)) {
                return slot;
            }
        }
        return null;
    }
}
