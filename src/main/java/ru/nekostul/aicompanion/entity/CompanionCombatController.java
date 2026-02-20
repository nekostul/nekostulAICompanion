package ru.nekostul.aicompanion.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import ru.nekostul.aicompanion.entity.inventory.CompanionEquipment;
import ru.nekostul.aicompanion.entity.movement.CompanionMovementSpeed;

final class CompanionCombatController {
    private static final int DEFENSE_RADIUS = 7;
    private static final double DEFENSE_RADIUS_SQR = DEFENSE_RADIUS * DEFENSE_RADIUS;
    private static final double ATTACK_RANGE_SQR = 4.0D;
    private static final double WATER_ATTACK_RANGE_SQR = 6.25D;
    private static final double CHASE_START_RANGE_SQR = 6.25D;
    private static final double CHASE_STOP_RANGE_SQR = ATTACK_RANGE_SQR;
    private static final double COMBAT_CHASE_SPEED = 0.42D;
    private static final double RETREAT_SPEED = 0.32D;
    private static final int ATTACK_COOLDOWN_TICKS = 20;
    private static final int COMBAT_ACTIVE_TICKS = 20;
    private static final float LOW_HEALTH_THRESHOLD = 4.0F;
    private static final double URGENT_ASSIST_RADIUS_SQR = 32.0D * 32.0D;
    private static final double DODGE_TRIGGER_RANGE_SQR = 9.0D;
    private static final double DODGE_STEP_DISTANCE = 1.2D;
    private static final int DODGE_COOLDOWN_MIN_TICKS = 12;
    private static final int DODGE_COOLDOWN_RANGE_TICKS = 16;

    private final CompanionEntity owner;
    private final CompanionEquipment equipment;
    private long nextAttackTick = -1L;
    private BlockPos lastChasePos;
    private long lastChaseTick = -1L;
    private long lastCombatTick = -10000L;
    private boolean chasingTarget;
    private long nextDodgeTick = -1L;

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

    boolean tickUrgentDefense(Player player, long gameTime) {
        if (player == null || player.isSpectator() || !player.isAlive()) {
            return false;
        }
        if (owner.getHealth() <= LOW_HEALTH_THRESHOLD) {
            return false;
        }
        Monster urgentThreat = findUrgentThreat(player);
        if (urgentThreat == null) {
            return false;
        }
        engageThreat(urgentThreat, gameTime);
        lastCombatTick = gameTime;
        return true;
    }

    boolean isEngaged(long gameTime) {
        return gameTime - lastCombatTick <= COMBAT_ACTIVE_TICKS;
    }

    void clearCombatFocus() {
        chasingTarget = false;
        resetChaseMoveTracking();
        owner.setTarget(null);
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
        double attackRangeSqr = resolveAttackRangeSqr(threat);
        double chaseStopRangeSqr = Math.max(CHASE_STOP_RANGE_SQR, attackRangeSqr);
        if (!chasingTarget && distanceSqr > chaseStopRangeSqr) {
            chasingTarget = true;
        }
        if (chasingTarget && distanceSqr > chaseStopRangeSqr) {
            owner.getNavigation().moveTo(threat, navSpeed(COMBAT_CHASE_SPEED));
            rememberChaseMove(target, gameTime);
            return;
        }
        chasingTarget = false;
        resetChaseMoveTracking();
        owner.getNavigation().stop();
        owner.getLookControl().setLookAt(threat, 30.0F, 30.0F);
        if (distanceSqr <= attackRangeSqr && gameTime >= nextAttackTick) {
            owner.performCriticalMobHit(threat);
            nextAttackTick = gameTime + ATTACK_COOLDOWN_TICKS;
        }
    }

    private double resolveAttackRangeSqr(Monster threat) {
        if (owner.isInWaterOrBubble() || (threat != null && threat.isInWaterOrBubble())) {
            return WATER_ATTACK_RANGE_SQR;
        }
        return ATTACK_RANGE_SQR;
    }

    private void tryDodgeThreat(Monster threat, long gameTime, double distanceSqr) {
        if (threat == null) {
            return;
        }
        if (gameTime < nextDodgeTick || distanceSqr > DODGE_TRIGGER_RANGE_SQR || !threat.hasLineOfSight(owner)) {
            return;
        }
        Vec3 toThreat = threat.position().subtract(owner.position());
        Vec3 strafe = new Vec3(-toThreat.z, 0.0D, toThreat.x);
        if (strafe.lengthSqr() < 1.0E-4D) {
            return;
        }
        strafe = strafe.normalize();
        if (owner.getRandom().nextBoolean()) {
            strafe = strafe.scale(-1.0D);
        }
        Vec3 dodgeTarget = owner.position().add(strafe.scale(DODGE_STEP_DISTANCE));
        owner.getNavigation().moveTo(dodgeTarget.x, dodgeTarget.y, dodgeTarget.z, navSpeed(COMBAT_CHASE_SPEED));
        if (owner.onGround() && owner.getRandom().nextFloat() < 0.35F) {
            owner.setDeltaMovement(owner.getDeltaMovement().x, 0.30D, owner.getDeltaMovement().z);
        }
        nextDodgeTick = gameTime + DODGE_COOLDOWN_MIN_TICKS + owner.getRandom().nextInt(DODGE_COOLDOWN_RANGE_TICKS + 1);
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
        return CompanionMovementSpeed.fallbackDesiredByAttribute(owner, desiredSpeed);
    }

    private Monster findThreat(Player player) {
        AABB range = owner.getBoundingBox().inflate(DEFENSE_RADIUS);
        Monster nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Monster monster : owner.level().getEntitiesOfClass(Monster.class, range)) {
            if (!monster.isAlive()) {
                continue;
            }
            double distance = owner.distanceToSqr(monster);
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

    private Monster findUrgentThreat(Player player) {
        if (player == null || !player.isAlive()) {
            return null;
        }
        if (player.getLastHurtByMob() instanceof Monster monster && monster.isAlive()) {
            if (player.distanceToSqr(monster) <= URGENT_ASSIST_RADIUS_SQR) {
                return monster;
            }
        }
        return null;
    }

}
