package ru.nekostul.aicompanion.entity.inventory;

import net.minecraft.world.entity.item.ItemEntity;

import java.util.UUID;

public final class CompanionDropTracker {
    private static final String DROPPER_TAG = "aicompanionDropper";
    private static final String MOB_DROP_TAG = "aicompanionMobDrop";
    private static final String MOB_DROPPER_TAG = "aicompanionMobDropper";
    private static final String PLAYER_DROP_TAG = "aicompanionPlayerDrop";
    private static final String PLAYER_DROPPER_TAG = "aicompanionPlayerDropper";

    private CompanionDropTracker() {
    }

    public static void markDropped(ItemEntity entity, UUID dropperId) {
        if (entity == null || dropperId == null) {
            return;
        }
        entity.getPersistentData().putUUID(DROPPER_TAG, dropperId);
    }

    public static boolean isDroppedBy(ItemEntity entity, UUID dropperId) {
        if (entity == null || dropperId == null) {
            return false;
        }
        if (!entity.getPersistentData().hasUUID(DROPPER_TAG)) {
            return false;
        }
        return dropperId.equals(entity.getPersistentData().getUUID(DROPPER_TAG));
    }

    public static void markMobDrop(ItemEntity entity, UUID dropperId) {
        if (entity == null) {
            return;
        }
        entity.getPersistentData().putBoolean(MOB_DROP_TAG, true);
        if (dropperId != null) {
            entity.getPersistentData().putUUID(MOB_DROPPER_TAG, dropperId);
        }
    }

    public static boolean isMobDrop(ItemEntity entity) {
        if (entity == null) {
            return false;
        }
        return entity.getPersistentData().getBoolean(MOB_DROP_TAG);
    }

    public static boolean isMobDropFrom(ItemEntity entity, UUID dropperId) {
        if (entity == null || dropperId == null) {
            return false;
        }
        if (!isMobDrop(entity)) {
            return false;
        }
        if (!entity.getPersistentData().hasUUID(MOB_DROPPER_TAG)) {
            return false;
        }
        return dropperId.equals(entity.getPersistentData().getUUID(MOB_DROPPER_TAG));
    }

    public static void markPlayerDrop(ItemEntity entity, UUID dropperId) {
        if (entity == null) {
            return;
        }
        entity.getPersistentData().putBoolean(PLAYER_DROP_TAG, true);
        if (dropperId != null) {
            entity.getPersistentData().putUUID(PLAYER_DROPPER_TAG, dropperId);
        }
    }

    public static UUID getPlayerDropper(ItemEntity entity) {
        if (entity == null) {
            return null;
        }
        if (!entity.getPersistentData().getBoolean(PLAYER_DROP_TAG)) {
            return null;
        }
        if (!entity.getPersistentData().hasUUID(PLAYER_DROPPER_TAG)) {
            return null;
        }
        return entity.getPersistentData().getUUID(PLAYER_DROPPER_TAG);
    }
}

