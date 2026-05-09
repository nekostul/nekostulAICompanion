package ru.nekostul.aicompanion.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import ru.nekostul.aicompanion.entity.movement.CompanionMovementSpeed;

final class CompanionMovementController {
    private enum MoveState {
        WALK,
        RUN
    }

    private static final double PLAYER_WALK_SPEED = 0.3D;
    private static final double PLAYER_SPRINT_MULTIPLIER = 1.5D;
    private static final double WALK_DISTANCE_SQR = 49.0D;
    private static final double RUN_DISTANCE_SQR = 100.0D;
    private static final double HOLD_DISTANCE_SQR = 12.25D;
    private static final double HOLD_FOLLOW_DISTANCE_SQR = 1.0D;
    private static final double HOLD_VERTICAL_EPS = 0.75D;
    private static final double APPROACH_START_DISTANCE = 6.0D;
    private static final double APPROACH_START_DISTANCE_SQR = 36.0D;
    private static final double APPROACH_STOP_DISTANCE = 2.75D;
    private static final double APPROACH_MIN_SPEED_FACTOR = 0.45D;
    private static final double FOLLOW_POINT_SLOWDOWN_DISTANCE = 2.25D;
    private static final double FOLLOW_POINT_SLOWDOWN_DISTANCE_SQR = 5.0625D;
    private static final double FOLLOW_POINT_MIN_SPEED_FACTOR = 0.38D;
    private static final double PLAYER_IDLE_SPEED = 0.02D;
    private static final double PLAYER_WALK_RATIO_MIN = 0.92D;
    private static final double PLAYER_WALK_RATIO_MAX = 1.08D;
    private static final double PLAYER_RUN_RATIO_MIN = 0.96D;
    private static final double PLAYER_RUN_RATIO_MAX = 1.16D;
    private static final double STYLE_WALK_RATIO_MIN = 0.94D;
    private static final double STYLE_WALK_RATIO_MAX = 1.08D;
    private static final double STYLE_RUN_RATIO_MIN = 0.94D;
    private static final double STYLE_RUN_RATIO_MAX = 1.12D;
    private static final double STYLE_LEARN_RATE = 0.08D;
    private static final double STYLE_JUMP_STEP = 0.18D;
    private static final double STYLE_JUMP_GAP_BONUS = 0.14D;
    private static final double STYLE_JUMP_DECAY = 0.92D;
    private static final double STYLE_JUMP_MIN_INTENT = 0.35D;
    private static final int STYLE_GAP_JUMP_MEMORY_TICKS = 80;
    private static final int STYLE_GAP_JUMP_LOCK_TICKS = 10;
    private static final double STYLE_GAP_JUMP_FORWARD_SPEED = 0.48D;
    private static final double STYLE_GAP_JUMP_VERTICAL_SPEED = 0.42D;
    private static final double STYLE_GAP_JUMP_ALIGN_DOT = 0.35D;
    private static final double STYLE_GAP_JUMP_MIN_DISTANCE_SQR = 2.25D;
    private static final int PLAYER_JUMP_COPY_WINDOW_TICKS = 30;
    private static final double PLAYER_JUMP_COPY_MIN_DISTANCE_SQR = 2.25D;
    private static final double PLAYER_JUMP_COPY_MAX_TAKEOFF_DISTANCE_SQR = 30.25D;
    private static final double PLAYER_JUMP_COPY_MIN_HORIZONTAL_SPEED = 0.32D;
    private static final double PLAYER_JUMP_COPY_MAX_HORIZONTAL_SPEED = 0.72D;
    private static final double PLAYER_JUMP_COPY_MIN_VERTICAL_SPEED = 0.38D;
    private static final double PLAYER_JUMP_COPY_MAX_VERTICAL_SPEED = 0.5D;
    private static final int PLAYER_JUMP_COPY_LOCK_TICKS = 12;
    private static final double PLAYER_JUMP_COPY_ALIGN_DOT = 0.2D;
    private static final int STATE_LOCK_TICKS = 20;
    private static final int JUMP_COOLDOWN_TICKS = 8;
    private static final int GAP_CHECK_DEPTH = 3;
    private static final double GAP_PROBE_DISTANCE = 0.9D;
    private static final double STEP_JUMP_PROBE_NEAR = 0.75D;
    private static final double STEP_JUMP_PROBE_FAR = 1.15D;
    private static final double STEP_JUMP_PROBE_EARLY = 1.45D;
    private static final double STEP_JUMP_MIN_SPEED = 0.10D;
    private static final double STEP_JUMP_SPEED_MULTIPLIER = 1.05D;
    private static final double LADDER_MIN_VERTICAL_GAP = 0.4D;
    private static final double LADDER_AHEAD_PROBE = 0.6D;
    private static final double LADDER_CLIMB_SPEED = 0.22D;
    private static final double LADDER_FORWARD_FACTOR = 0.65D;
    private static final int LADDER_ASSIST_TICKS = 4;
    private static final double SPEED_STEP_UP = 0.018D;
    private static final double SPEED_STEP_DOWN = 0.028D;

    private final PathfinderMob mob;
    private final double maxSpeedModifier;
    private final CompanionSafeMovement safeMovement;
    private MoveState state = MoveState.WALK;
    private long stateLockUntilTick;
    private long nextJumpTick;
    private double currentSpeed;
    private boolean holdPosition;
    private CompanionSafeMovement.SafetyLevel safetyLevel = CompanionSafeMovement.SafetyLevel.SAFE;
    private double learnedWalkRatio = 1.0D;
    private double learnedRunRatio = 1.0D;
    private double learnedJumpIntent;
    private Vec3 learnedGapJumpDirection = Vec3.ZERO;
    private long learnedGapJumpUntilTick;
    private long gapJumpLockUntilTick;
    private boolean lastPlayerOnGround = true;
    private Vec3 pendingPlayerJumpDirection = Vec3.ZERO;
    private Vec3 pendingPlayerJumpTakeoffPos = Vec3.ZERO;
    private double pendingPlayerJumpHorizontalSpeed;
    private double pendingPlayerJumpVerticalSpeed;
    private long pendingPlayerJumpUntilTick;
    private long ladderAssistUntilTick;

    CompanionMovementController(PathfinderMob mob, double maxSpeedModifier) {
        this.mob = mob;
        this.maxSpeedModifier = maxSpeedModifier;
        this.safeMovement = new CompanionSafeMovement(mob);
        this.currentSpeed = Math.min(walkSpeedModifier(), maxSpeedModifier);
    }

    double update(Player target, Vec3 followPos, long gameTime, double distanceSqr, double followDistanceSqr) {
        if (target == null || followPos == null) {
            return currentSpeed;
        }
        rememberPlayerStyle(target, gameTime);
        if (tryAssistLadderClimb(followPos, gameTime)) {
            holdPosition = false;
            currentSpeed = Math.max(currentSpeed, walkSpeedModifier());
            return currentSpeed;
        }
        safetyLevel = safeMovement.evaluate(followPos);
        holdPosition = shouldHold(target, distanceSqr, followDistanceSqr);
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
        double wantedSpeed = desiredSpeed(distanceSqr, playerRatio, followDistanceSqr);
        currentSpeed = approach(currentSpeed, wantedSpeed, SPEED_STEP_UP, SPEED_STEP_DOWN);
        tryJump(gameTime, target, followPos);
        return currentSpeed;
    }

    void reset() {
        state = MoveState.WALK;
        stateLockUntilTick = 0L;
        nextJumpTick = 0L;
        currentSpeed = Math.min(walkSpeedModifier(), maxSpeedModifier);
        holdPosition = false;
        safetyLevel = CompanionSafeMovement.SafetyLevel.SAFE;
        learnedWalkRatio = 1.0D;
        learnedRunRatio = 1.0D;
        learnedJumpIntent = 0.0D;
        learnedGapJumpDirection = Vec3.ZERO;
        learnedGapJumpUntilTick = 0L;
        gapJumpLockUntilTick = 0L;
        lastPlayerOnGround = true;
        pendingPlayerJumpDirection = Vec3.ZERO;
        pendingPlayerJumpTakeoffPos = Vec3.ZERO;
        pendingPlayerJumpHorizontalSpeed = 0.0D;
        pendingPlayerJumpVerticalSpeed = 0.0D;
        pendingPlayerJumpUntilTick = 0L;
        ladderAssistUntilTick = 0L;
    }

    boolean shouldHoldPosition() {
        return holdPosition || safetyLevel == CompanionSafeMovement.SafetyLevel.DANGER;
    }

    boolean isGapJumpLocked(long gameTime) {
        return gameTime < gapJumpLockUntilTick;
    }

    boolean shouldForceDirectMovementForJump(long gameTime) {
        return false;
    }

    boolean shouldForceDirectMovementForLadder(long gameTime) {
        return gameTime < ladderAssistUntilTick;
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

    private double desiredSpeed(double distanceSqr, double playerRatio, double followDistanceSqr) {
        double speed;
        double walkSpeed = walkSpeedModifier();
        double runSpeed = runSpeedModifier();
        double styleWalkRatio = clamp(learnedWalkRatio, STYLE_WALK_RATIO_MIN, STYLE_WALK_RATIO_MAX);
        double styleRunRatio = clamp(learnedRunRatio, STYLE_RUN_RATIO_MIN, STYLE_RUN_RATIO_MAX);
        if (distanceSqr <= WALK_DISTANCE_SQR) {
            speed = walkSpeed * clamp(playerRatio, PLAYER_WALK_RATIO_MIN, PLAYER_WALK_RATIO_MAX) * styleWalkRatio;
        } else if (distanceSqr >= RUN_DISTANCE_SQR) {
            speed = runSpeed * clamp(playerRatio, PLAYER_RUN_RATIO_MIN, PLAYER_RUN_RATIO_MAX) * styleRunRatio;
        } else {
            double walkDistance = Math.sqrt(WALK_DISTANCE_SQR);
            double runDistance = Math.sqrt(RUN_DISTANCE_SQR);
            double actualDistance = Math.sqrt(distanceSqr);
            double t = clamp((actualDistance - walkDistance) / (runDistance - walkDistance), 0.0D, 1.0D);
            double walkBlend = walkSpeedModifier()
                    * clamp(playerRatio, PLAYER_WALK_RATIO_MIN, PLAYER_WALK_RATIO_MAX)
                    * styleWalkRatio;
            double runBlend = runSpeedModifier()
                    * clamp(playerRatio, PLAYER_RUN_RATIO_MIN, PLAYER_RUN_RATIO_MAX)
                    * styleRunRatio;
            speed = lerp(walkBlend, runBlend, t);
        }
        speed = applyPlayerApproachSlowdown(speed, distanceSqr, walkSpeed);
        speed = applyFollowPointSlowdown(speed, followDistanceSqr, walkSpeed);
        if (safetyLevel == CompanionSafeMovement.SafetyLevel.CAUTION) {
            speed = Math.min(speed, walkSpeedModifier() * 0.8D);
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
        return CompanionMovementSpeed.strictByAttribute(mob, desiredSpeed);
    }

    private void tryJump(long gameTime, Player target, Vec3 followPos) {
        if (safetyLevel != CompanionSafeMovement.SafetyLevel.SAFE) {
            return;
        }
        if (!mob.onGround() || mob.isInWaterOrBubble()) {
            return;
        }
        if (gameTime < nextJumpTick) {
            return;
        }
        boolean earlyStepJump = shouldEarlyStepJump(followPos);
        if (!earlyStepJump && !shouldJumpForPath(target, followPos)) {
            return;
        }
        mob.getJumpControl().jump();
        if (earlyStepJump) {
            Vec3 direction = resolveMoveDirection(followPos);
            if (direction.lengthSqr() >= 1.0E-4D) {
                double jumpSpeed = Math.max(currentSpeed, walkSpeedModifier()) * STEP_JUMP_SPEED_MULTIPLIER;
                Vec3 motion = mob.getDeltaMovement();
                mob.setDeltaMovement(direction.x * jumpSpeed, motion.y, direction.z * jumpSpeed);
            }
        }
        nextJumpTick = gameTime + JUMP_COOLDOWN_TICKS;
    }

    private boolean shouldJumpForPath(Player target, Vec3 followPos) {
        if (mob.getNavigation() == null) {
            return false;
        }
        net.minecraft.world.level.pathfinder.Path path = mob.getNavigation().getPath();
        if (path == null || path.isDone()) {
            return false;
        }
        BlockPos next = path.getNextNodePos();
        if (next.getY() > mob.getBlockY()) {
            return true;
        }
        if (learnedJumpIntent < STYLE_JUMP_MIN_INTENT || target == null) {
            return false;
        }
        if (horizontalSpeed(target) < PLAYER_IDLE_SPEED) {
            return false;
        }
        return hasGapAhead(followPos);
    }

    private boolean shouldEarlyStepJump(Vec3 followPos) {
        Vec3 direction = resolveMoveDirection(followPos);
        if (direction.lengthSqr() < 1.0E-4D) {
            return false;
        }
        double mobSpeed = Math.hypot(mob.getDeltaMovement().x, mob.getDeltaMovement().z);
        if (mobSpeed < STEP_JUMP_MIN_SPEED && currentSpeed < STEP_JUMP_MIN_SPEED) {
            return false;
        }
        return hasSingleBlockStepObstacle(direction, STEP_JUMP_PROBE_NEAR)
                || hasSingleBlockStepObstacle(direction, STEP_JUMP_PROBE_FAR)
                || hasSingleBlockStepObstacle(direction, STEP_JUMP_PROBE_EARLY);
    }

    private boolean hasSingleBlockStepObstacle(Vec3 direction, double probeDistance) {
        if (direction == null || direction.lengthSqr() < 1.0E-4D) {
            return false;
        }
        Vec3 normalized = direction.normalize();
        BlockPos front = BlockPos.containing(
                mob.getX() + normalized.x * probeDistance,
                mob.getY(),
                mob.getZ() + normalized.z * probeDistance
        );
        if (isPassable(front)) {
            return false;
        }
        if (!isPassable(front.above()) || !isPassable(front.above(2))) {
            return false;
        }
        return !isPassable(front.below());
    }

    private boolean tryAssistLadderClimb(Vec3 followPos, long gameTime) {
        if (followPos == null || followPos.y - mob.getY() < LADDER_MIN_VERTICAL_GAP) {
            return false;
        }
        Vec3 direction = resolveMoveDirection(followPos);
        if (direction.lengthSqr() < 1.0E-4D) {
            return false;
        }
        BlockPos current = mob.blockPosition();
        BlockPos ahead = BlockPos.containing(
                mob.getX() + direction.x * LADDER_AHEAD_PROBE,
                mob.getY(),
                mob.getZ() + direction.z * LADDER_AHEAD_PROBE
        );
        boolean onLadder = isClimbable(current) || isClimbable(current.above());
        boolean hasLadderAhead = isClimbable(ahead) || isClimbable(ahead.above());
        if (!onLadder && !hasLadderAhead) {
            return false;
        }
        double wantedSpeed = Math.max(currentSpeed, walkSpeedModifier());
        double forwardSpeed = wantedSpeed * LADDER_FORWARD_FACTOR;
        Vec3 motion = mob.getDeltaMovement();
        mob.getMoveControl().setWantedPosition(followPos.x, followPos.y, followPos.z, wantedSpeed);
        mob.setDeltaMovement(
                direction.x * forwardSpeed,
                Math.max(motion.y, LADDER_CLIMB_SPEED),
                direction.z * forwardSpeed
        );
        ladderAssistUntilTick = gameTime + LADDER_ASSIST_TICKS;
        return true;
    }

    private boolean shouldHold(Player target, double distanceSqr, double followDistanceSqr) {
        double playerSpeed = horizontalSpeed(target);
        if (playerSpeed < PLAYER_IDLE_SPEED
                && distanceSqr <= HOLD_DISTANCE_SQR
                && followDistanceSqr <= HOLD_FOLLOW_DISTANCE_SQR
                && Math.abs(target.getY() - mob.getY()) <= HOLD_VERTICAL_EPS) {
            return true;
        }
        return false;
    }

    private double applyPlayerApproachSlowdown(double speed, double distanceSqr, double walkSpeed) {
        if (distanceSqr >= APPROACH_START_DISTANCE_SQR) {
            return speed;
        }
        double distance = Math.sqrt(Math.max(0.0D, distanceSqr));
        double t = clamp((distance - APPROACH_STOP_DISTANCE)
                / Math.max(1.0E-4D, APPROACH_START_DISTANCE - APPROACH_STOP_DISTANCE), 0.0D, 1.0D);
        return lerp(walkSpeed * APPROACH_MIN_SPEED_FACTOR, speed, smoothStep(t));
    }

    private double applyFollowPointSlowdown(double speed, double followDistanceSqr, double walkSpeed) {
        if (followDistanceSqr >= FOLLOW_POINT_SLOWDOWN_DISTANCE_SQR) {
            return speed;
        }
        double distance = Math.sqrt(Math.max(0.0D, followDistanceSqr));
        double t = clamp(distance / FOLLOW_POINT_SLOWDOWN_DISTANCE, 0.0D, 1.0D);
        return lerp(walkSpeed * FOLLOW_POINT_MIN_SPEED_FACTOR, speed, smoothStep(t));
    }

    private double playerSpeedRatio(Player target) {
        double base = target.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (base <= 0.0D) {
            return 0.0D;
        }
        return horizontalSpeed(target) / base;
    }

    private void rememberPlayerStyle(Player target, long gameTime) {
        capturePlayerJumpSample(target, gameTime);
        double playerSpeed = horizontalSpeed(target);
        double ratio = playerSpeedRatio(target);
        if (ratio > 0.0D) {
            double clampedRatio = clamp(ratio, 0.75D, 1.4D);
            if (target.isSprinting() || clampedRatio > 1.1D) {
                learnedRunRatio = lerp(learnedRunRatio,
                        clamp(clampedRatio, STYLE_RUN_RATIO_MIN, STYLE_RUN_RATIO_MAX),
                        STYLE_LEARN_RATE);
            } else {
                learnedWalkRatio = lerp(learnedWalkRatio,
                        clamp(clampedRatio, STYLE_WALK_RATIO_MIN, STYLE_WALK_RATIO_MAX),
                        STYLE_LEARN_RATE);
            }
        }
        learnedWalkRatio = clamp(learnedWalkRatio, STYLE_WALK_RATIO_MIN, STYLE_WALK_RATIO_MAX);
        learnedRunRatio = clamp(learnedRunRatio, STYLE_RUN_RATIO_MIN, STYLE_RUN_RATIO_MAX);
        learnedJumpIntent *= STYLE_JUMP_DECAY;
        if (isPlayerJumpingForward(target, playerSpeed)) {
            boolean playerGapJump = isGapAheadForPlayer(target);
            double bonus = playerGapJump ? STYLE_JUMP_GAP_BONUS : 0.0D;
            learnedJumpIntent = clamp(learnedJumpIntent + STYLE_JUMP_STEP + bonus, 0.0D, 1.0D);
            if (playerGapJump) {
                Vec3 jumpDirection = resolveForwardDirection(target);
                if (jumpDirection.lengthSqr() >= 1.0E-4D) {
                    learnedGapJumpDirection = jumpDirection;
                    learnedGapJumpUntilTick = mob.level().getGameTime() + STYLE_GAP_JUMP_MEMORY_TICKS;
                }
            }
        }
    }

    private void capturePlayerJumpSample(Player target, long gameTime) {
        if (target == null) {
            return;
        }
        boolean onGround = target.onGround();
        Vec3 motion = target.getDeltaMovement();
        boolean jumpStarted = !onGround && lastPlayerOnGround && motion.y > 0.18D;
        if (!jumpStarted) {
            lastPlayerOnGround = onGround;
            return;
        }
        Vec3 direction = resolveForwardDirection(target);
        if (direction.lengthSqr() < 1.0E-4D) {
            lastPlayerOnGround = onGround;
            return;
        }
        boolean jumpOverObstacle = isGapAheadForPlayer(target) || hasFrontObstacleForPlayer(target, direction);
        if (jumpOverObstacle) {
            pendingPlayerJumpDirection = direction;
            pendingPlayerJumpTakeoffPos = target.position();
            pendingPlayerJumpHorizontalSpeed = Math.hypot(motion.x, motion.z);
            pendingPlayerJumpVerticalSpeed = motion.y;
            pendingPlayerJumpUntilTick = gameTime + PLAYER_JUMP_COPY_WINDOW_TICKS;
        }
        lastPlayerOnGround = onGround;
    }

    private boolean hasFrontObstacleForPlayer(Player target, Vec3 direction) {
        if (target == null || direction == null || direction.lengthSqr() < 1.0E-4D) {
            return false;
        }
        Vec3 normalized = direction.normalize();
        BlockPos frontFeet = BlockPos.containing(
                target.getX() + normalized.x * 0.55D,
                target.getY(),
                target.getZ() + normalized.z * 0.55D
        );
        return !isPassable(frontFeet) || !isPassable(frontFeet.above());
    }

    private boolean tryMirrorPlayerJump(Player target, Vec3 followPos, long gameTime, double distanceSqr) {
        if (target == null || followPos == null) {
            return false;
        }
        if (distanceSqr <= PLAYER_JUMP_COPY_MIN_DISTANCE_SQR) {
            return false;
        }
        if (gameTime >= pendingPlayerJumpUntilTick) {
            return false;
        }
        if (!mob.onGround() || mob.isInWaterOrBubble()) {
            return false;
        }
        if (gameTime < nextJumpTick) {
            return false;
        }
        if (pendingPlayerJumpTakeoffPos == null
                || mob.position().distanceToSqr(pendingPlayerJumpTakeoffPos) > PLAYER_JUMP_COPY_MAX_TAKEOFF_DISTANCE_SQR) {
            return false;
        }
        Vec3 direction = pendingPlayerJumpDirection;
        if (direction == null || direction.lengthSqr() < 1.0E-4D) {
            return false;
        }
        direction = direction.normalize();
        Vec3 toTarget = new Vec3(target.getX() - mob.getX(), 0.0D, target.getZ() - mob.getZ());
        if (toTarget.lengthSqr() < 1.0E-4D) {
            return false;
        }
        toTarget = toTarget.normalize();
        if (toTarget.dot(direction) < PLAYER_JUMP_COPY_ALIGN_DOT) {
            return false;
        }
        if (!hasLandingAhead(direction)) {
            return false;
        }
        mob.getNavigation().stop();
        mob.getJumpControl().jump();
        double horizontal = clamp(
                pendingPlayerJumpHorizontalSpeed,
                PLAYER_JUMP_COPY_MIN_HORIZONTAL_SPEED,
                PLAYER_JUMP_COPY_MAX_HORIZONTAL_SPEED
        );
        double vertical = clamp(
                pendingPlayerJumpVerticalSpeed,
                PLAYER_JUMP_COPY_MIN_VERTICAL_SPEED,
                PLAYER_JUMP_COPY_MAX_VERTICAL_SPEED
        );
        Vec3 motion = mob.getDeltaMovement();
        mob.setDeltaMovement(
                direction.x * horizontal,
                Math.max(motion.y, vertical),
                direction.z * horizontal
        );
        nextJumpTick = gameTime + JUMP_COOLDOWN_TICKS;
        gapJumpLockUntilTick = gameTime + PLAYER_JUMP_COPY_LOCK_TICKS;
        pendingPlayerJumpUntilTick = 0L;
        return true;
    }

    private boolean tryLearnedGapJump(Player target, Vec3 followPos, long gameTime, double distanceSqr) {
        if (target == null || followPos == null) {
            return false;
        }
        if (distanceSqr <= STYLE_GAP_JUMP_MIN_DISTANCE_SQR) {
            return false;
        }
        if (gameTime >= learnedGapJumpUntilTick) {
            return false;
        }
        if (!mob.onGround() || mob.isInWaterOrBubble()) {
            return false;
        }
        if (gameTime < nextJumpTick) {
            return false;
        }
        Vec3 direction = resolveLearnedGapDirection(target, followPos);
        if (direction.lengthSqr() < 1.0E-4D) {
            return false;
        }
        Vec3 toTarget = new Vec3(target.getX() - mob.getX(), 0.0D, target.getZ() - mob.getZ());
        if (toTarget.lengthSqr() < 1.0E-4D) {
            return false;
        }
        toTarget = toTarget.normalize();
        if (toTarget.dot(direction) < STYLE_GAP_JUMP_ALIGN_DOT) {
            return false;
        }
        if (!hasGapAheadInDirection(direction) || !hasLandingAhead(direction)) {
            return false;
        }
        mob.getNavigation().stop();
        mob.getJumpControl().jump();
        Vec3 motion = mob.getDeltaMovement();
        mob.setDeltaMovement(
                direction.x * STYLE_GAP_JUMP_FORWARD_SPEED,
                Math.max(motion.y, STYLE_GAP_JUMP_VERTICAL_SPEED),
                direction.z * STYLE_GAP_JUMP_FORWARD_SPEED
        );
        nextJumpTick = gameTime + JUMP_COOLDOWN_TICKS;
        gapJumpLockUntilTick = gameTime + STYLE_GAP_JUMP_LOCK_TICKS;
        learnedGapJumpUntilTick = 0L;
        return true;
    }

    private boolean isPlayerJumpingForward(Player target, double horizontalSpeed) {
        if (target == null) {
            return false;
        }
        if (horizontalSpeed < PLAYER_IDLE_SPEED) {
            return false;
        }
        return !target.onGround() && target.getDeltaMovement().y > 0.18D;
    }

    private boolean isGapAheadForPlayer(Player target) {
        if (target == null) {
            return false;
        }
        Vec3 direction = resolveForwardDirection(target);
        if (direction.lengthSqr() < 1.0E-4D) {
            return false;
        }
        BlockPos probePos = BlockPos.containing(
                target.getX() + direction.x * GAP_PROBE_DISTANCE,
                target.getY(),
                target.getZ() + direction.z * GAP_PROBE_DISTANCE
        );
        return isGapAt(probePos);
    }

    private Vec3 resolveForwardDirection(Player target) {
        if (target == null) {
            return Vec3.ZERO;
        }
        Vec3 direction = new Vec3(target.getDeltaMovement().x, 0.0D, target.getDeltaMovement().z);
        if (direction.lengthSqr() < 1.0E-4D) {
            Vec3 look = target.getLookAngle();
            direction = new Vec3(look.x, 0.0D, look.z);
        }
        if (direction.lengthSqr() < 1.0E-4D) {
            return Vec3.ZERO;
        }
        return direction.normalize();
    }

    private Vec3 resolveLearnedGapDirection(Player target, Vec3 followPos) {
        if (learnedGapJumpDirection != null && learnedGapJumpDirection.lengthSqr() >= 1.0E-4D) {
            return learnedGapJumpDirection.normalize();
        }
        Vec3 toFollow = resolveMoveDirection(followPos);
        if (toFollow.lengthSqr() >= 1.0E-4D) {
            return toFollow;
        }
        return resolveForwardDirection(target);
    }

    private Vec3 resolveMoveDirection(Vec3 followPos) {
        Vec3 direction = null;
        if (mob.getNavigation() != null) {
            net.minecraft.world.level.pathfinder.Path path = mob.getNavigation().getPath();
            if (path != null && !path.isDone()) {
                BlockPos next = path.getNextNodePos();
                direction = new Vec3(next.getX() + 0.5D - mob.getX(), 0.0D, next.getZ() + 0.5D - mob.getZ());
            }
        }
        if (direction == null || direction.lengthSqr() < 1.0E-4D) {
            if (followPos == null) {
                return Vec3.ZERO;
            }
            direction = new Vec3(followPos.x - mob.getX(), 0.0D, followPos.z - mob.getZ());
        }
        if (direction.lengthSqr() < 1.0E-4D) {
            return Vec3.ZERO;
        }
        return direction.normalize();
    }

    private boolean hasGapAhead(Vec3 followPos) {
        Vec3 direction = resolveMoveDirection(followPos);
        if (direction.lengthSqr() < 1.0E-4D) {
            return false;
        }
        BlockPos probePos = BlockPos.containing(
                mob.getX() + direction.x * GAP_PROBE_DISTANCE,
                mob.getY(),
                mob.getZ() + direction.z * GAP_PROBE_DISTANCE
        );
        return isGapAt(probePos);
    }

    private boolean hasGapAheadInDirection(Vec3 direction) {
        if (direction == null || direction.lengthSqr() < 1.0E-4D) {
            return false;
        }
        Vec3 normalized = direction.normalize();
        BlockPos probePos = BlockPos.containing(
                mob.getX() + normalized.x * GAP_PROBE_DISTANCE,
                mob.getY(),
                mob.getZ() + normalized.z * GAP_PROBE_DISTANCE
        );
        return isGapAt(probePos);
    }

    private boolean hasLandingAhead(Vec3 direction) {
        if (direction == null || direction.lengthSqr() < 1.0E-4D) {
            return false;
        }
        Vec3 normalized = direction.normalize();
        for (double distance = 1.4D; distance <= 3.2D; distance += 0.6D) {
            BlockPos checkPos = BlockPos.containing(
                    mob.getX() + normalized.x * distance,
                    mob.getY(),
                    mob.getZ() + normalized.z * distance
            );
            if (!isPassable(checkPos) || !isPassable(checkPos.above())) {
                continue;
            }
            if (!isPassable(checkPos.below())) {
                return true;
            }
        }
        return false;
    }

    private boolean isGapAt(BlockPos frontPos) {
        if (frontPos == null) {
            return false;
        }
        if (!isPassable(frontPos) || !isPassable(frontPos.above())) {
            return false;
        }
        int solidDepth = firstSolidDepth(frontPos, GAP_CHECK_DEPTH);
        return solidDepth >= 2;
    }

    private int firstSolidDepth(BlockPos from, int maxDepth) {
        for (int depth = 1; depth <= maxDepth; depth++) {
            if (!isPassable(from.below(depth))) {
                return depth;
            }
        }
        return -1;
    }

    private boolean isPassable(BlockPos pos) {
        return mob.level().getBlockState(pos).getCollisionShape(mob.level(), pos).isEmpty();
    }

    private boolean isClimbable(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        return mob.level().getBlockState(pos).is(BlockTags.CLIMBABLE);
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

    private double smoothStep(double t) {
        double clamped = clamp(t, 0.0D, 1.0D);
        return clamped * clamped * (3.0D - 2.0D * clamped);
    }

    private double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }
}
