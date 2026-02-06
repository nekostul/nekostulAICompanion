package ru.nekostul.aicompanion.entity;

import net.minecraft.world.entity.item.ItemEntity;

import java.util.UUID;

final class CompanionDropTracker {
    private static final String DROPPER_TAG = "aicompanionDropper";

    private CompanionDropTracker() {
    }

    static void markDropped(ItemEntity entity, UUID dropperId) {
        if (entity == null || dropperId == null) {
            return;
        }
        entity.getPersistentData().putUUID(DROPPER_TAG, dropperId);
    }

    static boolean isDroppedBy(ItemEntity entity, UUID dropperId) {
        if (entity == null || dropperId == null) {
            return false;
        }
        if (!entity.getPersistentData().hasUUID(DROPPER_TAG)) {
            return false;
        }
        return dropperId.equals(entity.getPersistentData().getUUID(DROPPER_TAG));
    }
}
