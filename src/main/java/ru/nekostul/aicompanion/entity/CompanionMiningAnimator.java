package ru.nekostul.aicompanion.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class CompanionMiningAnimator {
    private final CompanionEntity owner;

    CompanionMiningAnimator(CompanionEntity owner) {
        this.owner = owner;
    }

    void tick(BlockPos target, float progressPerTick, long gameTime) {
        if (target == null) {
            return;
        }
        Vec3 center = Vec3.atCenterOf(target);
        lookAt(center);
        if (!owner.swinging) {
            owner.swing(InteractionHand.MAIN_HAND);
        }
    }

    void reset() {
    }

    private void lookAt(Vec3 target) {
        double dx = target.x - owner.getX();
        double dz = target.z - owner.getZ();
        double dy = target.y - owner.getEyeY();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0F / (float) Math.PI)) - 90.0F;
        float pitch = (float) (-Mth.atan2(dy, horizontal) * (180.0F / (float) Math.PI));
        owner.setYRot(yaw);
        owner.setYBodyRot(yaw);
        owner.setYHeadRot(yaw);
        owner.setXRot(pitch);
        owner.getLookControl().setLookAt(target.x, target.y, target.z);
    }

}
