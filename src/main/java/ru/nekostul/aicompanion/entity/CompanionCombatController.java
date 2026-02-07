package ru.nekostul.aicompanion.entity;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import ru.nekostul.aicompanion.entity.inventory.CompanionEquipment;

final class CompanionCombatController {
    private static final int DEFENSE_RADIUS = 12;
    private static final double DEFENSE_RADIUS_SQR = DEFENSE_RADIUS * DEFENSE_RADIUS;
    private static final double ATTACK_RANGE_SQR = 4.0D;
    private static final int ATTACK_COOLDOWN_TICKS = 20;
    private static final float LOW_HEALTH_THRESHOLD = 4.0F;

    private final CompanionEntity owner;
    private final CompanionEquipment equipment;
    private long nextAttackTick = -1L;

    CompanionCombatController(CompanionEntity owner, CompanionEquipment equipment) {
        this.owner = owner;
        this.equipment = equipment;
    }

    boolean tick(Player player, long gameTime) {
        if (player == null || player.isSpectator() || !player.isAlive()) {
            return false;
        }
        if (owner.getHealth() <= LOW_HEALTH_THRESHOLD) {
            retreatToPlayer(player);
            return true;
        }
        Monster threat = findThreat(player);
        if (threat == null) {
            return false;
        }
        engageThreat(threat, gameTime);
        return true;
    }

    private void retreatToPlayer(Player player) {
        owner.setTarget(null);
        Vec3 target = player.position();
        owner.getNavigation().moveTo(target.x, target.y, target.z, 1.35D);
    }

    private void engageThreat(Monster threat, long gameTime) {
        if (!threat.isAlive()) {
            return;
        }
        equipment.equipBestWeapon();
        owner.setTarget(threat);
        Vec3 target = threat.position();
        if (owner.distanceToSqr(target) > ATTACK_RANGE_SQR) {
            owner.getNavigation().moveTo(target.x, target.y, target.z, 1.25D);
            return;
        }
        owner.getNavigation().stop();
        owner.getLookControl().setLookAt(threat, 30.0F, 30.0F);
        if (gameTime >= nextAttackTick) {
            owner.swing(InteractionHand.MAIN_HAND, true);
            owner.doHurtTarget(threat);
            nextAttackTick = gameTime + ATTACK_COOLDOWN_TICKS;
        }
    }

    private Monster findThreat(Player player) {
        AABB range = player.getBoundingBox().inflate(DEFENSE_RADIUS);
        Monster nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Monster monster : owner.level().getEntitiesOfClass(Monster.class, range)) {
            if (!monster.isAlive()) {
                continue;
            }
            if (monster.getTarget() != null && monster.getTarget() != player && monster.getTarget() != owner) {
                continue;
            }
            double distance = player.distanceToSqr(monster);
            if (distance > DEFENSE_RADIUS_SQR) {
                continue;
            }
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = monster;
            }
        }
        return nearest;
    }
}
