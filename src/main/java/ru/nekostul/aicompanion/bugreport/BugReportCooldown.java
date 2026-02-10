package ru.nekostul.aicompanion.bugreport;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BugReportCooldown {
    private static final long DEFAULT_COOLDOWN_SECONDS = 15 * 60;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, Long> LAST_SENT = new HashMap<>();

    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve("aicompanion");
    private static final Path COOLDOWN_CONFIG = CONFIG_DIR.resolve("bugreport_cooldown.json");
    private static final Path COOLDOWN_DB = CONFIG_DIR.resolve("bugreport_cooldown_db.json");

    private static long cooldownSeconds = DEFAULT_COOLDOWN_SECONDS;
    private static boolean loaded;

    private BugReportCooldown() {
    }

    public static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        loadCooldownConfig();
        loadCooldownDb();
    }

    public static boolean canSend(ServerPlayer player) {
        load();
        UUID id = player.getUUID();
        long now = System.currentTimeMillis();
        long cooldownMs = cooldownSeconds * 1000L;

        return !LAST_SENT.containsKey(id)
                || now - LAST_SENT.get(id) >= cooldownMs;
    }

    public static long getRemainingSeconds(ServerPlayer player) {
        load();
        Long last = LAST_SENT.get(player.getUUID());
        if (last == null) {
            return 0;
        }
        long cooldownMs = cooldownSeconds * 1000L;
        long diff = cooldownMs - (System.currentTimeMillis() - last);
        return Math.max(0, diff / 1000);
    }

    public static void markSent(ServerPlayer player) {
        load();
        LAST_SENT.put(player.getUUID(), System.currentTimeMillis());
        saveCooldownDb();
    }

    private static void loadCooldownConfig() {
        if (!Files.exists(COOLDOWN_CONFIG)) {
            saveCooldownConfig();
            return;
        }
        try {
            JsonObject obj = readJsonFile(COOLDOWN_CONFIG);
            if (obj == null) {
                return;
            }
            if (obj.has("cooldownSeconds")) {
                long seconds = obj.get("cooldownSeconds").getAsLong();
                if (seconds > 0) {
                    cooldownSeconds = seconds;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void saveCooldownConfig() {
        try {
            Files.createDirectories(CONFIG_DIR);
            JsonObject obj = new JsonObject();
            obj.addProperty("cooldownSeconds", DEFAULT_COOLDOWN_SECONDS);
            Files.writeString(COOLDOWN_CONFIG, GSON.toJson(obj),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void loadCooldownDb() {
        try {
            if (!Files.exists(COOLDOWN_DB)) {
                return;
            }
            JsonObject obj = readJsonFile(COOLDOWN_DB);
            if (obj == null) {
                return;
            }
            for (String key : obj.keySet()) {
                UUID uuid = UUID.fromString(key);
                long time = obj.get(key).getAsLong();
                LAST_SENT.put(uuid, time);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void saveCooldownDb() {
        try {
            Files.createDirectories(CONFIG_DIR);
            JsonObject obj = new JsonObject();
            for (var entry : LAST_SENT.entrySet()) {
                obj.addProperty(entry.getKey().toString(), entry.getValue());
            }
            Files.writeString(COOLDOWN_DB, GSON.toJson(obj),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static JsonObject readJsonFile(Path path) {
        try {
            String json = Files.readString(path);
            json = json
                    .replace("```json", "")
                    .replace("```", "")
                    .trim();
            if (json.isEmpty()) {
                return null;
            }
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
