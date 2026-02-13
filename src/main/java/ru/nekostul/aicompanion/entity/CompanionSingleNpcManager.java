package ru.nekostul.aicompanion.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.core.registries.Registries;

import java.util.UUID;

public final class CompanionSingleNpcManager {
    private static UUID activeId;
    private static ResourceKey<Level> activeDimension;
    private static BlockPos lastKnownPos;
    private static BlockPos lastHomePos;
    private static ResourceKey<Level> lastHomeDimension;
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
        updateHomeState(entity);
        lastMode = entity.getMode();
        lastBusy = false;
        updatePersistedState(entity);
    }

    static void unregister(CompanionEntity entity) {
        if (entity.level().isClientSide) {
            return;
        }
        if (activeId != null && activeId.equals(entity.getUUID())) {
            if (entity.isAlive()) {
                activeDimension = entity.level().dimension();
                lastKnownPos = entity.blockPosition();
                updateHomeState(entity);
                lastMode = entity.getMode();
                updatePersistedState(entity);
            } else {
                activeId = null;
                activeDimension = null;
                lastKnownPos = null;
                lastHomePos = null;
                lastHomeDimension = null;
                clearPersistedState(entity);
            }
        }
    }

    public static CompanionEntity getActive(ServerPlayer player) {
        if (player == null || player.server == null) {
            return null;
        }
        ensureLoaded(player.server);
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
        updateHomeState(entity);
        lastMode = entity.getMode();
        lastBusy = busy;
        lastTeleportCycleTick = teleportCycleTick;
        lastTeleportOriginalTick = teleportOriginalTick;
        updatePersistedState(entity);
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

    public static BlockPos getLastHomePos() {
        return lastHomePos;
    }

    public static ResourceKey<Level> getLastHomeDimension() {
        return lastHomeDimension;
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

    private static void updateHomeState(CompanionEntity entity) {
        if (entity == null) {
            return;
        }
        BlockPos homePos = entity.getHomePos();
        if (homePos == null) {
            lastHomePos = null;
            lastHomeDimension = null;
            return;
        }
        lastHomePos = homePos;
        if (entity.getHomeDimensionId() != null) {
            lastHomeDimension = ResourceKey.create(Registries.DIMENSION, entity.getHomeDimensionId());
        }
    }

    private static void updatePersistedState(CompanionEntity entity) {
        if (entity == null || entity.getServer() == null) {
            return;
        }
        CompanionMemoryData data = CompanionMemoryData.get(entity.getServer());
        if (data == null) {
            return;
        }
        data.setActive(activeId, activeDimension, lastKnownPos);
        data.setHome(lastHomePos, lastHomeDimension);
        data.setDirty();
    }

    private static void clearPersistedState(CompanionEntity entity) {
        if (entity == null || entity.getServer() == null) {
            return;
        }
        CompanionMemoryData data = CompanionMemoryData.get(entity.getServer());
        if (data == null) {
            return;
        }
        data.setActive(null, null, null);
        data.setHome(null, null);
        data.setDirty();
    }

    public static void ensureLoaded(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (activeId != null || activeDimension != null || lastKnownPos != null || lastHomePos != null) {
            return;
        }
        CompanionMemoryData data = CompanionMemoryData.get(server);
        if (data == null) {
            return;
        }
        activeId = data.getActiveId();
        activeDimension = data.getActiveDimension();
        lastKnownPos = data.getLastKnownPos();
        lastHomePos = data.getHomePos();
        lastHomeDimension = data.getHomeDimension();
    }
}
