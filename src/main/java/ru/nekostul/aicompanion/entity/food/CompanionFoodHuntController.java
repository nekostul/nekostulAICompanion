package ru.nekostul.aicompanion.entity.food;

import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import ru.nekostul.aicompanion.entity.CompanionEntity;
import ru.nekostul.aicompanion.entity.inventory.CompanionEquipment;
import ru.nekostul.aicompanion.entity.inventory.CompanionInventory;

import java.util.UUID;

public final class CompanionFoodHuntController {
    private static final int HUNT_RADIUS = 16;
    private static final double HUNT_RADIUS_SQR = HUNT_RADIUS * HUNT_RADIUS;
    private static final double ATTACK_RANGE_SQR = 4.0D;
    private static final int ATTACK_COOLDOWN_TICKS = 20;
    private static final int FOOD_RESERVE_MIN = 4;

    private final CompanionEntity owner;
    private final CompanionInventory inventory;
    private final CompanionEquipment equipment;
    private final CompanionHungerSystem hungerSystem;
    private UUID targetId;
    private long nextAttackTick = -1L;

    public CompanionFoodHuntController(CompanionEntity owner,
                                CompanionInventory inventory,
                                CompanionEquipment equipment,
                                CompanionHungerSystem hungerSystem) {
        this.owner = owner;
        this.inventory = inventory;
        this.equipment = equipment;
        this.hungerSystem = hungerSystem;
    }

    public boolean tick(Player player, long gameTime, boolean busy) {
        if (busy) {
            return false;
        }
        if (owner.getMode() == CompanionEntity.CompanionMode.STOPPED) {
            return false;
        }
        if (player == null || player.isSpectator() || !player.isAlive()) {
            return false;
        }
        if (!shouldHunt()) {
            clearTarget();
            return false;
        }
        Animal target = resolveTarget();
        if (target == null) {
            return false;
        }
        huntTarget(target, gameTime);
        return true;
    }

    private boolean shouldHunt() {
        if (inventory.isFull()) {
            return false;
        }
        if (!hungerSystem.isHungry()) {
            return false;
        }
        return countEdible() < FOOD_RESERVE_MIN;
    }

    private int countEdible() {
        int total = 0;
        ItemStack foodSlot = owner.getFoodSlot();
        if (!foodSlot.isEmpty() && foodSlot.isEdible()) {
            total += foodSlot.getCount();
        }
        for (ItemStack stack : inventory.getItems()) {
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.isEdible()) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private Animal resolveTarget() {
        Animal current = findTargetById(targetId);
        if (isValidTarget(current)) {
            return current;
        }
        Animal nearest = findNearestFoodAnimal();
        targetId = nearest != null ? nearest.getUUID() : null;
        return nearest;
    }

    private boolean isValidTarget(Animal animal) {
        if (animal == null || !animal.isAlive() || animal.isBaby()) {
            return false;
        }
        if (!isFoodAnimal(animal)) {
            return false;
        }
        return owner.distanceToSqr(animal) <= HUNT_RADIUS_SQR;
    }

    private Animal findTargetById(UUID id) {
        if (id == null) {
            return null;
        }
        AABB range = owner.getBoundingBox().inflate(HUNT_RADIUS);
        for (Animal animal : owner.level().getEntitiesOfClass(Animal.class, range)) {
            if (animal.getUUID().equals(id)) {
                return animal;
            }
        }
        return null;
    }

    private Animal findNearestFoodAnimal() {
        AABB range = owner.getBoundingBox().inflate(HUNT_RADIUS);
        Animal nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Animal animal : owner.level().getEntitiesOfClass(Animal.class, range)) {
            if (!isValidTarget(animal)) {
                continue;
            }
            double distance = owner.distanceToSqr(animal);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = animal;
            }
        }
        return nearest;
    }

    private boolean isFoodAnimal(Animal animal) {
        return animal instanceof Cow
                || animal instanceof Pig
                || animal instanceof Sheep
                || animal instanceof Chicken
                || animal instanceof Rabbit;
    }

    private void huntTarget(Animal target, long gameTime) {
        if (!target.isAlive()) {
            clearTarget();
            return;
        }
        equipment.equipBestWeapon();
        owner.setTarget(target);
        Vec3 targetPos = target.position();
        if (owner.distanceToSqr(targetPos) > ATTACK_RANGE_SQR) {
            owner.getNavigation().moveTo(targetPos.x, targetPos.y, targetPos.z, 1.2D);
            return;
        }
        owner.getNavigation().stop();
        owner.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (gameTime >= nextAttackTick) {
            owner.performCriticalMobHit(target);
            nextAttackTick = gameTime + ATTACK_COOLDOWN_TICKS;
        }
    }

    private void clearTarget() {
        targetId = null;
    }
}

