package ru.nekostul.aicompanion.entity.ai;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.util.EnumSet;
import java.util.function.BooleanSupplier;

public class FollowNearestPlayerGoal extends Goal {
    private final PathfinderMob mob;
    private final double maxSpeedModifier;
    private final CompanionMovementController movementController;
    private final float startDistance;
    private final float stopDistance;
    private final BooleanSupplier followEnabled;
    private Player target;
    private int timeToRecalcPath;
    private Vec3 followPos;
    private Vec3 lastPlayerPos;
    private Vec3 lastPathPos;
    private double lastFollowDistanceSqr = -1.0D;
    private long lastProgressTick;
    private long nextTargetUpdateTick;
    private final int sideSign;

    private static final double FOLLOW_BEHIND_DISTANCE = 2.6D;
    private static final double FOLLOW_SIDE_DISTANCE = 1.2D;
    private static final double FOLLOW_MIN_DISTANCE_SQR = 2.25D;
    private static final double FOLLOW_MAX_DISTANCE_SQR = 20.25D;
    private static final double PLAYER_MOVE_RECALC_SQR = 1.0D;
    private static final int TARGET_UPDATE_TICKS = 10;
    private static final int PATH_RECALC_TICKS = 20;
    private static final double FOLLOW_POS_EPS_SQR = 0.25D;
    private static final double PATH_RECALC_EPS_SQR = 0.36D;
    private static final double FOLLOW_PROGRESS_EPS_SQR = 0.04D;

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
        this.movementController = new CompanionMovementController(mob, speedModifier);
        this.sideSign = (mob.getUUID().hashCode() & 1) == 0 ? 1 : -1;
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
        this.followPos = null;
        this.lastPlayerPos = null;
        this.lastPathPos = null;
        this.lastFollowDistanceSqr = -1.0D;
        this.lastProgressTick = 0L;
        this.nextTargetUpdateTick = 0L;
        this.movementController.reset();
    }

    @Override
    public void stop() {
        this.target = null;
        this.followPos = null;
        this.lastPlayerPos = null;
        this.lastPathPos = null;
        this.lastFollowDistanceSqr = -1.0D;
        this.lastProgressTick = 0L;
        this.mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (this.target == null) {
            return;
        }
        double distanceSqr = this.mob.distanceToSqr(this.target);
        long gameTime = this.mob.level().getGameTime();
        updateFollowTarget(gameTime);
        if (this.followPos == null) {
            return;
        }
        double followDistanceSqr = this.mob.distanceToSqr(this.followPos);
        if (followDistanceSqr <= (double) (this.stopDistance * this.stopDistance)) {
            this.mob.getNavigation().stop();
            return;
        }
        if (this.lastFollowDistanceSqr >= 0.0D
                && followDistanceSqr < this.lastFollowDistanceSqr - FOLLOW_PROGRESS_EPS_SQR) {
            this.lastProgressTick = gameTime;
        }
        this.lastFollowDistanceSqr = followDistanceSqr;
        this.mob.getLookControl().setLookAt(this.followPos.x, this.followPos.y, this.followPos.z);
        double speed = this.movementController.update(this.target, this.followPos, gameTime, distanceSqr);
        if (this.movementController.shouldHoldPosition() || speed <= 0.01D) {
            this.mob.getNavigation().stop();
            return;
        }
        if (--this.timeToRecalcPath <= 0) {
            this.timeToRecalcPath = this.adjustedTickDelay(PATH_RECALC_TICKS);
            boolean shouldRecalc = this.lastPathPos == null
                    || this.followPos.distanceToSqr(this.lastPathPos) >= PATH_RECALC_EPS_SQR;
            net.minecraft.world.level.pathfinder.Path path = this.mob.getNavigation().getPath();
            if (path == null || path.isDone()) {
                shouldRecalc = true;
            }
            if (shouldRecalc) {
                this.mob.getNavigation().moveTo(this.followPos.x, this.followPos.y, this.followPos.z, speed);
                this.lastPathPos = this.followPos;
            }
        }
    }

    private void updateFollowTarget(long gameTime) {
        Vec3 playerPos = this.target.position();
        double distanceSqr = this.mob.distanceToSqr(this.target);
        if (this.followPos == null || this.lastPlayerPos == null) {
            this.followPos = computeFollowPos(playerPos, distanceSqr);
            this.lastPlayerPos = playerPos;
            this.nextTargetUpdateTick = gameTime + TARGET_UPDATE_TICKS;
            return;
        }
        if (gameTime < this.nextTargetUpdateTick) {
            return;
        }
        boolean makingProgress = this.lastProgressTick > 0L
                && gameTime - this.lastProgressTick <= TARGET_UPDATE_TICKS;
        double moved = this.lastPlayerPos.distanceToSqr(playerPos);
        double followToPlayer = this.followPos.distanceToSqr(playerPos);
        if (moved < PLAYER_MOVE_RECALC_SQR && makingProgress
                && followToPlayer >= FOLLOW_MIN_DISTANCE_SQR
                && followToPlayer <= FOLLOW_MAX_DISTANCE_SQR) {
            this.nextTargetUpdateTick = gameTime + TARGET_UPDATE_TICKS;
            return;
        }
        Vec3 nextFollow = computeFollowPos(playerPos, distanceSqr);
        if (this.followPos != null && nextFollow.distanceToSqr(this.followPos) < FOLLOW_POS_EPS_SQR) {
            this.nextTargetUpdateTick = gameTime + TARGET_UPDATE_TICKS;
            this.lastPlayerPos = playerPos;
            return;
        }
        this.followPos = nextFollow;
        this.lastPlayerPos = playerPos;
        this.nextTargetUpdateTick = gameTime + TARGET_UPDATE_TICKS;
    }

    private Vec3 computeFollowPos(Vec3 playerPos, double distanceSqr) {
        Vec3 look = this.target.getLookAngle();
        Vec3 forward = new Vec3(look.x, 0.0D, look.z);
        if (forward.lengthSqr() < 1.0E-4D) {
            float yaw = this.target.getYRot() * ((float) Math.PI / 180.0F);
            forward = new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
        }
        forward = forward.normalize();
        double sideDistance = distanceSqr <= FOLLOW_MAX_DISTANCE_SQR ? FOLLOW_SIDE_DISTANCE : 0.0D;
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x).scale(sideDistance * sideSign);
        Vec3 behind = forward.scale(-FOLLOW_BEHIND_DISTANCE);
        Vec3 follow = new Vec3(playerPos.x + behind.x + right.x, playerPos.y, playerPos.z + behind.z + right.z);
        if (follow.distanceToSqr(playerPos) < FOLLOW_MIN_DISTANCE_SQR) {
            Vec3 farther = forward.scale(-(FOLLOW_BEHIND_DISTANCE + 1.0D));
            follow = new Vec3(playerPos.x + farther.x, playerPos.y, playerPos.z + farther.z);
        }
        return follow;
    }
}
