package ru.nekostul.aicompanion.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.UUID;

public final class CompanionSingleNpcManager {
    private static UUID activeId;
    private static ResourceKey<Level> activeDimension;
    private static BlockPos lastKnownPos;
    private static CompanionEntity.CompanionMode lastMode = CompanionEntity.CompanionMode.AUTONOMOUS;
    private static boolean lastBusy;
    private static long lastTeleportCycleTick = -10000L;
    private static long lastTeleportOriginalTick = -10000L;

    private CompanionSingleNpcManager() {
    }

    static void register(CompanionEntity entity) {
        if (entity.level().isClientSide) {
            return;
        }
        UUID currentId = entity.getUUID();
        if (activeId != null && !activeId.equals(currentId)) {
            ServerLevel previousLevel = resolveLevel(entity, activeDimension);
            if (previousLevel != null) {
                Entity previous = previousLevel.getEntity(activeId);
                if (previous instanceof CompanionEntity companion && companion.isAlive()) {
                    companion.kill();
                }
            }
        }
        activeId = currentId;
        activeDimension = entity.level().dimension();
        lastKnownPos = entity.blockPosition();
        lastMode = entity.getMode();
        lastBusy = false;
    }

    static void unregister(CompanionEntity entity) {
        if (entity.level().isClientSide) {
            return;
        }
        if (activeId != null && activeId.equals(entity.getUUID())) {
            if (entity.isAlive()) {
                activeDimension = entity.level().dimension();
                lastKnownPos = entity.blockPosition();
                lastMode = entity.getMode();
            } else {
                activeId = null;
                activeDimension = null;
                lastKnownPos = null;
            }
        }
    }

    public static CompanionEntity getActive(ServerPlayer player) {
        if (player == null || player.server == null) {
            return null;
        }
        if (activeId == null || activeDimension == null) {
            return null;
        }
        ServerLevel level = player.server.getLevel(activeDimension);
        if (level == null) {
            return null;
        }
        Entity entity = level.getEntity(activeId);
        if (entity instanceof CompanionEntity companion && companion.isAlive()) {
            return companion;
        }
        if (lastKnownPos != null && !level.hasChunkAt(lastKnownPos)) {
            return null;
        }
        activeId = null;
        activeDimension = null;
        lastKnownPos = null;
        return null;
    }

    static void updateState(CompanionEntity entity, boolean busy, long teleportCycleTick, long teleportOriginalTick) {
        if (entity == null || entity.level().isClientSide) {
            return;
        }
        activeId = entity.getUUID();
        activeDimension = entity.level().dimension();
        lastKnownPos = entity.blockPosition();
        lastMode = entity.getMode();
        lastBusy = busy;
        lastTeleportCycleTick = teleportCycleTick;
        lastTeleportOriginalTick = teleportOriginalTick;
    }

    public static UUID getActiveId() {
        return activeId;
    }

    public static ResourceKey<Level> getActiveDimension() {
        return activeDimension;
    }

    public static BlockPos getLastKnownPos() {
        return lastKnownPos;
    }

    public static CompanionEntity.CompanionMode getLastMode() {
        return lastMode;
    }

    public static boolean isLastBusy() {
        return lastBusy;
    }

    public static long getLastTeleportCycleTick() {
        return lastTeleportCycleTick;
    }

    public static void setLastTeleportCycleTick(long tick) {
        lastTeleportCycleTick = tick;
    }

    public static long getLastTeleportOriginalTick() {
        return lastTeleportOriginalTick;
    }

    public static void setLastTeleportOriginalTick(long tick) {
        lastTeleportOriginalTick = tick;
    }

    private static ServerLevel resolveLevel(CompanionEntity entity, ResourceKey<Level> dimension) {
        if (entity == null || entity.getServer() == null || dimension == null) {
            return null;
        }
        return entity.getServer().getLevel(dimension);
    }
}
