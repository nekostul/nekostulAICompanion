package ru.nekostul.aicompanion.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

final class CompanionMovementController {
    private enum MoveState {
        WALK,
        RUN
    }

    private static final double PLAYER_WALK_SPEED = 0.3D;
    private static final double PLAYER_SPRINT_MULTIPLIER = 1.5D;
    private static final double WALK_DISTANCE_SQR = 16.0D;
    private static final double RUN_DISTANCE_SQR = 16.0D;
    private static final double HOLD_DISTANCE_SQR = 4.0D;
    private static final double PLAYER_IDLE_SPEED = 0.02D;
    private static final double PLAYER_WALK_RATIO_MIN = 1.0D;
    private static final double PLAYER_WALK_RATIO_MAX = 1.0D;
    private static final double PLAYER_RUN_RATIO_MIN = 1.0D;
    private static final double PLAYER_RUN_RATIO_MAX = 1.0D;
    private static final int STATE_LOCK_TICKS = 20;
    private static final int JUMP_COOLDOWN_TICKS = 8;
    private static final double SPEED_STEP_UP = 0.05D;
    private static final double SPEED_STEP_DOWN = 0.02D;

    private final PathfinderMob mob;
    private final double maxSpeedModifier;
    private final CompanionSafeMovement safeMovement;
    private MoveState state = MoveState.WALK;
    private long stateLockUntilTick;
    private long nextJumpTick;
    private double currentSpeed;
    private boolean holdPosition;
    private CompanionSafeMovement.SafetyLevel safetyLevel = CompanionSafeMovement.SafetyLevel.SAFE;

    CompanionMovementController(PathfinderMob mob, double maxSpeedModifier) {
        this.mob = mob;
        this.maxSpeedModifier = maxSpeedModifier;
        this.safeMovement = new CompanionSafeMovement(mob);
        this.currentSpeed = Math.min(walkSpeedModifier(), maxSpeedModifier);
    }

    double update(Player target, Vec3 followPos, long gameTime, double distanceSqr) {
        if (target == null || followPos == null) {
            return currentSpeed;
        }
        safetyLevel = safeMovement.evaluate(followPos);
        holdPosition = shouldHold(target, distanceSqr);
        if (holdPosition || safetyLevel == CompanionSafeMovement.SafetyLevel.DANGER) {
            currentSpeed = approach(currentSpeed, 0.0D, SPEED_STEP_UP, SPEED_STEP_DOWN);
            return currentSpeed;
        }
        double playerRatio = playerSpeedRatio(target);
        MoveState desired = chooseState(target, distanceSqr, playerRatio);
        if (gameTime >= stateLockUntilTick && desired != state) {
            state = desired;
            stateLockUntilTick = gameTime + STATE_LOCK_TICKS;
        }
        double wantedSpeed = desiredSpeed(distanceSqr, playerRatio);
        currentSpeed = approach(currentSpeed, wantedSpeed, SPEED_STEP_UP, SPEED_STEP_DOWN);
        return currentSpeed;
    }

    void reset() {
        state = MoveState.WALK;
        stateLockUntilTick = 0L;
        nextJumpTick = 0L;
        currentSpeed = Math.min(walkSpeedModifier(), maxSpeedModifier);
        holdPosition = false;
        safetyLevel = CompanionSafeMovement.SafetyLevel.SAFE;
    }

    boolean shouldHoldPosition() {
        return holdPosition;
    }

    private MoveState chooseState(Player target, double distanceSqr, double playerRatio) {
        if (safetyLevel != CompanionSafeMovement.SafetyLevel.SAFE) {
            return MoveState.WALK;
        }
        if (distanceSqr > WALK_DISTANCE_SQR) {
            return MoveState.RUN;
        }
        return MoveState.WALK;
    }

    private double desiredSpeed(double distanceSqr, double playerRatio) {
        double speed;
        double walkSpeed = walkSpeedModifier();
        double runSpeed = runSpeedModifier();
        if (RUN_DISTANCE_SQR <= WALK_DISTANCE_SQR) {
            if (distanceSqr <= WALK_DISTANCE_SQR) {
                speed = walkSpeed * clamp(playerRatio, PLAYER_WALK_RATIO_MIN, PLAYER_WALK_RATIO_MAX);
            } else {
                speed = runSpeed * clamp(playerRatio, PLAYER_RUN_RATIO_MIN, PLAYER_RUN_RATIO_MAX);
            }
        } else if (distanceSqr <= WALK_DISTANCE_SQR) {
            speed = walkSpeed * clamp(playerRatio, PLAYER_WALK_RATIO_MIN, PLAYER_WALK_RATIO_MAX);
        } else if (distanceSqr >= RUN_DISTANCE_SQR) {
            speed = runSpeed * clamp(playerRatio, PLAYER_RUN_RATIO_MIN, PLAYER_RUN_RATIO_MAX);
        } else {
            double t = (distanceSqr - WALK_DISTANCE_SQR) / (RUN_DISTANCE_SQR - WALK_DISTANCE_SQR);
            double walkBlend = walkSpeedModifier() * clamp(playerRatio, PLAYER_WALK_RATIO_MIN, PLAYER_WALK_RATIO_MAX);
            double runBlend = runSpeedModifier() * clamp(playerRatio, PLAYER_RUN_RATIO_MIN, PLAYER_RUN_RATIO_MAX);
            speed = lerp(walkBlend, runBlend, t);
        }
        if (safetyLevel == CompanionSafeMovement.SafetyLevel.CAUTION) {
            speed = Math.min(speed, walkSpeedModifier() * 0.85D);
        }
        return Math.min(speed, maxSpeedModifier);
    }

    private double walkSpeedModifier() {
        return speedModifierFor(PLAYER_WALK_SPEED);
    }

    private double runSpeedModifier() {
        return speedModifierFor(PLAYER_WALK_SPEED * PLAYER_SPRINT_MULTIPLIER);
    }

    private double speedModifierFor(double desiredSpeed) {
        double base = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (base <= 0.0D) {
            return 0.0D;
        }
        return desiredSpeed / base;
    }

    private void tryJump(long gameTime) {
        if (safetyLevel != CompanionSafeMovement.SafetyLevel.SAFE) {
            return;
        }
        if (!mob.onGround() || mob.isInWaterOrBubble()) {
            return;
        }
        if (gameTime < nextJumpTick) {
            return;
        }
        if (!shouldJumpForPath()) {
            return;
        }
        mob.getJumpControl().jump();
        nextJumpTick = gameTime + JUMP_COOLDOWN_TICKS;
    }

    private boolean shouldJumpForPath() {
        if (mob.getNavigation() == null) {
            return false;
        }
        net.minecraft.world.level.pathfinder.Path path = mob.getNavigation().getPath();
        if (path == null || path.isDone()) {
            return false;
        }
        BlockPos next = path.getNextNodePos();
        return next.getY() > mob.getBlockY();
    }

    private boolean shouldHold(Player target, double distanceSqr) {
        double playerSpeed = horizontalSpeed(target);
        if (playerSpeed < PLAYER_IDLE_SPEED && distanceSqr <= HOLD_DISTANCE_SQR) {
            return true;
        }
        return false;
    }

    private double playerSpeedRatio(Player target) {
        double base = target.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (base <= 0.0D) {
            return 0.0D;
        }
        return horizontalSpeed(target) / base;
    }

    private double horizontalSpeed(Player target) {
        Vec3 delta = target.getDeltaMovement();
        return Math.hypot(delta.x, delta.z);
    }

    private double approach(double current, double target, double stepUp, double stepDown) {
        if (current == target) {
            return current;
        }
        double delta = target - current;
        if (delta > 0) {
            if (delta <= stepUp) {
                return target;
            }
            return current + stepUp;
        }
        if (Math.abs(delta) <= stepDown) {
            return target;
        }
        return current - stepDown;
    }

    private double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    private double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }
}
