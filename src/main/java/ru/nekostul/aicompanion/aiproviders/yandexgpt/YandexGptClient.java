package ru.nekostul.aicompanion.aiproviders.yandexgpt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import ru.nekostul.aicompanion.CompanionConfig;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class YandexGptClient {
    public enum Status {
        SUCCESS,
        DISABLED,
        NOT_CONFIGURED,
        DAILY_LIMIT,
        ERROR
    }

    public static final class Result {
        private final Status status;
        private final String text;
        private final int remainingLimit;

        private Result(Status status, String text, int remainingLimit) {
            this.status = status;
            this.text = text;
            this.remainingLimit = remainingLimit;
        }

        public Status status() {
            return status;
        }

        public String text() {
            return text;
        }

        public int remainingLimit() {
            return remainingLimit;
        }
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ENDPOINT =
            "https://llm.api.cloud.yandex.net/foundationModels/v1/completion";
    private static final int MAX_REPLY_LENGTH = 700;

    private YandexGptClient() {
    }

    public static Result ask(ServerPlayer player, String playerMessage) {
        if (player == null || !isNotBlank(playerMessage)) {
            return error();
        }
        return requestWithPrompts(
                player,
                YandexGptPrompts.system(),
                YandexGptPrompts.userPrompt(player.getName().getString(), playerMessage)
        );
    }

    public static Result interpretCommand(ServerPlayer player, String playerMessage) {
        if (player == null || !isNotBlank(playerMessage)) {
            return error();
        }
        return requestWithPrompts(
                player,
                YandexGptPrompts.commandSystemPrompt(),
                YandexGptPrompts.commandUserPrompt(player.getName().getString(), playerMessage)
        );
    }

    public static Result reviewHomeAssessment(ServerPlayer player, String assessmentPayload) {
        if (player == null || !isNotBlank(assessmentPayload)) {
            return error();
        }
        return requestWithPrompts(
                player,
                YandexGptPrompts.homeReviewSystemPrompt(),
                YandexGptPrompts.homeReviewUserPrompt(player.getName().getString(), assessmentPayload)
        );
    }

    private static Result requestWithPrompts(ServerPlayer player, String systemPrompt, String userPrompt) {
        if (player == null || !isNotBlank(systemPrompt) || !isNotBlank(userPrompt)) {
            return error();
        }
        if (!CompanionConfig.isYandexGptEnabled()) {
            return new Result(Status.DISABLED, "", 0);
        }

        String apiKey = CompanionConfig.getYandexGptApiKey();
        String folderId = CompanionConfig.getYandexGptFolderId();
        String model = CompanionConfig.getYandexGptModel();
        if (!isNotBlank(apiKey) || !isNotBlank(folderId) || !isNotBlank(model)) {
            return new Result(Status.NOT_CONFIGURED, "", 0);
        }

        UUID playerId = player.getUUID();
        int dailyLimit = CompanionConfig.getYandexGptDailyLimit();
        if (!YandexGptDailyUsageTracker.canUse(playerId, dailyLimit)) {
            int remaining = YandexGptDailyUsageTracker.remaining(playerId, dailyLimit);
            return new Result(Status.DAILY_LIMIT, "", remaining);
        }

        try {
            String answer = requestCompletion(apiKey, folderId, model, systemPrompt, userPrompt);
            if (!isNotBlank(answer)) {
                return error();
            }
            YandexGptDailyUsageTracker.markUsed(playerId);
            return new Result(Status.SUCCESS,
                    sanitizeReply(answer),
                    YandexGptDailyUsageTracker.remaining(playerId, dailyLimit));
        } catch (Exception exception) {
            LOGGER.debug("yandexgpt request failed: player={} error={}", playerId, exception.toString());
            return error();
        }
    }

    private static String requestCompletion(String apiKey,
                                            String folderId,
                                            String model,
                                            String systemPrompt,
                                            String userPrompt) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(CompanionConfig.getYandexGptConnectTimeoutMs());
        connection.setReadTimeout(CompanionConfig.getYandexGptReadTimeoutMs());
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Api-Key " + apiKey);

        JsonObject body = new JsonObject();
        body.addProperty("modelUri", "gpt://" + folderId + "/" + model);

        JsonObject options = new JsonObject();
        options.addProperty("temperature", CompanionConfig.getYandexGptTemperature());
        options.addProperty("maxTokens", CompanionConfig.getYandexGptMaxTokens());
        body.add("completionOptions", options);

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("text", systemPrompt);
        messages.add(system);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("text", userPrompt);
        messages.add(user);
        body.add("messages", messages);

        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = connection.getResponseCode();
        InputStream stream = responseCode >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        if (stream == null) {
            return null;
        }
        String responseText;
        try (InputStream input = stream) {
            responseText = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (responseCode >= 400) {
            LOGGER.debug("yandexgpt http error: code={} body={}", responseCode, responseText);
            return null;
        }

        JsonObject root = JsonParser.parseString(responseText).getAsJsonObject();
        JsonObject result = root.getAsJsonObject("result");
        if (result == null) {
            return null;
        }
        JsonArray alternatives = result.getAsJsonArray("alternatives");
        if (alternatives == null || alternatives.isEmpty()) {
            return null;
        }
        JsonObject first = alternatives.get(0).getAsJsonObject();
        JsonObject message = first.getAsJsonObject("message");
        if (message == null || !message.has("text")) {
            return null;
        }
        return message.get("text").getAsString();
    }

    private static Result error() {
        return new Result(Status.ERROR, "", 0);
    }

    private static String sanitizeReply(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s{2,}", " ")
                .trim();
        if (cleaned.length() <= MAX_REPLY_LENGTH) {
            return cleaned;
        }
        return cleaned.substring(0, MAX_REPLY_LENGTH).trim() + "...";
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
