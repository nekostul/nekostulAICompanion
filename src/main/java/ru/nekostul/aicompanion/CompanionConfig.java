package ru.nekostul.aicompanion;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CompanionConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue FULL_TREE_CHOP = BUILDER
            .comment("Full tree chop: break the whole trunk and leaves after the bottom log.")
            .define("tree.fullChop", true);

    public static final ForgeConfigSpec.BooleanValue NPC_PANEL_BUTTON = BUILDER
            .comment("Show the NPC panel button in the inventory.",
                    "If disabled, the panel can be opened with /ainpc gui.")
            .define("gui.npcPanelButton", true);

    public static final ForgeConfigSpec.IntValue MAX_BLOCKS_PER_TASK = BUILDER
            .comment("Maximum number of regular blocks per single task.",
                    "If player asks for more, amount is automatically clamped to this limit.")
            .defineInRange("limits.maxBlocksPerTask", 64, 1, 4096);

    public static final ForgeConfigSpec.IntValue MAX_TREES_PER_TASK = BUILDER
            .comment("Maximum number of full trees (treechop) per single task.",
                    "If player asks for more, amount is automatically clamped to this limit.")
            .defineInRange("limits.maxTreesPerTask", 5, 1, 256);

    public static final ForgeConfigSpec.IntValue MAX_ORES_PER_TASK = BUILDER
            .comment("Maximum number of ore items per single task.",
                    "Default value is intentionally low because ore tunneling is unstable.",
                    "Increasing this limit above 5 is VERY UNSTABLE and may cause NPC path/mining issues.")
            .defineInRange("limits.maxOresPerTask", 5, 1, 256);

    public static final ForgeConfigSpec.IntValue ORE_SCAN_RADIUS = BUILDER
            .comment("Ore scan radius in blocks.",
                    "Default value is intentionally low because long-range ore search is unstable.",
                    "Increasing this limit above 20 is VERY UNSTABLE and may cause wrong pathing/target switching.")
            .defineInRange("limits.oreScanRadius", 20, 1, 128);

    public static final ForgeConfigSpec.IntValue MAX_OCCLUDED_ORE_BLOCKS = BUILDER
            .comment("Maximum number of blocking blocks between NPC and ore target.",
                    "NPC can mine through blockers only within this depth.",
                    "Increasing this limit above 5 is VERY UNSTABLE and may cause tunneling/path issues.")
            .defineInRange("limits.maxOccludedOreBlocks", 5, 0, 64);

    public static final ForgeConfigSpec.BooleanValue OREHARVESTER_INTEGRATION = BUILDER
            .comment("Enable integration with oreharvester mod (modid: oreharvester).",
                    "If enabled and oreharvester is installed, NPC uses oreharvester for full ore mining.",
                    "If disabled or oreharvester is missing, NPC uses default mining logic.")
            .define("oreharvester_integration", true);

    public static final ForgeConfigSpec.BooleanValue YANDEX_GPT_ENABLED = BUILDER
            .comment("Enable YandexGPT chat for NPC.")
            .define("ai.yandexgpt.enabled", true);

    public static final ForgeConfigSpec.ConfigValue<String> YANDEX_GPT_API_KEY = BUILDER
            .comment("Yandex Cloud API key for YandexGPT.")
            .define("ai.yandexgpt.apiKey", "");

    public static final ForgeConfigSpec.ConfigValue<String> YANDEX_GPT_FOLDER_ID = BUILDER
            .comment("Yandex Cloud folder_id.")
            .define("ai.yandexgpt.folderId", "");

    public static final ForgeConfigSpec.ConfigValue<String> YANDEX_GPT_MODEL = BUILDER
            .comment("YandexGPT model id, for example: yandexgpt/latest or yandexgpt-lite/latest.")
            .define("ai.yandexgpt.model", "yandexgpt/latest");

    public static final ForgeConfigSpec.IntValue YANDEX_GPT_DAILY_LIMIT = BUILDER
            .comment("How many LLM requests per player are allowed per day.",
                    "0 = unlimited.")
            .defineInRange("ai.yandexgpt.dailyLimit", 15, 0, 5000);

    public static final ForgeConfigSpec.DoubleValue YANDEX_GPT_TEMPERATURE = BUILDER
            .comment("Sampling temperature for YandexGPT.")
            .defineInRange("ai.yandexgpt.temperature", 0.4D, 0.0D, 1.0D);

    public static final ForgeConfigSpec.IntValue YANDEX_GPT_MAX_TOKENS = BUILDER
            .comment("Maximum number of output tokens per answer.")
            .defineInRange("ai.yandexgpt.maxTokens", 256, 16, 2048);

    public static final ForgeConfigSpec.IntValue YANDEX_GPT_CONNECT_TIMEOUT_MS = BUILDER
            .comment("HTTP connect timeout for YandexGPT requests (ms).")
            .defineInRange("ai.yandexgpt.connectTimeoutMs", 5000, 500, 60000);

    public static final ForgeConfigSpec.IntValue YANDEX_GPT_READ_TIMEOUT_MS = BUILDER
            .comment("HTTP read timeout for YandexGPT requests (ms).")
            .defineInRange("ai.yandexgpt.readTimeoutMs", 10000, 500, 120000);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private CompanionConfig() {
    }

    public static boolean isFullTreeChopEnabled() {
        return FULL_TREE_CHOP.get();
    }

    public static boolean isNpcPanelButtonEnabled() {
        return NPC_PANEL_BUTTON.get();
    }

    public static int getMaxBlocksPerTask() {
        return MAX_BLOCKS_PER_TASK.get();
    }

    public static int getMaxTreesPerTask() {
        return MAX_TREES_PER_TASK.get();
    }

    public static int getMaxOresPerTask() {
        return MAX_ORES_PER_TASK.get();
    }

    public static int getOreScanRadius() {
        return ORE_SCAN_RADIUS.get();
    }

    public static int getMaxOccludedOreBlocks() {
        return MAX_OCCLUDED_ORE_BLOCKS.get();
    }

    public static boolean isOreHarvesterIntegrationEnabled() {
        return OREHARVESTER_INTEGRATION.get();
    }

    public static boolean isYandexGptEnabled() {
        return YANDEX_GPT_ENABLED.get();
    }

    public static String getYandexGptApiKey() {
        return YANDEX_GPT_API_KEY.get();
    }

    public static String getYandexGptFolderId() {
        return YANDEX_GPT_FOLDER_ID.get();
    }

    public static String getYandexGptModel() {
        return YANDEX_GPT_MODEL.get();
    }

    public static int getYandexGptDailyLimit() {
        return YANDEX_GPT_DAILY_LIMIT.get();
    }

    public static double getYandexGptTemperature() {
        return YANDEX_GPT_TEMPERATURE.get();
    }

    public static int getYandexGptMaxTokens() {
        return YANDEX_GPT_MAX_TOKENS.get();
    }

    public static int getYandexGptConnectTimeoutMs() {
        return YANDEX_GPT_CONNECT_TIMEOUT_MS.get();
    }

    public static int getYandexGptReadTimeoutMs() {
        return YANDEX_GPT_READ_TIMEOUT_MS.get();
    }

    public static void setFullTreeChopEnabled(boolean enabled) {
        FULL_TREE_CHOP.set(enabled);
        persistValue(enabled);
    }

    private static void persistValue(boolean enabled) {
        Path configPath = FMLPaths.CONFIGDIR.get().resolve("aicompanion-common.toml");
        List<String> lines = new ArrayList<>();
        if (Files.exists(configPath)) {
            try {
                lines.addAll(Files.readAllLines(configPath, StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                return;
            }
        }
        boolean updated = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("tree.fullChop")) {
                lines.set(i, "tree.fullChop = " + enabled);
                updated = true;
                break;
            }
        }
        if (!updated) {
            lines.add("tree.fullChop = " + enabled);
        }
        try {
            Files.createDirectories(configPath.getParent());
            Files.write(configPath, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}

