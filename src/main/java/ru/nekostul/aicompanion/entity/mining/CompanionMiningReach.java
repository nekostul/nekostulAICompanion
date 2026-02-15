package ru.nekostul.aicompanion.entity.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ru.nekostul.aicompanion.entity.CompanionEntity;

public final class CompanionMiningReach {
    private static final double MAX_DISTANCE = 5.0D;
    private static final double MAX_DISTANCE_SQR = MAX_DISTANCE * MAX_DISTANCE;
    private static final double EDGE_OFFSET = 0.2D;
    private static final double LOW_OFFSET = 0.2D;
    private static final double HIGH_OFFSET = 0.8D;

    private final CompanionEntity owner;

    public CompanionMiningReach(CompanionEntity owner) {
        this.owner = owner;
    }

    public boolean canMine(BlockPos target) {
        if (target == null) {
            return false;
        }
        Vec3 eye = owner.getEyePosition();
        double nearestX = clamp(eye.x, target.getX(), target.getX() + 1.0D);
        double nearestY = clamp(eye.y, target.getY(), target.getY() + 1.0D);
        double nearestZ = clamp(eye.z, target.getZ(), target.getZ() + 1.0D);
        double distanceSqr = eye.distanceToSqr(nearestX, nearestY, nearestZ);
        if (distanceSqr > MAX_DISTANCE_SQR) {
            return false;
        }
        return canSee(target);
    }

    public boolean canSee(BlockPos target) {
        if (target == null) {
            return false;
        }
        return hasLineOfSight(Vec3.atCenterOf(target), target);
    }

    private boolean hasLineOfSight(Vec3 targetCenter, BlockPos targetPos) {
        Vec3 eye = owner.getEyePosition();
        Vec3[] probePoints = new Vec3[] {
                targetCenter,
                new Vec3(targetPos.getX() + 0.5D, targetPos.getY() + LOW_OFFSET, targetPos.getZ() + 0.5D),
                new Vec3(targetPos.getX() + 0.5D, targetPos.getY() + HIGH_OFFSET, targetPos.getZ() + 0.5D),
                new Vec3(targetPos.getX() + EDGE_OFFSET, targetPos.getY() + 0.5D, targetPos.getZ() + 0.5D),
                new Vec3(targetPos.getX() + (1.0D - EDGE_OFFSET), targetPos.getY() + 0.5D, targetPos.getZ() + 0.5D),
                new Vec3(targetPos.getX() + 0.5D, targetPos.getY() + 0.5D, targetPos.getZ() + EDGE_OFFSET),
                new Vec3(targetPos.getX() + 0.5D, targetPos.getY() + 0.5D, targetPos.getZ() + (1.0D - EDGE_OFFSET))
        };

        for (Vec3 point : probePoints) {
            BlockHitResult hit = owner.level().clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, owner));
            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(targetPos)) {
                return true;
            }
        }
        return false;
    }

    private double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
