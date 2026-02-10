package ru.nekostul.aicompanion.entity.gratitude;

import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import ru.nekostul.aicompanion.entity.CompanionEntity;
import ru.nekostul.aicompanion.entity.inventory.CompanionDropTracker;

import java.util.Random;
import java.util.UUID;

public final class CompanionPickupGratitude {
    private static final String[] THANK_KEYS = range("entity.aicompanion.companion.thanks.pickup.", 1, 20);
    private static final int THANK_COOLDOWN_TICKS = 200;

    private final CompanionEntity owner;
    private final Random random = new Random();
    private long nextThankTick = -1L;
    private int lastIndex = -1;

    public CompanionPickupGratitude(CompanionEntity owner) {
        this.owner = owner;
    }

    public void onPickup(ItemEntity itemEntity, ItemStack pickedStack, long gameTime) {
        if (pickedStack == null || pickedStack.isEmpty()) {
            return;
        }
        if (CompanionDropTracker.isMobDrop(itemEntity)) {
            return;
        }
        if (!isThankable(pickedStack)) {
            return;
        }
        Player player = resolvePlayer(itemEntity);
        if (player == null) {
            return;
        }
        if (gameTime < nextThankTick) {
            return;
        }
        int index = pickIndex();
        nextThankTick = gameTime + THANK_COOLDOWN_TICKS;
        owner.sendReply(player, Component.translatable(THANK_KEYS[index]));
    }

    private boolean isThankable(ItemStack stack) {
        if (stack.isEdible()) {
            return true;
        }
        if (stack.is(ItemTags.AXES)
                || stack.is(ItemTags.PICKAXES)
                || stack.is(ItemTags.SHOVELS)
                || stack.is(ItemTags.SWORDS)
                || stack.is(ItemTags.HOES)) {
            return true;
        }
        return stack.is(Items.SHEARS);
    }

    private Player resolvePlayer(ItemEntity itemEntity) {
        if (itemEntity == null) {
            return null;
        }
        UUID ownerId = CompanionDropTracker.getPlayerDropper(itemEntity);
        if (ownerId == null) {
            return null;
        }
        Player player = owner.level().getPlayerByUUID(ownerId);
        if (player == null || player.isSpectator()) {
            return null;
        }
        return player;
    }

    private int pickIndex() {
        if (THANK_KEYS.length == 1) {
            return 0;
        }
        int index = random.nextInt(THANK_KEYS.length);
        if (index == lastIndex) {
            index = (index + 1) % THANK_KEYS.length;
        }
        lastIndex = index;
        return index;
    }

    private static String[] range(String prefix, int start, int end) {
        String[] keys = new String[end - start + 1];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = prefix + (start + i);
        }
        return keys;
    }
}

