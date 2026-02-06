package ru.nekostul.aicompanion.entity;

import java.util.UUID;

final class CompanionResourceRequest {
    private final UUID playerId;
    private final CompanionResourceType resourceType;
    private final int amount;

    CompanionResourceRequest(UUID playerId, CompanionResourceType resourceType, int amount) {
        this.playerId = playerId;
        this.resourceType = resourceType;
        this.amount = amount;
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
}
