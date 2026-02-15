package ru.nekostul.aicompanion.entity.movement;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

public final class CompanionMovementSpeed {
    private CompanionMovementSpeed() {
    }

    public static double strictByAttribute(LivingEntity entity, double desiredSpeed) {
        if (entity == null) {
            return 0.0D;
        }
        double base = entity.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (base <= 0.0D) {
            return 0.0D;
        }
        return desiredSpeed / base;
    }

    public static double fallbackDesiredByAttribute(LivingEntity entity, double desiredSpeed) {
        if (entity == null) {
            return desiredSpeed;
        }
        double base = entity.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (base <= 1.0E-4D) {
            return desiredSpeed;
        }
        return desiredSpeed / base;
    }
}
