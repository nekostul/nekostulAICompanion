package ru.nekostul.aicompanion.entity;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CompanionCommandParser {
    static final class CommandRequest {
        private final CompanionResourceType resourceType;
        private final int amount;

        CommandRequest(CompanionResourceType resourceType, int amount) {
            this.resourceType = resourceType;
            this.amount = amount;
        }

        CompanionResourceType getResourceType() {
            return resourceType;
        }

        int getAmount() {
            return amount;
        }
    }

    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");
    private static final int DEFAULT_BLOCK_AMOUNT = 16;
    private static final int SMALL_BLOCK_AMOUNT = 8;
    private static final int DEFAULT_BUCKET_AMOUNT = 1;

    CommandRequest parse(String message) {
        String normalized = normalize(message);
        if (normalized.isEmpty()) {
            return null;
        }
        if (!containsActionKeyword(normalized)) {
            return null;
        }
        CompanionResourceType resourceType = parseResourceType(normalized);
        if (resourceType == null) {
            return null;
        }
        int amount = parseAmount(normalized, resourceType);
        if (amount <= 0) {
            return null;
        }
        return new CommandRequest(resourceType, amount);
    }

    private boolean containsActionKeyword(String normalized) {
        return normalized.contains("\u0434\u043e\u0431\u0443\u0434")
                || normalized.contains("\u0434\u043e\u0431\u044b")
                || normalized.contains("\u043f\u0440\u0438\u043d\u0435\u0441")
                || normalized.contains("\u0441\u043e\u0431\u0435\u0440")
                || normalized.contains("\u0434\u043e\u0441\u0442\u0430\u043d")
                || normalized.contains("\u0434\u043e\u0441\u0442\u0430\u0442")
                || normalized.contains("bring")
                || normalized.contains("get");
    }

    private CompanionResourceType parseResourceType(String normalized) {
        if (normalized.contains("\u0432\u043e\u0434") || normalized.contains("water")) {
            return CompanionResourceType.WATER;
        }
        if (normalized.contains("\u043b\u0430\u0432") || normalized.contains("lava")) {
            return CompanionResourceType.LAVA;
        }
        if (normalized.contains("\u043f\u0435\u0441\u043e\u043a")) {
            return CompanionResourceType.SAND;
        }
        if (normalized.contains("\u0433\u0440\u0430\u0432")) {
            return CompanionResourceType.GRAVEL;
        }
        if (normalized.contains("\u043a\u0430\u043c\u043d")) {
            return CompanionResourceType.STONE;
        }
        if (normalized.contains("\u0437\u0435\u043c\u043b") || normalized.contains("\u0433\u0440\u044f\u0437")) {
            return CompanionResourceType.DIRT;
        }
        if (normalized.contains("\u0434\u0435\u0440\u0435\u0432")
                || normalized.contains("\u0431\u0440\u0435\u0432")
                || normalized.contains("\u0434\u0440\u0435\u0432\u0435\u0441")) {
            return CompanionResourceType.LOG;
        }
        return null;
    }

    private int parseAmount(String normalized, CompanionResourceType resourceType) {
        Matcher matcher = NUMBER_PATTERN.matcher(normalized);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return defaultAmount(normalized, resourceType);
            }
        }
        return defaultAmount(normalized, resourceType);
    }

    private int defaultAmount(String normalized, CompanionResourceType resourceType) {
        if (resourceType.isBucketResource()) {
            return DEFAULT_BUCKET_AMOUNT;
        }
        if (normalized.contains("\u043d\u0435\u043c\u043d\u043e\u0433\u043e")
                || normalized.contains("\u043d\u0435\u043c\u043d\u043e\u0436\u043a\u043e")
                || normalized.contains("\u043f\u0430\u0440\u0443")
                || normalized.contains("\u043d\u0435\u0441\u043a\u043e\u043b\u044c\u043a")) {
            return SMALL_BLOCK_AMOUNT;
        }
        return DEFAULT_BLOCK_AMOUNT;
    }

    private String normalize(String message) {
        if (message == null) {
            return "";
        }
        return message.trim()
                .toLowerCase(Locale.ROOT)
                .replace('\u0451', '\u0435');
    }
}
