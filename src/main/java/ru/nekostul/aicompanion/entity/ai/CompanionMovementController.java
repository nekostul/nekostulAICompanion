package ru.nekostul.aicompanion.entity.ai;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

final class CompanionMovementController {
    private enum MoveState {
        WALK,
        RUN,
        RUN_JUMP
    }

    private static final double WALK_SPEED = 0.95D;
    private static final double RUN_SPEED = 1.1D;
    private static final double WALK_DISTANCE_SQR = 64.0D;
    private static final double RUN_DISTANCE_SQR = 196.0D;
    private static final double JUMP_DISTANCE_SQR = 144.0D;
    private static final double CATCHUP_DISTANCE_SQR = 400.0D;
    private static final int STATE_LOCK_TICKS = 20;
    private static final int JUMP_COOLDOWN_TICKS = 8;
    private static final double SPEED_STEP_UP = 0.05D;
    private static final double SPEED_STEP_DOWN = 0.02D;

    private final PathfinderMob mob;
    private final double maxSpeedModifier;
    private MoveState state = MoveState.WALK;
    private long stateLockUntilTick;
    private long nextJumpTick;
    private double currentSpeed;

    CompanionMovementController(PathfinderMob mob, double maxSpeedModifier) {
        this.mob = mob;
        this.maxSpeedModifier = maxSpeedModifier;
        this.currentSpeed = Math.min(WALK_SPEED, maxSpeedModifier);
    }

    double update(Player target, Vec3 followPos, long gameTime, double distanceSqr) {
        if (target == null || followPos == null) {
            return currentSpeed;
        }
        MoveState desired = chooseState(target, distanceSqr);
        if (gameTime >= stateLockUntilTick && desired != state) {
            state = desired;
            stateLockUntilTick = gameTime + STATE_LOCK_TICKS;
        }
        double wantedSpeed = desiredSpeed(distanceSqr);
        currentSpeed = approach(currentSpeed, wantedSpeed, SPEED_STEP_UP, SPEED_STEP_DOWN);
        tryJump(gameTime, distanceSqr);
        applyHorizontalSpeed(followPos);
        applyFacing(followPos);
        return currentSpeed;
    }

    void reset() {
        state = MoveState.WALK;
        stateLockUntilTick = 0L;
        nextJumpTick = 0L;
        currentSpeed = Math.min(WALK_SPEED, maxSpeedModifier);
    }

    private MoveState chooseState(Player target, double distanceSqr) {
        boolean far = distanceSqr > RUN_DISTANCE_SQR;
        boolean veryFar = distanceSqr > CATCHUP_DISTANCE_SQR;
        boolean sprinting = target.isSprinting();

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

    private double desiredSpeed(double distanceSqr) {
        double speed;
        if (distanceSqr <= WALK_DISTANCE_SQR) {
            speed = WALK_SPEED;
        } else if (distanceSqr >= RUN_DISTANCE_SQR) {
            speed = RUN_SPEED;
        } else {
            double t = (distanceSqr - WALK_DISTANCE_SQR) / (RUN_DISTANCE_SQR - WALK_DISTANCE_SQR);
            speed = lerp(WALK_SPEED, RUN_SPEED, t);
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
        if (!mob.onGround() || mob.isInWaterOrBubble()) {
            return;
        }
        if (gameTime < nextJumpTick) {
            return;
        }
        mob.getJumpControl().jump();
        nextJumpTick = gameTime + JUMP_COOLDOWN_TICKS;
    }

    private void applyHorizontalSpeed(Vec3 targetPos) {
        double desiredHorizontal = currentSpeed * mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (desiredHorizontal <= 0.0D) {
            return;
        }
        Vec3 delta = mob.getDeltaMovement();
        double horizontal = Math.hypot(delta.x, delta.z);
        if (horizontal < 1.0E-4D) {
            Vec3 dir = directionToTarget(targetPos);
            if (dir == null) {
                return;
            }
            mob.setDeltaMovement(dir.x * desiredHorizontal, delta.y, dir.z * desiredHorizontal);
            return;
        }
        double scale = desiredHorizontal / horizontal;
        mob.setDeltaMovement(delta.x * scale, delta.y, delta.z * scale);
    }

    private Vec3 directionToTarget(Vec3 targetPos) {
        if (targetPos == null) {
            return null;
        }
        double dx = targetPos.x - mob.getX();
        double dz = targetPos.z - mob.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 1.0E-4D) {
            return null;
        }
        return new Vec3(dx / length, 0.0D, dz / length);
    }

    private void applyFacing(Vec3 targetPos) {
        Vec3 dir = directionToTarget(targetPos);
        if (dir == null) {
            return;
        }
        float targetYaw = (float) (Mth.atan2(dir.z, dir.x) * (180.0F / (float) Math.PI)) - 90.0F;
        float currentYaw = mob.getYRot();
        float newYaw = Mth.approachDegrees(currentYaw, targetYaw, 8.0F);
        mob.setYRot(newYaw);
        mob.setYBodyRot(newYaw);
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
}
