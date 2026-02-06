package ru.nekostul.aicompanion.entity.ai;

import net.minecraft.core.BlockPos;
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

    private static final double PROBE_DISTANCE = 2.0D;
    private static final int MAX_DROP = 4;
    private static final int CAUTION_DROP = 2;
    private static final int MAX_RISE = 4;
    private static final int CAUTION_RISE = 2;
    private static final int CHECK_DOWN = 5;
    private static final int CHECK_UP = 2;

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
        double probeX = mob.getX() + dir.x * PROBE_DISTANCE;
        double probeZ = mob.getZ() + dir.z * PROBE_DISTANCE;
        BlockPos probePos = new BlockPos((int) Math.floor(probeX), mob.getBlockY(), (int) Math.floor(probeZ));
        int groundY = findGroundY(probePos);
        if (groundY == Integer.MIN_VALUE) {
            return SafetyLevel.DANGER;
        }
        int delta = groundY - mob.getBlockY();
        if (delta <= -MAX_DROP || delta >= MAX_RISE) {
            return SafetyLevel.DANGER;
        }
        if (delta <= -CAUTION_DROP || delta >= CAUTION_RISE) {
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
