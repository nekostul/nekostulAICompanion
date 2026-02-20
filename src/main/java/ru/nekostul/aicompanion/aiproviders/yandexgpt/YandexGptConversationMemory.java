package ru.nekostul.aicompanion.aiproviders.yandexgpt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class YandexGptConversationMemory {
    static final int MAX_MESSAGES = 15;
    private static final int MAX_MESSAGE_LENGTH = 512;
    private static final Map<UUID, Deque<Message>> HISTORY = new ConcurrentHashMap<>();

    record Message(String role, String text) {
    }

    private YandexGptConversationMemory() {
    }

    static List<Message> snapshot(UUID playerId) {
        if (playerId == null) {
            return List.of();
        }
        Deque<Message> deque = HISTORY.get(playerId);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    static void appendUser(UUID playerId, String text) {
        append(playerId, "user", text);
    }

    static void appendAssistant(UUID playerId, String text) {
        append(playerId, "assistant", text);
    }

    private static void append(UUID playerId, String role, String text) {
        if (playerId == null || role == null || role.isBlank() || text == null || text.isBlank()) {
            return;
        }
        String cleaned = text.replace('\u0000', ' ').trim();
        if (cleaned.isBlank()) {
            return;
        }
        if (cleaned.length() > MAX_MESSAGE_LENGTH) {
            cleaned = cleaned.substring(0, MAX_MESSAGE_LENGTH);
        }
        Deque<Message> deque = HISTORY.computeIfAbsent(playerId, id -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(new Message(role, cleaned));
            while (deque.size() > MAX_MESSAGES) {
                deque.pollFirst();
            }
        }
    }
}
