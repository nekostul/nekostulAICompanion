package ru.nekostul.aicompanion.entity;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import ru.nekostul.aicompanion.entity.inventory.CompanionEquipment;

final class CompanionCombatController {
    private static final int DEFENSE_RADIUS = 12;
    private static final double DEFENSE_RADIUS_SQR = DEFENSE_RADIUS * DEFENSE_RADIUS;
    private static final double ATTACK_RANGE_SQR = 4.0D;
    private static final double CHASE_START_RANGE_SQR = 6.25D;
    private static final double CHASE_STOP_RANGE_SQR = ATTACK_RANGE_SQR;
    private static final double COMBAT_CHASE_SPEED = 0.32D;
    private static final double RETREAT_SPEED = 0.32D;
    private static final int ATTACK_COOLDOWN_TICKS = 20;
    private static final int CHASE_REPATH_TICKS = 10;
    private static final int COMBAT_ACTIVE_TICKS = 20;
    private static final float LOW_HEALTH_THRESHOLD = 4.0F;
    private static final double IMMEDIATE_THREAT_DISTANCE_SQR = 4.0D;

    private final CompanionEntity owner;
    private final CompanionEquipment equipment;
    private long nextAttackTick = -1L;
    private BlockPos lastChasePos;
    private long lastChaseTick = -1L;
    private long lastCombatTick = -10000L;
    private boolean chasingTarget;

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
            lastCombatTick = gameTime;
            return true;
        }
        Monster threat = findThreat(player);
        if (threat == null) {
            return false;
        }
        engageThreat(threat, gameTime);
        lastCombatTick = gameTime;
        return true;
    }

    boolean isEngaged(long gameTime) {
        return gameTime - lastCombatTick <= COMBAT_ACTIVE_TICKS;
    }

    private void retreatToPlayer(Player player) {
        owner.setTarget(null);
        Vec3 target = player.position();
        owner.getNavigation().moveTo(target.x, target.y, target.z, navSpeed(RETREAT_SPEED));
    }

    private void engageThreat(Monster threat, long gameTime) {
        if (!threat.isAlive()) {
            resetChaseMoveTracking();
            chasingTarget = false;
            return;
        }
        equipment.equipBestWeapon();
        owner.setTarget(threat);
        Vec3 target = threat.position();
        double distanceSqr = owner.distanceToSqr(target);
        if (!chasingTarget && distanceSqr > CHASE_START_RANGE_SQR) {
            chasingTarget = true;
        }
        if (chasingTarget && distanceSqr > CHASE_STOP_RANGE_SQR) {
            if (shouldIssueChaseMove(target, gameTime)) {
                owner.getNavigation().moveTo(threat, navSpeed(COMBAT_CHASE_SPEED));
                rememberChaseMove(target, gameTime);
            }
            return;
        }
        chasingTarget = false;
        resetChaseMoveTracking();
        owner.getNavigation().stop();
        owner.getLookControl().setLookAt(threat, 30.0F, 30.0F);
        if (gameTime >= nextAttackTick) {
            owner.swing(InteractionHand.MAIN_HAND, true);
            owner.doHurtTarget(threat);
            nextAttackTick = gameTime + ATTACK_COOLDOWN_TICKS;
        }
    }

    private boolean shouldIssueChaseMove(Vec3 target, long gameTime) {
        if (target == null) {
            return false;
        }
        BlockPos targetPos = BlockPos.containing(target);
        if (lastChasePos == null || !lastChasePos.equals(targetPos)) {
            return true;
        }
        if (!owner.getNavigation().isDone()) {
            return false;
        }
        return lastChaseTick < 0L || gameTime - lastChaseTick >= CHASE_REPATH_TICKS;
    }

    private void rememberChaseMove(Vec3 target, long gameTime) {
        lastChasePos = target != null ? BlockPos.containing(target) : null;
        lastChaseTick = gameTime;
    }

    private void resetChaseMoveTracking() {
        lastChasePos = null;
        lastChaseTick = -1L;
    }

    private double navSpeed(double desiredSpeed) {
        double baseSpeed = owner.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (baseSpeed <= 1.0E-4D) {
            return desiredSpeed;
        }
        return desiredSpeed / baseSpeed;
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
            if (!isThreatRelevant(player, monster, distance)) {
                continue;
            }
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = monster;
            }
        }
        return nearest;
    }

    private boolean isThreatRelevant(Player player, Monster monster, double distanceSqr) {
        if (isImmediateThreat(player, monster, distanceSqr)) {
            return true;
        }
        if (monster.hasLineOfSight(player)) {
            return true;
        }
        return canReachPlayer(monster, player);
    }

    private boolean isImmediateThreat(Player player, Monster monster, double distanceSqr) {
        if (distanceSqr > IMMEDIATE_THREAT_DISTANCE_SQR) {
            return false;
        }
        return player.getLastHurtByMob() == monster;
    }

    private boolean canReachPlayer(Monster monster, Player player) {
        if (monster.getNavigation() == null) {
            return false;
        }
        Path path = monster.getNavigation().createPath(player, 0);
        return path != null && path.canReach();
    }
}
