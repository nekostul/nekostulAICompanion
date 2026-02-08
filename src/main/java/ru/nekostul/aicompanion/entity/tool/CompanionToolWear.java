package ru.nekostul.aicompanion.entity.tool;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;

import ru.nekostul.aicompanion.entity.CompanionEntity;

public final class CompanionToolWear {
    private static final float WOOD_WEAR_CHANCE = 1.0F;
    private static final float STONE_WEAR_CHANCE = 0.85F;
    private static final float IRON_WEAR_CHANCE = 0.7F;
    private static final float DIAMOND_WEAR_CHANCE = 0.55F;
    private static final float NETHERITE_WEAR_CHANCE = 0.45F;

    private CompanionToolWear() {
    }

    public static void applyToolWear(CompanionEntity owner, ItemStack tool, InteractionHand hand) {
        if (owner == null || tool == null || tool.isEmpty() || !tool.isDamageableItem()) {
            return;
        }
        float chance = resolveWearChance(tool);
        if (chance <= 0.0F) {
            return;
        }
        if (chance < 1.0F && owner.getRandom().nextFloat() > chance) {
            return;
        }
        tool.hurtAndBreak(1, owner, entity -> entity.broadcastBreakEvent(hand));
    }

    private static float resolveWearChance(ItemStack tool) {
        if (!(tool.getItem() instanceof TieredItem tiered)) {
            return WOOD_WEAR_CHANCE;
        }
        int level = tiered.getTier().getLevel();
        if (level <= 0) {
            return WOOD_WEAR_CHANCE;
        }
        if (level == 1) {
            return STONE_WEAR_CHANCE;
        }
        if (level == 2) {
            return IRON_WEAR_CHANCE;
        }
        if (level == 3) {
            return DIAMOND_WEAR_CHANCE;
        }
        return NETHERITE_WEAR_CHANCE;
    }
}
