package ru.nekostul.aicompanion.entity.resource;

import java.util.UUID;

import ru.nekostul.aicompanion.entity.tree.CompanionTreeRequestMode;

public final class CompanionResourceRequest {
    private final UUID playerId;
    private final CompanionResourceType resourceType;
    private final int amount;
    private final CompanionTreeRequestMode treeMode;

    public CompanionResourceRequest(UUID playerId, CompanionResourceType resourceType, int amount,
                                    CompanionTreeRequestMode treeMode) {
        this.playerId = playerId;
        this.resourceType = resourceType;
        this.amount = amount;
        this.treeMode = treeMode == null ? CompanionTreeRequestMode.NONE : treeMode;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public CompanionResourceType getResourceType() {
        return resourceType;
    }

    public int getAmount() {
        return amount;
    }

    public CompanionTreeRequestMode getTreeMode() {
        return treeMode;
    }

    public boolean isTreeRequest() {
        return treeMode != CompanionTreeRequestMode.NONE;
    }

    public boolean isTreeCountRequest() {
        return treeMode == CompanionTreeRequestMode.TREE_COUNT;
    }
}
