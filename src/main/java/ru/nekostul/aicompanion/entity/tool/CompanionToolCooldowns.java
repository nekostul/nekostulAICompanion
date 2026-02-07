package ru.nekostul.aicompanion.entity.tool;

import java.util.EnumMap;

final class CompanionToolCooldowns {
    private static final int COOLDOWN_TICKS = 20 * 60 * 2;

    private final EnumMap<CompanionToolHandler.ToolType, Long> nextTicks =
            new EnumMap<>(CompanionToolHandler.ToolType.class);

    boolean canNotify(CompanionToolHandler.ToolType tool, long gameTime) {
        Long next = nextTicks.get(tool);
        return next == null || gameTime >= next;
    }

    void markNotified(CompanionToolHandler.ToolType tool, long gameTime) {
        nextTicks.put(tool, gameTime + COOLDOWN_TICKS);
    }
}
