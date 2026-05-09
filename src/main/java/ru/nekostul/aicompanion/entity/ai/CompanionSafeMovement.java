package ru.nekostul.aicompanion.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

final class CompanionSafeMovement {
    enum SafetyLevel {
        SAFE,
        CAUTION,
        DANGER
    }

    private enum SurfaceType {
        GROUND,
        WATER,
        NONE
    }

    private static final class ProbeSample {
        private final SurfaceType surfaceType;
        private final int deltaY;
        private final boolean safeWaterEntry;

        private ProbeSample(SurfaceType surfaceType, int deltaY, boolean safeWaterEntry) {
            this.surfaceType = surfaceType;
            this.deltaY = deltaY;
            this.safeWaterEntry = safeWaterEntry;
        }

        private static ProbeSample ground(int deltaY) {
            return new ProbeSample(SurfaceType.GROUND, deltaY, false);
        }

        private static ProbeSample water(int deltaY, boolean safeWaterEntry) {
            return new ProbeSample(SurfaceType.WATER, deltaY, safeWaterEntry);
        }

        private static ProbeSample none() {
            return new ProbeSample(SurfaceType.NONE, 0, false);
        }
    }

    private static final double PROBE_NEAR_DISTANCE = 0.95D;
    private static final double PROBE_FAR_DISTANCE = 1.85D;
    private static final int MAX_DROP = 4;
    private static final int CAUTION_DROP = 2;
    private static final int MAX_RISE = 4;
    private static final int CAUTION_RISE = 2;
    private static final int CHECK_DOWN = 5;
    private static final int CHECK_UP = 2;
    private static final int CAUTION_FAR_DROP = 5;
    private static final int CAUTION_WATER_SURFACE_DROP = 3;
    private static final int MIN_WATER_ENTRY_SIDE_OPENINGS = 1;

    private final PathfinderMob mob;

    CompanionSafeMovement(PathfinderMob mob) {
        this.mob = mob;
    }

    SafetyLevel evaluate(Vec3 targetPos) {
        Vec3 probeTarget = resolveProbeTarget(targetPos);
        if (probeTarget == null) {
            return SafetyLevel.SAFE;
        }
        Vec3 dir = probeTarget.subtract(mob.position());
        dir = new Vec3(dir.x, 0.0D, dir.z);
        if (dir.lengthSqr() < 1.0E-4D) {
            return SafetyLevel.SAFE;
        }
        dir = dir.normalize();
        ProbeSample near = sampleTerrain(dir, PROBE_NEAR_DISTANCE);
        if (near.surfaceType == SurfaceType.NONE) {
            return SafetyLevel.DANGER;
        }
        if (near.surfaceType == SurfaceType.WATER) {
            if (!near.safeWaterEntry) {
                return SafetyLevel.DANGER;
            }
            if (near.deltaY <= -CAUTION_WATER_SURFACE_DROP) {
                return SafetyLevel.CAUTION;
            }
            return SafetyLevel.SAFE;
        }
        if (near.deltaY <= -MAX_DROP || near.deltaY >= MAX_RISE) {
            return SafetyLevel.DANGER;
        }
        if (near.deltaY <= -CAUTION_DROP || near.deltaY >= CAUTION_RISE) {
            return SafetyLevel.CAUTION;
        }
        ProbeSample far = sampleTerrain(dir, PROBE_FAR_DISTANCE);
        if (far.surfaceType == SurfaceType.NONE) {
            return SafetyLevel.CAUTION;
        }
        if (far.surfaceType == SurfaceType.WATER) {
            return far.safeWaterEntry ? SafetyLevel.SAFE : SafetyLevel.CAUTION;
        }
        if (far.deltaY <= -CAUTION_FAR_DROP || far.deltaY >= MAX_RISE + 1) {
            return SafetyLevel.CAUTION;
        }
        return SafetyLevel.SAFE;
    }

    private Vec3 resolveProbeTarget(Vec3 fallback) {
        if (mob.getNavigation().getPath() != null && !mob.getNavigation().getPath().isDone()) {
            BlockPos next = mob.getNavigation().getPath().getNextNodePos();
            return Vec3.atCenterOf(next);
        }
        return fallback;
    }

    private ProbeSample sampleTerrain(Vec3 direction, double probeDistance) {
        double probeX = mob.getX() + direction.x * probeDistance;
        double probeZ = mob.getZ() + direction.z * probeDistance;
        BlockPos probePos = BlockPos.containing(probeX, mob.getY(), probeZ);
        ProbeSample waterSample = sampleWaterEntry(probePos);
        if (waterSample != null) {
            return waterSample;
        }
        int groundY = findGroundY(probePos);
        if (groundY == Integer.MIN_VALUE) {
            return ProbeSample.none();
        }
        return ProbeSample.ground(groundY - mob.getBlockY());
    }

    private ProbeSample sampleWaterEntry(BlockPos base) {
        int surfaceY = findWaterSurfaceY(base);
        if (surfaceY == Integer.MIN_VALUE) {
            return null;
        }
        BlockPos surfaceBlock = new BlockPos(base.getX(), surfaceY - 1, base.getZ());
        return ProbeSample.water(surfaceY - mob.getBlockY(), isWideWaterEntry(surfaceBlock));
    }

    private int findWaterSurfaceY(BlockPos base) {
        Level level = mob.level();
        int startY = base.getY() + CHECK_UP;
        int minY = base.getY() - CHECK_DOWN;
        boolean foundWater = false;
        int topWaterY = Integer.MIN_VALUE;
        for (int y = startY; y >= minY; y--) {
            BlockPos pos = new BlockPos(base.getX(), y, base.getZ());
            if (level.getFluidState(pos).is(FluidTags.WATER)) {
                if (!foundWater) {
                    topWaterY = y;
                    foundWater = true;
                }
            } else if (foundWater) {
                break;
            }
        }
        return foundWater ? topWaterY + 1 : Integer.MIN_VALUE;
    }

    private boolean isWideWaterEntry(BlockPos waterPos) {
        Level level = mob.level();
        if (!level.getFluidState(waterPos).is(FluidTags.WATER)) {
            return false;
        }
        BlockPos headPos = waterPos.above();
        if (!level.getFluidState(headPos).is(FluidTags.WATER)
                && !level.getBlockState(headPos).getCollisionShape(level, headPos).isEmpty()) {
            return false;
        }
        int openings = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = waterPos.relative(direction);
            if (level.getFluidState(side).is(FluidTags.WATER)
                    || level.getBlockState(side).getCollisionShape(level, side).isEmpty()) {
                openings++;
                if (openings >= MIN_WATER_ENTRY_SIDE_OPENINGS) {
                    return true;
                }
            }
        }
        return false;
    }

    private int findGroundY(BlockPos base) {
        Level level = mob.level();
        int startY = base.getY() + CHECK_UP;
        int minY = base.getY() - CHECK_DOWN;
        for (int y = startY; y >= minY; y--) {
            BlockPos pos = new BlockPos(base.getX(), y, base.getZ());
            BlockState state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }
}
