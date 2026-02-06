package ru.nekostul.aicompanion.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

final class CompanionHungerSystem {
    private static final String HUNGER_NBT = "Hunger";
    private static final int MAX_HUNGER = 20;
    private static final int LOW_HUNGER = 6;
    private static final float LOW_HEALTH = 10.0F;
    private static final int HUNGER_DECAY_TICKS = 1200;
    private static final int FOOD_REQUEST_COOLDOWN_TICKS = 1200;
    private static final int EAT_COOLDOWN_TICKS = 40;
    private static final String FOOD_NEED_KEY = "entity.aicompanion.companion.food.need";

    private final CompanionEntity owner;
    private final CompanionInventory inventory;
    private int hunger = MAX_HUNGER;
    private long nextHungerTick = -1L;
    private long nextFoodRequestTick = -1L;
    private long nextEatTick = -1L;

    CompanionHungerSystem(CompanionEntity owner, CompanionInventory inventory) {
        this.owner = owner;
        this.inventory = inventory;
    }

    void tick(Player player, long gameTime) {
        updateHunger(gameTime);
        if (!needsFood()) {
            return;
        }
        if (gameTime < nextEatTick) {
            return;
        }
        ItemStack foodStack = findEdible();
        if (!foodStack.isEmpty()) {
            consumeFood(foodStack, gameTime);
            return;
        }
        if (player != null && !player.isSpectator() && gameTime >= nextFoodRequestTick) {
            nextFoodRequestTick = gameTime + FOOD_REQUEST_COOLDOWN_TICKS;
            owner.sendReply(player, Component.translatable(FOOD_NEED_KEY));
        }
    }

    void saveToTag(CompoundTag tag) {
        tag.putInt(HUNGER_NBT, hunger);
    }

    void loadFromTag(CompoundTag tag) {
        if (tag.contains(HUNGER_NBT)) {
            hunger = Math.max(0, Math.min(MAX_HUNGER, tag.getInt(HUNGER_NBT)));
        }
    }

    private void updateHunger(long gameTime) {
        if (nextHungerTick < 0L) {
            nextHungerTick = gameTime + HUNGER_DECAY_TICKS;
            return;
        }
        if (gameTime >= nextHungerTick) {
            hunger = Math.max(0, hunger - 1);
            nextHungerTick = gameTime + HUNGER_DECAY_TICKS;
        }
    }

    private boolean needsFood() {
        return hunger <= LOW_HUNGER || owner.getHealth() <= LOW_HEALTH;
    }

    private ItemStack findEdible() {
        for (ItemStack stack : inventory.getItems()) {
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.isEdible()) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private void consumeFood(ItemStack stack, long gameTime) {
        FoodProperties food = stack.getFoodProperties(owner);
        if (food == null) {
            return;
        }
        Item item = stack.getItem();
        if (!inventory.consumeItem(item, 1)) {
            return;
        }
        hunger = Math.min(MAX_HUNGER, hunger + food.getNutrition());
        float heal = Math.max(1.0F, food.getNutrition() * 0.5F);
        owner.heal(heal);
        owner.swing(InteractionHand.MAIN_HAND);
        owner.playSound(SoundEvents.GENERIC_EAT, 1.0F, 1.0F);
        nextEatTick = gameTime + EAT_COOLDOWN_TICKS;
    }
}
