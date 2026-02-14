package ru.nekostul.aicompanion.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;

final class CompanionHelpSystem {
    private static final String HELP_GENERAL_KEY = "entity.aicompanion.companion.help.general";
    private static final String HELP_CHEST_KEY = "entity.aicompanion.companion.help.chest";
    private static final String HELP_INVENTORY_KEY = "entity.aicompanion.companion.help.inventory";
    private static final String HELP_HOME_KEY = "entity.aicompanion.companion.help.home";
    private static final String HELP_WHERE_KEY = "entity.aicompanion.companion.help.where";
    private static final String HELP_MINING_KEY = "entity.aicompanion.companion.help.mining";

    boolean handleHelp(Player player, String message) {
        if (player == null || message == null) {
            return false;
        }
        String normalized = normalize(message);
        if (normalized.isEmpty()) {
            return false;
        }
        if (normalized.contains("\u043f\u043e\u043c\u043e\u0449\u044c \u0441\u0443\u043d\u0434\u0443\u043a")
                || normalized.contains("help chest")) {
            player.sendSystemMessage(Component.translatable(HELP_CHEST_KEY));
            return true;
        }
        if (normalized.contains("\u043f\u043e\u043c\u043e\u0449\u044c \u0434\u043e\u043c")
                || normalized.contains("help home")) {
            player.sendSystemMessage(Component.translatable(HELP_HOME_KEY));
            return true;
        }
        if (normalized.contains("\u043f\u043e\u043c\u043e\u0449\u044c \u0433\u0434\u0435 npc")
                || normalized.contains("help where npc")) {
            player.sendSystemMessage(Component.translatable(HELP_WHERE_KEY));
            return true;
        }
        if (normalized.contains("\u043f\u043e\u043c\u043e\u0449\u044c \u0438\u043d\u0432\u0435\u043d\u0442\u0430\u0440\u044c")
                || normalized.contains("help inventory")) {
            player.sendSystemMessage(Component.translatable(HELP_INVENTORY_KEY));
            return true;
        }
        if (normalized.contains("\u043f\u043e\u043c\u043e\u0449\u044c \u0434\u043e\u0431\u044b\u0447")
                || normalized.contains("help mining")) {
            player.sendSystemMessage(Component.translatable(HELP_MINING_KEY));
            return true;
        }
        if (normalized.equals("\u043f\u043e\u043c\u043e\u0449\u044c") || normalized.equals("help")
                || normalized.startsWith("\u043f\u043e\u043c\u043e\u0449\u044c ")
                || normalized.startsWith("help ")) {
            player.sendSystemMessage(Component.translatable(HELP_GENERAL_KEY));
            return true;
        }
        return false;
    }

    private String normalize(String message) {
        return message.trim()
                .toLowerCase(Locale.ROOT)
                .replace('\u0451', '\u0435');
    }
}
