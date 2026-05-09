package ru.nekostul.aicompanion.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Iterator;
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
    private final ArrayDeque<RoutePoint> routeMemory = new ArrayDeque<>();
    private RoutePoint currentRoutePoint;
    private long nextRouteSampleTick;
    private boolean lastTargetOnGround = true;

    private static final double FOLLOW_BEHIND_DISTANCE = 3.0D;
    private static final double FOLLOW_SIDE_DISTANCE = 0.0D;
    private static final double FOLLOW_BEHIND_FAR_DISTANCE = 4.5D;
    private static final double FOLLOW_MIN_DISTANCE_SQR = 6.25D;
    private static final double FOLLOW_MAX_DISTANCE_SQR = 42.25D;
    private static final double FOLLOW_STOP_VERTICAL_EPS = 0.9D;
    private static final double PLAYER_MOVE_RECALC_SQR = 1.0D;
    private static final int TARGET_UPDATE_TICKS = 10;
    private static final int PATH_RECALC_TICKS = 8;
    private static final double FOLLOW_POS_EPS_SQR = 0.25D;
    private static final double PATH_RECALC_EPS_SQR = 0.36D;
    private static final double FOLLOW_PROGRESS_EPS_SQR = 0.04D;
    private static final float RUN_YAW_STEP_DEGREES = 4.0F;
    private static final float RUN_YAW_DEADZONE_DEGREES = 14.0F;
    private static final double RUN_ROTATE_MIN_SPEED_SQR = 1.0E-3D;
    private static final double TARGET_IDLE_SPEED_SQR = 4.0E-4D;
    private static final int ROUTE_SAMPLE_TICKS = 2;
    private static final int ROUTE_MAX_AGE_TICKS = 12 * 20;
    private static final int ROUTE_MAX_POINTS = 120;
    private static final double ROUTE_SAMPLE_MOVE_SQR = 0.09D;
    private static final double ROUTE_LOOKBACK_MAX_DISTANCE_SQR = 196.0D;
    private static final double ROUTE_WAYPOINT_REACHED_SQR = 1.2D;
    private static final double ROUTE_DIRECT_MOVE_DISTANCE_SQR = 16.0D;
    private static final int SAFE_PATH_MAX_DRY_DROP = 1;
    private static final int SAFE_PATH_WATER_SCAN_DOWN = 6;
    private static final int SAFE_PATH_MIN_WATER_SIDE_OPENINGS = 1;

    private static final class RoutePoint {
        private final Vec3 pos;
        private final long tick;
        private final boolean forceDirect;

        private RoutePoint(Vec3 pos, long tick, boolean forceDirect) {
            this.pos = pos;
            this.tick = tick;
            this.forceDirect = forceDirect;
        }
    }

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
        this.routeMemory.clear();
        this.currentRoutePoint = null;
        this.nextRouteSampleTick = 0L;
        this.lastTargetOnGround = this.target != null && this.target.onGround();
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
        this.routeMemory.clear();
        this.currentRoutePoint = null;
        this.nextRouteSampleTick = 0L;
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
        double speed = this.movementController.update(this.target, this.followPos, gameTime, distanceSqr,
                followDistanceSqr);
        if (this.movementController.shouldHoldPosition() || speed <= 0.01D) {
            this.mob.getNavigation().stop();
            return;
        }
        if (this.movementController.shouldForceDirectMovementForJump(gameTime)) {
            this.mob.getNavigation().stop();
            this.mob.getMoveControl().setWantedPosition(this.followPos.x, this.followPos.y, this.followPos.z, speed);
            alignRunRotation(this.followPos);
            return;
        }
        if (this.movementController.shouldForceDirectMovementForLadder(gameTime)) {
            this.mob.getNavigation().stop();
            this.mob.getMoveControl().setWantedPosition(this.followPos.x, this.followPos.y, this.followPos.z, speed);
            alignRunRotation(this.followPos);
            return;
        }
        if (this.movementController.isGapJumpLocked(gameTime)) {
            this.mob.getNavigation().stop();
            alignRunRotation(this.followPos);
            return;
        }
        net.minecraft.world.level.pathfinder.Path currentPath = this.mob.getNavigation().getPath();
        alignRunRotation(this.followPos);
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

    private void alignRunRotation(Vec3 fallbackTarget) {
        Vec3 direction = resolveRunDirection(fallbackTarget);
        double vx = direction.x;
        double vz = direction.z;
        if (vx * vx + vz * vz < 1.0E-4D) {
            return;
        }
        Vec3 motion = this.mob.getDeltaMovement();
        if (motion.x * motion.x + motion.z * motion.z < RUN_ROTATE_MIN_SPEED_SQR) {
            return;
        }
        float currentYaw = this.mob.getYRot();
        float desiredYaw = (float) (Mth.atan2(vz, vx) * (180.0D / Math.PI)) - 90.0F;
        float yawDelta = Math.abs(Mth.wrapDegrees(desiredYaw - currentYaw));
        if (yawDelta < RUN_YAW_DEADZONE_DEGREES) {
            desiredYaw = currentYaw;
        }
        float rotateStep = yawDelta >= 45.0F ? RUN_YAW_STEP_DEGREES * 1.5F : RUN_YAW_STEP_DEGREES;
        float smoothedYaw = Mth.approachDegrees(currentYaw, desiredYaw, rotateStep);
        this.mob.setYRot(smoothedYaw);
        this.mob.setYBodyRot(smoothedYaw);
        this.mob.setYHeadRot(smoothedYaw);
        this.mob.setXRot(0.0F);
    }

    private Vec3 resolveRunDirection(Vec3 fallbackTarget) {
        if (this.mob.getNavigation() != null) {
            Path path = this.mob.getNavigation().getPath();
            if (path != null && !path.isDone()) {
                BlockPos nextNode = path.getNextNodePos();
                Vec3 toNode = Vec3.atCenterOf(nextNode).subtract(this.mob.position());
                Vec3 horizontalToNode = new Vec3(toNode.x, 0.0D, toNode.z);
                if (horizontalToNode.lengthSqr() >= 0.04D) {
                    return horizontalToNode;
                }
            }
        }
        if (fallbackTarget == null) {
            return Vec3.ZERO;
        }
        return new Vec3(fallbackTarget.x - this.mob.getX(), 0.0D, fallbackTarget.z - this.mob.getZ());
    }

    private void updateFollowTarget(long gameTime) {
        Vec3 playerPos = this.target.position();
        rememberPlayerRoute(playerPos, gameTime);
        Vec3 routeFollow = pickRouteFollowPos(playerPos, gameTime);
        if (routeFollow != null) {
            if (this.followPos == null || routeFollow.distanceToSqr(this.followPos) >= FOLLOW_POS_EPS_SQR) {
                this.followPos = routeFollow;
            }
            this.lastPlayerPos = playerPos;
            this.nextTargetUpdateTick = gameTime + ROUTE_SAMPLE_TICKS;
            return;
        }
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

    private void rememberPlayerRoute(Vec3 playerPos, long gameTime) {
        if (this.target == null || playerPos == null) {
            return;
        }
        trimRouteMemory(gameTime);
        boolean onGround = this.target.onGround();
        boolean jumpStarted = !onGround && lastTargetOnGround && this.target.getDeltaMovement().y > 0.08D;
        RoutePoint last = this.routeMemory.peekLast();
        double movedSqr = last == null ? Double.MAX_VALUE : last.pos.distanceToSqr(playerPos);
        boolean shouldSample = jumpStarted || gameTime >= this.nextRouteSampleTick || movedSqr >= ROUTE_SAMPLE_MOVE_SQR;
        if (shouldSample) {
            boolean forceDirect = jumpStarted || !onGround;
            this.routeMemory.addLast(new RoutePoint(playerPos, gameTime, forceDirect));
            this.nextRouteSampleTick = gameTime + ROUTE_SAMPLE_TICKS;
        }
        this.lastTargetOnGround = onGround;
        trimRouteMemory(gameTime);
    }

    private Vec3 pickRouteFollowPos(Vec3 playerPos, long gameTime) {
        trimRouteMemory(gameTime);
        consumeReachedRoutePoints();
        RoutePoint picked = null;
        Iterator<RoutePoint> it = this.routeMemory.descendingIterator();
        while (it.hasNext()) {
            RoutePoint point = it.next();
            double toPlayerSqr = point.pos.distanceToSqr(playerPos);
            if (toPlayerSqr < FOLLOW_MIN_DISTANCE_SQR) {
                continue;
            }
            if (toPlayerSqr > ROUTE_LOOKBACK_MAX_DISTANCE_SQR) {
                break;
            }
            picked = point;
            break;
        }
        this.currentRoutePoint = picked;
        return picked != null ? picked.pos : null;
    }

    private void trimRouteMemory(long gameTime) {
        while (!this.routeMemory.isEmpty()) {
            RoutePoint first = this.routeMemory.peekFirst();
            if (first == null) {
                break;
            }
            if (gameTime - first.tick <= ROUTE_MAX_AGE_TICKS && this.routeMemory.size() <= ROUTE_MAX_POINTS) {
                break;
            }
            this.routeMemory.removeFirst();
        }
        if (this.currentRoutePoint != null && gameTime - this.currentRoutePoint.tick > ROUTE_MAX_AGE_TICKS) {
            this.currentRoutePoint = null;
        }
        while (this.routeMemory.size() > ROUTE_MAX_POINTS) {
            this.routeMemory.removeFirst();
        }
    }

    private void consumeReachedRoutePoints() {
        while (!this.routeMemory.isEmpty()) {
            RoutePoint first = this.routeMemory.peekFirst();
            if (first == null) {
                return;
            }
            if (this.mob.position().distanceToSqr(first.pos) > ROUTE_WAYPOINT_REACHED_SQR) {
                return;
            }
            this.routeMemory.removeFirst();
        }
    }

    private boolean shouldUseDirectMoveFromRoute(long gameTime) {
        if (this.currentRoutePoint == null || !this.currentRoutePoint.forceDirect) {
            return false;
        }
        if (gameTime - this.currentRoutePoint.tick > ROUTE_MAX_AGE_TICKS) {
            return false;
        }
        return this.mob.position().distanceToSqr(this.currentRoutePoint.pos) <= ROUTE_DIRECT_MOVE_DISTANCE_SQR;
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
        Vec3 fallback = buildFallbackFollowPos(playerPos, forward);
        Vec3 direct = new Vec3(playerPos.x, playerPos.y, playerPos.z);
        return chooseReachableFollowPos(playerPos, preferred, behindOnly, fallback, direct);
    }

    private Vec3 chooseReachableFollowPos(Vec3 playerPos, Vec3 preferred, Vec3 behindOnly, Vec3 fallback,
                                          Vec3 direct) {
        if (isGoodFollowCandidate(playerPos, preferred)) {
            return preferred;
        }
        if (isGoodFollowCandidate(playerPos, behindOnly)) {
            return behindOnly;
        }
        if (isGoodFollowCandidate(playerPos, fallback)) {
            return fallback;
        }
        if (isGoodFollowCandidate(playerPos, direct)) {
            return direct;
        }
        return this.followPos != null ? this.followPos : this.mob.position();
    }

    private Vec3 buildFallbackFollowPos(Vec3 playerPos, Vec3 forward) {
        Vec3 fromPlayerToMob = new Vec3(this.mob.getX() - playerPos.x, 0.0D, this.mob.getZ() - playerPos.z);
        if (fromPlayerToMob.lengthSqr() < 1.0E-4D) {
            fromPlayerToMob = forward.scale(-1.0D);
        }
        if (fromPlayerToMob.lengthSqr() < 1.0E-4D) {
            fromPlayerToMob = new Vec3(0.0D, 0.0D, -1.0D);
        }
        fromPlayerToMob = fromPlayerToMob.normalize().scale(FOLLOW_BEHIND_DISTANCE);
        return new Vec3(playerPos.x + fromPlayerToMob.x, playerPos.y, playerPos.z + fromPlayerToMob.z);
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
        return path != null && path.canReach() && isSafeFollowPath(path);
    }

    private boolean isSafeFollowPath(Path path) {
        if (path == null || path.getNodeCount() <= 1) {
            return true;
        }
        BlockPos previous = toBlockPos(path.getNode(0));
        for (int i = 1; i < path.getNodeCount(); i++) {
            BlockPos current = toBlockPos(path.getNode(i));
            int dryDrop = previous.getY() - current.getY();
            if (dryDrop > SAFE_PATH_MAX_DRY_DROP && !hasSafeWaterLanding(current)) {
                return false;
            }
            previous = current;
        }
        return true;
    }

    private BlockPos toBlockPos(Node node) {
        return new BlockPos(node.x, node.y, node.z);
    }

    private boolean hasSafeWaterLanding(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        for (int depth = 0; depth <= SAFE_PATH_WATER_SCAN_DOWN; depth++) {
            BlockPos cursor = pos.below(depth);
            if (this.mob.level().getFluidState(cursor).is(FluidTags.WATER)) {
                return hasWaterOpening(cursor);
            }
            if (!this.mob.level().getBlockState(cursor).getCollisionShape(this.mob.level(), cursor).isEmpty()) {
                return false;
            }
        }
        return false;
    }

    private boolean hasWaterOpening(BlockPos waterPos) {
        if (waterPos == null || !this.mob.level().getFluidState(waterPos).is(FluidTags.WATER)) {
            return false;
        }
        BlockPos headPos = waterPos.above();
        if (!this.mob.level().getFluidState(headPos).is(FluidTags.WATER)
                && !this.mob.level().getBlockState(headPos).getCollisionShape(this.mob.level(), headPos).isEmpty()) {
            return false;
        }
        int openings = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = waterPos.relative(direction);
            if (this.mob.level().getFluidState(side).is(FluidTags.WATER)
                    || this.mob.level().getBlockState(side).getCollisionShape(this.mob.level(), side).isEmpty()) {
                openings++;
                if (openings >= SAFE_PATH_MIN_WATER_SIDE_OPENINGS) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean canHoldAtCurrentVerticalOffset(Player player) {
        if (player == null) {
            return false;
        }
        return Math.abs(player.getY() - this.mob.getY()) <= FOLLOW_STOP_VERTICAL_EPS;
    }
}
