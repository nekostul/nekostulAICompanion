package ru.nekostul.aicompanion.entity.ai;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;

public final class CompanionSwimmingMoveControl extends MoveControl {
    private static final float PITCH_ROTATE_STEP_DEGREES = 10.0F;
    private static final double DIVE_ACCELERATION = 0.085D;
    private static final double SURFACE_ACCELERATION = 0.06D;
    private final int maxTurnX;
    private final int maxTurnY;
    private final float inWaterSpeedModifier;
    private final float outsideWaterSpeedModifier;
    private final boolean applyBuoyancy;

    public CompanionSwimmingMoveControl(Mob mob, int maxTurnX, int maxTurnY, float inWaterSpeedModifier,
                                        float outsideWaterSpeedModifier, boolean applyBuoyancy) {
        super(mob);
        this.maxTurnX = maxTurnX;
        this.maxTurnY = maxTurnY;
        this.inWaterSpeedModifier = inWaterSpeedModifier;
        this.outsideWaterSpeedModifier = outsideWaterSpeedModifier;
        this.applyBuoyancy = applyBuoyancy;
    }

    @Override
    public void tick() {
        if (this.applyBuoyancy && this.mob.isInWater()) {
            this.mob.setDeltaMovement(this.mob.getDeltaMovement().add(0.0D, 0.005D, 0.0D));
        }

        if (this.operation != Operation.MOVE_TO) {
            this.mob.setSpeed(0.0F);
            this.mob.setXxa(0.0F);
            this.mob.setYya(0.0F);
            this.mob.setZza(0.0F);
            return;
        }

        this.operation = Operation.WAIT;
        double dx = this.wantedX - this.mob.getX();
        double dy = this.wantedY - this.mob.getY();
        double dz = this.wantedZ - this.mob.getZ();
        double distanceSqr = dx * dx + dy * dy + dz * dz;
        if (distanceSqr < (double) MIN_SPEED_SQR) {
            this.mob.setSpeed(0.0F);
            this.mob.setXxa(0.0F);
            this.mob.setYya(0.0F);
            this.mob.setZza(0.0F);
            return;
        }

        float desiredYaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        this.mob.setYRot(this.rotlerp(this.mob.getYRot(), desiredYaw, (float) this.maxTurnY));
        this.mob.setYBodyRot(this.mob.getYRot());
        this.mob.setYHeadRot(this.mob.getYRot());

        float movementSpeed = (float) (this.speedModifier * this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED));
        if (this.mob.isInWaterOrBubble()) {
            this.mob.setSpeed(movementSpeed * this.inWaterSpeedModifier);
            double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
            if (Math.abs(dy) > 1.0E-5D || horizontalDistance > 1.0E-5D) {
                float desiredPitch = -((float) (Mth.atan2(dy, horizontalDistance) * (180.0D / Math.PI)));
                desiredPitch = Mth.clamp(Mth.wrapDegrees(desiredPitch), (float) (-this.maxTurnX), (float) this.maxTurnX);
                this.mob.setXRot(this.rotlerp(this.mob.getXRot(), desiredPitch, PITCH_ROTATE_STEP_DEGREES));
            }

            float xRotRadians = this.mob.getXRot() * ((float) Math.PI / 180.0F);
            float forwardFactor = Mth.cos(xRotRadians);
            float verticalFactor = Mth.sin(xRotRadians);
            this.mob.setZza(forwardFactor * movementSpeed);
            this.mob.setYya(-verticalFactor * movementSpeed);
            this.mob.setXxa(0.0F);
            if (this.mob.isSwimming()) {
                double lookY = this.mob.getLookAngle().y;
                double verticalAcceleration = lookY < -0.2D ? DIVE_ACCELERATION : SURFACE_ACCELERATION;
                this.mob.setDeltaMovement(this.mob.getDeltaMovement().add(
                        0.0D,
                        (lookY - this.mob.getDeltaMovement().y) * verticalAcceleration,
                        0.0D
                ));
            }
            return;
        }

        float yawDelta = Math.abs(Mth.wrapDegrees(this.mob.getYRot() - desiredYaw));
        float turningFactor = 1.0F - Mth.clamp((yawDelta - 10.0F) / 50.0F, 0.0F, 1.0F);
        this.mob.setSpeed(movementSpeed * this.outsideWaterSpeedModifier * turningFactor);
        this.mob.setXRot(0.0F);
        this.mob.setXxa(0.0F);
        this.mob.setYya(0.0F);
    }
}
