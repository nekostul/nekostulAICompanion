package ru.nekostul.aicompanion.entity;

import java.util.UUID;

final class CompanionResourceRequest {
    private final UUID playerId;
    private final CompanionResourceType resourceType;
    private final int amount;
    private final CompanionTreeRequestMode treeMode;

    CompanionResourceRequest(UUID playerId, CompanionResourceType resourceType, int amount,
                             CompanionTreeRequestMode treeMode) {
        this.playerId = playerId;
        this.resourceType = resourceType;
        this.amount = amount;
        this.treeMode = treeMode == null ? CompanionTreeRequestMode.NONE : treeMode;
    }

    UUID getPlayerId() {
        return playerId;
    }

    CompanionResourceType getResourceType() {
        return resourceType;
    }

    int getAmount() {
        return amount;
    }

    CompanionTreeRequestMode getTreeMode() {
        return treeMode;
    }

    boolean isTreeRequest() {
        return treeMode != CompanionTreeRequestMode.NONE;
    }

    boolean isTreeCountRequest() {
        return treeMode == CompanionTreeRequestMode.TREE_COUNT;
    }
}
