package ru.nekostul.aicompanion.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import ru.nekostul.aicompanion.AiCompanionMod;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mod.EventBusSubscriber(modid = AiCompanionMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VersionUpdateChecker {
    private static final String MODRINTH_VERSIONS_URL = "https://api.modrinth.com/v2/project/nekostulAICompanion/version";
    private static final String MODRINTH_VERSION_PAGE_URL_TEMPLATE = "https://modrinth.com/mod/nekostulAICompanion/version/%s";
    private static final String GITHUB_LATEST_RELEASE_URL =
            "https://api.github.com/repos/nekostul/nekostulAICompanion/releases/latest";
    private static final String GITHUB_RELEASES_FALLBACK_URL = "https://github.com/nekostul/nekostulAICompanion/releases/latest";
    private static final String MODRINTH_PROJECT_FALLBACK_URL = "https://modrinth.com/mod/nekostulAICompanion/versions";

    private static final String TARGET_LOADER = "forge";
    private static final String TARGET_MINECRAFT_VERSION = "1.20.1";

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final Pattern VERSION_PART_PATTERN = Pattern.compile("\\d+");
    private static final String UPDATE_AVAILABLE_KEY = "message.aicompanion.update.available";
    private static final String UPDATE_BUTTON_KEY = "message.aicompanion.update.button";
    private static final AtomicBoolean CHECK_STARTED = new AtomicBoolean(false);

    private static volatile UpdateInfo pendingUpdate;
    private static volatile UpdateInfo cachedUpdate;

    private VersionUpdateChecker() {
    }

    @SubscribeEvent
    public static void onPlayerLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (cachedUpdate != null) {
            notifyClient(cachedUpdate);
            return;
        }
        if (!CHECK_STARTED.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture
                .supplyAsync(VersionUpdateChecker::findUpdateSilently)
                .exceptionally(error -> Optional.empty())
                .thenAccept(update -> update.ifPresent(found -> {
                    cachedUpdate = found;
                    notifyClient(found);
                }));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        UpdateInfo update = pendingUpdate;
        if (update == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        pendingUpdate = null;
        sendUpdateMessage(player, update);
    }

    private static Optional<UpdateInfo> findUpdateSilently() {
        try {
            String currentVersion = getCurrentVersion();
            if (currentVersion.isBlank()) {
                return Optional.empty();
            }

            ReleaseInfo modrinthRelease = fetchLatestModrinthRelease();
            ReleaseInfo githubRelease = fetchLatestGithubRelease();
            ReleaseInfo newestRelease = pickNewestRelease(modrinthRelease, githubRelease);
            if (newestRelease == null) {
                return Optional.empty();
            }

            if (compareVersions(newestRelease.version, currentVersion) > 0) {
                return Optional.of(new UpdateInfo(currentVersion, newestRelease.version, newestRelease.sourceUrl));
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static String getCurrentVersion() {
        return ModList.get().getModContainerById(AiCompanionMod.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("");
    }

    private static ReleaseInfo fetchLatestModrinthRelease() {
        HttpURLConnection connection = null;
        try {
            connection = openGetConnection(MODRINTH_VERSIONS_URL);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            String response = readResponseBody(connection);
            JsonElement parsed = JsonParser.parseString(response);
            if (!parsed.isJsonArray()) {
                return null;
            }
            JsonArray versions = parsed.getAsJsonArray();

            ReleaseInfo latest = null;
            for (JsonElement element : versions) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject version = element.getAsJsonObject();
                if (!isReleaseVersion(version)) {
                    continue;
                }
                if (!containsValue(version.getAsJsonArray("loaders"), TARGET_LOADER)) {
                    continue;
                }
                if (!containsValue(version.getAsJsonArray("game_versions"), TARGET_MINECRAFT_VERSION)) {
                    continue;
                }

                String candidateVersion = getString(version, "version_number");
                if (candidateVersion == null || candidateVersion.isBlank()) {
                    continue;
                }

                String versionId = getString(version, "id");
                String sourceUrl = buildModrinthVersionUrl(versionId);
                ReleaseInfo candidate = new ReleaseInfo(candidateVersion, sourceUrl);
                latest = pickNewestRelease(latest, candidate);
            }
            return latest;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static ReleaseInfo fetchLatestGithubRelease() {
        HttpURLConnection connection = null;
        try {
            connection = openGetConnection(GITHUB_LATEST_RELEASE_URL);
            connection.setRequestProperty("Accept", "application/vnd.github+json");

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            String response = readResponseBody(connection);
            JsonElement parsed = JsonParser.parseString(response);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject release = parsed.getAsJsonObject();

            String version = getString(release, "tag_name");
            if (version == null || version.isBlank()) {
                version = getString(release, "name");
            }
            if (version == null || version.isBlank()) {
                return null;
            }

            String releaseUrl = getString(release, "html_url");
            if (releaseUrl == null || releaseUrl.isBlank()) {
                releaseUrl = GITHUB_RELEASES_FALLBACK_URL;
            }

            return new ReleaseInfo(version, releaseUrl);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static HttpURLConnection openGetConnection(String targetUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(targetUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "aicompanion-update-checker");
        return connection;
    }

    private static String readResponseBody(HttpURLConnection connection) throws Exception {
        InputStream stream = connection.getResponseCode() >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String buildModrinthVersionUrl(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return MODRINTH_PROJECT_FALLBACK_URL;
        }
        return String.format(MODRINTH_VERSION_PAGE_URL_TEMPLATE, versionId);
    }

    private static boolean isReleaseVersion(JsonObject version) {
        String versionType = getString(version, "version_type");
        return versionType == null || versionType.equalsIgnoreCase("release");
    }

    private static boolean containsValue(JsonArray array, String expected) {
        if (array == null || expected == null) {
            return false;
        }
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            String value = element.getAsString();
            if (value != null && expected.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private static String getString(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key)) {
            return null;
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        String value = element.getAsString();
        return value == null || value.isBlank() ? null : value;
    }

    private static void notifyClient(UpdateInfo update) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            LocalPlayer player = mc.player;
            if (player == null) {
                pendingUpdate = update;
                return;
            }
            sendUpdateMessage(player, update);
        });
    }

    private static void sendUpdateMessage(LocalPlayer player, UpdateInfo update) {
        MutableComponent prefix = Component.literal("[AICompanion] ")
                .withStyle(ChatFormatting.GOLD);
        MutableComponent body = Component.translatable(
                        UPDATE_AVAILABLE_KEY,
                        update.latestVersion,
                        update.currentVersion)
                .withStyle(ChatFormatting.YELLOW);

        MutableComponent message = prefix.append(body);
        if (update.sourceUrl != null && !update.sourceUrl.isBlank()) {
            message.append(Component.literal(" "));
            message.append(Component.translatable(UPDATE_BUTTON_KEY)
                    .withStyle(style -> style
                            .withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, update.sourceUrl))));
        }
        player.sendSystemMessage(message);
    }

    private static ReleaseInfo pickNewestRelease(ReleaseInfo first, ReleaseInfo second) {
        if (first == null || first.version == null || first.version.isBlank()) {
            return second;
        }
        if (second == null || second.version == null || second.version.isBlank()) {
            return first;
        }
        return compareVersions(first.version, second.version) >= 0 ? first : second;
    }

    private static int compareVersions(String left, String right) {
        long[] leftParts = parseVersionParts(left);
        long[] rightParts = parseVersionParts(right);
        int size = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < size; i++) {
            long leftValue = i < leftParts.length ? leftParts[i] : 0L;
            long rightValue = i < rightParts.length ? rightParts[i] : 0L;
            if (leftValue != rightValue) {
                return Long.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    private static long[] parseVersionParts(String version) {
        if (version == null || version.isBlank()) {
            return new long[]{0L};
        }

        Matcher matcher = VERSION_PART_PATTERN.matcher(version);
        List<Long> parts = new ArrayList<>();
        while (matcher.find()) {
            try {
                parts.add(Long.parseLong(matcher.group()));
            } catch (NumberFormatException ignored) {
                // Ignore invalid chunks and continue parsing.
            }
        }

        if (parts.isEmpty()) {
            return new long[]{0L};
        }

        long[] result = new long[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            result[i] = parts.get(i);
        }
        return result;
    }

    private record ReleaseInfo(String version, String sourceUrl) {
    }

    private record UpdateInfo(String currentVersion, String latestVersion, String sourceUrl) {
    }
}
