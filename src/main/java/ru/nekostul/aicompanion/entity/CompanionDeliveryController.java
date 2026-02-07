package ru.nekostul.aicompanion.entity;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import ru.nekostul.aicompanion.entity.resource.CompanionResourceRequest;

import java.util.ArrayList;
import java.util.List;

final class CompanionDeliveryController {
    private static final double DELIVERY_RANGE_SQR = 9.0D;
    private static final int DELIVERY_COOLDOWN_TICKS = 10;

    private final CompanionEntity owner;
    private final CompanionInventory inventory;
    private int deliveredCount;
    private long nextDeliverTick;
    private List<ItemStack> pendingDrops;
    private int pendingDropIndex;

    CompanionDeliveryController(CompanionEntity owner, CompanionInventory inventory) {
        this.owner = owner;
        this.inventory = inventory;
    }

    void startDelivery() {
        deliveredCount = 0;
        nextDeliverTick = -1L;
        pendingDrops = null;
        pendingDropIndex = 0;
    }

    void startDelivery(List<ItemStack> drops) {
        deliveredCount = 0;
        nextDeliverTick = -1L;
        if (drops == null || drops.isEmpty()) {
            pendingDrops = null;
            pendingDropIndex = 0;
            return;
        }
        pendingDrops = new ArrayList<>();
        for (ItemStack stack : drops) {
            if (!stack.isEmpty()) {
                pendingDrops.add(stack);
            }
        }
        pendingDropIndex = 0;
    }

    boolean tickDelivery(CompanionResourceRequest request, Player player, long gameTime) {
        if (request == null || player == null) {
            return false;
        }
        if (deliveredCount >= request.getAmount()) {
            return true;
        }
        Vec3 target = player.position();
        if (owner.distanceToSqr(target) > DELIVERY_RANGE_SQR) {
            owner.getNavigation().moveTo(target.x, target.y, target.z, 1.1D);
            return false;
        }
        owner.getNavigation().stop();
        if (gameTime < nextDeliverTick) {
            return false;
        }
        nextDeliverTick = gameTime + DELIVERY_COOLDOWN_TICKS;
        int remaining = request.getAmount() - deliveredCount;
        List<ItemStack> toDrop = inventory.takeMatching(request.getResourceType()::matchesItem, remaining);
        int dropped = dropStacksNearPlayer(player, toDrop);
        deliveredCount += dropped;
        return deliveredCount >= request.getAmount();
    }

    boolean tickDeliveryStacks(Player player, long gameTime) {
        if (player == null) {
            return false;
        }
        if (pendingDrops == null || pendingDropIndex >= pendingDrops.size()) {
            return true;
        }
        Vec3 target = player.position();
        if (owner.distanceToSqr(target) > DELIVERY_RANGE_SQR) {
            owner.getNavigation().moveTo(target.x, target.y, target.z, 1.1D);
            return false;
        }
        owner.getNavigation().stop();
        if (gameTime < nextDeliverTick) {
            return false;
        }
        nextDeliverTick = gameTime + DELIVERY_COOLDOWN_TICKS;
        ItemStack stack = pendingDrops.get(pendingDropIndex);
        if (!stack.isEmpty()) {
            dropStacksNearPlayer(player, List.of(stack));
        }
        pendingDropIndex++;
        return pendingDropIndex >= pendingDrops.size();
    }

    private int dropStacksNearPlayer(Player player, List<ItemStack> stacks) {
        int dropped = 0;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            dropped += stack.getCount();
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
        return dropped;
    }
}
