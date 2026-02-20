package ru.nekostul.aicompanion.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.registries.Registries;

import java.util.List;
import java.util.UUID;

public final class CompanionSingleNpcManager {
    private static final double WORLD_SEARCH_BOUND = 30_000_000.0D;
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
        rememberCompanionState(entity, false);
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
        CompanionEntity tracked = resolveTrackedCompanion(player, false);
        if (tracked != null && tracked.canPlayerControl(player)) {
            return tracked;
        }
        CompanionEntity owned = findLoadedCompanion(player, false, true);
        if (owned != null) {
            rememberCompanionState(owned, false);
            return owned;
        }
        CompanionEntity controllable = findLoadedCompanion(player, false, false);
        if (controllable != null) {
            rememberCompanionState(controllable, false);
        }
        return controllable;
    }

    public static CompanionEntity getActiveIncludingDead(ServerPlayer player) {
        if (player == null || player.server == null) {
            return null;
        }
        ensureLoaded(player.server);
        CompanionEntity tracked = resolveTrackedCompanion(player, true);
        if (tracked != null && tracked.canPlayerControl(player)) {
            return tracked;
        }
        CompanionEntity owned = findLoadedCompanion(player, true, true);
        if (owned != null) {
            rememberCompanionState(owned, false);
            return owned;
        }
        CompanionEntity controllable = findLoadedCompanion(player, true, false);
        if (controllable != null) {
            rememberCompanionState(controllable, false);
        }
        return controllable;
    }

    static void updateState(CompanionEntity entity, boolean busy, long teleportCycleTick, long teleportOriginalTick) {
        if (entity == null || entity.level().isClientSide) {
            return;
        }
        rememberCompanionState(entity, busy);
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

    private static CompanionEntity resolveTrackedCompanion(ServerPlayer player, boolean includeDead) {
        if (player == null || player.server == null || activeId == null || activeDimension == null) {
            return null;
        }
        ServerLevel level = player.server.getLevel(activeDimension);
        if (level == null) {
            return null;
        }
        Entity entity = level.getEntity(activeId);
        if (entity instanceof CompanionEntity companion) {
            if (includeDead || companion.isAlive()) {
                return companion;
            }
            return null;
        }
        if (lastKnownPos != null && !level.hasChunkAt(lastKnownPos)) {
            return null;
        }
        activeId = null;
        activeDimension = null;
        lastKnownPos = null;
        return null;
    }

    private static CompanionEntity findLoadedCompanion(ServerPlayer player, boolean includeDead, boolean ownedOnly) {
        if (player == null || player.server == null) {
            return null;
        }
        CompanionEntity nearest = null;
        boolean nearestInPlayerLevel = false;
        double nearestDistanceSqr = Double.MAX_VALUE;
        for (ServerLevel level : player.server.getAllLevels()) {
            AABB searchBounds = new AABB(
                    -WORLD_SEARCH_BOUND,
                    level.getMinBuildHeight(),
                    -WORLD_SEARCH_BOUND,
                    WORLD_SEARCH_BOUND,
                    level.getMaxBuildHeight(),
                    WORLD_SEARCH_BOUND
            );
            List<CompanionEntity> companions = level.getEntitiesOfClass(
                    CompanionEntity.class,
                    searchBounds,
                    companion -> {
                        if (companion == null) {
                            return false;
                        }
                        if (!includeDead && !companion.isAlive()) {
                            return false;
                        }
                        return ownedOnly
                                ? companion.isOwnedBy(player)
                                : companion.canPlayerControl(player);
                    }
            );
            for (CompanionEntity companion : companions) {
                boolean inPlayerLevel = companion.level() == player.level();
                double distanceSqr = inPlayerLevel
                        ? player.distanceToSqr(companion.getX(), companion.getY(), companion.getZ())
                        : Double.MAX_VALUE;
                if (nearest == null
                        || (inPlayerLevel && !nearestInPlayerLevel)
                        || (inPlayerLevel == nearestInPlayerLevel && distanceSqr < nearestDistanceSqr)) {
                    nearest = companion;
                    nearestInPlayerLevel = inPlayerLevel;
                    nearestDistanceSqr = distanceSqr;
                }
            }
        }
        return nearest;
    }

    private static void rememberCompanionState(CompanionEntity entity, boolean busy) {
        if (entity == null || entity.level().isClientSide) {
            return;
        }
        activeId = entity.getUUID();
        activeDimension = entity.level().dimension();
        lastKnownPos = entity.blockPosition();
        updateHomeState(entity);
        lastMode = entity.getMode();
        lastBusy = busy;
        updatePersistedState(entity);
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
