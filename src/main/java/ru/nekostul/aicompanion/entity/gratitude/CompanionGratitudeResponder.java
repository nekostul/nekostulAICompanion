package ru.nekostul.aicompanion.entity.gratitude;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import ru.nekostul.aicompanion.entity.CompanionEntity;

import java.util.Locale;

public final class CompanionGratitudeResponder {
    private static final int COOLDOWN_TICKS = 40;
    private static final String[] THANKS_KEYS = range("entity.aicompanion.companion.thanks.", 1, 20);
    private static final String[] THANKS_KEYWORDS = {"спасибо", "спс", "thanks"};

    private final CompanionEntity owner;
    private String lastKey;
    private long nextReplyTick = -1L;

    public CompanionGratitudeResponder(CompanionEntity owner) {
        this.owner = owner;
    }

    public boolean handle(Player player, String message, long gameTime) {
        if (player == null) {
            return false;
        }
        if (!isThanks(message)) {
            return false;
        }
        if (gameTime < nextReplyTick) {
            return true;
        }
        String key = pickRandomKeyAvoiding(THANKS_KEYS, lastKey);
        lastKey = key;
        nextReplyTick = gameTime + COOLDOWN_TICKS;
        owner.sendReply(player, Component.translatable(key));
        return true;
    }

    private boolean isThanks(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        for (String keyword : THANKS_KEYWORDS) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String[] range(String prefix, int from, int to) {
        return java.util.stream.IntStream.rangeClosed(from, to)
                .mapToObj(i -> prefix + i)
                .toArray(String[]::new);
    }

    private String pickRandomKeyAvoiding(String[] keys, String last) {
        if (keys.length == 0) {
            return "entity.aicompanion.companion.chat.default";
        }
        if (keys.length == 1) {
            return keys[0];
        }
        String key;
        do {
            key = keys[owner.getRandom().nextInt(keys.length)];
        } while (key.equals(last));
        return key;
    }
}

