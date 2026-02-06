package ru.nekostul.aicompanion.entity.ai;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;
import java.util.function.BooleanSupplier;

public class FollowNearestPlayerGoal extends Goal {
    private final PathfinderMob mob;
    private final double maxSpeedModifier;
    private final float startDistance;
    private final float stopDistance;
    private final BooleanSupplier followEnabled;
    private Player target;
    private int timeToRecalcPath;
    private long nextJumpTick;

    private static final double MIN_SPEED_MOD = 0.9D;
    private static final double WALK_SPEED_MOD = 1.0D;
    private static final double RUN_SPEED_MOD = 1.15D;
    private static final double SPRINT_SPEED_MOD = 1.25D;
    private static final double CATCHUP_SPEED_MOD = 1.35D;
    private static final double RUN_THRESHOLD = 0.08D;
    private static final double SPRINT_THRESHOLD = 0.12D;
    private static final int JUMP_COOLDOWN_TICKS = 20;

    public FollowNearestPlayerGoal(PathfinderMob mob, double speedModifier, float startDistance, float stopDistance) {
        this(mob, speedModifier, startDistance, stopDistance, () -> true);
    }

    public FollowNearestPlayerGoal(PathfinderMob mob, double speedModifier, float startDistance, float stopDistance,
                                   BooleanSupplier followEnabled) {
        this.mob = mob;
        this.maxSpeedModifier = speedModifier;
        this.startDistance = startDistance;
        this.stopDistance = stopDistance;
        this.followEnabled = followEnabled;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!this.followEnabled.getAsBoolean()) {
            return false;
        }
        Player nearest = this.mob.level().getNearestPlayer(this.mob, this.startDistance);
        if (nearest == null || nearest.isSpectator()) {
            return false;
        }
        if (this.mob.distanceToSqr(nearest) <= (double) (this.stopDistance * this.stopDistance)) {
            return false;
        }
        this.target = nearest;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!this.followEnabled.getAsBoolean()) {
            return false;
        }
        if (this.target == null || this.target.isSpectator()) {
            return false;
        }
        double distanceSqr = this.mob.distanceToSqr(this.target);
        if (distanceSqr <= (double) (this.stopDistance * this.stopDistance)) {
            return false;
        }
        return distanceSqr <= (double) (this.startDistance * this.startDistance);
    }

    @Override
    public void start() {
        this.timeToRecalcPath = 0;
    }

    @Override
    public void stop() {
        this.target = null;
        this.mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (this.target == null) {
            return;
        }
        this.mob.getLookControl().setLookAt(this.target, 10.0F, this.mob.getMaxHeadXRot());
        double speed = getAdaptiveSpeed();
        double distanceSqr = this.mob.distanceToSqr(this.target);
        if (--this.timeToRecalcPath <= 0) {
            this.timeToRecalcPath = this.adjustedTickDelay(10);
            this.mob.getNavigation().moveTo(this.target, speed);
        }
        tryJump(speed, distanceSqr);
    }

    private double getAdaptiveSpeed() {
        if (this.target == null) {
            return this.maxSpeedModifier;
        }
        double targetSpeed = this.target.getDeltaMovement().horizontalDistance();
        double speed;
        if (targetSpeed < 0.03D) {
            speed = MIN_SPEED_MOD;
        } else if (targetSpeed < RUN_THRESHOLD) {
            speed = WALK_SPEED_MOD;
        } else if (targetSpeed < SPRINT_THRESHOLD) {
            speed = RUN_SPEED_MOD;
        } else {
            speed = SPRINT_SPEED_MOD;
        }
        if (this.mob.distanceToSqr(this.target) > 256.0D) {
            speed = Math.max(speed, CATCHUP_SPEED_MOD);
        }
        return Math.min(this.maxSpeedModifier, speed);
    }

    private void tryJump(double speed, double distanceSqr) {
        if (speed < RUN_SPEED_MOD) {
            return;
        }
        if (distanceSqr < 36.0D) {
            return;
        }
        if (!this.mob.onGround() || this.mob.isInWaterOrBubble()) {
            return;
        }
        long gameTime = this.mob.level().getGameTime();
        if (gameTime < this.nextJumpTick) {
            return;
        }
        this.mob.getJumpControl().jump();
        this.nextJumpTick = gameTime + JUMP_COOLDOWN_TICKS;
    }
}
