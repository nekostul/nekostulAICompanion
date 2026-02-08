package ru.nekostul.aicompanion.entity.ai;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

final class CompanionMovementController {
    private enum MoveState {
        WALK,
        RUN,
        RUN_JUMP
    }

    private static final double WALK_SPEED = 0.95D;
    private static final double RUN_SPEED = 1.25D;
    private static final double WALK_DISTANCE_SQR = 36.0D;
    private static final double RUN_DISTANCE_SQR = 121.0D;
    private static final double JUMP_DISTANCE_SQR = 144.0D;
    private static final double CATCHUP_DISTANCE_SQR = 400.0D;
    private static final double HOLD_DISTANCE_SQR = 36.0D;
    private static final double PLAYER_IDLE_SPEED = 0.02D;
    private static final double PLAYER_WALK_RATIO_MIN = 0.6D;
    private static final double PLAYER_WALK_RATIO_MAX = 1.2D;
    private static final double PLAYER_RUN_RATIO_MIN = 0.9D;
    private static final double PLAYER_RUN_RATIO_MAX = 1.45D;
    private static final int STATE_LOCK_TICKS = 20;
    private static final int JUMP_COOLDOWN_TICKS = 8;
    private static final double SPEED_STEP_UP = 0.05D;
    private static final double SPEED_STEP_DOWN = 0.02D;
    private static final double JUMP_SPEED_BONUS = 0.0D;

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
        this.currentSpeed = Math.min(WALK_SPEED, maxSpeedModifier);
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
        if (state == MoveState.RUN_JUMP && !mob.onGround()) {
            wantedSpeed = Math.min(maxSpeedModifier, wantedSpeed + JUMP_SPEED_BONUS);
        }
        currentSpeed = approach(currentSpeed, wantedSpeed, SPEED_STEP_UP, SPEED_STEP_DOWN);
        tryJump(gameTime, distanceSqr);
        return currentSpeed;
    }

    void reset() {
        state = MoveState.WALK;
        stateLockUntilTick = 0L;
        nextJumpTick = 0L;
        currentSpeed = Math.min(WALK_SPEED, maxSpeedModifier);
        holdPosition = false;
        safetyLevel = CompanionSafeMovement.SafetyLevel.SAFE;
    }

    boolean shouldHoldPosition() {
        return holdPosition;
    }

    private MoveState chooseState(Player target, double distanceSqr, double playerRatio) {
        boolean far = distanceSqr > RUN_DISTANCE_SQR;
        boolean veryFar = distanceSqr > CATCHUP_DISTANCE_SQR;
        boolean sprinting = target.isSprinting() || playerRatio > 1.2D;

        if (safetyLevel != CompanionSafeMovement.SafetyLevel.SAFE) {
            return MoveState.WALK;
        }
        if (state == MoveState.RUN_JUMP) {
            if (!sprinting && distanceSqr < JUMP_DISTANCE_SQR) {
                return MoveState.RUN;
            }
            return MoveState.RUN_JUMP;
        }
        if (state == MoveState.RUN) {
            if (!far) {
                return MoveState.WALK;
            }
            if (sprinting || veryFar) {
                return MoveState.RUN_JUMP;
            }
            return MoveState.RUN;
        }

        if (far) {
            return (sprinting || veryFar) ? MoveState.RUN_JUMP : MoveState.RUN;
        }
        return MoveState.WALK;
    }

    private double desiredSpeed(double distanceSqr, double playerRatio) {
        double speed;
        if (distanceSqr <= WALK_DISTANCE_SQR) {
            speed = WALK_SPEED * clamp(playerRatio, PLAYER_WALK_RATIO_MIN, PLAYER_WALK_RATIO_MAX);
        } else if (distanceSqr >= RUN_DISTANCE_SQR) {
            speed = RUN_SPEED * clamp(playerRatio, PLAYER_RUN_RATIO_MIN, PLAYER_RUN_RATIO_MAX);
        } else {
            double t = (distanceSqr - WALK_DISTANCE_SQR) / (RUN_DISTANCE_SQR - WALK_DISTANCE_SQR);
            double walkSpeed = WALK_SPEED * clamp(playerRatio, PLAYER_WALK_RATIO_MIN, PLAYER_WALK_RATIO_MAX);
            double runSpeed = RUN_SPEED * clamp(playerRatio, PLAYER_RUN_RATIO_MIN, PLAYER_RUN_RATIO_MAX);
            speed = lerp(walkSpeed, runSpeed, t);
        }
        if (safetyLevel == CompanionSafeMovement.SafetyLevel.CAUTION) {
            speed = Math.min(speed, WALK_SPEED * 0.85D);
        }
        return Math.min(speed, maxSpeedModifier);
    }

    private void tryJump(long gameTime, double distanceSqr) {
        if (state != MoveState.RUN_JUMP) {
            return;
        }
        if (distanceSqr < JUMP_DISTANCE_SQR) {
            return;
        }
        if (safetyLevel != CompanionSafeMovement.SafetyLevel.SAFE) {
            return;
        }
        if (!mob.onGround() || mob.isInWaterOrBubble()) {
            return;
        }
        if (gameTime < nextJumpTick) {
            return;
        }
        mob.getJumpControl().jump();
        nextJumpTick = gameTime + JUMP_COOLDOWN_TICKS;
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
