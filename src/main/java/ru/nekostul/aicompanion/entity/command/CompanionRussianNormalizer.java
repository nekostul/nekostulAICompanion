package ru.nekostul.aicompanion.entity.command;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CompanionRussianNormalizer {
    private static final Pattern NUMBERED_WORD = Pattern.compile("(\\d+)\\s+([а-яё]+)");

    private CompanionRussianNormalizer() {
    }

    static String normalize(String message) {
        if (message == null) {
            return "";
        }
        String normalized = message.trim()
                .toLowerCase(Locale.ROOT)
                .replace('\u0451', '\u0435');
        return normalizeNumberedWords(normalized);
    }

    private static String normalizeNumberedWords(String normalized) {
        Matcher matcher = NUMBERED_WORD.matcher(normalized);
        StringBuilder builder = new StringBuilder(normalized);
        int offset = 0;
        while (matcher.find()) {
            String word = matcher.group(2);
            String stem = stemWord(word);
            if (stem.equals(word)) {
                continue;
            }
            int insertPos = matcher.end(2) + offset;
            builder.insert(insertPos, " " + stem);
            offset += stem.length() + 1;
        }
        return builder.toString();
    }

    private static String stemWord(String word) {
        if (word.length() <= 3) {
            return word;
        }
        if (word.endsWith("ень")) {
            return word.substring(0, word.length() - 1);
        }
        if (word.endsWith("ья")) {
            return word.substring(0, word.length() - 1);
        }
        String trimmed = trimEnding(word, "ей", "ов", "ев", "ам", "ям", "ах", "ях", "ым", "им", "ом", "ем");
        if (!trimmed.equals(word)) {
            return trimmed;
        }
        return trimEnding(word, "а", "я", "ы", "и", "е", "ь");
    }

    private static String trimEnding(String word, String... endings) {
        for (String ending : endings) {
            if (word.endsWith(ending) && word.length() > ending.length() + 2) {
                return word.substring(0, word.length() - ending.length());
            }
        }
        return word;
    }
}

