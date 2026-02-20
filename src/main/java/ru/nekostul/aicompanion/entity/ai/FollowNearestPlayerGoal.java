package ru.nekostul.aicompanion.entity.ai;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.util.EnumSet;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class FollowNearestPlayerGoal extends Goal {
    private final PathfinderMob mob;
    private final double maxSpeedModifier;
    private final CompanionMovementController movementController;
    private final float startDistance;
    private final float stopDistance;
    private final BooleanSupplier followEnabled;
    private final Supplier<Player> targetSelector;
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
    private static final double FOLLOW_SIDE_DISTANCE = 0.0D;
    private static final double FOLLOW_BEHIND_FAR_DISTANCE = 3.6D;
    private static final double FOLLOW_MIN_DISTANCE_SQR = 2.25D;
    private static final double FOLLOW_MAX_DISTANCE_SQR = 20.25D;
    private static final double FOLLOW_STOP_VERTICAL_EPS = 0.9D;
    private static final double PLAYER_MOVE_RECALC_SQR = 1.0D;
    private static final int TARGET_UPDATE_TICKS = 10;
    private static final int PATH_RECALC_TICKS = 8;
    private static final double FOLLOW_POS_EPS_SQR = 0.25D;
    private static final double PATH_RECALC_EPS_SQR = 0.36D;
    private static final double FOLLOW_PROGRESS_EPS_SQR = 0.04D;
    private static final float RUN_YAW_STEP_DEGREES = 7.0F;
    private static final float RUN_YAW_DEADZONE_DEGREES = 10.0F;
    private static final double RUN_ROTATE_MIN_SPEED_SQR = 1.0E-3D;
    private static final double TARGET_IDLE_SPEED_SQR = 4.0E-4D;

    public FollowNearestPlayerGoal(PathfinderMob mob, double speedModifier, float startDistance, float stopDistance) {
        this(mob, speedModifier, startDistance, stopDistance, () -> true, null);
    }

    public FollowNearestPlayerGoal(PathfinderMob mob, double speedModifier, float startDistance, float stopDistance,
                                   BooleanSupplier followEnabled) {
        this(mob, speedModifier, startDistance, stopDistance, followEnabled, null);
    }

    public FollowNearestPlayerGoal(PathfinderMob mob, double speedModifier, float startDistance, float stopDistance,
                                   BooleanSupplier followEnabled, Supplier<Player> targetSelector) {
        this.mob = mob;
        this.maxSpeedModifier = speedModifier;
        this.startDistance = startDistance;
        this.stopDistance = stopDistance;
        this.followEnabled = followEnabled;
        this.targetSelector = targetSelector;
        this.movementController = new CompanionMovementController(mob, speedModifier);
        this.sideSign = (mob.getUUID().hashCode() & 1) == 0 ? 1 : -1;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!this.followEnabled.getAsBoolean()) {
            return false;
        }
        Player selected = this.targetSelector != null
                ? this.targetSelector.get()
                : this.mob.level().getNearestPlayer(this.mob, this.startDistance);
        if (selected == null || selected.isSpectator() || !selected.isAlive()) {
            return false;
        }
        if (this.targetSelector != null
                && this.mob.distanceToSqr(selected) > (double) (this.startDistance * this.startDistance)) {
            return false;
        }
        Vec3 nearestMotion = selected.getDeltaMovement();
        double nearestSpeedSqr = nearestMotion.x * nearestMotion.x + nearestMotion.z * nearestMotion.z;
        if (this.mob.distanceToSqr(selected) <= (double) (this.stopDistance * this.stopDistance)
                && this.mob.hasLineOfSight(selected)
                && canHoldAtCurrentVerticalOffset(selected)
                && nearestSpeedSqr < TARGET_IDLE_SPEED_SQR) {
            return false;
        }
        this.target = selected;
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
        Vec3 targetMotion = this.target.getDeltaMovement();
        double targetSpeedSqr = targetMotion.x * targetMotion.x + targetMotion.z * targetMotion.z;
        if (followDistanceSqr <= (double) (this.stopDistance * this.stopDistance)
                && this.mob.hasLineOfSight(this.target)
                && canHoldAtCurrentVerticalOffset(this.target)
                && targetSpeedSqr < TARGET_IDLE_SPEED_SQR) {
            this.mob.getNavigation().stop();
            return;
        }
        if (this.lastFollowDistanceSqr >= 0.0D
                && followDistanceSqr < this.lastFollowDistanceSqr - FOLLOW_PROGRESS_EPS_SQR) {
            this.lastProgressTick = gameTime;
        }
        this.lastFollowDistanceSqr = followDistanceSqr;
        double speed = this.movementController.update(this.target, this.followPos, gameTime, distanceSqr);
        if (this.movementController.shouldHoldPosition() || speed <= 0.01D) {
            this.mob.getNavigation().stop();
            return;
        }
        net.minecraft.world.level.pathfinder.Path currentPath = this.mob.getNavigation().getPath();
        alignRunRotation();
        if (currentPath == null || currentPath.isDone()) {
            this.mob.getNavigation().moveTo(this.followPos.x, this.followPos.y, this.followPos.z, speed);
            this.lastPathPos = this.followPos;
            this.timeToRecalcPath = this.adjustedTickDelay(PATH_RECALC_TICKS);
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

    private void alignRunRotation() {
        Vec3 motion = this.mob.getDeltaMovement();
        double vx = motion.x;
        double vz = motion.z;
        if (vx * vx + vz * vz < 1.0E-4D) {
            return;
        }
        if (vx * vx + vz * vz < RUN_ROTATE_MIN_SPEED_SQR) {
            return;
        }
        float currentYaw = this.mob.getYRot();
        float desiredYaw = (float) (Mth.atan2(vz, vx) * (180.0D / Math.PI)) - 90.0F;
        if (Math.abs(Mth.wrapDegrees(desiredYaw - currentYaw)) < RUN_YAW_DEADZONE_DEGREES) {
            desiredYaw = currentYaw;
        }
        float smoothedYaw = Mth.approachDegrees(currentYaw, desiredYaw, RUN_YAW_STEP_DEGREES);
        this.mob.setYRot(smoothedYaw);
        this.mob.setYBodyRot(smoothedYaw);
        this.mob.setXRot(0.0F);
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
        Vec3 motion = this.target.getDeltaMovement();
        Vec3 forward = new Vec3(motion.x, 0.0D, motion.z);
        if (forward.lengthSqr() < 1.0E-4D) {
            Vec3 look = this.target.getLookAngle();
            forward = new Vec3(look.x, 0.0D, look.z);
        }
        if (forward.lengthSqr() < 1.0E-4D) {
            float yaw = this.target.getYRot() * ((float) Math.PI / 180.0F);
            forward = new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
        }
        forward = forward.normalize();
        double sideDistance = distanceSqr <= FOLLOW_MAX_DISTANCE_SQR ? FOLLOW_SIDE_DISTANCE : 0.0D;
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x).scale(sideDistance * sideSign);
        Vec3 behind = forward.scale(-FOLLOW_BEHIND_DISTANCE);
        Vec3 preferred = new Vec3(playerPos.x + behind.x + right.x, playerPos.y, playerPos.z + behind.z + right.z);
        if (preferred.distanceToSqr(playerPos) < FOLLOW_MIN_DISTANCE_SQR) {
            Vec3 farther = forward.scale(-FOLLOW_BEHIND_FAR_DISTANCE);
            preferred = new Vec3(playerPos.x + farther.x, playerPos.y, playerPos.z + farther.z);
        }
        Vec3 behindOnly = new Vec3(playerPos.x + behind.x, playerPos.y, playerPos.z + behind.z);
        Vec3 direct = new Vec3(playerPos.x, playerPos.y, playerPos.z);
        return chooseReachableFollowPos(playerPos, preferred, behindOnly, direct);
    }

    private Vec3 chooseReachableFollowPos(Vec3 playerPos, Vec3 preferred, Vec3 behindOnly, Vec3 direct) {
        if (isGoodFollowCandidate(playerPos, preferred)) {
            return preferred;
        }
        if (isGoodFollowCandidate(playerPos, behindOnly)) {
            return behindOnly;
        }
        return direct;
    }

    private boolean isGoodFollowCandidate(Vec3 playerPos, Vec3 candidate) {
        if (playerPos == null || candidate == null) {
            return false;
        }
        if (!hasClearPlayerLine(playerPos, candidate)) {
            return false;
        }
        return canReach(candidate);
    }

    private boolean hasClearPlayerLine(Vec3 playerPos, Vec3 candidate) {
        Vec3 from = new Vec3(playerPos.x, playerPos.y + this.target.getEyeHeight(), playerPos.z);
        Vec3 to = new Vec3(candidate.x, candidate.y + Math.max(0.2D, this.mob.getBbHeight() * 0.5D), candidate.z);
        HitResult hit = this.mob.level().clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.target));
        return hit.getType() == HitResult.Type.MISS;
    }

    private boolean canReach(Vec3 targetPos) {
        if (targetPos == null || this.mob.getNavigation() == null) {
            return false;
        }
        Path path = this.mob.getNavigation().createPath(targetPos.x, targetPos.y, targetPos.z, 0);
        return path != null && path.canReach();
    }

    private boolean canHoldAtCurrentVerticalOffset(Player player) {
        if (player == null) {
            return false;
        }
        return Math.abs(player.getY() - this.mob.getY()) <= FOLLOW_STOP_VERTICAL_EPS;
    }
}
