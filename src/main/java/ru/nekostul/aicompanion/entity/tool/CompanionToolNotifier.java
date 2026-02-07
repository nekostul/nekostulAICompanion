package ru.nekostul.aicompanion.entity.tool;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import ru.nekostul.aicompanion.entity.CompanionEntity;

final class CompanionToolNotifier {
    private static final String TOOL_AXE_KEY = "entity.aicompanion.companion.tool.suggest.axe";
    private static final String TOOL_SHOVEL_KEY = "entity.aicompanion.companion.tool.suggest.shovel";
    private static final String TOOL_PICKAXE_KEY = "entity.aicompanion.companion.tool.suggest.pickaxe";
    private static final String TOOL_REQUIRE_PICKAXE_KEY = "entity.aicompanion.companion.tool.require.pickaxe";
    private final CompanionEntity owner;
    private final CompanionToolCooldowns cooldowns = new CompanionToolCooldowns();

    CompanionToolNotifier(CompanionEntity owner) {
        this.owner = owner;
    }

    void notifyMissingTool(Player player,
                           CompanionToolHandler.ToolType tool,
                           BlockPos target,
                           long gameTime) {
        if (tool == null || target == null) {
            return;
        }
        if (tool == CompanionToolHandler.ToolType.PICKAXE) {
            return;
        }
        Player resolved = player;
        if (resolved == null || resolved.isSpectator()) {
            resolved = owner.level().getNearestPlayer(owner, 24.0D);
        }
        if (resolved == null || resolved.isSpectator()) {
            return;
        }
        if (!cooldowns.canNotify(tool, gameTime)) {
            return;
        }
        cooldowns.markNotified(tool, gameTime);
        owner.sendReply(resolved, Component.translatable(toolKey(tool)));
    }

    void notifyRequiredTool(Player player,
                            CompanionToolHandler.ToolType tool,
                            BlockPos target,
                            long gameTime) {
        if (tool == null || target == null) {
            return;
        }
        Player resolved = player;
        if (resolved == null || resolved.isSpectator()) {
            resolved = owner.level().getNearestPlayer(owner, 24.0D);
        }
        if (resolved == null || resolved.isSpectator()) {
            return;
        }
        if (!cooldowns.canNotify(tool, gameTime)) {
            return;
        }
        cooldowns.markNotified(tool, gameTime);
        owner.sendReply(resolved, Component.translatable(requiredKey(tool)));
    }

    private String toolKey(CompanionToolHandler.ToolType tool) {
        return switch (tool) {
            case AXE -> TOOL_AXE_KEY;
            case SHOVEL -> TOOL_SHOVEL_KEY;
            case PICKAXE -> TOOL_PICKAXE_KEY;
        };
    }

    private String requiredKey(CompanionToolHandler.ToolType tool) {
        if (tool == CompanionToolHandler.ToolType.PICKAXE) {
            return TOOL_REQUIRE_PICKAXE_KEY;
        }
        return toolKey(tool);
    }
}
