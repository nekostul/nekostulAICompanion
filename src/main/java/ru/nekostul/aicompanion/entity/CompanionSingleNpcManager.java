package ru.nekostul.aicompanion.entity;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.UUID;

public final class CompanionSingleNpcManager {
    private static UUID activeId;
    private static ResourceKey<Level> activeDimension;

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
    }

    static void unregister(CompanionEntity entity) {
        if (entity.level().isClientSide) {
            return;
        }
        if (activeId != null && activeId.equals(entity.getUUID())) {
            activeId = null;
            activeDimension = null;
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
        activeId = null;
        activeDimension = null;
        return null;
    }

    private static ServerLevel resolveLevel(CompanionEntity entity, ResourceKey<Level> dimension) {
        if (entity == null || entity.getServer() == null || dimension == null) {
            return null;
        }
        return entity.getServer().getLevel(dimension);
    }
}
