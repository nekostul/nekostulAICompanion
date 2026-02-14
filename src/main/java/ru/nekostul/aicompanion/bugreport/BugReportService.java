package ru.nekostul.aicompanion.bugreport;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public final class BugReportService {
    private static final String BUG_REPORT_URL =
            "https://nekostulai-bug-report.n3kostul.workers.dev/";
    private static final Gson GSON = new Gson();

    private static final String COOLDOWN_KEY = "entity.aicompanion.bugreport.cooldown";
    private static final String SENDING_KEY = "entity.aicompanion.bugreport.sending";
    private static final String SENT_KEY = "entity.aicompanion.bugreport.sent";
    private static final String FAILED_KEY = "entity.aicompanion.bugreport.failed";

    private BugReportService() {
    }

    public static void sendAsync(ServerPlayer player, String message) {
        if (!BugReportCooldown.canSend(player)) {
            long sec = BugReportCooldown.getRemainingSeconds(player);
            long min = sec / 60;
            long s = sec % 60;
            player.sendSystemMessage(Component.translatable(COOLDOWN_KEY, min, s));
            return;
        }

        player.sendSystemMessage(Component.translatable(SENDING_KEY));
        MinecraftServer server = player.getServer();
        if (server == null) {
            player.sendSystemMessage(Component.translatable(FAILED_KEY));
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                send(player, message);
                server.execute(() -> {
                    BugReportCooldown.markSent(player);
                    player.sendSystemMessage(Component.translatable(SENT_KEY));
                });
            } catch (Exception e) {
                server.execute(() -> player.sendSystemMessage(Component.translatable(FAILED_KEY)));
                e.printStackTrace();
            }
        });
    }

    private static void send(ServerPlayer player, String message) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("player", player.getName().getString());
        payload.addProperty("version", player.getServer().getServerVersion());
        payload.addProperty("mods", getInstalledModsShort());
        payload.addProperty("message", message);

        HttpURLConnection con = (HttpURLConnection)
                new URL(BUG_REPORT_URL).openConnection();

        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setDoOutput(true);

        try (OutputStream os = con.getOutputStream()) {
            os.write(GSON.toJson(payload).getBytes(StandardCharsets.UTF_8));
        }

        if (con.getResponseCode() != 200) {
            throw new RuntimeException("HTTP " + con.getResponseCode());
        }
    }

    private static String getInstalledModsShort() {
        return ModList.get().getMods().stream()
                .limit(20)
                .map(mod -> mod.getModId())
                .reduce((a, b) -> a + ", " + b)
                .orElse("unknown");
    }
}
