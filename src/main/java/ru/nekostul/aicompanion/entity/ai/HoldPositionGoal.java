package ru.nekostul.aicompanion.entity.ai;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.function.BooleanSupplier;

public class HoldPositionGoal extends Goal {
    private final PathfinderMob mob;
    private final BooleanSupplier shouldHold;

    public HoldPositionGoal(PathfinderMob mob, BooleanSupplier shouldHold) {
        this.mob = mob;
        this.shouldHold = shouldHold;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return this.shouldHold.getAsBoolean();
    }

    @Override
    public boolean canContinueToUse() {
        return this.shouldHold.getAsBoolean();
    }

    @Override
    public void start() {
        this.mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        this.mob.getNavigation().stop();
        this.mob.setDeltaMovement(0.0D, this.mob.getDeltaMovement().y, 0.0D);
    }
}
