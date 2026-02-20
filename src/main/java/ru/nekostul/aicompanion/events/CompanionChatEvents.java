package ru.nekostul.aicompanion.events;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import ru.nekostul.aicompanion.AiCompanionMod;
import ru.nekostul.aicompanion.entity.CompanionEntity;
import ru.nekostul.aicompanion.entity.CompanionSingleNpcManager;

import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Mod.EventBusSubscriber(modid = AiCompanionMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CompanionChatEvents {
    private static final double COMMAND_RANGE = 48.0D;
    private static final String MODE_STOP_KEY = "entity.aicompanion.companion.mode.stop";
    private static final String MODE_FOLLOW_KEY = "entity.aicompanion.companion.mode.follow";
    private static final String MODE_AUTONOMOUS_KEY = "entity.aicompanion.companion.mode.autonomous";
    private static final Queue<Runnable> PENDING_CHAT_REPLIES = new ConcurrentLinkedQueue<>();

    private CompanionChatEvents() {
    }

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        String rawMessage = event.getRawText();
        if (handlePlayerMessage(player, rawMessage)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Runnable task;
        while ((task = PENDING_CHAT_REPLIES.poll()) != null) {
            task.run();
        }
        CompanionEntity.tickPendingTeleports(ServerLifecycleHooks.getCurrentServer());
        CompanionEntity.tickTeleportRequestFallback(ServerLifecycleHooks.getCurrentServer());
    }

    public static boolean handlePlayerMessage(ServerPlayer player, String rawMessage) {
        String message = rawMessage == null ? "" : rawMessage.trim();
        if (message.isEmpty()) {
            return false;
        }

        Boolean response = parseYesNo(message);
        if (response != null && CompanionEntity.handleTeleportResponse(player, response)) {
            return true;
        }

        if (isWhereCommand(message)) {
            CompanionEntity active = CompanionSingleNpcManager.getActive(player);
            if (active != null) {
                return active.handleWhereCommand(player);
            }
            CompanionEntity nearest = findNearestCompanion(player);
            if (nearest != null) {
                return nearest.handleWhereCommand(player);
            }
            return CompanionEntity.handleWhereCommandFallback(player);
        }

        CompanionEntity companion = findNearestCompanion(player);
        if (companion == null) {
            return false;
        }

        if (companion.handleThanks(player, message)) {
            return true;
        }

        if (isGoHomeCommand(message)) {
            return companion.handleGoHomeCommand(player);
        }

        if (handlePartyCommand(player, companion, message)) {
            return true;
        }

        if (containsCommandsKeyword(message)) {
            if (!companion.canPlayerControl(player)) {
                return true;
            }
            if (companion.getMode() == CompanionEntity.CompanionMode.STOPPED) {
                companion.sendCommandList(player);
                return true;
            }
            return false;
        }

        CompanionEntity.CompanionMode mode = parseMode(message);
        if (mode != null) {
            if (!companion.canPlayerControl(player)) {
                return true;
            }
            boolean changed = companion.setMode(mode);
            if (changed) {
                if (mode == CompanionEntity.CompanionMode.STOPPED) {
                    companion.markStopCommand(player);
                }
                companion.sendReply(player, Component.translatable(modeKey(mode)));
            }
            return true;
        }
        if (companion.handlePlayerCommand(player, message)) {
            return true;
        }
        return false;
    }

    private static CompanionEntity findNearestCompanion(ServerPlayer player) {
        AABB range = player.getBoundingBox().inflate(COMMAND_RANGE);
        CompanionEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (CompanionEntity companion : player.level().getEntitiesOfClass(CompanionEntity.class, range)) {
            double distance = player.distanceToSqr(companion);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = companion;
            }
        }
        return nearest;
    }

    private static CompanionEntity.CompanionMode parseMode(String message) {
        String normalized = normalize(stripAiPrefix(message));
        if (normalized.contains("следуй")
                || normalized.contains("following")
                || normalized.contains("follow")) {
            return CompanionEntity.CompanionMode.AUTONOMOUS;
        }
        if (normalized.contains("стоп")
                || normalized.contains("стой")
                || normalized.contains("stop")) {
            return CompanionEntity.CompanionMode.STOPPED;
        }
        return null;
    }

    private static String stripAiPrefix(String message) {
        if (message == null) {
            return "";
        }
        String normalized = normalize(message);
        if (normalized.equals("ии") || normalized.equals("ai") || normalized.equals("gpt")) {
            return "";
        }
        if (normalized.startsWith("ии ")
                || normalized.startsWith("ai ")
                || normalized.startsWith("gpt ")) {
            String trimmed = message.trim();
            int firstSpace = trimmed.indexOf(' ');
            if (firstSpace < 0 || firstSpace + 1 >= trimmed.length()) {
                return "";
            }
            return trimmed.substring(firstSpace + 1).trim();
        }
        return message;
    }

    private static boolean containsCommandsKeyword(String message) {
        String normalized = message.toUpperCase(Locale.ROOT);
        return normalized.contains("КОМАНД") || normalized.contains("COMMAND");
    }

    private static String modeKey(CompanionEntity.CompanionMode mode) {
        return switch (mode) {
            case STOPPED -> MODE_STOP_KEY;
            case FOLLOW -> MODE_FOLLOW_KEY;
            case AUTONOMOUS -> MODE_AUTONOMOUS_KEY;
        };
    }

    private static Boolean parseYesNo(String message) {
        String normalized = message.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.equals("YES") || normalized.equals("Y")
                || normalized.equals("\u0414\u0410") || normalized.equals("\u0414")) {
            return Boolean.TRUE;
        }
        if (normalized.equals("NO") || normalized.equals("N")
                || normalized.equals("\u041D\u0415\u0422") || normalized.equals("\u041D")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static boolean handlePartyCommand(ServerPlayer player, CompanionEntity companion, String message) {
        String normalized = normalize(message);
        if (normalized.isEmpty()) {
            return false;
        }
        if (!normalized.startsWith("party") && !normalized.startsWith("\u043f\u0430\u0442\u0438")) {
            return false;
        }
        String[] rawParts = message.trim().split("\\s+");
        String[] parts = normalized.split("\\s+");
        if (parts.length < 2) {
            return true;
        }
        PartyAction action = parsePartyAction(parts[1]);
        if (action == null) {
            return true;
        }
        if (parts.length < 3 || rawParts.length < 3) {
            return true;
        }
        String targetName = rawParts[2];
        if (targetName.isEmpty()) {
            return true;
        }
        if (!companion.canManageParty(player)) {
            return true;
        }
        ServerPlayer target = resolvePlayerByName(player, targetName);
        if (target == null) {
            return true;
        }
        if (action == PartyAction.ADD) {
            companion.addPartyMember(player, target);
        } else if (action == PartyAction.REMOVE) {
            companion.removePartyMember(player, target);
        }
        return true;
    }

    private static PartyAction parsePartyAction(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        if (token.equals("+") || token.startsWith("add") || token.startsWith("\u0434\u043e\u0431\u0430\u0432")) {
            return PartyAction.ADD;
        }
        if (token.equals("-") || token.startsWith("remove") || token.startsWith("del")
                || token.startsWith("\u0443\u0434\u0430\u043b") || token.startsWith("\u0443\u0431\u0435\u0440")) {
            return PartyAction.REMOVE;
        }
        return null;
    }

    private static ServerPlayer resolvePlayerByName(ServerPlayer player, String name) {
        if (player == null || name == null || name.isEmpty()) {
            return null;
        }
        if (player.getServer() == null) {
            return null;
        }
        for (ServerPlayer candidate : player.getServer().getPlayerList().getPlayers()) {
            if (candidate.getGameProfile().getName().equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        return null;
    }

    private static String normalize(String message) {
        return message.trim()
                .toLowerCase(Locale.ROOT)
                .replace('\u0451', '\u0435');
    }

    private static boolean isWhereCommand(String message) {
        String normalized = normalize(message);
        return normalized.equals("\u0433\u0434\u0435 \u0442\u044b")
                || normalized.equals("\u0433\u0434\u0435 \u0442\u044b?")
                || normalized.equals("where are you");
    }

    private static boolean isGoHomeCommand(String message) {
        String normalized = normalize(message);
        return normalized.equals("\u0438\u0434\u0438 \u0434\u043e\u043c\u043e\u0439")
                || normalized.equals("\u0438\u0434\u0438 \u0434\u043e\u043c\u043e\u0439!");
    }

    private enum PartyAction {
        ADD,
        REMOVE
    }
}
