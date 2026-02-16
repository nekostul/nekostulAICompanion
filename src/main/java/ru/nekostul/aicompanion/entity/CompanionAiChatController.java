package ru.nekostul.aicompanion.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import ru.nekostul.aicompanion.aiproviders.yandexgpt.YandexGptClient;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class CompanionAiChatController {
    private static final String AI_EMPTY_PROMPT_KEY = "entity.aicompanion.companion.ai.empty_prompt";
    private static final String AI_WAIT_KEY = "entity.aicompanion.companion.ai.wait";
    private static final String AI_DISABLED_KEY = "entity.aicompanion.companion.ai.disabled";
    private static final String AI_NOT_CONFIGURED_KEY = "entity.aicompanion.companion.ai.not_configured";
    private static final String AI_DAILY_LIMIT_KEY = "entity.aicompanion.companion.ai.daily_limit";
    private static final String AI_FAILED_KEY = "entity.aicompanion.companion.ai.failed";

    private final CompanionEntity owner;
    private final Set<UUID> inFlightByPlayer = ConcurrentHashMap.newKeySet();

    CompanionAiChatController(CompanionEntity owner) {
        this.owner = owner;
    }

    boolean handleMessage(ServerPlayer player, String message) {
        if (player == null || message == null) {
            return false;
        }
        String prompt = extractPrompt(message);
        if (prompt == null) {
            return false;
        }
        if (prompt.isBlank()) {
            owner.sendReply(player, Component.translatable(AI_EMPTY_PROMPT_KEY));
            return true;
        }
        UUID playerId = player.getUUID();
        if (!inFlightByPlayer.add(playerId)) {
            owner.sendReply(player, Component.translatable(AI_WAIT_KEY));
            return true;
        }
        CompletableFuture
                .supplyAsync(() -> YandexGptClient.ask(player, prompt))
                .thenAccept(result -> completeOnServerThread(playerId, result))
                .exceptionally(error -> {
                    completeOnServerThread(playerId, null);
                    return null;
                });
        return true;
    }

    private void completeOnServerThread(UUID playerId, YandexGptClient.Result result) {
        if (owner.getServer() == null) {
            inFlightByPlayer.remove(playerId);
            return;
        }
        owner.getServer().execute(() -> {
            try {
                Player player = owner.getPlayerById(playerId);
                if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.isSpectator()) {
                    return;
                }
                if (result == null) {
                    owner.sendReply(serverPlayer, Component.translatable(AI_FAILED_KEY));
                    return;
                }
                switch (result.status()) {
                    case SUCCESS -> owner.sendReply(serverPlayer, Component.literal(result.text()));
                    case DISABLED -> owner.sendReply(serverPlayer, Component.translatable(AI_DISABLED_KEY));
                    case NOT_CONFIGURED -> owner.sendReply(serverPlayer, Component.translatable(AI_NOT_CONFIGURED_KEY));
                    case DAILY_LIMIT -> owner.sendReply(serverPlayer,
                            Component.translatable(AI_DAILY_LIMIT_KEY, result.remainingLimit()));
                    case ERROR -> owner.sendReply(serverPlayer, Component.translatable(AI_FAILED_KEY));
                }
            } finally {
                inFlightByPlayer.remove(playerId);
            }
        });
    }

    private String extractPrompt(String rawMessage) {
        if (rawMessage == null) {
            return null;
        }
        String trimmed = rawMessage.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String normalized = normalize(trimmed);
        if (normalized.equals("ии") || normalized.equals("ai") || normalized.equals("gpt")) {
            return "";
        }
        if (normalized.startsWith("ии ")
                || normalized.startsWith("ai ")
                || normalized.startsWith("gpt ")) {
            int firstSpace = trimmed.indexOf(' ');
            if (firstSpace < 0 || firstSpace + 1 >= trimmed.length()) {
                return "";
            }
            return trimmed.substring(firstSpace + 1).trim();
        }
        return null;
    }

    private String normalize(String message) {
        return message.trim()
                .toLowerCase(Locale.ROOT)
                .replace('\u0451', '\u0435');
    }
}
