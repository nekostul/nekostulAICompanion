package ru.nekostul.aicompanion.aiproviders.yandexgpt;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class YandexGptDailyUsageTracker {
    private static final class Usage {
        private int count;
        private LocalDate day;

        private Usage() {
            this.count = 0;
            this.day = LocalDate.now();
        }
    }

    private static final Map<UUID, Usage> USAGE = new ConcurrentHashMap<>();

    private YandexGptDailyUsageTracker() {
    }

    static boolean canUse(UUID playerId, int dailyLimit) {
        if (playerId == null || dailyLimit <= 0) {
            return true;
        }
        Usage usage = USAGE.computeIfAbsent(playerId, ignored -> new Usage());
        synchronized (usage) {
            rotateIfNeeded(usage);
            return usage.count < dailyLimit;
        }
    }

    static void markUsed(UUID playerId) {
        if (playerId == null) {
            return;
        }
        Usage usage = USAGE.computeIfAbsent(playerId, ignored -> new Usage());
        synchronized (usage) {
            rotateIfNeeded(usage);
            usage.count++;
        }
    }

    static int remaining(UUID playerId, int dailyLimit) {
        if (playerId == null || dailyLimit <= 0) {
            return Integer.MAX_VALUE;
        }
        Usage usage = USAGE.computeIfAbsent(playerId, ignored -> new Usage());
        synchronized (usage) {
            rotateIfNeeded(usage);
            return Math.max(0, dailyLimit - usage.count);
        }
    }

    private static void rotateIfNeeded(Usage usage) {
        LocalDate today = LocalDate.now();
        if (!today.equals(usage.day)) {
            usage.day = today;
            usage.count = 0;
        }
    }
}
