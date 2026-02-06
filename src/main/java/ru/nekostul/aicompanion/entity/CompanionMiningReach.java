package ru.nekostul.aicompanion.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class CompanionMiningReach {
    private static final double MAX_DISTANCE = 5.0D;
    private static final double MAX_DISTANCE_SQR = MAX_DISTANCE * MAX_DISTANCE;

    private final CompanionEntity owner;

    CompanionMiningReach(CompanionEntity owner) {
        this.owner = owner;
    }

    boolean canMine(BlockPos target) {
        if (target == null) {
            return false;
        }
        Vec3 center = Vec3.atCenterOf(target);
        if (owner.distanceToSqr(center) > MAX_DISTANCE_SQR) {
            return false;
        }
        return hasLineOfSight(center, target);
    }

    private boolean hasLineOfSight(Vec3 targetCenter, BlockPos targetPos) {
        Vec3 eye = owner.getEyePosition();
        BlockHitResult hit = owner.level().clip(new ClipContext(eye, targetCenter, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, owner));
        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }
        if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        return hit.getBlockPos().equals(targetPos);
    }
}
