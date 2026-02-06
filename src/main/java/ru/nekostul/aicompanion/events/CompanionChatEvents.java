package ru.nekostul.aicompanion.events;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import ru.nekostul.aicompanion.AiCompanionMod;
import ru.nekostul.aicompanion.entity.CompanionEntity;

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
    }

    public static boolean handlePlayerMessage(ServerPlayer player, String rawMessage) {
        String message = rawMessage == null ? "" : rawMessage.trim();
        if (message.isEmpty()) {
            return false;
        }

        CompanionEntity pendingTeleport = CompanionEntity.getPendingTeleportFor(player);
        Boolean response = parseYesNo(message);
        if (pendingTeleport != null && response != null) {
            pendingTeleport.handleTeleportResponse(player, response);
            return true;
        }

        CompanionEntity companion = findNearestCompanion(player);
        if (companion == null) {
            return false;
        }

        if (containsCommandsKeyword(message)) {
            if (companion.getMode() == CompanionEntity.CompanionMode.STOPPED) {
                companion.sendCommandList(player);
                return true;
            }
            return false;
        }

        CompanionEntity.CompanionMode mode = parseMode(message);
        if (mode != null) {
            boolean changed = companion.setMode(mode);
            if (changed) {
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
        String normalized = message.toUpperCase(Locale.ROOT);
        if (normalized.contains("УМНОЕ СЛЕДОВАНИЕ") || normalized.contains("SMART FOLLOW")) {
            return CompanionEntity.CompanionMode.AUTONOMOUS;
        }
        if (normalized.contains("СЛЕДОВАНИЕ") || normalized.contains("FOLLOWING")) {
            return CompanionEntity.CompanionMode.AUTONOMOUS;
        }
        if (normalized.contains("СТОП") || normalized.contains("STOP")) {
            return CompanionEntity.CompanionMode.STOPPED;
        }
        return null;
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
        if (normalized.equals("YES") || normalized.equals("Y") || normalized.equals("ДА") || normalized.equals("Д")) {
            return Boolean.TRUE;
        }
        if (normalized.equals("NO") || normalized.equals("N") || normalized.equals("НЕТ") || normalized.equals("Н")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static void scheduleReply(ServerPlayer player, CompanionEntity companion, Component message) {
        PENDING_CHAT_REPLIES.add(() -> {
            if (player.isRemoved() || !player.isAlive()) {
                return;
            }
            if (companion.isRemoved() || !companion.isAlive()) {
                return;
            }
            companion.sendReply(player, message);
        });
    }
}
